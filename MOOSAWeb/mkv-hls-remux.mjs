/**
 * Live MKV -> HLS/fMP4 remux for a media-gateway-approved exact film.
 * Stream copy only (HEVC/E-AC-3). NEVER transcodes, bypasses access controls,
 * accepts visitor URLs, or logs source paths. Private gateway tokens only.
 *
 * HLS is a sliding live window, not VOD seeking. A full-film seekable version
 * requires a separate per-timestamp segment service and upstream permission.
 */
import {spawn,spawnSync} from 'node:child_process';
import {createReadStream} from 'node:fs';
import {mkdtemp,readFile,stat,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
function bundledFfmpeg(){
  try{return require('ffmpeg-static')||'ffmpeg';}
  catch{return 'ffmpeg';}
}

const FILE=/^(?:index\.m3u8|init\.mp4|seg_[0-9]{5}\.m4s)$/;
const TYPES={'.m3u8':'application/vnd.apple.mpegurl',
  '.mp4':'video/mp4','.m4s':'video/iso.segment'};
const wait=ms=>new Promise(r=>setTimeout(r,ms));
function send(res,code,message){
  if(res.headersSent){res.destroy();return;}
  res.writeHead(code,{'Content-Type':'text/plain; charset=utf-8',
    'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
  res.end(message);
}
/** Safari may request byte ranges for HLS init or fMP4 segment files. */
export function parseHlsByteRange(value,size){
  if(typeof value!=='string'||!Number.isSafeInteger(size)||size<1)
    return null;
  const match=value.match(/^bytes=(0|[1-9][0-9]{0,14})-((?:0|[1-9][0-9]{0,14}))?$/);
  if(!match)return null;
  const start=Number(match[1]);
  const last=match[2]===undefined?size-1:Number(match[2]);
  if(!Number.isSafeInteger(start)||!Number.isSafeInteger(last)||
     start>=size||start>last)return null;
  const end=Math.min(last,size-1);
  return {start,end,length:end-start+1};
}
export function remuxCli(input,outputDir,{binary='ffmpeg',headers={}}={}){
  const parsed=new URL(input);
  if(parsed.protocol!=='https:'||parsed.username||parsed.password||
     parsed.port||parsed.hash||input.length>4000)throw Error('Invalid trusted HTTPS source');
  if(!path.isAbsolute(outputDir))throw Error('Invalid output directory');
  const requestHeaders=[];
  for(const name of ['referer','origin','user-agent']){
    const value=headers?.[name];
    if(typeof value==='string'&&value.length>0&&value.length<=512&&
       !/[\r\n]/.test(value))
      requestHeaders.push(name+': '+value);
  }
  const ffHeaders=requestHeaders.length?[
    '-headers',requestHeaders.join('\r\n')+'\r\n'
  ]:[];
  const args=[
    '-nostdin','-hide_banner','-loglevel','error',
    // Without -re FFmpeg remuxes an entire 4K feature in seconds and
    // deletes early live HLS segments before Safari can request them.
    '-re',
    // FFmpeg must not escape the operator-approved host through HTTP 302.
    '-max_redirects','0',
    '-rw_timeout','15000000',...ffHeaders,'-i',parsed.href,
    '-map','0:v:0','-map','0:a:0?',
    '-c:v','copy','-tag:v','hvc1','-c:a','copy',
    '-sn','-dn',
    '-f','hls','-hls_segment_type','fmp4',
    '-hls_time','6','-hls_list_size','6',
    '-hls_flags','delete_segments+temp_file',
    '-hls_fmp4_init_filename','init.mp4',
    '-hls_segment_filename',path.join(outputDir,'seg_%05d.m4s'),
    path.join(outputDir,'index.m3u8')
  ];
  return {binary,args};
}
export function createMkvHlsRemux({
  binary=process.env.MOOSA_FFMPEG_BIN||bundledFfmpeg(),
  spawnImpl=spawn,
  maxSessions=1,
  idleMs=90000
}={}){
  const sessions=new Map();
  const launching=new Map();
  let available;
  function isAvailable(){
    if(available!==undefined)return available;
    try{
      const check=spawnSync(binary,['-version'],{
        timeout:1800,stdio:'ignore'
      });
      available=check.status===0;
    }catch{available=false;}
    return available;
  }
  function cleanup(token){
    const s=sessions.get(token);
    if(!s)return;
    sessions.delete(token);
    s.process?.kill('SIGTERM');
    void rm(s.dir,{recursive:true,force:true,maxRetries:1}).catch(()=>{});
  }
  const cleanupTimer=setInterval(()=>{
    const now=Date.now();
    for(const [id,s] of sessions)
      if(now-s.touched>idleMs || s.failed)cleanup(id);
  },15000);
  cleanupTimer.unref?.();
  async function launch(token,url,headers={}){
    const old=sessions.get(token);
    if(old)return old.failed?null:old;
    const pending=launching.get(token);
    if(pending)return pending;
    // Safari can request the playlist and init file at the same time.
    // A single-flight launch avoids two FFmpeg writers racing for filenames.
    if(sessions.size+launching.size>=maxSessions || !isAvailable())return null;
    const start=(async()=>{
      const dir=await mkdtemp(path.join(os.tmpdir(),'moosa-remux-'));
      let config,proc;
      try{
        config=remuxCli(url,dir,{binary,headers});
        proc=spawnImpl(config.binary,config.args,{
          stdio:['ignore','ignore','ignore'],windowsHide:true
        });
      }catch{
        await rm(dir,{recursive:true,force:true}).catch(()=>{});
        return null;
      }
      const state={dir,process:proc,touched:Date.now(),failed:false};
      sessions.set(token,state);
      proc.on('error',()=>{state.failed=true;});
      proc.on('exit',code=>{
        state.exitCode=code;
        if(code!==0 && code!==null)state.failed=true;
      });
      return state;
    })().finally(()=>launching.delete(token));
    launching.set(token,start);
    return start;
  }
  async function handle({token,file='index.m3u8',url,headers={},req,res}){
    if(req.method!=='GET'&&req.method!=='HEAD')
      return send(res,405,'Unsupported method');
    if(!FILE.test(file))return send(res,404,'Unknown HLS segment');
    if(req.headers.range && file==='index.m3u8')
      return send(res,416,'Playlist cannot be ranged');
    const session=await launch(token,url,headers);
    if(!session)return send(res,503,'Live remux unavailable');
    session.touched=Date.now();
    const target=path.join(session.dir,file);
    // The first playlist and fMP4 init box take time to appear. Segment
    // polling is bounded so a dead ffmpeg never hangs the browser forever.
    let info=null;
    for(let i=0;i<120;i++){
      if(session.failed)break;
      try{
        info=await stat(target);
        if(info.isFile()&&info.size>0)break;
        info=null;
      }catch{}
      await wait(200);
    }
    if(!info)return send(res,503,'Media segment is not ready');
    if(info.size>72*1024*1024)return send(res,413,'Segment too large');
    const requestedRange=req.headers.range;
    const partial=requestedRange?
      parseHlsByteRange(requestedRange,info.size):null;
    if(requestedRange&&!partial)return send(res,416,'Invalid media byte range');
    const extension=path.extname(file);
    const mime=TYPES[extension];
    const hdr={
      'Content-Type':mime,
      'Content-Length':String(partial?.length||info.size),
      'Cache-Control':'no-store',
      'X-Content-Type-Options':'nosniff',
      'Cross-Origin-Resource-Policy':'same-origin',
      'Accept-Ranges':'bytes',
      ...(partial?{'Content-Range':
        'bytes '+partial.start+'-'+partial.end+'/'+info.size}:{})
    };
    res.writeHead(partial?206:200,hdr);
    if(req.method==='HEAD'){res.end();return;}
    createReadStream(target,partial?
      {start:partial.start,end:partial.end}:undefined)
      .on('error',()=>res.destroy()).pipe(res);
  }
  function close(){
    clearInterval(cleanupTimer);
    for(const token of [...sessions.keys()])cleanup(token);
  }
  return {isAvailable,handle,close,activeSessions:()=>sessions.size};
}
