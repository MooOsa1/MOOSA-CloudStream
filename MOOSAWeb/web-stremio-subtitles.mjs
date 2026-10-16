/**
 * CineStream/StreamPlay-compatible Stremio subtitle resource client.
 *
 * Upstream Android providers call:
 * /subtitles/movie/tt...json and
 * /subtitles/series/tt...:season:episode.json.
 *
 * Operators configure trusted HTTPS manifest URLs via WEB_SOURCE_ADDONS.
 * Never request a user-selected provider, send cookies, proxy media, or
 * execute CloudStream Android extensions. Fail closed for unapproved URL,
 * a mismatching resource type, invalid language, format or episode.
 */
const IMDB=/^tt\d{5,12}$/;
const SUB_EXT=/\.(?:vtt|srt)$/i;
const BAD_HOST=/(?:^localhost$|\.localhost$|\.local$|\.internal$|\.test$|\.invalid$)/i;
function safeHttps(value,{subtitle=false}={}){
  if(typeof value!=='string'||value.length>2500)return null;
  try{
    const u=new URL(value);
    if(u.protocol!=='https:'||u.username||u.password||
      BAD_HOST.test(u.hostname)||u.hostname.includes(':')||
      /^(?:\d+\.){3}\d+$/.test(u.hostname))return null;
    if(subtitle&&!SUB_EXT.test(u.pathname))return null;
    return u.href;
  }catch{return null;}
}
function languageOf(value){
  const x=String(value||'').trim().toLowerCase();
  if(/^(?:ar|ara|arabic|ar-[a-z]{2}|العربية|عربي)$/.test(x))return 'ar';
  if(/^(?:en|eng|english|en-[a-z]{2})$/.test(x))return 'en';
  return null;
}
function supportsResource(manifest,name,type,imdbId){
  if(!Array.isArray(manifest?.types)||!manifest.types.includes(type)||
    !Array.isArray(manifest.resources))return false;
  if(Array.isArray(manifest.idPrefixes)&&manifest.idPrefixes.length &&
    !manifest.idPrefixes.some(p=>typeof p==='string'&&imdbId.startsWith(p)))
    return false;
  return manifest.resources.some(resource=>{
    if(resource===name)return true;
    return resource?.name===name &&
      (!Array.isArray(resource.types)||resource.types.includes(type)) &&
      (!Array.isArray(resource.idPrefixes)||resource.idPrefixes.some(p=>
        typeof p==='string'&&imdbId.startsWith(p)));
  });
}
async function fetchJson(url,fetchImpl,signal){
  const response=await fetchImpl(url,{
    method:'GET',redirect:'error',
    headers:{Accept:'application/json'},signal
  });
  if(!response.ok || !String(response.headers.get('content-type')||'').includes('json') ||
    Number(response.headers.get('content-length')||0)>450_000)return null;
  const txt=await response.text();
  if(txt.length>450_000)return null;
  try{return JSON.parse(txt);}catch{return null;}
}
export function selectStremioSubtitles(data,{sourceName='Stremio subtitles'}={}){
  if(!Array.isArray(data?.subtitles))return [];
  const seen=new Set();
  return data.subtitles.slice(0,35).flatMap(item=>{
    const lang=languageOf(item?.lang||item?.lang_code||item?.language);
    const url=safeHttps(item?.url,{subtitle:true});
    if(!lang||!url||seen.has(url))return [];
    if(item?.headers||item?.referer||item?.cookie||item?.drm)return [];
    seen.add(url);
    return [{
      url,language:lang,
      label:String(item.id||item.name||item.label||lang).slice(0,64),
      source:String(sourceName).slice(0,45)
    }];
  }).sort((a,b)=>Number(b.language==='ar')-Number(a.language==='ar'))
    .slice(0,16);
}
export function stremioSubtitleIdentity(meta){
  if(!IMDB.test(meta?.imdbId||''))return null;
  if(meta.media==='movie')return {type:'movie',id:meta.imdbId};
  if(meta.media==='tv' &&
    Number.isInteger(meta.season)&&meta.season>=0&&meta.season<=100 &&
    Number.isInteger(meta.episode)&&meta.episode>=1&&meta.episode<=1000)
    return {type:'series',id:meta.imdbId+':'+meta.season+':'+meta.episode};
  return null;
}
export async function collectStremioAddonSubtitles(meta,{
  addons=[],fetchImpl=fetch,deadlineMs=2800
}={}){
  const identity=stremioSubtitleIdentity(meta);
  if(!identity||!Array.isArray(addons)||!addons.length)return [];
  const urls=[...new Set(addons)].slice(0,6);
  const results=await Promise.allSettled(urls.map(async manifestUrl=>{
    const safe=safeHttps(manifestUrl);
    if(!safe||!new URL(safe).pathname.endsWith('/manifest.json'))return [];
    const signal=AbortSignal.timeout(Math.min(4000,Math.max(250,deadlineMs)));
    const manifest=await fetchJson(safe,fetchImpl,signal);
    if(!supportsResource(manifest,'subtitles',identity.type,meta.imdbId))return [];
    const endpoint=safe.slice(0,-'manifest.json'.length)+'subtitles/'+
      identity.type+'/'+encodeURIComponent(identity.id)+'.json';
    const data=await fetchJson(endpoint,fetchImpl,signal);
    return selectStremioSubtitles(data,{sourceName:manifest.name||'Stremio subtitles'});
  }));
  const seen=new Set();
  return results.flatMap(r=>r.status==='fulfilled'?r.value:[])
    .filter(s=>!seen.has(s.url)&&seen.add(s.url)).slice(0,20);
}
