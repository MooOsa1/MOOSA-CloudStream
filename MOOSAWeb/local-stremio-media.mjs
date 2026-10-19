/**
 * Local-only, same-origin gateway to an already-running Stremio Service.
 * No arbitrary URL proxy, no remote host selection, no credential access,
 * no SSH/admin privileges and no website-to-internet media distribution.
 *
 * Enables MOOSA's local-PC site (127.0.0.1:3000) to read Stremio's HLS
 * playlists/segments (127.0.0.1:11470) regardless of browser CORS policy.
 * The route is disabled for any non-loopback MOOSA server deployment.
 */
import {Readable} from 'node:stream';

const LOCAL_ENGINE='http://127.0.0.1:11470';
export const MEDIA_PREFIX='/api/stremio-local/media';
// Stremio exposes TWO legitimate HLS families. The previous proxy only
// permitted legacy routes, rejecting valid hlsv2 media/master references.
// Both families are pinned to a 40-hex torrent hash + bounded file index.
// No /proxy, /settings, /create, generic URL or disk path is reachable.
const LEGACY_PATH=/^\/[a-f0-9]{40}\/(?:-1|[0-9]{1,4})\/(?:[a-zA-Z0-9_-]{1,90}\/){0,3}[a-zA-Z0-9_-]{1,100}\.(?:m3u8|ts|mp4|m4s|vtt)$/;
const HLSV2_PATH=/^\/hlsv2\/([a-f0-9]{40})-(-1|[0-9]{1,4})\/(?:(?:master|video\d{1,2}|audio\d{1,2}|subtitle\d{1,2})\.m3u8|(?:video\d{1,2}|audio\d{1,2}|subtitle\d{1,2})\/(?:init\.mp4|segment\d{1,7}\.(?:m4s|vtt)))$/;
function validEnginePath(path){return LEGACY_PATH.test(path)||HLSV2_PATH.test(path);}
function safeHlsQuery(path,search){
 if(!search)return '';
 const match=HLSV2_PATH.exec(path);
 if(!match||!path.endsWith('/master.m3u8'))return null;
 const q=new URLSearchParams(search);
 const allowed=new Set(['mediaURL','maxAudioChannels']);
 for(const key of q.keys())if(!allowed.has(key)||q.getAll(key).length!==1)return null;
 if(!q.has('mediaURL'))return null;
 const media=q.get('mediaURL');
 if(media!=='http://127.0.0.1:11470/'+match[1]+'/'+match[2])return null;
 if(q.has('maxAudioChannels')&&!['1','2'].includes(q.get('maxAudioChannels')))return null;
 return '?'+q.toString();
}
const PLAYLIST=/\.m3u8$/i;
const MAX_PLAYLIST=512_000;
const MAX_SEGMENT=42*1024*1024;

