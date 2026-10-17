/**
 * Live, limited, RIGHTS-APPROVED Sintel film test of the independent
 * Paperframe QuickJS plugin executor + same-origin HLS/MP4 media relay.
 * Runs in an isolated GitHub Actions worker; DOES NOT ship or store media.
 * Signed URLs, source headers and capability tokens are never logged.
 */
import assert from 'node:assert/strict';
const BASE='http://127.0.0.1:4173';
const REPO='https://raw.githubusercontent.com/itsmeadarsh2008/Streamline/27c2cb2c864a056003241c48a26ede5a7cff037a/manifest.json';
const APPROVED_FILM={title:'Sintel',imdb:'tt1727587',tmdb:'45745',year:2010};
const totalStart=Date.now();

async function readLimited(response,maxBytes=100_000){
  const reader=response.body?.getReader();
  if(!reader)return Buffer.alloc(0);
  const parts=[];let len=0;
  try{
    for(;;){
      const {done,value}=await reader.read();
      if(done)break;
      len+=value.byteLength;
      if(len>maxBytes)throw Error('upstream_body_larger_than_probe');
      parts.push(value);
    }
    return Buffer.concat(parts,len);
  }finally{await reader.cancel().catch(()=>{});}
}
function nextHlsChild(manifest){
  if(!manifest.startsWith('#EXTM3U'))return null;
  if(/#EXT-X-(?:SESSION-)?KEY:/i.test(manifest))return null;
  const next=manifest.split(/\r?\n/).map(x=>x.trim())
    .find(x=>x && !x.startsWith('#'));
  if(!next||!/^\/api\/media\/[\w-]+$/.test(next))return null;
  return next;
}
function videoSignature(bytes){
  if(!bytes?.length)return false;
  if(bytes[0]===0x47)return true;
  const prefix=bytes.subarray(0,40).toString('latin1');
  return /(?:ftyp|moof|styp)/.test(prefix);
}

const response=await fetch(BASE+'/api/plugins/resolve',{
  method:'POST',
  headers:{'Content-Type':'application/json'},
  body:JSON.stringify({repositoryUrl:REPO,tmdbId:APPROVED_FILM.tmdb,
    mediaType:'movie'}),
  signal:AbortSignal.timeout(320_000)
});
assert.equal(response.status,200,'The real sandbox plugin resolver must succeed');
const document=await response.json();
const sources=Array.isArray(document?.streams)?document.streams:[];
const diagnostics=Array.isArray(document?.diagnostics)?document.diagnostics:[];
const providerCounts={};
for(const item of diagnostics){
  const key=String(item.scraperId||item.scraperName||'unknown').slice(0,36);
  providerCounts[key]={status:item.status,count:Number(item.streamCount)||0};
}
console.log('MOOSA_PAPERFRAME_ORIGINAL_PLUGIN_EXECUTION '+JSON.stringify({
  exactFilm:APPROVED_FILM.title,
  upstreamPluginCount:diagnostics.length,
  resolvedSources:sources.length,
  providerCounts,
  seconds:Math.round((Date.now()-totalStart)/1000),
  audioProven:false,
  videoProven:false
}));
const relevant=sources.filter(x=>{
  const label=String(x.provider||x.name||'').toLowerCase();
  return /vidlink|vaplayer/.test(label) &&
    /^\/api\/media\/[A-Za-z0-9_-]+$/.test(String(x.url||''));
}).slice(0,6);
console.log('MOOSA_PAPERFRAME_APPROVED_CAPABILITIES '+JSON.stringify({
  candidates:relevant.length,
  temporarySameOriginTokenized:relevant.length>0,
  noThirdPartyMediaExposed:true
}));
let segmentValid=0,manifestValid=0;
for(let i=0;i<relevant.length;i++){
  const item=relevant[i];
  let path=String(item.url);
  const entry={attempt:i+1,provider:String(item.provider||item.name||'').slice(0,50),
    status:null,format:'unknown',verifiedSegment:false,headersRelayedByBackend:true};
  try {
    for(let depth=0;depth<3;depth++){
      const r=await fetch(BASE+path,{
        headers:{Range:'bytes=0-2047'},
        redirect:'error',signal:AbortSignal.timeout(18_000)
      });
      entry.status=r.status;
      if(![200,206].includes(r.status)){
        await r.body?.cancel().catch(()=>{});
        entry.format='upstream_denied_or_unavailable';
        break;
      }
      const type=(r.headers.get('content-type')||'').toLowerCase();
      if(type.includes('mpegurl')){
        // A lightweight manifest never contains the video itself.
        const body=(await readLimited(r,100_000)).toString('utf8');
        if(!body.startsWith('#EXTM3U')){entry.format='not_hls';break;}
        manifestValid++;entry.format='rewritten_hls';
        const next=nextHlsChild(body);
        if(!next)break;
        path=next;
      }else{
        const reader=r.body?.getReader();
        const first=reader?await reader.read():{};
        const data=first.value||new Uint8Array();
        entry.format=type.split(';')[0]||'binary';
        entry.verifiedSegment=videoSignature(data);
        if(entry.verifiedSegment)segmentValid++;
        await reader?.cancel().catch(()=>{});
        break;
      }
    }
  }catch(error){
    entry.format=String(error?.name||'probe_failed').slice(0,30);
  }
  console.log('MOOSA_PAPERFRAME_MEDIA_RELAY_PROOF '+JSON.stringify(entry));
}
console.log('MOOSA_PAPERFRAME_EXPERIMENT_RESULT '+JSON.stringify({
  approvedFilm:'Sintel',providerCount:diagnostics.length,
  mediaCandidates:relevant.length,hlsPlaylistsRead:manifestValid,
  videoByteSignatures:segmentValid,
  noMediaURLsLogged:true,
  actualFullMovieNotYetProven:true,
  readyToDeploy:false
}));
assert.ok(diagnostics.length>0,'Original plugin repository did not load in QuickJS');
// Do not mark a returned URL as playback success. Real Chrome frames and
// movie duration verification are necessary before enabling in production.
assert.ok(relevant.length>0,'No original CineStream plugin produced media capabilities');
