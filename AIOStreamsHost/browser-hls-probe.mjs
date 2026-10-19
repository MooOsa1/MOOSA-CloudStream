#!/usr/bin/env node
/* Real MOOSA browser-HLS compatibility probe, using official Stremio engine.
 * Disposable AIOStreams account, only 2025 Sinners, no full-film downloads.
 * Logs no UUID, torrent hashes, private manifest URLs or downloaded filenames.
 * A passing manifest is not equivalent to HTML5 video playback.
 */
import {readFile} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {localStremioHls} from '../MOOSAWeb/public/local-stremio-client.js';
const BASE='https://aiostreams-nightly.fortheweak.cloud', ENGINE='http://127.0.0.1:11470';
const template=JSON.parse(await readFile(new URL('./moosa-hub-template.json',import.meta.url),'utf8'));
const config={...template.config,services:[],formatter:{id:'gdrive'},addonName:'MOOSA HLS Browser CI Test'};
const pw=randomBytes(36).toString('hex');let uuid='';
async function checkedJson(url,opts={},ms=65000){
 const r=await fetch(url,{...opts,signal:AbortSignal.timeout(ms)});
 const body=await r.json().catch(()=>({}));
 return {status:r.status,ok:r.ok,body};
}
try{
 const r=await checkedJson(BASE+'/api/v1/user',{method:'POST',
  headers:{'Content-Type':'application/json'},body:JSON.stringify({config,password:pw})});
 if(!r.ok||!r.body?.data?.uuid||!r.body?.data?.encryptedPassword)throw Error('AIOStreams disposable user unavailable');
 uuid=r.body.data.uuid;
 const streams=await checkedJson(BASE+'/stremio/'+encodeURIComponent(uuid)+'/'+
  encodeURIComponent(r.body.data.encryptedPassword)+'/stream/movie/tt31193180.json');
 if(!streams.ok||!Array.isArray(streams.body.streams))throw Error('Sinners 2025 source lookup failed');
 const candidates=streams.body.streams.filter(x=>/^[a-f0-9]{40}$/i.test(x.infoHash||'')&&
   /\bsinners\b/i.test([x.name,x.description,x.behaviorHints?.filename].join(' '))&&
   /\b2025\b/.test([x.name,x.description,x.behaviorHints?.filename].join(' '))).slice(0,3);
 console.log('MOOSA_HLS_BROWSER_SINNERS_2025 total='+streams.body.streams.length+' checked='+candidates.length);
 let ok=false;
 for(let i=0;i<candidates.length;i++){
   const url=localStremioHls({infoHash:candidates[i].infoHash,
       fileIdx:Number.isInteger(candidates[i].fileIdx)?candidates[i].fileIdx:-1});
   if(!url)continue;
   try{
     const response=await fetch(url,{signal:AbortSignal.timeout(45000)});
     const ctype=response.headers.get('content-type')||'';
     const body=(await response.text()).slice(0,4096);
     const hls=response.ok&&body.startsWith('#EXTM3U');
     console.log('MOOSA_HLS_BROWSER_ATTEMPT index='+i+' status='+response.status+
       ' contentTypeHls='+/mpegurl/i.test(ctype)+' manifestHls='+hls+
       ' manifestBytes='+body.length);
     if(hls){ok=true;break;}
   }catch(e){
     console.log('MOOSA_HLS_BROWSER_ATTEMPT index='+i+' result='+
       (e?.name==='TimeoutError'?'timeout':'network_or_route_error'));
   }
 }
 console.log('MOOSA_HLS_BROWSER_MASTER_MANIFEST='+ok);
 if(!ok)process.exitCode=1;
}finally{
 if(uuid){
  const auth='Basic '+Buffer.from(uuid+':'+pw).toString('base64');
  try{
   const del=await checkedJson(BASE+'/api/v1/user',{method:'DELETE',headers:{Authorization:auth}},15000);
   console.log('MOOSA_HLS_BROWSER_TEMP_REMOVED='+del.ok);
  }catch{console.log('MOOSA_HLS_BROWSER_TEMP_REMOVED=false');process.exitCode=1;}
 }
}
