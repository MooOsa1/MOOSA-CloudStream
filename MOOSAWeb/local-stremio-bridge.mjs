/**
 * Allowlisted, ephemeral AIOStreams source lookup for an opt-in LOCAL Stremio
 * streaming-service client. No generic proxy and no video enters MOOSA Web.
 * The add-on's private installation URL is never stored, echoed or logged.
 */
export const AIOSTREAMS_HOST = 'aiostreams-nightly.fortheweak.cloud';
const HASH = /^[a-f0-9]{40}$/i;
const IMDB = /^tt[0-9]{7,10}$/;
const URL_PATH=/^\/stremio\/[0-9a-f-]{36}\/[a-zA-Z0-9_-]{15,1024}\/manifest\.json$/;

export function parsePrivateAddonManifest(raw){
  if(typeof raw!=='string'||raw.length>1600)return null;
  let url;
  try{url=new URL(raw.startsWith('stremio://')?'https://'+raw.slice(10):raw);}catch{return null;}
  if(url.protocol!=='https:'||url.hostname!==AIOSTREAMS_HOST||url.port||
    url.username||url.password||url.search||url.hash||!URL_PATH.test(url.pathname))return null;
  return url;
}

export function safeP2PSources(payload,{imdbId=null}={}){
  const array=Array.isArray(payload?.streams)?payload.streams:[];
  const seen=new Set();
  const expected=imdbId==='tt31193180'?/\bsinners\b/i:
    imdbId==='tt16311594'?/\bf1\b|formula.?1/i:null;
  return array.filter(x=>x&&typeof x==='object'&&HASH.test(String(x.infoHash||'')))
    .filter(x=>!expected||(()=>{
      const release=[x.name,x.description,x.behaviorHints?.filename].join(' ');
      return expected.test(release)&&/\b2025\b/.test(release);
    })())
    .filter(x=>!(typeof x.name==='string'&&/\bCAM\b|\bTS\b|TELESYNC|TRAILER|\b480p\b|\b360p\b/i.test(x.name)))
    .map(x=>{
      const infoHash=String(x.infoHash).toLowerCase();
      const fileIdx=Number.isInteger(x.fileIdx)&&x.fileIdx>=0&&x.fileIdx<10000?x.fileIdx:-1;
      const name=String(x.name||'Stremio P2P').replace(/[\x00-\x1F\x7F]/g,' ').slice(0,120);
      const quality=/(2160|1440|1080|720)p/i.exec(name)?.[1]||'unknown';
      return {infoHash,fileIdx,name,quality};
    })
    .filter(x=>{const key=x.infoHash+':'+x.fileIdx;if(seen.has(key))return false;seen.add(key);return true;})
    .slice(0,35);
}

export async function lookupAIOStreams({manifestUrl,imdbId,fetcher=fetch,timeoutMs=55000}={}){
  const manifest=parsePrivateAddonManifest(manifestUrl);
  if(!manifest||typeof imdbId!=='string'||!IMDB.test(imdbId)){
    const error=new Error('Invalid AIOStreams manifest or IMDb ID');
    error.status=400;throw error;
  }
  const streamUrl=new URL(manifest.href);
  streamUrl.pathname=streamUrl.pathname.replace(/\/manifest\.json$/,'/stream/movie/'+imdbId+'.json');
  let response;
  try{
    response=await fetcher(streamUrl.href,{
      headers:{Accept:'application/json'},
      redirect:'error',
      signal:AbortSignal.timeout(Math.min(60000,Math.max(1000,timeoutMs)))
    });
  }catch{
    const error=new Error('AIOStreams is unavailable');error.status=502;throw error;
  }
  if(!response.ok){
    const error=new Error('AIOStreams returned HTTP '+response.status);
    error.status=502;throw error;
  }
  const length=Number(response.headers?.get?.('content-length'));
  if(Number.isFinite(length)&&length>3_000_000){
    const error=new Error('AIOStreams response too large');error.status=502;throw error;
  }
  let data;
  try{
    const body=await response.text();
    if(body.length>3_000_000)throw Error('response too large');
    data=JSON.parse(body);
  }catch{
    const error=new Error('Invalid provider response');error.status=502;throw error;
  }
  const sources=safeP2PSources(data,{imdbId});
  return {sources,count:sources.length,requiresLocalEngine:true,
    note:'Only source metadata; video is played by a Stremio service on the viewer PC.'};
}
