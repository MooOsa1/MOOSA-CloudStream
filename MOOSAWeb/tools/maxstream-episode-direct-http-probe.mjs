/**
 * HEAD + zero-media-byte Range request for the one actual modern episodic
 * direct-MP4 candidate from MaxStream's VidLink provider.
 *
 * We intentionally do NOT read copyrighted media bytes, cache movie content,
 * supply Referer or spoof user agents, relay/circumvent 403/429, or change
 * production. Plain browser-origin request checks CDN's public accessibility.
 * URI secrets and signed query parameters are never logged.
 */
const api='https://maxstream-api.maxstream123.workers.dev/v1/stream'+
  '?tmdb=106379&type=tv&season=1&episode=1&server=vidlink';
async function result(){
 const res=await fetch(api,{headers:{Accept:'application/json'},
   redirect:'error',signal:AbortSignal.timeout(13500)});
 if(!res.ok)return {apiStatus:res.status,reason:'backend_failed'};
 const doc=await res.json();
 if(doc.ok!==true||typeof doc.url!=='string')
   return {apiStatus:res.status,reason:'no_url'};
 const u=new URL(doc.url);
 if(u.protocol!=='https:'||u.hostname!=='bcdn.hakunaymatata.com'||
    !u.pathname.endsWith('.mp4')||u.port||u.username||u.password||
    (doc.headers&&Object.keys(doc.headers).length))
   return {apiStatus:res.status,reason:'not_browser_direct'};
 const output={
   apiStatus:res.status,cdnHost:u.hostname,claimedFormat:'mp4',
   declaredQualityOptions:Array.isArray(doc.qualities)?doc.qualities.slice(0,8).map(x=>String(x.label||x.quality||x.resolution||'').slice(0,18)):[],
   subtitleCount:Array.isArray(doc.subtitles)?doc.subtitles.length:0,
   mediaBytesRead:0,rightsProven:false,videoDecoded:false
 };
 for(const method of ['HEAD','GET']){
   const started=Date.now();
   try{
     const r=await fetch(doc.url,{
       method,redirect:'manual',headers:
         method==='GET'?{Range:'bytes=0-0',Origin:'https://moosa-web.onrender.com'}:
           {Origin:'https://moosa-web.onrender.com'},
       signal:AbortSignal.timeout(11000)
     });
     // Cancel immediately, no movie bytes ever read.
     await r.body?.cancel().catch(()=>{});
     const h=r.headers;
     output[method]={
       status:r.status,mime:(h.get('content-type')||'').slice(0,80),
       locationHost:(()=>{
         try{return new URL(h.get('location')).hostname}catch{return null}
       })(),
       contentRange:h.get('content-range')||null,
       contentLength:h.get('content-length')||null,
       acceptsRanges:h.get('accept-ranges')||null,
       cors:(h.get('access-control-allow-origin')||'').slice(0,80),
       corp:h.get('cross-origin-resource-policy')||null,
       elapsedMs:Date.now()-started
     };
   }catch(e){
     output[method]={error:String(e?.name||'unknown'),elapsedMs:Date.now()-started};
   }
 }
 return output;
}
let x;
try{x=await result()}catch(e){x={error:String(e?.name||'unknown')}}
console.log('MOOSA_MAXSTREAM_REAL_EPISODE_TRANSPORT '+JSON.stringify(x));
console.log('MOOSA_MAXSTREAM_EPISODE_VERDICT '+(
  x.HEAD?.status===200&&
  [200,206].includes(x.GET?.status)&&
  /^video\/(?:mp4|webm)/i.test(x.GET?.mime||'')?
  'MIME_AND_RANGE_POTENTIAL_ONLY_NOT_FRAME_PROOF_NOT_LICENSED':
  'NOT_BROWSER_PLAYABLE_IN_CURRENT_PUBLIC_TRANSPORT'));
