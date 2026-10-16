/**
 * LIVE OPTIONAL external-service probe.
 * A Wikimedia outage/rate limit must not break the main website CI.
 * Does not download any video content, only verifies API + media HEAD.
 */
import {fromCommons} from '../commons-provider.mjs';
const movie={media:'movie',id:1,title:'Big Buck Bunny',
  originalTitle:'Big Buck Bunny',year:2008,imdbId:'tt1254207'};
const now=Date.now();
const files=await fromCommons(movie);
console.log('WIKIMEDIA_COMMONS_DISCOVERY',JSON.stringify({
  results:files.length,latencyMs:Date.now()-now,
  qualities:files.map(x=>x.quality),licenses:files.map(x=>x.licenseUrl),
  mimePaths:files.map(x=>x.url.split('?')[0].split('.').at(-1))
}));
if(!files.length){
  console.error('No Commons sources: API not reachable, rate-limited, search phrase not indexed, or strict licensing/quality/title check failed.');
  process.exitCode=1;
}else{
  const candidate=[...files].sort((a,b)=>a.quality-b.quality)[0];
  try{
    const r=await fetch(candidate.url,{method:'HEAD',
      redirect:'error',signal:AbortSignal.timeout(12000),
      headers:{'User-Agent':'MOOSAWeb/0.1 (https://github.com/MooOsa1/MOOSA-CloudStream; open-license video smoke)'}});
    console.log('WIKIMEDIA_MEDIA_HEAD',JSON.stringify({
      status:r.status,contentType:r.headers.get('content-type'),
      bytes:r.headers.get('content-length'),
      cors:r.headers.get('access-control-allow-origin')||null
    }));
    if(!r.ok)process.exitCode=1;
  }catch(e){console.error('Commons video HEAD failed',e.message);process.exitCode=1;}
}
