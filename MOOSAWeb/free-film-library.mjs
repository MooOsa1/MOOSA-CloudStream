/**
 * Small curated discovery shelf for open-licensed full films.
 * A film is listed ONLY when Wikimedia Commons returns source-level verified
 * CC/public-domain metadata and a direct video file, no arbitrary embeds.
 *
 * This is not a commercial film catalog; no claims about CineStream .cs3.
 */
import {fromCommons,curatedExactFile,selectCommonsMovies} from './commons-provider.mjs';

const FILMS=Object.freeze([
  Object.freeze({id:'singularity-2026',title:'Singularity',year:2026,creator:'Blender Studio',genre:'خيال علمي • مغامرة فضائية'}),
  Object.freeze({id:'wing-it',title:'Wing It!',year:2023,creator:'Blender Studio',genre:'أنيميشن • مغامرة'}),
  Object.freeze({id:'charge',title:'Charge',year:2022,creator:'Blender Studio',genre:'خيال علمي • أكشن'}),
  Object.freeze({id:'sprite-fright',title:'Sprite Fright',year:2021,creator:'Blender Studio',genre:'رعب • أنيميشن'}),
  Object.freeze({id:'coffee-run',title:'Coffee Run',year:2020,creator:'Blender Studio',genre:'دراما • أنيميشن'}),
  Object.freeze({id:'spring',title:'Spring',year:2019,creator:'Blender Studio',genre:'فانتازيا • أنيميشن'}),
  Object.freeze({id:'hero',title:'Hero',year:2018,creator:'Blender Studio',genre:'أنيميشن • فني'}),
  Object.freeze({id:'daily-dweebs',title:'The Daily Dweebs',year:2017,creator:'Blender Studio',genre:'أنيميشن • كوميديا'}),
  Object.freeze({id:'glass-half',title:'Glass Half',year:2015,creator:'Blender Studio',genre:'أنيميشن • كوميديا'}),
  Object.freeze({id:'cosmos-laundromat',title:'Cosmos Laundromat: First Cycle',year:2015,creator:'Blender Studio',genre:'فانتازيا • دراما'}),
  Object.freeze({id:'caminandes-gran-dillama',title:'Caminandes: Gran Dillama',year:2013,creator:'Blender Studio',genre:'أنيميشن • كوميديا'}),
  Object.freeze({id:'tears-of-steel',title:'Tears of Steel',year:2012,creator:'Blender Foundation',genre:'خيال علمي • أكشن'}),
  Object.freeze({id:'sintel',title:'Sintel',year:2010,creator:'Blender Foundation',genre:'فانتازيا • مغامرة'}),
  Object.freeze({id:'big-buck-bunny',title:'Big Buck Bunny',year:2008,creator:'Blender Foundation',genre:'أنيميشن • كوميديا'}),
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
// Batch 11 vetted newer films in one Commons imageinfo request rather than
// flooding free Wikimedia with 11 independent searches on every shelf visit.
// Individual film playback always revalidates its complete media metadata.
let recentShelfCache=null;
async function listRecentVerifiedFilms({fetchImpl=fetch,now=Date.now}={}){
  if(fetchImpl===fetch&&recentShelfCache&&recentShelfCache.expires>now())
    return recentShelfCache.promise;
  const reviewed=FILMS.filter(f=>curatedExactFile(f.title,f.year));
  const urls=reviewed.map(f=>curatedExactFile(f.title,f.year));
  const api=new URL('https://commons.wikimedia.org/w/api.php');
  Object.entries({
    action:'query',prop:'imageinfo',iiprop:'url|size|mime|extmetadata',
    titles:urls.join('|'),format:'json',formatversion:'2'
  }).forEach(([k,v])=>api.searchParams.set(k,v));
  const pending=(async()=>{
    try{
      const response=await fetchImpl(api.href,{
        headers:{Accept:'application/json',
          'User-Agent':'MOOSAWeb/0.1 (https://github.com/MooOsa1/MOOSA-CloudStream; verified CC film discovery)'},
        redirect:'error',signal:AbortSignal.timeout(5500)
      });
      if(!response.ok||Number(response.headers.get('content-length')||0)>900000)return [];
      const raw=await response.text();
      if(raw.length>900000)return [];
      const data=JSON.parse(raw);
      return reviewed.flatMap(f=>{
        const matched=selectCommonsMovies({
          media:'movie',title:f.title,originalTitle:f.title,year:f.year
        },data);
        if(!matched.length)return [];
        return [{
          ...f,available:true,qualities:matched.map(x=>x.quality),
          smallestSizeBytes:Math.min(...matched.map(x=>x.sizeBytes)),
          license:matched.every(x=>x.licenseUrl===matched[0].licenseUrl)
            ?matched[0].licenseUrl:'Creative Commons'
        }];
      });
    }catch{return [];}
  })();
  if(fetchImpl===fetch){
    recentShelfCache={promise:pending,expires:now()+60_000};
    void pending.then(items=>{
      if(recentShelfCache?.promise===pending)
        recentShelfCache.expires=Date.now()+(items.length?7*60_000:35_000);
    });
  }
  return pending;
}
export async function listCuratedFilms(options={}){
  // Preserve four historically verified films; batch recent vetted Commons
  // entries so the shelf remains fast even on the free Render tier.
  const legacy=FILMS.filter(f=>!curatedExactFile(f.title,f.year));
  const [newFilms,oldFilms]=await Promise.all([
    listRecentVerifiedFilms(options),
    Promise.all(legacy.map(f=>getCuratedFilm(f.id,options)))
  ]);
  const available=new Map([
    ...newFilms.map(f=>[f.id,f]),
    ...oldFilms.filter(f=>f?.available).map(({sources,...f})=>[f.id,f])
  ]);
  return FILMS.map(f=>available.get(f.id)).filter(Boolean);
}
