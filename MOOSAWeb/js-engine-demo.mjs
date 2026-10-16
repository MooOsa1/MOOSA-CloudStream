/**
 * Fixed, rights-reviewed JS worker media preflight on the free Render Node host.
 * Only four hand-reviewed, independently licensed Commons titles are allowed.
 * This proves a Node JS provider path, not Android CloudStream .cs3 support.
 */
import approved from './js-approved-films.cjs';
import {resolveBrowserReadyJsSources} from './js-provider-validation.mjs';
const {films,bySlug}=approved;
export const DEMO_META=Object.freeze({
  media:'movie',id:10378,title:'Big Buck Bunny',originalTitle:'Big Buck Bunny'
});
const HOSTS=Object.freeze(['upload.wikimedia.org']);
const GOOD_TTL=5*60_000,FAIL_TTL=45_000;
export function supportedJsFilmIds(){return films.map(f=>f.slug);}

export function createJsEngineDemo({run=resolveBrowserReadyJsSources,now=Date.now}={}){
  const cache=new Map(), pending=new Map();
  async function load(slug='big-buck-bunny'){
    const film=bySlug(slug);
    if(!film)return null;
    const hit=cache.get(slug);
    if(hit&&hit.expires>now())return hit.data;
    if(pending.has(slug))return pending.get(slug);
    const work=(async()=>{
      let data;
      try {
        const meta=film.slug==='big-buck-bunny'?DEMO_META:{
          media:'movie',id:film.id,title:film.title,originalTitle:film.title,year:film.year
        };
        const result=await run(meta,{
          provider:'commons-authorized',licensedMedia:true,
          permittedHosts:[...HOSTS],timeoutMs:3500
        });
        const sources=(Array.isArray(result.sources)?result.sources:[])
          .filter(s=>{
            try{return new URL(s.url).hostname==='upload.wikimedia.org' &&
              s.preflight?.verified===true && Number(s.quality)>=720;}
            catch{return false;}
          }).slice(0,4).map(s=>({
            name:'MOOSA JS Engine • Wikimedia Commons',
            quality:s.quality,url:s.url,
            subtitles:[],preferred:s.quality<=1080,
            preflight:{verified:true,mime:s.preflight.mime},
            ...(film.slug==='big-buck-bunny'?{
              licenseUrl:'https://creativecommons.org/licenses/by/3.0/',
              attribution:'Blender Foundation'
            }:{})
          }));
        data={
          engine:'reviewed_js_worker',filmId:film.slug,
          sample:film.title,authorizedContent:true,
          originalCloudStreamPlugins:false,
          state:sources.length?'browser_preflight_passed':'no_browser_approved_sources',
          sources,count:sources.length,
          caveat:'HEAD/CORS readiness is not guaranteed Safari playback. Original CineStream and StreamPlay .cs3 plugins are NOT running on Render.'
        };
      }catch{
        data={
          engine:'reviewed_js_worker',filmId:film.slug,
          sample:film.title,authorizedContent:true,
          originalCloudStreamPlugins:false,
          state:'temporarily_unavailable',sources:[],count:0,
          caveat:'This licensed JS sample has no verified stream at this moment.'
        };
      }
      cache.set(slug,{data,expires:now()+(data.count?GOOD_TTL:FAIL_TTL)});
      return data;
    })();
    pending.set(slug,work);
    try{return await work;}finally{if(pending.get(slug)===work)pending.delete(slug);}
  }
  return {load};
}

/**
 * Match only an independently metadata-licensed source URL for this EXACT film.
 * Do not drop alternate licensed sources: Safari may reject a preflight-passing
 * codec and needs a fallback. Original license/attribution always wins.
 */
export function matchVerifiedJsMedia(film,verified){
  if(!bySlug(film?.id)||!Array.isArray(film.sources)||
    verified?.filmId!==film.id||
    verified.state!=='browser_preflight_passed'||
    !Array.isArray(verified.sources))return null;
  const approvedUrls=new Map(verified.sources.filter(s=>
    s?.preflight?.verified===true && typeof s.url==='string')
    .map(s=>[s.url,s]));
  const matching=film.sources.filter(s=>approvedUrls.has(s.url));
  if(!matching.length)return null;
  return film.sources.map(s=>approvedUrls.has(s.url)?{
    ...s,
    preflight:{verified:true,mime:approvedUrls.get(s.url).preflight.mime}
  }:s);
}
