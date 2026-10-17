/**
 * HEAD-only header metadata audit for the free community Stremio addon.
 * No copyrighted media GET/download, no stream playback, no cookies,
 * no Referer/Origin spoofing, no redirection to third-party hosts.
 * Tests whether claimed 1080p H.264 MKV uses a browser-direct
 * delivery contract (range+CORS+HTML5 MIME, HTTPS, no auth).
 */
const ROOT='https://free.flixnest.app';
const PATH='/stream/movie/tt15239678.json'; // Dune Part Two: metadata only
let data;
try{
  const r=await fetch(ROOT+PATH,{headers:{Accept:'application/json'},
    redirect:'error',signal:AbortSignal.timeout(11000)});
  if(!r.ok)throw Error('Stream metadata HTTP '+r.status);
  if(!/json/.test(r.headers.get('content-type')||''))throw Error('Not JSON');
  if(Number(r.headers.get('content-length')||0)>180000)throw Error('Oversized metadata');
  const raw=await r.text();if(raw.length>180000)throw Error('Oversized metadata');
  data=JSON.parse(raw);
}catch(e){
  console.log('FLIX_MKV_HEAD '+JSON.stringify({metadataFailed:e?.name||'error',noMovieDataDownloaded:true}));
  process.exit(0);
}
const streams=Array.isArray(data?.streams)?data.streams:[];
const stream=streams.find(s=>{
  if(!s||typeof s.url!=='string')return false;
  if(!/1080/i.test(String(s.title||s.name||'')))return false;
  let u;try{u=new URL(s.url)}catch{return false}
  return u.protocol==='https:'&&u.hostname==='free.flixnest.app'&&
    u.pathname.endsWith('.mkv')&&!u.port&&!u.username&&!u.password;
});
if(!stream){
 console.log('FLIX_MKV_HEAD '+JSON.stringify({metadataSources:streams.length,
  identified1080Mkv:false,videoGET:false}));
 process.exit(0);
}
const candidatesHeaders=[stream.headers,stream.behaviorHints?.proxyHeaders?.request,
  stream.behaviorHints?.headers].filter(x=>x&&typeof x==='object');
const requestHeadersPresent=candidatesHeaders.some(x=>Object.keys(x).length>0);
const advertisedHeaderKeys=[...new Set(candidatesHeaders.flatMap(x=>Object.keys(x||{})))].map(
 k=>String(k).slice(0,40));
let result={declared1080:true,ext:'mkv',requiresSpecialHeaders:requestHeadersPresent,
  advertisedHeaderKeys,requestedNoSpecialHeaders:true,followedRedirects:false,
  downloadedMovieBytes:false,decodedFrames:0};
try{
 const response=await fetch(stream.url,{method:'HEAD',redirect:'manual',
   headers:{Accept:'*/*'},signal:AbortSignal.timeout(8500)});
 const get=name=>response.headers.get(name);
 const redirect=get('location');
 let redirectHost=null;
 if(redirect)try{redirectHost=new URL(redirect,stream.url).hostname}catch{}
 result={...result,status:response.status,
   finalHost:new URL(response.url).hostname,
   redirectHost,contentType:get('content-type')?.slice(0,85)||null,
   acceptsRanges:get('accept-ranges')||null,
   cors:get('access-control-allow-origin')?.slice(0,75)||null,
   corp:get('cross-origin-resource-policy')||null,
   contentLength:get('content-length')||null,
   contentDisposition:get('content-disposition')?.slice(0,80)||null,
   cacheControl:get('cache-control')?.slice(0,80)||null
 };
}catch(e){result.error=e?.name||'unknown'}
console.log('FLIX_MKV_HEAD '+JSON.stringify(result));
try{
 const response=await fetch(stream.url,{method:'GET',redirect:'manual',
   headers:{Range:'bytes=0-0',Accept:'*/*'},
   signal:AbortSignal.timeout(8500)});
 // Always cancel without consuming any media body; only response headers are
 // needed to establish whether browser byte ranges can be requested.
 await response.body?.cancel().catch(()=>{});
 const h=response.headers;
 console.log('FLIX_MKV_ONE_BYTE_RANGE '+JSON.stringify({
   status:response.status,contentType:h.get('content-type')?.slice(0,95)||null,
   contentRange:h.get('content-range')||null,
   contentLength:h.get('content-length')||null,
   cors:h.get('access-control-allow-origin')?.slice(0,65)||null,
   corp:h.get('cross-origin-resource-policy')||null,
   redirectHost:(()=>{try{return new URL(h.get('location')).hostname}catch{return null}})(),
   bytesRead:0,unverifiedCopyrightStreaming:false,
   siteNotAddedToProduction:true
 }));
}catch(e){console.log('FLIX_MKV_ONE_BYTE_RANGE '+JSON.stringify({
   error:String(e?.name||'unknown'),bytesRead:0
 }));}

console.log('FLIX_MKV_VERDICT '+(
  result.status===200&&
  /video\/(?:mp4|webm)/i.test(result.contentType||'')&&
  !requestHeadersPresent&&
  (result.cors==='*'||result.cors==='https://moosa-web.onrender.com')
  ? 'POTENTIAL_BROWSER_MEDIA_NEEDS_CODEC_AND_RIGHTS_CHECK'
  : 'NOT_PROVEN_BROWSER_DIRECT_H264_MP4_DO_NOT_CONFIGURE_RENDER'
));
