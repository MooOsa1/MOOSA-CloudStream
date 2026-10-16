/**
 * A locally reviewed Nuvio-compatible implementation for ONE open-licensed
 * title. Uses the existing strict Wikimedia Commons video-source verifier.
 *
 * Proves a local Node plugin can resolve an actual approved source through
 * the sidecar. This is NOT CineStream/Vidlink/StreamPlay code.
 */
module.exports.getStreams=async function getStreams(tmdbId,mediaType,season,episode){
  if(mediaType!=='movie'||season!==null||episode!==null||
    !Number.isSafeInteger(tmdbId)||tmdbId<1)return [];
  const {byId}=require('../js-approved-films.cjs');
  const film=byId(tmdbId);
  if(!film)return [];
  const {fromCommons}=await import('../commons-provider.mjs');
  const sources=await fromCommons({
    media:'movie',title:film.title,originalTitle:film.title,
    year:film.year,id:film.id
  });
  return sources.map(s=>({
    name:'Wikimedia Commons • '+new URL(s.licenseUrl).pathname,
    title:film.title+' '+s.quality+'p',
    quality:s.quality+'p',
    url:s.url,
    headers:{},
    subtitles:[],
    licenseUrl:s.licenseUrl,
    attribution:s.attribution
  }));
};
