import {fromCommons} from '../commons-provider.mjs';
const titles=['Big Buck Bunny','Sintel','Tears of Steel','Elephants Dream','Spring','Cosmos Laundromat','Caminandes'];
const results=await Promise.all(titles.map(async title=>{
 const start=Date.now(),sources=await fromCommons({media:'movie',originalTitle:title,title});
 return {title,elapsedMs:Date.now()-start,count:sources.length,
  resolutions:sources.map(s=>s.quality),
  domains:[...new Set(sources.map(s=>new URL(s.url).hostname))],
  licenses:[...new Set(sources.map(s=>s.licenseUrl))],
  sourcePages:sources.slice(0,2).map(s=>s.sourcePage)};
}));
for(const r of results)console.log('COMMONS_FILM_SCAN '+JSON.stringify(r));
