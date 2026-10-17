/**
 * A NEW, full-length widely indexed, US public-domain film — not Sintel,
 * synthetic media or another Blender clip. Request original CineStream JS
 * provider results for precisely Night of the Living Dead (1968)
 * TMDB 10331 / IMDb tt0063350. Scan for a REAL media byte signature.
 *
 * This diagnostic NEVER returns playable URLs to MOOSA viewers and does not
 * distribute a third-party remaster (which may have distinct rights).
 * Streaming isn't proven until exact movie identity/duration are validated.
 */
import assert from 'node:assert/strict';
const ORIGIN='http://127.0.0.1:4173';
const STREAMLINE='https://raw.githubusercontent.com/itsmeadarsh2008/Streamline/27c2cb2c864a056003241c48a26ede5a7cff037a/manifest.json';
const TARGET={title:'Night of the Living Dead',year:1968,tmdb:'10331',
  imdb:'tt0063350',expectedSeconds:5760};
async function firstBytes(res,max=10240){
  const reader=res.body?.getReader();if(!reader)return Buffer.alloc(0);
  const parts=[];let len=0;
  try{
    while(len<max){
      const chunk=await reader.read();
      if(chunk.done)break;
      const buf=Buffer.from(chunk.value).subarray(0,max-len);
      parts.push(buf);len+=buf.length;
    }
  }finally{await reader.cancel().catch(()=>{});}
  return Buffer.concat(parts);
}
function videoMagic(bytes,mime){
  const type=String(mime||'').split(';')[0].trim().toLowerCase();
  if(type==='video/mp4'||type==='application/octet-stream'){
    const box=bytes.subarray(4,8).toString('latin1');
    if(['ftyp','styp','moof'].includes(box))return 'mp4';
  }
  if((type==='video/webm'||type==='application/octet-stream')&&
      bytes.length>4&&bytes.subarray(0,4).equals(Buffer.from([26,69,223,163])))
    return 'webm';
  if((type==='video/mp2t'||type==='application/octet-stream')&&
      bytes.length>=377&&bytes[0]===71&&bytes[188]===71&&bytes[376]===71)
    return 'mpegts';
  return null;
}
const response=await fetch(ORIGIN+'/api/plugins/resolve',{
  method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify({repositoryUrl:STREAMLINE,mediaType:'movie',tmdbId:TARGET.tmdb}),
  signal:AbortSignal.timeout(180000)});
assert.equal(response.status,200,'Original CineStream JS port must finish exact film search');
const data=await response.json();
const all=Array.isArray(data.streams)?data.streams:[];
const counts={};
for(const x of all){
  const key=String(x.provider||'unknown').slice(0,40);
  counts[key]=(counts[key]||0)+1;
}
const sources=all.filter(x=>
  /^\/api\/media\/[A-Za-z0-9_-]{32,128}$/.test(x.url||'')).slice(0,24);
console.log('MOOSA_ORIGINAL_FEATURE_CINESTREAM_RESOLVE '+JSON.stringify({
  title:TARGET.title,year:TARGET.year,tmdbId:TARGET.tmdb,
  providerCount:data.diagnostics?.length||0,issuedCapabilities:sources.length,
  providers:counts,
  correctFilmBytesProven:false
}));
let authenticVideoBytes=0,hlsManifests=0,wrongMime=0,upstreamRefused=0;
for(let i=0;i<sources.length;i++){
  const item=sources[i],safeProvider=String(item.provider||'unknown').slice(0,50);
  let route=item.url;
  let status='not_checked';
  let format='unknown';
  let confirmed=null;
  try{
    for(let depth=0;depth<5;depth++){
      const res=await fetch(ORIGIN+route,{
        method:'GET',redirect:'error',signal:AbortSignal.timeout(8500),
        headers:{Range:'bytes=0-10239'}});
      const mime=(res.headers.get('content-type')||'').split(';')[0].toLowerCase();
      status=String(res.status);format=mime;
      if(![200,206].includes(res.status)){
        upstreamRefused++;
        await res.body?.cancel().catch(()=>{});
        break;
      }
      if(/mpegurl/.test(mime)){
        hlsManifests++;
        const bytes=await firstBytes(res,200000);
        const lines=bytes.toString('utf8').split(/\r?\n/);
        if(!lines[0]?.startsWith('#EXTM3U')){wrongMime++;break;}
        const next=lines.find(x=>x.trim()&&!x.startsWith('#'));
        if(!next||!/^\/api\/media\/[A-Za-z0-9_-]{32,128}$/.test(next.trim())){
          wrongMime++;break;
        }
        route=next.trim();continue;
      }
      const bytes=await firstBytes(res);
      confirmed=videoMagic(bytes,mime);
      if(confirmed) authenticVideoBytes++;
      else wrongMime++;
      break;
    }
  }catch(e){status=String(e.name||'network_error').slice(0,35);}
  // Deliberately never print any media capability token, URL path or headers.
  console.log('MOOSA_ORIGINAL_FEATURE_MEDIA_PROBE '+JSON.stringify({
    i:i+1,provider:safeProvider,httpStatus:status,mime:format,
    actualVideoBytes:!!confirmed,codecContainer:confirmed||null
  }));
}
console.log('MOOSA_ORIGINAL_FEATURE_RESULT '+JSON.stringify({
  target:'Night of the Living Dead (1968)',
  candidateCount:sources.length,hlsManifests,authenticVideoBytes,
  upstreamRefused,wrongMime,
  exactMovieIdentityConfirmed:false,
  filmDurationConfirmed:false,browserPlaybackConfirmed:false,
  deployAllowed:false
}));
assert.ok(data.diagnostics?.length>0,'No genuine original provider execution');
assert.ok(authenticVideoBytes>0,
  'No provider returned real video bytes for this full-length movie — do not deploy');
