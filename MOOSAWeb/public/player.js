const $ = s => document.querySelector(s);
const video = $('#video');
let hls = null;
let mediaUrl = null;
let subtitleUrl = null;
let activeSources = [];
let previousFocus = null;
let cueBaseline = new Map();
let playbackToken = 0;
let subtitleToken = 0;
let customSubtitle = null;
const DEMO = 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4';
const HTTPS = /^https:\/\/[^\s]+$/i;
const extensions = /\.(m3u8|mp4|webm|mov)(?:[?#]|$)/i;

function notice(message){$('#player-message').textContent=message;}
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
      hls.on(Hls.Events.ERROR,(_event,data)=>{if(data.fatal && currentPlayback===playbackToken){notice(`تعذر تشغيل HLS (${data.type}). قد يكون الرابط محميًا أو لا يسمح بالوصول من المتصفح.`);}});
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
  activeSources=sources;
  resetVideo();
  $('#player-title').textContent=title;
  const options=$('#player-source');options.replaceChildren(new Option('اختر مصدرًا مصرّحًا به',''));
  for(let i=0;i<sources.length;i++)options.add(new Option(sources[i].name||`مصدر ${i+1}`,String(i)));
  $('#player-subtitle').textContent=sources.length?`${sources.length} مصادر مصرّح بها`:'لا يوجد مصدر تلقائي مضاف لهذا العنوان';
  $('#overlay').classList.remove('hidden');$('#player-panel').classList.remove('hidden');
  notice('اختر مصدرًا مصرحًا به أو استخدم فيديو الاختبار الحر. ملفات HLS تتطلب CORS من السيرفر.');
  $('#close-player').focus();
}
export function hidePlayer(){
  document.dispatchEvent(new Event('moosa-player-closed'));
  resetVideo();$('#player-panel').classList.add('hidden');
  if($('#detail-panel').classList.contains('hidden'))$('#overlay').classList.add('hidden');
  previousFocus?.focus?.();
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
    activeSources.push(item);
    added++;
  }
  if (!added) return;
  activeSources.sort((a,b) => (Number(b.quality)||0)-(Number(a.quality)||0));
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

export function startDemo(){
  $('#player-source').value='';
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
function sourceSelected(value){
  if(value===''){resetVideo();notice('اختر مصدرًا للتشغيل أو افتح فيديو من جهازك.');return;}
  const src=activeSources[Number(value)];
  if(!src)return;
  $('#player-title').textContent=src.name||'مشغل MOOSA';
  // playLink resets the track menu synchronously; repopulate immediately,
  // instead of waiting for a remote video to load or fail.
  void playLink(src.url,src.name);
  const list=$('#player-subtitles');
  for(const t of src.subtitles||[]){const option=new Option(`${t.language==='ar'?'Arabic':'English'} • ${t.source} • ${t.label}`,t.url);option.dataset.label=t.label;option.dataset.language=t.language;list.add(option);}
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
$('#play-custom').addEventListener('click',()=>{const url=$('#custom-stream').value.trim();if(!extensions.test(url))notice('ملاحظة: يفضل رابط MP4 أو HLS بصيغة m3u8 قابل للتشغيل بالمتصفح.');playLink(url,'رابط الفيديو الخاص بك');});
$('#custom-stream').addEventListener('keydown',e=>{if(e.key==='Enter')$('#play-custom').click();});
$('#local-video').addEventListener('change',e=>{const file=e.target.files?.[0];if(!file)return;resetVideo();mediaUrl=URL.createObjectURL(file);video.src=mediaUrl;showVideo();$('#player-title').textContent=file.name;video.play().catch(()=>{});notice('تشغيل ملف محلي من جهازك');e.target.value='';});
$('#local-subtitle').addEventListener('change',async e=>{const file=e.target.files?.[0];if(!file)return;try{if(file.size>3*1024*1024)throw new Error('ملف الترجمة كبير جدًا');const ticket=subtitleToken;const raw=await file.text();if(ticket===subtitleToken)loadSubtitle(raw,file.name,/\bar\b|arabic/i.test(file.name)?'ar':'en')}catch(error){notice(error.message)}e.target.value='';});
video.addEventListener('error',()=>{
  if(!video.error)return;
  const isHls=/\.m3u8(?:[?#]|$)/i.test(video.currentSrc||video.src);
  notice(isHls
    ? 'تعذر تشغيل HLS. قد يكون الرابط محميًا، أو يحتاج Referer أو ترويسات لا يسمح المتصفح بإرسالها.'
    : 'تعذر تشغيل الملف في المتصفح. جرّب رابطًا مباشرًا آخر أو تحقق من الصيغة والصلاحيات.');
});
