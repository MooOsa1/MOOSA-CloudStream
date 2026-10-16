/**
 * Adapter from Nuvio/Streamline getStreams() results into MOOSA Web sources.
 * No Streamline code is bundled here. This adapter NEVER executes a third
 * party plugin and never bypasses Referer/CORS/DRM or site access controls.
 *
 * The caller must independently verify the exact media match and that the
 * viewer/operator has distribution/playback authorization for the URL.
 */
const SAFE_VIDEO=/\.(?:m3u8|mp4|webm)(?:$|[?#])/i;
const HEADERS_BLOCKED=new Set([
  'authorization','cookie','referer','origin','proxy-authorization','x-api-key'
]);
const LOCAL_HOST=/(?:^localhost$|\.localhost$|\.local$|\.internal$|\.test$|\.invalid$)/i;
function httpsMedia(url){
  if(typeof url!=='string'||url.length>4096)return null;
  try{
    const parsed=new URL(url);
    if(parsed.protocol!=='https:'||parsed.username||parsed.password||
       LOCAL_HOST.test(parsed.hostname)||parsed.hostname.includes(':')||
       /^(?:127\.|10\.|0\.|192\.168\.|169\.254\.|172\.(?:1[6-9]|2\d|3[01])\.)/.test(parsed.hostname))return null;
    if(!SAFE_VIDEO.test(parsed.pathname))return null;
    return parsed.href;
  }catch{return null;}
}
function qualityLabel(raw){
  if(typeof raw==='number'&&Number.isInteger(raw))return raw;
  const s=String(raw??'').toLowerCase().trim();
  if(/(?:cam|telesync|hdcam|\bts\b)/.test(s))return null;
  if(/\b(?:4k|uhd|2160p?)\b/.test(s))return 2160;
  const m=s.match(/(?:^|\D)(1080|720|1440|2160|480|360)p?(?:\D|$)/);
  return m?Number(m[1]):null;
}
function unauthorizedHeaderCount(value){
  if(!value||typeof value!=='object'||Array.isArray(value))return 0;
  return Object.keys(value).filter(k=>HEADERS_BLOCKED.has(k.toLowerCase())&&String(value[k]??'').trim()).length;
}
function languageCode(value){
  const s=String(value||'').toLowerCase();
  if(/^(?:ar(?:abic)?|العربية)$/.test(s))return 'ar';
  if(/^(?:en(?:glish)?)$/.test(s))return 'en';
  return null;
}
function subtitles(list){
  if(!Array.isArray(list))return [];
  const seen=new Set();
  return list.slice(0,25).flatMap(item=>{
    if(!item||typeof item!=='object')return [];
    // Video extensions are not subtitle extensions. Use a separate
    // restrictive HTTPS check without the media file extension.
    let sub;
    try {
      const x=new URL(item.url);
      if(x.protocol!=='https:'||LOCAL_HOST.test(x.hostname)||
        !/\.(?:srt|vtt)(?:$|[?#])/i.test(x.pathname))return [];
      sub=x.href;
    }catch{return [];}
    const language=languageCode(item.language||item.lang);
    if(!language||seen.has(sub))return [];
    seen.add(sub);
    return [{url:sub,language,label:String(item.name||language).slice(0,55)}];
  });
}
export function adaptNuvioStreams(results,{
  provider='Streamline',
  permittedHosts=[],
  licensedMedia=false
}={}){
  // Without rights confirmation and an explicit list of allowed CDN hosts
  // there are ZERO sources. Never expose unknown commercial links to viewers.
  if(!licensedMedia||!Array.isArray(permittedHosts)||!permittedHosts.length ||
    !Array.isArray(results))return {sources:[],rejected:0,blockedHeaders:0};
  const allowed=new Set(permittedHosts.map(x=>String(x).toLowerCase()));
  const sources=[],seen=new Set();
  let rejected=0,blockedHeaders=0;
  for(const item of results.slice(0,80)){
    if(!item||typeof item!=='object'){rejected++;continue;}
    const url=httpsMedia(item.url);
    const quality=qualityLabel(item.quality||item.title);
    if(!url||!quality||quality<720||quality>4320 ||
      !allowed.has(new URL(url).hostname.toLowerCase()) ||
      item.infoHash||item.magnet){rejected++;continue;}
    if(unauthorizedHeaderCount(item.headers)>0 ||
      item.referer || item.cookie || item.drm){
      blockedHeaders++;rejected++;continue;
    }
    if(seen.has(url))continue;
    seen.add(url);
    sources.push({
      url,quality,
      name:String(provider).slice(0,45)+' • '+String(item.title||item.name||'video').slice(0,72),
      subtitles:subtitles(item.subtitles)
    });
  }
  return {sources:sources.sort((a,b)=>b.quality-a.quality).slice(0,30),rejected,blockedHeaders};
}
