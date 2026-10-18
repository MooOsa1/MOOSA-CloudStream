import { appendPlayerSources, appendExternalSubtitles, markNoAutomaticSources, showPlayer } from './player.js';
import { describeSourceResolution } from './source-diagnostics.js';
let generation=0;
let currentAbort=null;
import {sourceLookupBudgetMs} from './source-timeout-policy.js';
let runtimeReadiness;
let cineStreamReadiness;
function approvedCineStreamReady(){
  if(!cineStreamReadiness){
    cineStreamReadiness=fetch('/api/config',{
      headers:{Accept:'application/json'},
      signal:AbortSignal.timeout(2000)
    }).then(async response=>{
      if(!response.ok)return false;
      const config=await response.json();
      return config?.verifiedCineStreamPilotReady===true;
    }).catch(()=>false);
  }
  return cineStreamReadiness;
}
function pilotRuntimeReady(){
  if(!runtimeReadiness){
    runtimeReadiness=fetch('/api/cloudstream/pilot-status',{
      headers:{Accept:'application/json'},
      signal:AbortSignal.timeout(2000)
    }).then(async r=>{
      if(!r.ok)return false;
      const data=await r.json();
      return data?.readyForQuery===true && data?.filmImdbId==='tt5719076';
    }).catch(()=>false);
  }
  return runtimeReadiness;
}
const $=query=>document.querySelector(query);
function message(text){
  const el=$('#automatic-source-status');
  if(el)el.textContent=text;
}
export function cancelAutomaticSourceSearch() {
  generation++;
  if(currentAbort){currentAbort.abort();currentAbort=null;}
}
export async function searchAutomaticSources(media,id,season=null,episode=null) {
  cancelAutomaticSourceSearch();
  const job=++generation;
  const controller=new AbortController();
  currentAbort=controller;
  const params=new URLSearchParams({media,id:String(id)});
  if(media==='tv'){
    params.set('season',String(season));
    params.set('episode',String(episode));
  }
  message('جاري البحث عن السيرفرات تلقائيًا من مصادر الويب...');
  // Subtitles are optional and must NEVER delay or block the video sources.
  // Start both requests independently and append tracks after the video
  // can already be playing. The same AbortController guards stale titles.
  void fetch('/api/stremio-subtitles?'+params.toString(),{
    headers:{Accept:'application/json'},
    signal:AbortSignal.any([controller.signal,AbortSignal.timeout(5500)])
  }).then(async res=>res.ok?res.json():null)
    .then(data=>{
      if(job!==generation || !data || !Array.isArray(data.subtitles))return;
      appendExternalSubtitles(data.subtitles);
    }).catch(()=>{}); // Subtitle network failure must not affect playback.
  try {
    // Only explicitly reviewed CineStream experimental titles can use the
    // extended verification window. The public/disabled source flow retains
    // the normal 16.5s deadline to avoid wasting the viewer's time.
    const idText=String(id);
    const reviewedMulti=media==='movie' &&
      ['19','832'].includes(idText) && await approvedCineStreamReady();
    const longPilot=media==='movie' &&
      (reviewedMulti || ['10331','961','3085'].includes(idText) ||
        await pilotRuntimeReady());
    if(job!==generation)return;
    const response=await fetch('/api/resolve?'+params.toString(),{
      headers:{Accept:'application/json'},
      signal:AbortSignal.any([controller.signal,AbortSignal.timeout(sourceLookupBudgetMs(media,longPilot))])
    });
    if(!response.ok) {
      const result=await response.json().catch(()=>({}));
      throw new Error(result.error||'تعذر البحث عن المصادر');
    }
    const result=await response.json();
    if(job!==generation)return;
    const sources=Array.isArray(result.sources)?result.sources:[];
    if(sources.length){
      appendPlayerSources(sources);
      message(describeSourceResolution(result));
    } else {
      markNoAutomaticSources();
      message(describeSourceResolution(result));
    }
  }catch(error){
    if(job===generation) {
      markNoAutomaticSources();
      message(error.name==='TimeoutError'?
        'انتهت مهلة البحث؛ ما حصلنا روابط مؤكدة. جرّب مرة ثانية لاحقًا.':
        'تعذر البحث عن السيرفرات: '+error.message);
    }
  }finally {
    if(job===generation)currentAbort=null;
  }
}
async function openLicensedFullFilm(){
  const button=$('#open-free-film');
  if(!button||button.disabled)return;
  button.disabled=true;
  message('جاري البحث عن النسخة المرخّصة من فيلم Big Buck Bunny...');
  try{
    const response=await fetch('/api/free-film',{
      headers:{Accept:'application/json'},
      signal:AbortSignal.timeout(12000)
    });
    if(!response.ok)throw new Error('تعذر الاتصال بمكتبة الأفلام المجانية');
    const result=await response.json();
    const sources=Array.isArray(result.sources)?result.sources:[];
    if(!sources.length) {
      message('نسخة الفيلم المفتوح الترخيص غير متاحة حاليًا من Wikimedia Commons. حاول لاحقًا.');
      return;
    }
    cancelAutomaticSourceSearch();
    // Do not confuse this separate CC BY test title with the selected film.
    showPlayer({title:'Big Buck Bunny — فيلم مجاني مستقل',sources:[]});
    appendPlayerSources(sources);
    message('تم العثور على '+sources.length+' نسخة من Big Buck Bunny برخصة CC BY 3.0. التشغيل يعتمد على دعم المتصفح لصيغة الفيديو.');
  }catch(error){
    message(error.name==='TimeoutError'?
      'تأخر مصدر الفيلم المفتوح. حاول لاحقًا.':
      'تعذر تحميل الفيلم المفتوح: '+error.message);
  }finally{button.disabled=false;}
}
export function initAutomaticSources(){
  document.addEventListener('moosa-player-closed',cancelAutomaticSourceSearch);
  document.addEventListener('moosa-player-opened',cancelAutomaticSourceSearch);
  $('#open-free-film')?.addEventListener('click',openLicensedFullFilm);
  void pilotRuntimeReady();
  void approvedCineStreamReady();
}
