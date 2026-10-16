/**
 * Small curated discovery shelf for open-licensed full films.
 * A film is listed ONLY when Wikimedia Commons returns source-level verified
 * CC/public-domain metadata and a direct video file, no arbitrary embeds.
 *
 * This is not a commercial film catalog; no claims about CineStream .cs3.
 */
import {fromCommons} from './commons-provider.mjs';

const FILMS=Object.freeze([
  Object.freeze({id:'big-buck-bunny',title:'Big Buck Bunny',year:2008,creator:'Blender Foundation',genre:'أنيميشن • كوميديا'}),
  Object.freeze({id:'sintel',title:'Sintel',year:2010,creator:'Blender Foundation',genre:'فانتازيا • مغامرة'}),
  Object.freeze({id:'tears-of-steel',title:'Tears of Steel',year:2012,creator:'Blender Foundation',genre:'خيال علمي • أكشن'}),
  Object.freeze({id:'elephants-dream',title:'Elephants Dream',year:2006,creator:'Orange Open Movie Project',genre:'أنيميشن • خيال'})
]);
const cache=new Map();
const TTL_OK=8*60_000;
const TTL_EMPTY=55_000;

export function curatedFilmIds(){return FILMS.map(x=>x.id);}
export function findCuratedFilm(id){return FILMS.find(x=>x.id===id)||null;}
export async function getCuratedFilm(id,{fetchImpl=fetch,now=Date.now}={}){
  const film=findCuratedFilm(id);
  if(!film)return null;
  const key=id;
  const at=now();
  const cached=cache.get(key);
  if(fetchImpl===fetch && cached && cached.expires>at)return cached.promise;
  const pending=fromCommons({media:'movie',title:film.title,originalTitle:film.title,year:film.year},{fetchImpl})
    .then(sources=>({
      ...film,available:sources.length>0,
      sources:sources.slice(0,4),
      qualities:sources.map(x=>x.quality),
      smallestSizeBytes:sources.length?Math.min(...sources.map(x=>x.sizeBytes||Number.MAX_SAFE_INTEGER)):null,
      license:sources.length?new Set(sources.map(x=>x.licenseUrl)).size===1?
        sources[0].licenseUrl:'Creative Commons':null
    })).catch(()=>({...film,available:false,sources:[],qualities:[],license:null}));
  if(fetchImpl===fetch){
    cache.set(key,{promise:pending,expires:at+TTL_EMPTY});
    void pending.then(value=>{
      if(cache.get(key)?.promise===pending)cache.set(key,{
        promise:Promise.resolve(value),expires:Date.now()+(value.available?TTL_OK:TTL_EMPTY)
      });
    });
  }
  return pending;
}
export async function listCuratedFilms(options={}){
  const full=await Promise.all(FILMS.map(f=>getCuratedFilm(f.id,options)));
  return full.filter(f=>f.available).map(({sources,...film})=>film);
}
