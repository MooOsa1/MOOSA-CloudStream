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
   const opaque=direct.filter(x=>!x.notWebReady&&!x.extension);
   let tried=0,reachable=0,mediaLike=0,cors=0,htmlPages=0,redirects=0,errors=0;
   const typeCounts={};
   for(const x of [...web,...opaque].slice(0,6)){
     tried++;
     try{
       let target=x.url,response;
       for(let hop=0;hop<3;hop++){
         response=await fetch(target,{redirect:'manual',headers:{Range:'bytes=0-65535'},
           signal:AbortSignal.timeout(13000)});
         if(![301,302,303,307,308].includes(response.status))break;
         redirects++;
         const raw=response.headers.get('location');
         if(!raw)throw Error('redirect_without_location');
         const u=new URL(raw,target);
         if(u.protocol!=='https:'||u.username||u.password||isPrivateHostname(u.hostname))
           throw Error('blocked_redirect_host');
         await response.body?.cancel();
         target=u.href;
       }
       if(!response.ok)throw Error('HTTP_'+response.status);
       const type=String(response.headers.get('content-type')||'').toLowerCase();
       const allow=String(response.headers.get('access-control-allow-origin')||'');
       const reader=response.body?.getReader();
       const chunks=[];let size=0;
       if(reader){
         try {
           while(size<65536){
             const part=await reader.read();
             if(part.done)break;
             const b=Buffer.from(part.value).subarray(0,65536-size);
             chunks.push(b);size+=b.byteLength;
           }
         }finally{await reader.cancel().catch(()=>{});}
       }
       const head=Buffer.concat(chunks,size);
       const hls=head.subarray(0,16).toString('utf8').startsWith('#EXTM3U');
       const mp4=head.subarray(4,8).toString('ascii')==='ftyp';
       const video=type.startsWith('video/') || mp4;
       const kind=hls?'HLS':video?'VIDEO':/html/i.test(type)?'HTML':'OTHER';
       typeCounts[kind]=(typeCounts[kind]||0)+1;
       if(head.length>0)reachable++;
       if(hls||video)mediaLike++;
       if(kind==='HTML')htmlPages++;
       if(allow==='*'||allow.includes('moosa-web.onrender.com'))cors++;
     }catch{errors++;}
   }
   console.log('MOOSA_HTTP_2025 movie='+movie.id+' total='+all.length+
     ' httpsDirect='+direct.length+' browserPathCandidate='+web.length+
     ' opaqueHttps='+opaque.length+' tested='+tried+' reachable='+reachable+
     ' actualMediaResponses='+mediaLike+' htmlResponses='+htmlPages+
     ' redirectHops='+redirects+' corsPotential='+cors+' errors='+errors+
     ' responseKinds='+JSON.stringify(typeCounts));

 }
}catch(e){
 console.log('MOOSA_HTTP_FREE_DISCOVERY_ERROR='+String(e?.message||'unknown').slice(0,120));
 process.exitCode=1;
}
