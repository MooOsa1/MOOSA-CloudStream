/**
 * Server-native JavaScript provider integration for the ordinary TMDB catalog.
 *
 * The reviewed provider runs locally on Render (no phone pairing). Only film
 * identities separately verified against the movie's original title AND
 * release year are admitted. Source URLs must also match independently
 * retrieved, per-file Creative Commons licensed Commons metadata exactly.
 *
 * CloudStream .cs3 Kotlin/Android extensions are NOT executable here.
 */
import approved from './js-approved-films.cjs';
import {createJsEngineDemo,matchVerifiedJsMedia} from './js-engine-demo.mjs';

const {films}=approved;
export const nativeJsEngine=createJsEngineDemo();

function titleKey(value){
  return String(value||'').normalize('NFKD').toLowerCase()
    .replace(/[\u0300-\u036f]/g,'').replace(/[^a-z0-9]+/g,' ').trim()
    .replace(/\s+/g,' ');
}

/** Catalog metadata is trusted only as an identity suggestion, never rights. */
export function nativeJsFilmForCatalog(meta){
  if(meta?.media!=='movie' || !Number.isInteger(Number(meta.id)) ||
    Number(meta.id)<1 || !Number.isInteger(Number(meta.year)))return null;
  const actual=titleKey(meta.originalTitle||meta.title);
  if(!actual)return null;
  const year=Number(meta.year);
  return films.find(f=>f.year===year && titleKey(f.title)===actual)||null;
}

/** Keep verified independent originals if worker fails, stalls or disagrees. */
export async function augmentCatalogCommonsWithNativeJs(meta,commonsSources,{
  load=(id)=>nativeJsEngine.load(id),timeoutMs=1400
}={}){
  const sources=Array.isArray(commonsSources)?commonsSources:[];
  const film=nativeJsFilmForCatalog(meta);
  if(!film || !sources.length)return {
    sources,engine:'commons_metadata',attempted:false,verified:0
  };
  let timer;
  let verified;
  try{
    verified=await Promise.race([
      Promise.resolve().then(()=>load(film.slug)).catch(()=>null),
      new Promise(resolve=>{
        timer=setTimeout(()=>resolve(null),Math.max(1,Math.min(timeoutMs,3000)));
      })
    ]);
  }finally{clearTimeout(timer);}
  const matched=matchVerifiedJsMedia({id:film.slug,sources},verified);
  return {
    sources:matched||sources,
    engine:matched?'reviewed_js_worker_preflight':'commons_metadata',
    attempted:true,
    verified:matched?.filter(s=>s.preflight?.verified===true).length||0
  };
}
