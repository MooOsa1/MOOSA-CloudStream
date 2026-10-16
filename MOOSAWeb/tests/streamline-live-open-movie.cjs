/**
 * One-off upstream Streamline Vidlink LIVE discovery probe for the openly
 * licensed Big Buck Bunny. This does not play, download or reveal any streams.
 *
 * The original bundled provider is loaded by the CI runner from a pinned
 * upstream checkout, not redistributed or deployed by MOOSA Web.
 * TMDB metadata is fixed to the verified free sample and mocked locally.
 * Actual outbound calls permitted ONLY to the original provider APIs.
 */
'use strict';
const path=require('node:path');
const assert=require('node:assert/strict');
const original=require(path.resolve('third-party/streamline/providers/vidlink.js'));

const HOSTS=new Set(['enc-dec.app','vidlink.pro']);
const SAMPLES=new Map([
  [10378,{
    title:'Big Buck Bunny',original_title:'Big Buck Bunny',
    release_date:'2008-04-10',external_ids:{imdb_id:'tt1254207'},
    production_countries:[]
  }],
  [133701,{
    title:'Tears of Steel',original_title:'Tears of Steel',
    release_date:'2012-09-28',external_ids:{imdb_id:'tt2285752'},
    production_countries:[]
  }]
]);
const network=[];
const fetchOriginal=globalThis.fetch.bind(globalThis);
global.fetch=async function guardedFetch(raw,options={}){
  const u=new URL(String(raw));
  if(u.hostname==='api.themoviedb.org'){
    const id=Number(u.pathname.split('/').at(-1));
    assert.ok(SAMPLES.has(id),'Only verified CC Blender film IDs are allowed');
    network.push({host:'local-TMDB-fixture',result:200});
    return new Response(JSON.stringify(SAMPLES.get(id)),{status:200,
      headers:{'Content-Type':'application/json'}});
  }
  if(u.protocol!=='https:'||!HOSTS.has(u.hostname)){
    throw Error('Upstream requested forbidden host');
  }
  try{
    const res=await fetchOriginal(raw,{
      ...options,redirect:'error',
      signal:AbortSignal.timeout(8000)
    });
    const declared=Number(res.headers.get('content-length')||0);
    if(declared>500000)throw Error('Provider response too large');
    network.push({host:u.hostname,result:res.status});
    return res;
  }catch(e){
    network.push({host:u.hostname,result:e.name==='TimeoutError'?'timeout':'network_error'});
    throw e;
  }
};
async function main(){
  for(const [id,meta] of SAMPLES){
    let outcome='no_sources',sources=[];
    const visitedAt=network.length;
    try{
      sources=await Promise.race([
        original.getStreams(id,'movie',null,null),
        new Promise(resolve=>setTimeout(()=>resolve([]),19500))
      ]);
      if(Array.isArray(sources)&&sources.length)outcome='candidates_unverified';
    }catch(e){outcome='provider_error';}
    const summaries=Array.isArray(sources)?sources.slice(0,30).map(x=>{
      let host='';
      try{host=new URL(x.url).hostname;}catch{}
      return {
        host,quality:String(x.quality||'').slice(0,20),
        extension:/\.(m3u8|mp4|webm)(?:$|[?#])/i.exec(String(x.url||''))?.[1]||'unknown',
        needsReferer:!!(x.headers?.Referer||x.headers?.referer),
        needsCookie:!!(x.headers?.Cookie||x.headers?.cookie)
      };
    }):[];
    console.log('STREAMLINE_LIVE_OPEN_MOVIE_RESULT '+JSON.stringify({
      title:meta.title+' (CC-BY)',outcome,candidateCount:summaries.length,
      sources:summaries,requests:network.slice(visitedAt)
    }));
  }
}
main().catch(e=>{console.error('STREAMLINE_LIVE_PROBE_FATAL '+e.name);process.exitCode=1;})
  .finally(()=>process.exit(process.exitCode||0));
