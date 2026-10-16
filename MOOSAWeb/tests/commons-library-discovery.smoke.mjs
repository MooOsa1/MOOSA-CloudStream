import {fromCommons} from '../commons-provider.mjs';
const titles=['Big Buck Bunny','Sintel','Tears of Steel','Elephants Dream','Spring','Cosmos Laundromat','Caminandes'];
const outcomes=await Promise.all(titles.map(async title=>{
 const start=Date.now(),sources=await fromCommons({media:'movie',originalTitle:title,title});
 const best=sources[0]||null;
 const outcome={title,elapsedMs:Date.now()-start,count:sources.length,
  resolutions:sources.map(s=>s.quality),
  licenses:[...new Set(sources.map(s=>s.licenseUrl))],
  videoHost:best?new URL(best.url).hostname:null};
 if(best){
   try{
     const response=await fetch(best.url,{
       method:'HEAD',redirect:'error',signal:AbortSignal.timeout(8000),
       headers:{'User-Agent':'MOOSAWeb/0.1 (https://github.com/MooOsa1/MOOSA-CloudStream; rights-verified video HEAD test)'}
     });
     outcome.mediaStatus=response.status;
     outcome.mediaType=response.headers.get('content-type');
     outcome.cors=response.headers.get('access-control-allow-origin');
     outcome.mediaBytes=Number(response.headers.get('content-length'))||null;
   }catch(e){outcome.mediaStatus='network_error';outcome.error=e.name;}
 }
 return outcome;
}));
for(const entry of outcomes)console.log('COMMONS_FILM_SCAN '+JSON.stringify(entry));
const available=outcomes.filter(x=>x.count&&x.mediaStatus===200
  &&x.mediaType?.startsWith('video/') &&x.videoHost==='upload.wikimedia.org');
console.log('COMMONS_FILM_AVAILABLE',available.map(x=>x.title).join(', '));
if(available.length<3)process.exitCode=1;
