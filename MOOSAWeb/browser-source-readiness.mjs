/**
 * Browser-source readiness gate for operator-approved HTTPS video hosts.
 *
 * This is a strict, read-only preflight. It never proxies media or tries to
 * impersonate a referring site, acquire cookies, bypass rate limits or fetch
 * arbitrary URLs from plugin responses. URLs must match an explicitly trusted
 * CDN host; redirects are rejected. A passing result is a strong indication,
 * NOT a guarantee that a particular iPhone codec or signed stream will play.
 */

const VIDEO_MIME=Object.freeze({
  mp4:new Set(['video/mp4']),
  m4v:new Set(['video/mp4','video/x-m4v']),
  webm:new Set(['video/webm']),
  m3u8:new Set([
    'application/vnd.apple.mpegurl','application/x-mpegurl',
    'audio/mpegurl','audio/x-mpegurl'
  ])
});
const PUBLIC_ORIGIN='https://moosa-web.onrender.com';
const READY_LIMIT=12;
const COMMONS_HOSTS=Object.freeze(['upload.wikimedia.org']);

function parseCandidate(value,allowedHosts){
  if(typeof value!=='string'||value.length>3200)return null;
  try{
    const url=new URL(value);
    const host=url.hostname.toLowerCase();
    if(url.protocol!=='https:'||url.username||url.password||
      url.port || host.includes(':') ||
      !Array.isArray(allowedHosts)||
      !allowedHosts.some(h=>typeof h==='string'&&h.toLowerCase()===host))return null;
    const match=url.pathname.match(/\.(mp4|m4v|webm|m3u8)$/i);
    if(!match)return null;
    return {url:url.href,host,format:match[1].toLowerCase()};
  }catch{return null;}
}
function typeAccepted(contentType,format){
  const content=String(contentType||'').split(';')[0].trim().toLowerCase();
  return VIDEO_MIME[format]?.has(content)===true;
}
function corsAccepted(header,origin){
  const cors=String(header||'').trim();
  return cors==='*'||cors===origin;
}
function sanitizeStatus(status){
  if(status===401||status===403)return 'access_denied';
  if(status===429)return 'rate_limited';
  if(status>=300&&status<400)return 'redirect_blocked';
  if(status===404||status===410)return 'not_found';
  if(status===405||status===501)return 'head_unsupported';
  return 'http_error';
}
export async function inspectBrowserSource(source,{
  fetchImpl=fetch,allowedHosts=COMMONS_HOSTS,origin=PUBLIC_ORIGIN,
  timeoutMs=3500
}={}){
  const data=parseCandidate(source?.url,allowedHosts);
  if(!data)return {ready:false,reason:'unapproved_media_host_or_format'};
  if(!source||!Number.isInteger(Number(source.quality))||
     Number(source.quality)<720)return {ready:false,reason:'quality_not_verified'};
  const start=Date.now();
  try{
    const response=await fetchImpl(data.url,{
      method:'HEAD',redirect:'manual',
      headers:{Origin:origin,Accept:'video/*,application/vnd.apple.mpegurl'},
      signal:AbortSignal.timeout(Math.max(300,Math.min(10000,Number(timeoutMs)||3500)))
    });
    if(response.status!==200&&response.status!==206)
      return {ready:false,reason:sanitizeStatus(response.status),
        status:response.status,host:data.host,durationMs:Date.now()-start};
    const mime=response.headers.get('content-type')||'';
    const cors=response.headers.get('access-control-allow-origin');
    const length=Number(response.headers.get('content-length')||0);
    if(!typeAccepted(mime,data.format))
      return {ready:false,reason:'not_media',status:response.status,
        host:data.host,durationMs:Date.now()-start};
    if(!corsAccepted(cors,origin))
      return {ready:false,reason:'browser_origin_not_allowed',status:response.status,
        host:data.host,durationMs:Date.now()-start};
    if(!Number.isFinite(length)||length<0)
      return {ready:false,reason:'invalid_size',host:data.host};
    return {ready:true,reason:'head_video_cors_ok',status:response.status,
      host:data.host,mime:mime.split(';')[0].toLowerCase(),
      bytes:length||null,format:data.format,durationMs:Date.now()-start};
  }catch(error){
    return {ready:false,
      reason:error?.name==='TimeoutError'?'timeout':'network_error',
      host:data.host,durationMs:Date.now()-start};
  }
}
export async function checkApprovedSources(sources,{
  allowedHosts=COMMONS_HOSTS,fetchImpl=fetch,
  origin=PUBLIC_ORIGIN,max=READY_LIMIT,timeoutMs=3500
}={}){
  const candidates=Array.isArray(sources)?sources.slice(0,Math.max(0,Math.min(READY_LIMIT,max))):[];
  // Cap live network pressure to three HEAD requests at once, instead of
  // hammering a remote CDN or delaying the UI with dozens of preflights.
  const checked=new Array(candidates.length);
  let next=0;
  await Promise.all(Array.from({length:Math.min(3,candidates.length)},async()=>{
    while(next<candidates.length){
      const i=next++;
      const source=candidates[i];
      const result=await inspectBrowserSource(source,{
        allowedHosts,fetchImpl,origin,timeoutMs
      });
      checked[i]={source,result};
    }
  }));
  return {
    ready:checked.filter(x=>x.result.ready).map(x=>({...x.source,preflight:{
      verified:true,mime:x.result.mime,bytes:x.result.bytes
    }})),
    rejected:checked.filter(x=>!x.result.ready).map(x=>({
      // Log only the diagnostic reason. Never echo rejected stream URLs,
      // bearer tokens, signed parameters or third-party cookies.
      reason:x.result.reason,host:x.result.host||null,status:x.result.status||null
    }))
  };
}
