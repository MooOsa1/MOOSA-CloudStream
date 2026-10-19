#!/usr/bin/env node
/* Discovery-only; none of these addons are promoted to the install preset.
 * Always compare real movie bytes, and exclude P2P and download links.
 */
import {isHttpsMediaCandidate} from './verify-http-only.mjs';
import {probeMedia} from './probe-media-kind.mjs';
const sources=[
  {id:'webstreamrmbg',base:'https://87d6a6ef6b58-webstreamrmbg.baby-beamup.club'},
  {id:'pipe',base:'https://pipe.boringways.workers.dev'},
  {id:'hdhub',base:'https://hdhub.thevolecitor.qzz.io'},
  {id:'sootio',base:'https://sooti.info'}
];
const movies=[['tt31193180','Sinners-2025'],['tt16311594','F1-2025']];
const get=async (url,timeout=18000)=>{
 const resp=await fetch(url,{headers:{Accept:'application/json'},signal:AbortSignal.timeout(timeout)});
 if(!resp.ok)throw Error('HTTP_'+resp.status);
 const str=await resp.text();
 if(str.length>1_500_000)throw Error('too_big');
 return JSON.parse(str);
};
let anyDistinct=false;
for(const a of sources){
 try {
  const m=await get(a.base+'/manifest.json',15000);
  console.log('SCOUT_MANIFEST '+a.id+' name='+String(m.name||'').slice(0,50)+
   ' stream='+Boolean(m.resources?.some(r=>r==='stream'||r?.name==='stream'))+
   ' configureRequired='+Boolean(m.behaviorHints?.configurationRequired));
 }catch(e){console.log('SCOUT_MANIFEST '+a.id+' error='+String(e.message).slice(0,45));continue;}
 const fingerprints=[];
 for(const [id,alias] of movies){
  try{
   const data=await get(a.base+'/stream/movie/'+id+'.json',26000);
   const streams=Array.isArray(data.streams)?data.streams:[];
   const candidates=streams.filter(isHttpsMediaCandidate).filter(x=>!/(download|trailer|demo|sample)/i.test([x.title,x.name,x.description].join(' ')));
   console.log('SCOUT_FILM '+a.id+' movie='+alias+' listed='+streams.length+' httpCandidates='+candidates.length);
   for(const src of candidates.slice(0,2)){
    try{
      const p=await probeMedia(src.url);
      console.log('SCOUT_MEDIA '+a.id+' movie='+alias+' status='+p.status+' kind='+p.kind+' bytes='+p.bytes);
      if(p.fingerprint && ['mp4_container','webm_or_mkv_container','hls_playlist_only'].includes(p.kind)) fingerprints.push({id,f:p.fingerprint});
    }catch(e){console.log('SCOUT_MEDIA '+a.id+' movie='+alias+' fail='+String(e.message).slice(0,42));}
   }
  }catch(e){console.log('SCOUT_FILM '+a.id+' movie='+alias+' fail='+String(e.message).slice(0,50));}
 }
 const f1=fingerprints.filter(x=>x.id===movies[0][0]).map(x=>x.f);
 const f2=fingerprints.filter(x=>x.id===movies[1][0]).map(x=>x.f);
 const distinct=f1.length>0&&f2.length>0&&f1.some(f=>!f2.includes(f));
 console.log('SCOUT_IDENTITY '+a.id+' bothTitlesMedia='+Boolean(f1.length&&f2.length)+
  ' distinctPrefixes='+distinct+' nativePlayback=NOT_TESTED');
 if(distinct)anyDistinct=true;
}
console.log('SCOUT_FINAL differentMediaEvidence='+anyDistinct+
 ' playableMovieVerified=false');
if(!anyDistinct)process.exitCode=1;
