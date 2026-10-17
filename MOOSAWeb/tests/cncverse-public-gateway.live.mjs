/**
 * Public hosted CNCVerse CloudStream-to-Stremio gateway audit.
 * Independent approach that might run *original Kotlin .cs3* extensions
 * remotely without a user-side Android bridge or account/device pairing.
 *
 * READ-ONLY, publicly documented Stremio HTTP endpoints, no profile edits.
 * Never log video URLs, cookies, provider tokens, or private credentials.
 * This only measures availability; candidate count is not playable proof.
 */
import assert from 'node:assert/strict';
const base='https://cncverse.dpdns.org';
const checkedHost='cncverse.dpdns.org';
async function fetchAllowed(path,ms){
  const u=new URL(path,base);
  if(u.protocol!=='https:'||u.hostname!==checkedHost||u.port||
    !['/manifest.json','/stream/movie/tt0063350.json'].includes(u.pathname))
    throw Error('Unapproved bridge path');
  const r=await fetch(u,{redirect:'error',
    headers:{Accept:'application/json'},signal:AbortSignal.timeout(ms)});
  const type=r.headers.get('content-type')||'';
  if(Number(r.headers.get('content-length')||0)>2_000_000)
    throw Error('Oversized bridge JSON body');
  const body=await r.text();
  if(body.length>2_000_000)throw Error('Oversized bridge JSON response');
  return {status:r.status,mime:type,body};
}
try{
  const response=await fetchAllowed('/manifest.json',12000);
  let manifest=null;
  try{manifest=JSON.parse(response.body);}
  catch{}
  const valid=!!manifest &&
    typeof manifest.id==='string'&&Array.isArray(manifest.resources);
  console.log('MOOSA_HOSTED_ORIGINAL_CLOUDSTREAM_MANIFEST '+JSON.stringify({
    status:response.status,parseableStremioManifest:valid,
    providerName:valid?String(manifest.name||'').slice(0,55):null,
    movieTypes:valid?Array.isArray(manifest.types)&&manifest.types.includes('movie'):false,
    streamResource:valid?manifest.resources.some(x=>x==='stream'||x?.name==='stream'):false,
    loginOrAndroidDeviceRequiredByRequest:false
  }));
  assert.equal(response.status,200,'Public CNCVerse manifest not available');
  assert.equal(valid,true,'Public CNCVerse route did not return Stremio JSON');
  if(!manifest.types?.includes('movie')||
      !manifest.resources?.some(x=>x==='stream'||x?.name==='stream'))
    throw Error('Public instance has no movie stream source');
  const film=await fetchAllowed('/stream/movie/tt0063350.json',65000);
  let result=null;
  try{result=JSON.parse(film.body);}
  catch{}
  const streams=Array.isArray(result?.streams)?result.streams:[];
  const counts={directHttps:0,relayPaths:0,needsSpecialHeaders:0,
    playableVideoFileCandidates:0,torrentOnly:0};
  for(const entry of streams.slice(0,75)){
    let u;
    try{u=new URL(String(entry.url||''));}
    catch{continue;}
    if(u.protocol==='https:')counts.directHttps++;
    if(u.hostname===checkedHost)counts.relayPaths++;
    if(entry.headers||entry.behaviorHints?.proxyHeaders)counts.needsSpecialHeaders++;
    if(/\.(mp4|m3u8|webm)(?:$)/i.test(u.pathname))counts.playableVideoFileCandidates++;
    if(entry.infoHash)counts.torrentOnly++;
  }
  console.log('MOOSA_HOSTED_ORIGINAL_CLOUDSTREAM_MOVIE '+JSON.stringify({
    query:'Night of the Living Dead',imdbId:'tt0063350',
    status:film.status,validJson:!!result,sourceCount:streams.length,
    ...counts,correctFilmDecoded:false,candidateProven:false
  }));
  assert.equal(film.status,200,'Public CNCVerse movie lookup unavailable');
  assert.ok(streams.length>0,
    'Public original CloudStream-to-Stremio gateway returned no movie sources');
}catch(e){
  console.log('MOOSA_HOSTED_BRIDGE_BLOCKER '+JSON.stringify({
    reason:String(e.name||'error').slice(0,55),
    // Error messages may contain a CDN or signed URL. NEVER log them.
    hostedPublicGatewayReachable:false
  }));
  throw Error('Hosted CNCVerse gateway not verified end-to-end');
}
