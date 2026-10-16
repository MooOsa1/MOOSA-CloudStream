import {showPlayer,appendPlayerSources} from './player.js';

const $=selector=>document.querySelector(selector);
let currentJob=0;

function status(message){const el=$('#free-library-status');if(el)el.textContent=message;}
async function jsonGet(path){
  const response=await fetch(path,{headers:{Accept:'application/json'},
    signal:AbortSignal.timeout(16000)});
  if(!response.ok)throw new Error('HTTP '+response.status);
  return response.json();
}
export async function playCuratedFilm(id,button=null){
  const job=++currentJob;
  if(button)button.disabled=true;
  try {
    const film=await jsonGet('/api/free-film?id='+encodeURIComponent(id));
    if(job!==currentJob)return;
    if(!Array.isArray(film.sources)||!film.sources.length){
      status('رابط الفيلم غير متاح حاليًا. جرّب فيلمًا آخر من القسم.');
      return;
    }
    // This is an independent rights-verified film; do not label it as an
    // automatic source for any previously selected title.
    showPlayer({title:film.title+' — فيلم مجاني',sources:[]});
    appendPlayerSources(film.sources);
    $('#automatic-source-status').textContent='فيلم مستقل برخصة مفتوحة • '+film.sources.length+
      ' مصدر متاح • تقدر تختار جودة أخرى من قائمة المصدر.';
  }catch(e){
    if(job===currentJob)status('تعذر التأكد من مصدر هذا الفيلم. حاول مرة ثانية.');
  }finally{if(button)button.disabled=false;}
}
function makeFreeCard(film){
  const button=document.createElement('button');
  button.type='button';
  button.className='free-film-card';
  button.dataset.freeFilm=film.id;
  const art=document.createElement('div');art.className='free-film-art';
  const title=document.createElement('strong');title.textContent=film.title;
  const year=document.createElement('span');year.textContent=String(film.year);
  art.append(title,year);
  const body=document.createElement('div');body.className='free-film-details';
  const genre=document.createElement('span');genre.textContent=film.genre;
  const quality=document.createElement('span');
  quality.textContent='▶ '+(film.qualities?.length?film.qualities.join('p / ')+'p':'مشاهدة مباشرة');
  body.append(genre,quality);
  button.append(art,body);
  button.setAttribute('aria-label','شاهد '+film.title+' مجانًا');
  button.addEventListener('click',()=>{void playCuratedFilm(film.id,button);});
  return button;
}
export async function loadFreeLibrary(){
  const holder=$('#free-library-list');
  if(!holder)return;
  status('جاري التأكد من توفر الأفلام...');
  try{
    const data=await jsonGet('/api/free-library');
    const films=Array.isArray(data.films)?data.films:[];
    holder.replaceChildren(...films.map(makeFreeCard));
    status(films.length?'متوفر '+films.length+' أفلام كاملة بروابط مفتوحة الترخيص. اختر أي فيلم للتشغيل.':
      'ما حصلنا أفلامًا متاحة الآن من المصدر. جرّب لاحقًا.');
  }catch{
    status('تعذر التأكد من توفر الأفلام المجانية حاليًا.');
  }
}
