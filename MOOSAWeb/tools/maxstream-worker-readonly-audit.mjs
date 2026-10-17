/**
 * MOOSA read-only evaluation of current MaxStream Cloudflare Worker HTTP API.
 * Endpoint derived directly from upstream MaxStream WebStreamService:
 *   https://maxstream-api.maxstream123.workers.dev/v1/stream
 * This tests ONLY JSON stream-resolution metadata, no copyright video
 * downloads, no credentials, proxy/DRM bypass, site playback or iframe.
 *
 * Valid candidate: ordinary HTTPS mp4/webm/m3u8, NO custom request headers,
 * NO redirection, NOT a hosted embed, NOT a signed HLS disguised as an mp4.
 * Displayed quality from upstream is unverified until actual decoding.
 */
const WORKER='https://maxstream-api.maxstream123.workers.dev';
const CASES=[
 {label:'Sintel 2010 (CC)',type:'movie',tmdb:45745},
 {label:'Dune Part Two 2024 (metadata only)',type:'movie',tmdb:693134},
 {label:'Fallout S01E01 2024 (metadata only)',type:'tv',tmdb:106379,season:1,episode:1}
];
const PROVIDERS=['netmirror','vixsrc','vidlink','videasy'];
const capped=(v,length=90)=>String(v||'').slice(0,length);
function verdict(p){
 const media=p?.url;
 if(p?.ok!==true||typeof media!=='string'||!media)return {kind:'no_stream'};
 let u;try{u=new URL(media)}catch{return {kind:'invalid_link'}}
 const headers=p.headers&&typeof p.headers==='object'?Object.keys(p.headers):[];
 const protectedHeaders=headers.filter(x=>
   /^(?:referer|origin|cookie|authorization|x-api-key|proxy-authorization)$/i.test(x));
 const special=headers.length>0;
 const expected=/\.(?:mp4|webm|m4v|m3u8)$/i.test(u.pathname);
 const type=capped(p.type,25);
 const basic=u.protocol==='https:'&&!!u.hostname.includes('.')&&
   !u.username&&!u.password&&!u.port&&!u.hash&&
   !/^(?:127\.|10\.|0\.|169\.254\.|192\.168\.|localhost)/i.test(u.hostname);
 let kind= !basic?'unsupported_url':
   special?'requires_custom_headers':
   !expected?'non_direct_or_embed':
   'direct_candidate_not_playback';
 return {kind,host:capped(u.hostname),ext:(u.pathname.match(/\.([a-z0-9]{2,6})$/i)||[])[1]||'none',
  sourceType:type,qualityClaims:Array.isArray(p.qualities)?p.qualities.slice(0,3).map(x=>
    capped(x?.label||x?.quality||x?.resolution||'',24)):[],
  subtitleEntries:Array.isArray(p.subtitles)?p.subtitles.length:0,
  protectedHeaders,otherHeaderKeyCount:headers.length,
  sourceLabel:capped(p.label||p.provider,46),
  playbackProven:false,rightsProven:false};
}
async function run(test,server){
 const u=new URL('/v1/stream',WORKER);
 for(const [k,v] of Object.entries({...test,server}))if(!['label'].includes(k))
   u.searchParams.set(k,String(v));
 try{
   const r=await fetch(u,{headers:{Accept:'application/json'},
     redirect:'error',signal:AbortSignal.timeout(10500)});
   let data=null,bytes=0;
   const ct=r.headers.get('content-type')||'';
   if(r.ok&&ct.includes('json')&&Number(r.headers.get('content-length')||0)<180000){
     const raw=await r.text();bytes=raw.length;
     if(bytes<=180000)try{data=JSON.parse(raw)}catch{}
   }else{await r.body?.cancel().catch(()=>{})}
   const v=verdict(data);
   console.log('MAXSTREAM_SOURCE_PROBE '+JSON.stringify({
     title:test.label,server,status:r.status,
     contentType:capped(ct,70),size:bytes,
     ...v,
     upstreamError:capped(data?.error||data?.message,110),
     movieBytesDownloaded:0,verified1080Frames:0
   }));
   return v;
 }catch(e){
   console.log('MAXSTREAM_SOURCE_PROBE '+JSON.stringify({
      title:test.label,server,networkError:capped(e.name,50),
      kind:'request_error',movieBytesDownloaded:0,verified1080Frames:0
   }));
   return {kind:'request_error'};
 }
}
const all=[];
for(const t of CASES){
  // Keep concurrency low and avoid abusing a community-owned free worker.
  for(let i=0;i<PROVIDERS.length;i+=2){
    all.push(...await Promise.all(PROVIDERS.slice(i,i+2).map(s=>run(t,s))));
  }
}
const counts={};
for(const a of all)counts[a.kind]=(counts[a.kind]||0)+1;
console.log('MAXSTREAM_SOURCE_SUMMARY '+JSON.stringify({
  requests:all.length,counts,
  browserStreamReady:0,
  siteSourcesAdded:0,unlicensedContentPlayed:false,
  decisiveNextStep:counts.direct_candidate_not_playback?
    'validate_authorized_film_browser_and_segment_no_proxy':
    'not_ready_for_browser_do_not_deploy'
}));
