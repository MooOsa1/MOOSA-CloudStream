/**
 * Candidate upstream SDK validation (strictly research, no MOOSA production use).
 * Library docs: https://vyla.cc/sdk/
 *
 * Probe ONLY Blender's Creative Commons movie Sintel (TMDB 45745).
 * We do not replay copyrighted titles, transmit credentials, download
 * video, bypass 403/429, or log complete signed media URLs.
 *
 * A metadata link, 1080p label or CDN HEAD 200 is NEVER playback proof.
 */
import VylaSDK from '@vyla-entertainment/sdk';
import {setTimeout as sleep} from 'node:timers/promises';

const ID='45745'; // Sintel — Blender CC-BY movie
const sdk=new VylaSDK({tmdbApiKey:''});
let sources;
try{sources=await sdk.getSources();}
catch(e){
  console.log('VYLA_BOOT result=source_discovery_failed class='+String(e?.name||'unknown'));
  process.exit(0);
}
const raw=Array.isArray(sources)?sources:
  Array.isArray(sources?.sources)?sources.sources:
  Object.values(sources||{}).flatMap(x=>Array.isArray(x)?x:[]);
// Movies, not anime-only extractors. Restrict to source configurations that
// explicitly claim browser-direct operation without protected CDN headers.
const MOVIE_DIRECT_PREFERENCE=[
  '1embed','fsonline','purstream','vidrift','xpass','vixsrc','vidnest-sub','flaxmovies'
];
const candidates=raw.filter(x=>
  x&&typeof x.key==='string'&&x.key.length<64&&
  MOVIE_DIRECT_PREFERENCE.includes(x.key)&&x.disabled!==true&&x.skipProxy===true&&
  (!Array.isArray(x.cdnHeaders)||x.cdnHeaders.length===0))
  .sort((a,b)=>MOVIE_DIRECT_PREFERENCE.indexOf(a.key)-
    MOVIE_DIRECT_PREFERENCE.indexOf(b.key));
console.log('VYLA_BOOT installed=true total='+raw.length+
  ' declaredDirectNoProxy='+candidates.length+
  ' directProviderNames='+JSON.stringify(candidates.map(x=>x.key).slice(0,20))+
  ' playbackVerified=false');
if(!candidates.length)process.exit(0);
const guardUrl=url=>{
  if(typeof url!=='string'||url.length>2500)return null;
  try{
    const u=new URL(url);
    if(u.protocol!=='https:'||u.username||u.password||u.port||u.hash||
       /^(localhost|127\.|0\.|10\.|192\.168\.|169\.254\.)/i.test(u.hostname))
      return null;
    if(!/\.(m3u8|mp4|webm)$/i.test(u.pathname))return null;
    return {host:u.hostname,type:u.pathname.split('.').at(-1)};
  }catch{return null;}
};
for(const candidate of candidates.slice(0,7)){
  let result;
  try{
    result=await Promise.race([
      sdk.getStream(candidate.key,ID),
      sleep(7500).then(()=>({timeout:true}))
    ]);
  }catch(e){result={error:e?.name||'unknown'}}
  const streamResults=[
    ...Array.isArray(result?.allUrls)?result.allUrls:[],
    ...(result?.url?[result]:[])
  ];
  const approved=streamResults.filter(s=>
    s&&!s.headers&&!result?.headers&&!s.behaviorHints?.proxyHeaders&&guardUrl(s.url));
  console.log('VYLA_SINTEL metadataProvider='+JSON.stringify(candidate.key)+
    ' responseType='+JSON.stringify(result?.timeout?'timeout':result?.error?'error':typeof result)+
    ' rawStreamCandidates='+streamResults.length+
    ' noHeadersDirect='+approved.length+
    ' streams='+JSON.stringify(approved.slice(0,5).map(s=>({
      ...guardUrl(s.url),claimedQuality:String(s.quality||'').slice(0,25)
    })))+
    ' decodedFhd=false urlOmitted=true');
}
