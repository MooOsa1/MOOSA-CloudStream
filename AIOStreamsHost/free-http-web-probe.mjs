#!/usr/bin/env node
/**
 * Find a current no-debrid HTTP Stremio addon and VERIFY HTTP media transport
 * on mainstream movies released in 2025, not torrent listing counts.
 * No paid subscriptions, no account tokens and no private source URLs in logs.
 * Browser playback is not confirmed until actual <video> decodes.
 */
const ROOT='https://free.flixnest.app';
const TITLES=[
  {id:'tt31193180',name:'Sinners (2025)'},
  {id:'tt16311594',name:'F1: The Movie (2025)'}
];
const manifestPattern=/https:\/\/free\.flixnest\.app\/[A-Za-z0-9_-]{60,1400}\/manifest\.json/g;
const isPrivateHostname=h=>/^(?:localhost|0\.|127\.|10\.|192\.168\.|169\.254\.|172\.(?:1[6-9]|2\d|3[01])\.|.*\.local$)/i.test(h)||h.includes(':');
const delay=async (u,ms,init={})=>{
  const response=await fetch(u,{...init,redirect:'error',signal:AbortSignal.timeout(ms)});
  if(!response.ok)throw Error('HTTP_'+response.status);
  return response;
};
let url='';
try{
 const html=await(await delay(ROOT+'/',14000)).text();
 const list=[...new Set(html.match(manifestPattern)||[])];
 if(!list.length)throw Error('No public manifest URL visible on free config homepage');
 url=list[0];
 const route=new URL(url);
 if(route.origin!==ROOT||!/^\/[A-Za-z0-9_-]{60,1400}\/manifest\.json$/.test(route.pathname))
   throw Error('Unsafe or malformed public manifest URL');
 const manifest=await(await delay(url,15000,{headers:{Accept:'application/json'}})).json();
 if(!Array.isArray(manifest.resources)||!Array.isArray(manifest.types))
   throw Error('Public addon did not return a Stremio manifest');
 console.log('MOOSA_HTTP_FREE_MANIFEST=true name='+String(manifest.name||'Unknown').replace(/[^a-zA-Z0-9 -]/g,'').slice(0,55)+
   ' supportsMovie='+manifest.types.includes('movie'));
 for(const movie of TITLES){
   let data;
   try{
    const endpoint=new URL(route.href);
    endpoint.pathname=endpoint.pathname.replace(/\/manifest\.json$/,'/stream/movie/'+movie.id+'.json');
    data=await(await delay(endpoint.href,40000,{headers:{Accept:'application/json'}})).json();
   }catch(e){
    console.log('MOOSA_HTTP_2025 movie='+movie.id+' lookupFailed='+String(e.message||'Unknown').slice(0,45));
    continue;
   }
   const all=Array.isArray(data.streams)?data.streams:[];
   const direct=all.filter(x=>typeof x?.url==='string').map(x=>{
     try{
       const u=new URL(x.url);
       if(u.protocol!=='https:'||u.username||u.password||isPrivateHostname(u.hostname))return null;
       const extension=/\.(?:mp4|m4v|m3u8|webm)(?:$|\?)/i.test(u.pathname);
       const notWebReady=x.behaviorHints?.notWebReady===true;
       return {url:u.href,extension,notWebReady};
     }catch{return null;}
   }).filter(Boolean);
   const web=direct.filter(x=>!x.notWebReady&&x.extension);
   let reachable=0,mediaLike=0,cors=0;
   for(const x of web.slice(0,3)){
     try{
       const response=await delay(x.url,13000,{headers:{Range:'bytes=0-65535'}});
       const type=String(response.headers.get('content-type')||'').toLowerCase();
       const allow=String(response.headers.get('access-control-allow-origin')||'');
       const result=await response.arrayBuffer();
       const hls=(type.includes('mpegurl')&&new TextDecoder().decode(result.slice(0,30)).startsWith('#EXTM3U'));
       if(result.byteLength)reachable++;
       if(hls||type.startsWith('video/'))mediaLike++;
       if(allow==='*'||allow.includes(ROOT))cors++;
     }catch{}
   }
   console.log('MOOSA_HTTP_2025 movie='+movie.id+' total='+all.length+
     ' httpsDirect='+direct.length+' browserCandidate='+web.length+
     ' probeResponses='+reachable+' mediaHeaders='+mediaLike+' corsPotential='+cors);
 }
}catch(e){
 console.log('MOOSA_HTTP_FREE_DISCOVERY_ERROR='+String(e?.message||'unknown').slice(0,120));
 process.exitCode=1;
}
