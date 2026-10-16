import {nextPlaybackSource} from './playback-fallback.js';
import {chooseBrowserSources} from './source-compatibility.js';
const $ = s => document.querySelector(s);
const video = $('#video');
let hls = null;
let mediaUrl = null;
let subtitleUrl = null;
let activeSources = [];
let externalSubtitles = [];
let currentMediaTitle='';
let previousFocus = null;
let cueBaseline = new Map();
let playbackToken = 0;
let subtitleToken = 0;
let customSubtitle = null;
let selectedSourceUrl = null;
let attemptedSourceUrls = new Set();
let fallbackAttempts = 0;
const DEMO = 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4';
const HTTPS = /^https:\/\/[^\s]+$/i;
const extensions = /\.(m3u8|mp4|webm|mov)(?:[?#]|$)/i;

function notice(message){$('#player-message').textContent=message;}
function setAttribution(source){
  const box=$('#source-attribution');
  if(!box)return;
  const valid=source && /^https:\/\/commons\.wikimedia\.org\/wiki\/File:/i.test(source.sourcePage||'')
    && /^https:\/\/creativecommons\.org\//.test(source.licenseUrl||'');
  box.classList.toggle('hidden',!valid);
  if(!valid)return;
  $('#source-attribution-text').textContent=
    'العمل متاح برخصة Creative Commons • '+String(source.attribution||'Wikimedia Commons').slice(0,135);
  $('#source-attribution-link').href=source.sourcePage;
}

function clearSubtitle(resetMenu=true){
  subtitleToken++;
  for(const tr of [...video.querySelectorAll('track')])tr.remove();
  if(subtitleUrl){URL.revokeObjectURL(subtitleUrl);subtitleUrl=null;}
  cueBaseline=new Map();
  $('#subtitle-offset').value='0';$('#offset-value').textContent='0.0s';
  for(const t of video.textTracks)t.mode='disabled';
  if(resetMenu)$('#player-subtitles').replaceChildren(new Option('بدون ترجمة',''));
}
function resetVideo(){
  setAttribution(null);
  playbackToken++;
  customSubtitle=null;
  video.pause();clearSubtitle();
  if(hls){hls.destroy();hls=null;}
  video.removeAttribute('src');video.load();
  if(mediaUrl){URL.revokeObjectURL(mediaUrl);mediaUrl=null;}
  $('#player-quality').replaceChildren(new Option('تلقائي','-1'));
  $('#player-subtitles').replaceChildren(new Option('بدون ترجمة',''));
  $('#video-overlay').classList.remove('hidden');
}
function showVideo(){ $('#video-overlay').classList.add('hidden'); }
function updateQuality(){
  const el=$('#player-quality');el.replaceChildren(new Option('تلقائي','-1'));
  if(hls?.levels?.length){hls.levels.forEach((level,i)=>el.add(new Option(level.height?`${level.height}p`:`مستوى ${i+1}`,String(i))));}
  else el.add(new Option('حسب مصدر الفيديو','-2'));
}
let scriptPromise=null;
function loadHls(){
  if(window.Hls)return Promise.resolve(window.Hls);
  if(scriptPromise)return scriptPromise;
  scriptPromise=new Promise((resolve,reject)=>{
    const script=document.createElement('script');
    script.src='https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.min.js';
    script.async=true;
    script.onload=()=>window.Hls?resolve(window.Hls):reject(new Error('HLS.js لم يبدأ'));
    script.onerror=()=>reject(new Error('تعذر تحميل مكتبة HLS.js. تحقق من الاتصال.'));
    document.head.append(script);
  }).catch(e=>{scriptPromise=null;throw e});
  return scriptPromise;
}
async function playLink(url, label='Video'){
  if(!HTTPS.test(url)){notice('الرجاء استخدام رابط فيديو آمن يبدأ بـ https://');return;}
  resetVideo();
  const currentPlayback=playbackToken;
  notice(`جاري تجهيز ${label}...`);
  $('#video-overlay').classList.remove('hidden');
  const isHls=/\.m3u8(?:[?#]|$)/i.test(url);
  try {
    if(isHls && video.canPlayType('application/vnd.apple.mpegurl')){video.src=url;}
    else if(isHls){
      const Hls=await loadHls();
      if(currentPlayback!==playbackToken)return;
      if(!Hls.isSupported())throw new Error('هذا المتصفح لا يدعم تشغيل HLS');
      hls=new Hls({enableWorker:true,capLevelToPlayerSize:true,backBufferLength:40});
      hls.loadSource(url);hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED,()=>updateQuality());
      hls.on(Hls.Events.ERROR,(_event,data)=>{
        if(data.fatal && currentPlayback===playbackToken && !tryFallbackSource()){
          notice(`تعذر تشغيل HLS (${data.type}). قد يكون الرابط محميًا أو لا يسمح بالوصول من المتصفح.`);
        }
      });
    }else{
      video.src=url;
    }
    if(currentPlayback!==playbackToken)return;
    showVideo();
    await video.play().catch(error=>{
      if(currentPlayback!==playbackToken)return;
      // Autoplay being blocked is not the same as a broken video file.
      // In particular, never overwrite a native HLS decoding error with
      // the misleading "ready, press play" message.
      if(error?.name==='NotAllowedError' && !video.error){
        notice('الفيديو جاهز. اضغط ▶ لتبدأ.');
      } else {
        notice(isHls
          ? 'تعذر تشغيل HLS. الرابط معطّل أو تنقصه صلاحيات المتصفح أو الترويسات المطلوبة.'
          : 'تعذر تشغيل الفيديو. تحقق من الرابط أو صيغة الفيديو أو الصلاحيات.');
      }
    });
    if(currentPlayback===playbackToken && !video.paused)notice(`يُشغّل الآن: ${label}`);
  }catch(e){if(currentPlayback===playbackToken)notice(e.message||'تعذر تشغيل الفيديو');}
}
export function showPlayer({title='مشغل MOOSA',sources=[]}={}){
  previousFocus=document.activeElement;
  externalSubtitles=[];
  activeSources=chooseBrowserSources(sources,navigator.userAgent);
  currentMediaTitle=title;
  selectedSourceUrl=null;
  attemptedSourceUrls.clear();
  fallbackAttempts=0;
  resetVideo();
  $('#player-title').textContent=title;
  const options=$('#player-source');
  options.replaceChildren(new Option(activeSources.length?'اختر مصدرًا مصرّحًا به':'لا توجد سيرفرات متاحة',''));
  options.disabled=!activeSources.length;
  for(let i=0;i<activeSources.length;i++)options.add(new Option(activeSources[i].name||`مصدر ${i+1}`,String(i)));
  $('#player-subtitle').textContent=activeSources.length?`${activeSources.length} مصادر مصرّح بها`:'لا يوجد مصدر تلقائي مضاف لهذا العنوان';
  $('#automatic-source-status').textContent='يبدأ البحث تلقائيًا عند اختيار فيلم أو حلقة.';
  $('#overlay').classList.remove('hidden');$('#player-panel').classList.remove('hidden');
  notice('اختر مصدرًا مصرحًا به أو استخدم فيديو الاختبار الحر. ملفات HLS تتطلب CORS من السيرفر.');
  $('#close-player').focus();
}
export function hidePlayer(){
  document.dispatchEvent(new Event('moosa-player-closed'));
  selectedSourceUrl=null;attemptedSourceUrls.clear();fallbackAttempts=0;
  externalSubtitles=[];
  resetVideo();$('#player-panel').classList.add('hidden');
  if($('#detail-panel').classList.contains('hidden'))$('#overlay').classList.add('hidden');
  previousFocus?.focus?.();
}
function attachExternalSubtitles(source){
  if(!externalSubtitles.length)return source;
  const tracks=Array.isArray(source.subtitles)?source.subtitles:[];
  const seen=new Set(tracks.map(t=>t.url));
  return {...source,subtitles:[...tracks,...externalSubtitles.filter(t=>
    !seen.has(t.url)&&seen.add(t.url))]};
}
function addSubtitleOption(optionList,track){
  if([...optionList.options].some(option=>option.value===track.url))return;
  const option=new Option((track.language==='ar'?'Arabic':'English')+
    ' • '+(track.source||'Stremio')+' • '+(track.label||track.language),track.url);
  option.dataset.label=track.label||track.language;
  option.dataset.language=track.language;
  optionList.add(option);
}
export function appendExternalSubtitles(items){
  if($('#player-panel').classList.contains('hidden')||!Array.isArray(items))return;
  const seen=new Set(externalSubtitles.map(x=>x.url));
  for(const item of items.slice(0,24)){
    if(!item||!['ar','en'].includes(item.language) ||
       typeof item.url!=='string'||!/^https:\/\//i.test(item.url) ||
       !/\.(?:vtt|srt)(?:[?#]|$)/i.test(item.url) ||
       seen.has(item.url))continue;
    seen.add(item.url);
    externalSubtitles.push({
      url:item.url,language:item.language,
      source:String(item.source||'Stremio').slice(0,45),
      label:String(item.label||item.language).slice(0,64)
    });
  }
  // A subtitle fetch may finish before any sources arrive or after video
  // started playing. In both cases preserve playback instead of replaying.
  activeSources=activeSources.map(attachExternalSubtitles);
  if(!selectedSourceUrl)return;
  const current=activeSources.find(x=>x.url===selectedSourceUrl);
  if(!current)return;
  const picker=$('#player-subtitles');
  for(const track of current.subtitles||[])addSubtitleOption(picker,track);
}
export function appendPlayerSources(items) {
  if ($('#player-panel').classList.contains('hidden') || !Array.isArray(items)) return;
  const picker = $('#player-source');
  const wasSelected = activeSources[Number(picker.value)]?.url || '';
  const seen = new Set(activeSources.map(item => item.url));
  let added = 0;
  for (const item of items) {
    if (!item?.url || seen.has(item.url)) continue;
    seen.add(item.url);
    activeSources.push(attachExternalSubtitles(item));
    added++;
  }
  if (!added) return;
  // The official Elephants Dream Safari transcode is known to decode on
  // iPhone while its older original does not. Keep both on desktop; omit
  // only that specific original on iOS when the same-file transcode exists.
  activeSources=chooseBrowserSources(activeSources,navigator.userAgent);
  picker.disabled=false;
  activeSources.sort((a,b) =>
    Number(b.codecOptimized===true)-Number(a.codecOptimized===true) ||
    Number(b.preferred===true)-Number(a.preferred===true) ||
    (Number(b.quality)||0)-(Number(a.quality)||0));
  picker.replaceChildren(new Option('اختر السيرفر',''));
  for (let i=0;i<activeSources.length;i++) {
    const src=activeSources[i];
    picker.add(new Option((src.name || 'سيرفر') +
      (src.quality ? ' • ' + src.quality + 'p' : ''), String(i)));
  }
  const restored = wasSelected ? activeSources.findIndex(x => x.url===wasSelected) : -1;
  if (restored >= 0) picker.value=String(restored);
  else {
    picker.value='0';
    sourceSelected('0');
  }
  $('#player-subtitle').textContent=activeSources.length + ' مصدر متاح • الجودة حسب السيرفر';
}

export function markNoAutomaticSources(){
  // Prevent iOS Safari from opening a native picker containing only an
  // empty placeholder. No playable URL exists until actual sources arrive.
  if(activeSources.length||$('#player-panel').classList.contains('hidden'))return;
  const picker=$('#player-source');
  picker.replaceChildren(new Option('لا توجد سيرفرات متاحة',''));
  picker.disabled=true;
  $('#player-subtitle').textContent='لا توجد سيرفرات مشاهدة لهذا العنوان حاليًا';
}

export function startDemo(){
  selectedSourceUrl=null;
  $('#player-source').value='';
  currentMediaTitle='فيديو تجريبي حر — MDN Flower (CC0)';
  $('#player-title').textContent='فيديو تجريبي حر — MDN Flower (CC0)';
  playLink(DEMO,'الفيديو التجريبي الحر');
}
function vttText(input){
  let raw=input.replace(/^\uFEFF/,'').replace(/\r\n?/g,'\n').trim();
  if(raw.startsWith('WEBVTT'))return raw+'\n';
  // Convert the SRT time separator to WebVTT; skip unsupported ASS/SSA.
  if(!raw.includes('-->'))throw new Error('ملف الترجمة لا يحتوي توقيتات SRT/VTT');
  raw=raw.replace(/(\d{1,2}:\d\d:\d\d),(\d{3})\s*-->\s*(\d{1,2}:\d\d:\d\d),(\d{3})/g,'$1.$2 --> $3.$4');
  if(!/\d{2}:\d{2}:\d{2}\.\d{3}\s*-->/.test(raw))throw new Error('صيغة توقيت الترجمة غير مدعومة');
  return 'WEBVTT\n\n'+raw+'\n';
}
function loadSubtitle(raw,label='Subtitle',lang='ar',origin='custom'){
  const webvtt=vttText(raw);
  if(origin==='custom')customSubtitle={raw,label,lang};
  clearSubtitle(false);
  subtitleUrl=URL.createObjectURL(new Blob([webvtt],{type:'text/vtt;charset=utf-8'}));
  const track=document.createElement('track');track.kind='subtitles';track.label=label;track.srclang=lang;track.src=subtitleUrl;track.default=true;
  track.addEventListener('load',()=>{
    for(const t of video.textTracks)t.mode=t===track.track?'showing':'disabled';
    storeCues();notice(`تم تحميل الترجمة: ${label}`);
  });
  track.addEventListener('error',()=>notice('فشل تحميل ملف الترجمة'));
  video.append(track);
  const picker=$('#player-subtitles');
  if(origin==='custom' && ![...picker.options].some(o=>o.value==='custom'))picker.add(new Option(label,'custom'));
  picker.value=origin;
}
function storeCues(){
  cueBaseline=new Map();const t=video.textTracks[video.textTracks.length-1];
  for(const cue of (t?.cues||[]))cueBaseline.set(cue,{start:cue.startTime,end:cue.endTime});
}
function adjustCues(value){
  const offset=Number(value)||0;$('#offset-value').textContent=`${offset>0?'+':''}${offset.toFixed(1)}s`;
  for(const [cue,base] of cueBaseline){try{cue.startTime=Math.max(0,base.start+offset);cue.endTime=Math.max(cue.startTime+0.1,base.end+offset);}catch{}}
}
function sourceSelected(value,{fallback=false}={}){
  if(!fallback){attemptedSourceUrls.clear();fallbackAttempts=0;}
  if(value===''){
    selectedSourceUrl=null;resetVideo();
    notice('اختر مصدرًا للتشغيل أو افتح فيديو من جهازك.');return;
  }
  const src=activeSources[Number(value)];
  if(!src)return;
  selectedSourceUrl=src.url;
  attemptedSourceUrls.add(src.url);
  $('#player-title').textContent=currentMediaTitle||src.name||'مشغل MOOSA';
  // playLink resets the track menu synchronously; repopulate immediately,
  // instead of waiting for a remote video to load or fail.
  void playLink(src.url,src.name);
  setAttribution(src);
  const list=$('#player-subtitles');
  for(const t of src.subtitles||[])addSubtitleOption(list,t);
}
function tryFallbackSource(){
  // Only retry already returned sources for this exact movie/episode.
  // Never switch from a user-supplied URL, local video or CC demo to an
  // unrelated catalog source. Retry at most 3 alternatives without cycling.
  if(!selectedSourceUrl || fallbackAttempts>=3 ||
      $('#player-panel').classList.contains('hidden'))return false;
  const next=nextPlaybackSource(activeSources,attemptedSourceUrls,
    mime=>video.canPlayType(mime),4);
  if(!next)return false;
  fallbackAttempts++;
  const picker=$('#player-source');
  picker.value=String(next.index);
  sourceSelected(String(next.index),{fallback:true});
  notice('تعذر تشغيل المصدر السابق؛ جاري تجربة بديل '+fallbackAttempts+' من 3…');
  return true;
}
async function subtitleSelected(url){
  const request=++subtitleToken;
  if(!url){clearSubtitle(false);return;}
  const option=$('#player-subtitles').selectedOptions[0];
  if(url==='custom'){
    if(customSubtitle)loadSubtitle(customSubtitle.raw,customSubtitle.label,customSubtitle.lang,'custom');
    else notice('أعد اختيار ملف الترجمة المحلي.');
    return;
  }
  if(!HTTPS.test(url)){notice('عنوان الترجمة غير صالح');return;}
  try {
    notice('جاري تحميل الترجمة...');const res=await fetch(url,{signal:AbortSignal.timeout(7000)});
    if(!res.ok)throw new Error(`HTTP ${res.status}`);
    if(Number(res.headers.get('content-length'))>3*1024*1024)throw new Error('Subtitle too large');
    const body=await res.text();
    if(request!==subtitleToken)return;
    if(body.length>3*1024*1024)throw new Error('Subtitle too large');
    const label=option?.dataset.label||'Subtitle';const lang=option?.dataset.language||'ar';
    loadSubtitle(body,label,lang,url);
  }catch{if(request===subtitleToken)notice('تعذر قراءة ملف الترجمة. تأكد من CORS وصيغة SRT/VTT وحجم الملف.');}
}
$('#player-source').addEventListener('change',e=>sourceSelected(e.target.value));
$('#player-quality').addEventListener('change',e=>{if(hls){hls.currentLevel=Number(e.target.value);}else if(e.target.value!=='-1')notice('الجودة مرتبطة بمصدر الفيديو نفسه.');});
$('#player-subtitles').addEventListener('change',e=>subtitleSelected(e.target.value));
$('#subtitle-offset').addEventListener('input',e=>adjustCues(e.target.value));
$('#close-player').addEventListener('click',hidePlayer);
$('#play-demo').addEventListener('click',startDemo);
$('#play-custom').addEventListener('click',()=>{
  selectedSourceUrl=null;
  const url=$('#custom-stream').value.trim();
  if(!extensions.test(url))notice('ملاحظة: يفضل رابط MP4 أو HLS بصيغة m3u8 قابل للتشغيل بالمتصفح.');
  playLink(url,'رابط الفيديو الخاص بك');
});
$('#custom-stream').addEventListener('keydown',e=>{if(e.key==='Enter')$('#play-custom').click();});
$('#local-video').addEventListener('change',e=>{const file=e.target.files?.[0];if(!file)return;selectedSourceUrl=null;resetVideo();mediaUrl=URL.createObjectURL(file);video.src=mediaUrl;showVideo();$('#player-title').textContent=file.name;video.play().catch(()=>{});notice('تشغيل ملف محلي من جهازك');e.target.value='';});
$('#local-subtitle').addEventListener('change',async e=>{const file=e.target.files?.[0];if(!file)return;try{if(file.size>3*1024*1024)throw new Error('ملف الترجمة كبير جدًا');const ticket=subtitleToken;const raw=await file.text();if(ticket===subtitleToken)loadSubtitle(raw,file.name,/\bar\b|arabic/i.test(file.name)?'ar':'en')}catch(error){notice(error.message)}e.target.value='';});
video.addEventListener('error',()=>{
  if(!video.error)return;
  // Do not let a stale error from an old URL switch the current movie source.
  const reported=video.currentSrc||video.src;
  if(selectedSourceUrl && reported===selectedSourceUrl && tryFallbackSource())return;
  const isHls=/\.m3u8(?:[?#]|$)/i.test(video.currentSrc||video.src);
  notice(isHls
    ? 'تعذر تشغيل HLS. قد يكون الرابط محميًا، أو يحتاج Referer أو ترويسات لا يسمح المتصفح بإرسالها.'
    : 'تعذر تشغيل الملف في المتصفح. جرّب رابطًا مباشرًا آخر أو تحقق من الصيغة والصلاحيات.');
});
