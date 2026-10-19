import {lookup} from 'node:dns/promises';
import {isIP} from 'node:net';
const MEDIA_MAX=131072;
export function inspectPrefix(bytes,ct=''){
  const b=Buffer.from(bytes);
  const s=b.subarray(0,128).toString('utf8').trimStart();
  if(s.startsWith('#EXTM3U'))return 'hls_playlist_only';
  if(b.length>12&&b.subarray(4,8).toString('ascii')==='ftyp')return 'mp4_container';
  if(b.length>4&&b.subarray(0,4).equals(Buffer.from([0x1a,0x45,0xdf,0xa3])))return 'webm_or_mkv_container';
  if(b.length>752&&b[0]===0x47&&b[188]===0x47&&b[376]===0x47)return 'mpegts_segment';
  if(/<!doctype|<html|<script|<head|^\{|"error"|access denied|cloudflare/i.test(s))return 'html_or_error';
  if(/json|html|text\/plain/i.test(ct))return 'non_video_response';
  return 'unknown_not_proven';
}
function safeURL(raw){
 let u;try{u=new URL(raw);}catch{throw Error('invalid-url');}
 if(u.protocol!=='https:'||u.username||u.password||u.port&&u.port!=='443')throw Error('nonstandard-url');
 const h=u.hostname.toLowerCase();
 if(isIP(h)||h==='localhost'||h.endsWith('.local')||h.includes(':')||h.includes('['))throw Error('ip-or-local-url');
 return u;
}
function publicIp(ip){
 if(ip.includes(':'))return false;
 const a=ip.split('.').map(Number);if(a.length!==4)return false;
 const [p,q]=a;return !(p===0||p===10||p===127||p>=224||
 (p===169&&q===254)||(p===192&&q===168)||(p===172&&q>=16&&q<=31)||
 (p===100&&q>=64&&q<=127)||(p===198&&q>=18&&q<=19)||
 (p===192&&q===0)||(p===192&&q===0)||(p===192&&q===88));
}
export async function probeMedia(raw){
 let u=safeURL(raw);
 for(let hop=0;hop<3;hop++){
   const addresses=await lookup(u.hostname,{all:true,family:4});
   if(!addresses.length||addresses.some(x=>!publicIp(x.address)))throw Error('blocked-dns');
   const r=await fetch(u.href,{method:'GET',redirect:'manual',
     headers:{Range:'bytes=0-'+String(MEDIA_MAX-1),Accept:'*/*'},
     signal:AbortSignal.timeout(16000)});
   if([301,302,303,307,308].includes(r.status)){
     const location=r.headers.get('location');
     await r.body?.cancel().catch(()=>{});
     if(!location)throw Error('redirect-without-location');
     u=safeURL(new URL(location,u).href);continue;
   }
   const ct=(r.headers.get('content-type')||'').toLowerCase().split(';')[0];
   if(!r.ok){await r.body?.cancel().catch(()=>{});return {status:r.status,kind:'http_error'};}
   const reader=r.body?.getReader();const chunks=[];let size=0;
   if(reader)try{
     while(size<MEDIA_MAX){
       const part=await reader.read();if(part.done)break;
       const data=Buffer.from(part.value).subarray(0,MEDIA_MAX-size);
       chunks.push(data);size+=data.length;
     }
   }finally{await reader.cancel().catch(()=>{});}
   return {status:r.status,kind:inspectPrefix(Buffer.concat(chunks,size),ct),
     bytes:size,contentType:ct};
 }
 throw Error('too-many-redirects');
}