export function localMediaPath(urlPath){
 if(typeof urlPath!=='string'||!urlPath.startsWith(MEDIA_PREFIX+'/'))return null;
 const enginePath=urlPath.slice(MEDIA_PREFIX.length);
 return validEnginePath(enginePath)?enginePath:null;
}
export function makeLocalMediaUrl(raw,baseEngineUrl){
 let u;
 try{u=new URL(raw,baseEngineUrl);}catch{return null;}
 if(u.origin!==LOCAL_ENGINE||u.hash||u.username||u.password)return null;
 if(!validEnginePath(u.pathname))return null;
 const params=safeHlsQuery(u.pathname,u.search);
 if(params===null)return null;
 return MEDIA_PREFIX+u.pathname+params;
}
export function rewriteStremioPlaylist(raw,baseEngineUrl){
 if(typeof raw!=='string'||raw.length>MAX_PLAYLIST||
   !raw.trimStart().startsWith('#EXTM3U'))return null;
 const output=[];
 for(const line of raw.split(/\r?\n/)){
   const s=line.trim();
   if(!s){output.push(line);continue;}
   if(s.startsWith('#')){
     // An HLS key, subtitle or init segment must also remain on loopback.
     let invalid=false;
     const replaced=line.replace(/URI="([^"]+)"/g,(_,uri)=>{
       const local=makeLocalMediaUrl(uri,baseEngineUrl);
       if(!local){invalid=true;return '';}
       return 'URI="'+local+'"';
     });
     if(invalid)return null;
     output.push(replaced);
   }else{
     const local=makeLocalMediaUrl(s,baseEngineUrl);
     if(!local)return null;
     output.push(local);
   }
 }
 return output.join('\n');
}
export async function handleLocalMedia(req,res,url,{fetcher=fetch}={}){
 const file=localMediaPath(url.pathname);
 const params=file?safeHlsQuery(file,url.search):null;
 if(!file||params===null)return send(res,400,'Invalid Stremio local media route');
 if(req.method!=='GET'&&req.method!=='HEAD')
   return send(res,405,'Only GET/HEAD supported');
 const source=LOCAL_ENGINE+file+params;
 const playlist=PLAYLIST.test(file);
 const abort=new AbortController();
 const timer=setTimeout(()=>abort.abort(),playlist?35000:60000);
 const options={method:req.method,signal:abort.signal,redirect:'error'};
 const range=String(req.headers.range||'');
 if(range){
   const match=range.match(/^bytes=(\d+)-(\d+)$/);
   if(!match||Number(match[2])-Number(match[1])>MAX_SEGMENT)
     return send(res,416,'Range not supported');
   options.headers={Range:range};
 }
 try{
   const up=await fetcher(source,options);
   if(!up.ok){
     await up.body?.cancel();
     return send(res,502,'Local Stremio Service returned '+up.status+' for this HLS resource');
   }
   const size=Number(up.headers.get('content-length')||0);
   if((playlist&&size>MAX_PLAYLIST)||(!playlist&&size>MAX_SEGMENT)){
     await up.body?.cancel();
     return send(res,502,'Stremio segment exceeds local gateway size limit');
   }
   if(playlist){
     if(req.method==='HEAD'){
       res.writeHead(200,{'Content-Type':'application/vnd.apple.mpegurl',
         'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
       return res.end();
     }
     const body=await up.text();
     const rewritten=rewriteStremioPlaylist(body,source);
     if(rewritten===null)return send(res,502,'Stremio HLS playlist contains a route outside the supported local media paths');
     res.writeHead(200,{
       'Content-Type':'application/vnd.apple.mpegurl',
       'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
       'Access-Control-Allow-Origin':'*'
     });
     return res.end(req.method==='HEAD'?'':rewritten);
   }
   const headers={
     'Content-Type':up.headers.get('content-type')||'video/mp2t',
     'Cache-Control':'no-store',
     'X-Content-Type-Options':'nosniff'
   };
   if(up.headers.has('content-range'))headers['Content-Range']=up.headers.get('content-range');
   if(up.headers.has('accept-ranges'))headers['Accept-Ranges']=up.headers.get('accept-ranges');
   if(size>0)headers['Content-Length']=String(size);
   res.writeHead(up.status,headers);
   if(req.method==='HEAD'||!up.body)return res.end();
   const stream=Readable.fromWeb(up.body);
   res.once('close',()=>clearTimeout(timer));
   res.once('finish',()=>clearTimeout(timer));
   let count=0;
   stream.on('data',chunk=>{
     count+=chunk.length;
     if(count>MAX_SEGMENT){stream.destroy();res.destroy();}
   });
   stream.on('error',()=>{if(!res.destroyed)res.destroy();});
   return stream.pipe(res);
 }catch{
   if(!res.headersSent)return send(res,502,'Local Stremio engine unreachable or timed out');
   if(!res.destroyed)res.destroy();
 }finally{if(res.writableEnded || res.destroyed)clearTimeout(timer);}
}
function send(res,status,message){
 res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
 res.end(JSON.stringify({error:message}));
}
