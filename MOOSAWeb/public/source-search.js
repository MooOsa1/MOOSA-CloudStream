import { appendPlayerSources, markNoAutomaticSources } from './player.js';
import { describeSourceResolution } from './source-diagnostics.js';
let generation=0;
let currentAbort=null;
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
  try {
    const response=await fetch('/api/resolve?'+params.toString(),{
      headers:{Accept:'application/json'},
      signal:AbortSignal.any([controller.signal,AbortSignal.timeout(16500)])
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
export function initAutomaticSources(){
  document.addEventListener('moosa-player-closed',cancelAutomaticSourceSearch);
}
