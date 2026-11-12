#!/usr/bin/env node
/**
 * MOOSA: real modern-film P2P playback transport and FFmpeg decode checks.
 * Only IMDb-matched 2025 mainstream releases; no 1960s/archival films.
 * AIOStreams -> official Stremio server -> Range bytes -> decoded video/audio.
 * Uses bounded transient reads and automatically deletes temporary addon config.
 * No torrent hashes, private URLs, tokens, source names or passwords in logs.
 * Linux test != proof of native iPhone / Windows application playback.
 */
import { randomBytes } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const exec = promisify(execFile);

const HUB='https://aiostreams-nightly.fortheweak.cloud';
const ENGINE='http://127.0.0.1:11470';
const MOVIES=[
  { imdb:'tt31193180', label:'Sinners (2025)', title:/\bsinners\b/i, year:'2025' },
  { imdb:'tt16311594', label:'F1: The Movie (2025)', title:/\bf1\b|\bformula.?1\b/i, year:'2025' }
];
const CONTINUITY_SECONDS=Number(process.env.MOOSA_CONTINUITY_SECONDS||0);
const trialCap=CONTINUITY_SECONDS>0?3:5; // strict sustained check has a bounded three-source budget
const configTemplate=JSON.parse(await readFile(new URL('./moosa-hub-template.json',import.meta.url),'utf8'));
const config={...configTemplate.config, services:[], formatter:{id:'gdrive'},addonName:'MOOSA 2025 movie isolated integration'};
const password=randomBytes(32).toString('hex');
let account=null;

async function fetchJSON(url,options={},timeout=40000){
 const response=await fetch(url,{...options,signal:AbortSignal.timeout(timeout)});
 const body=await response.json().catch(()=>({}));
 return {ok:response.ok,status:response.status,body};
}

async function decodeOne(src,index,movie){
 const idx=Number.isInteger(src.fileIdx)&&src.fileIdx>=0?src.fileIdx:-1;
 const url=ENGINE+'/'+src.infoHash.toLowerCase()+'/'+idx;
 const cap=(CONTINUITY_SECONDS>0?32:(movie.imdb==='tt16311594'?10:6))*1024*1024; // 32 MiB only in optional sustained-playback test
 let chunks=[], bytes=0, signature=Buffer.alloc(0), transportStatus=0, fileSize=0;
 try {
  const response=await fetch(url,{
    headers:{Range:'bytes=0-'+(cap-1)},
    signal:AbortSignal.timeout(65000)
  });
  transportStatus=response.status;
  const contentRange=response.headers.get('content-range')||'';
  const totalMatch=contentRange.match(/\/(\d+)$/);
  fileSize=totalMatch?Number(totalMatch[1]):0;
  if(response.status!==206){
    await response.body?.cancel();
    return {ok:false,reason:'range_http_'+response.status};
  }
  const reader=response.body.getReader();
  try {
    while(bytes<cap){
      const n=await reader.read();
      if(n.done)break;
      const part=Buffer.from(n.value).subarray(0,cap-bytes);
      chunks.push(part); bytes+=part.byteLength;
      if(signature.length<24)signature=Buffer.concat([signature,part]).subarray(0,24);
    }
  }finally{await reader.cancel().catch(()=>{});}
 }catch(e){return {ok:false,reason:e.name==='TimeoutError'?'range_timeout':'range_network_error'};}
 const matroska=signature.subarray(0,4).equals(Buffer.from([0x1a,0x45,0xdf,0xa3]));
 const mp4=signature.subarray(4,8).toString('ascii')==='ftyp';
 if(bytes<65536 || !(matroska||mp4))return {ok:false,reason:'invalid_container',status:transportStatus,bytes};
 const folder=await mkdtemp(join(tmpdir(),'moosa-modern-'));
 const file=join(folder,matroska?'head.mkv':'head.mp4');
 const baseArgs=['-nostdin','-hide_banner','-loglevel','error',
    '-analyzeduration','5000000','-probesize','5000000'];
 try {
  await writeFile(file,Buffer.concat(chunks,bytes));
  chunks=[];
  let streams=[];
  try{
    const probe=await exec('ffprobe',['-v','error',
      '-analyzeduration','5000000','-probesize','5000000',
      '-show_entries','stream=codec_type,codec_name,width,height',
      '-of','json',file],{timeout:20000,maxBuffer:150000});
    streams=JSON.parse(probe.stdout).streams||[];
  }catch(e){
    return {ok:false,reason:'ffprobe_failed',diagnostic:String(e?.code||e?.signal||'unknown').slice(0,30),status:transportStatus,bytes};
  }
  const video=streams.find(t=>t.codec_type==='video'&&t.width>=640&&t.height>=360);
  const audio=streams.some(t=>t.codec_type==='audio');
  if(!video)return {ok:false,reason:'no_video_track',status:transportStatus,bytes};
  let decodedFrames=0;
  try{
   const decoded=await exec('ffmpeg',[...baseArgs,'-i',file,'-map','0:v:0','-frames:v','2',
    '-an','-sn','-dn','-progress','pipe:1','-f','null','-'],{timeout:25000,maxBuffer:150000});
   const matched=[...decoded.stdout.matchAll(/^frame=([0-9]+)/gm)];
   decodedFrames=matched.length?Number(matched[matched.length-1][1]):0;
   if(decodedFrames<2)return {ok:false,reason:'insufficient_decoded_video_frames',
     status:transportStatus,bytes,videoFrames:decodedFrames};
  }catch(e){
   return {ok:false,reason:'video_decode_failed',diagnostic:String(e?.code||e?.signal||'unknown').slice(0,30),status:transportStatus,bytes};
  }
  let audioDecoded=false;
  if(audio) {
   try{
    const sound=await exec('ffmpeg',[...baseArgs,'-i',file,'-map','0:a:0','-vn','-sn','-dn',
      '-t','0.4','-progress','pipe:1','-f','null','-'],{timeout:20000,maxBuffer:150000});
    const times=[...sound.stdout.matchAll(/^out_time_(?:us|ms)=([0-9]+)/gm)];
    audioDecoded=times.some(x=>Number(x[1])>0);
   }catch{}
  }
  let sustainedSeconds=0,sustainedFrames=0;
  if(audioDecoded && CONTINUITY_SECONDS>0){
    try {
      const long=await exec('ffmpeg',[...baseArgs,'-i',file,'-map','0:v:0',
        '-map','0:a:0','-t',String(CONTINUITY_SECONDS),'-progress','pipe:1',
        '-f','null','-'],{timeout:70000,maxBuffer:200000});
      const outputTimes=[...long.stdout.matchAll(/^out_time_(?:us|ms)=([0-9]+)/gm)];
      const outputFrames=[...long.stdout.matchAll(/^frame=([0-9]+)/gm)];
      sustainedSeconds=Math.max(0,...outputTimes.map(x=>Number(x[1])/1e6));
      sustainedFrames=Math.max(0,...outputFrames.map(x=>Number(x[1])));
    }catch {
      return {ok:false,reason:'sustained_decode_failed',bytes,status:transportStatus,
        videoFrames:decodedFrames,audioDecoded,pixels:video.width+'x'+video.height};
    }
    if(sustainedSeconds < CONTINUITY_SECONDS*0.90 ||
       sustainedFrames < Math.floor(CONTINUITY_SECONDS*15)){
      return {ok:false,reason:'insufficient_sustained_playback',bytes,status:transportStatus,
        sustainedSeconds,sustainedFrames,videoFrames:decodedFrames,audioDecoded,
        pixels:video.width+'x'+video.height};
    }
  }
  let middleSeek=false,seekReason='not_run',seekBytes=0;
  // Once frames and sound are confirmed, check real random access at 30% of
  // the SAME torrent file. This is a bounded seek/RANGE transport test only:
  // arbitrary middle bytes are not guaranteed to begin on a video keyframe.
  if(audioDecoded && Number.isSafeInteger(fileSize) && fileSize>20*1024*1024){
    const start=Math.floor(fileSize*0.3);
    try {
      const seek=await fetch(url,{headers:{Range:'bytes='+start+'-'+(start+65535)},
        signal:AbortSignal.timeout(45000)});
      const match=(seek.headers.get('content-range')||'').match(/^bytes (\d+)-(\d+)\/(\d+)$/);
      if(seek.status!==206 || !match || Number(match[1])!==start){
        seekReason='unexpected_range_response';
        await seek.body?.cancel();
      }else{
        const reader=seek.body.getReader();
        try{
          while(seekBytes<65536){
            const chunk=await reader.read();
            if(chunk.done)break;
            seekBytes+=chunk.value.byteLength;
          }
        }finally{await reader.cancel().catch(()=>{});}
        middleSeek=seekBytes>=65536;
        seekReason=middleSeek?'verified':'partial_range';
      }
    }catch(e){seekReason=e.name==='TimeoutError'?'seek_timeout':'seek_network_error';}
  }else if(audioDecoded){seekReason='size_unknown';}
  return {ok:true,bytes,status:transportStatus,codec:video.codec_name,
   pixels:video.width+'x'+video.height,videoFrames:decodedFrames,audioTrack:audio,
   audioDecoded,middleSeek,seekBytes,seekReason,sustainedSeconds,sustainedFrames};
 }finally{
  await rm(folder,{force:true,recursive:true}).catch(()=>{});
 }
}
function releaseHints(stream){
 return [stream.behaviorHints?.filename||'',stream.name||'',stream.description||''].join(' ');
}
function rank(stream){
 const s=releaseHints(stream);
 if(/\b1080p\b/i.test(s))return 0;
 if(/\b720p\b/i.test(s))return 1;
 if(/\b2160p\b|\b4k\b/i.test(s))return 2;
 return 3;
}
async function inspectMovie(base,movie){
 const reply=await fetchJSON(base+'/stream/movie/'+movie.imdb+'.json',{},65000);
 if(!reply.ok||!Array.isArray(reply.body.streams))throw Error(movie.imdb+' aggregator response '+reply.status);
 const all=reply.body.streams;
 const p2p=all.filter(s=>/^[a-f0-9]{40}$/i.test(s.infoHash||''));
 const titleMatched=p2p.filter(s=>movie.title.test(releaseHints(s)));
 const releaseYear=new RegExp('\\b'+movie.year+'\\b');
 const correctYear=titleMatched.filter(s=>releaseYear.test(releaseHints(s)));
 const pool=(correctYear.length?correctYear:(titleMatched.length?titleMatched:p2p))
   .filter(s=>!/CAM|TELESYNC|TRAILER|SAMPLE|TS-SCR/i.test(releaseHints(s)))
   .sort((a,b)=>rank(a)-rank(b));
 const uniq=new Set(), selected=[];
 for(const item of pool){
   const key=item.infoHash.toLowerCase()+':'+(item.fileIdx??-1);
   if(uniq.has(key))continue;
   uniq.add(key);selected.push(item);
   if(selected.length===trialCap)break;
 }
 console.log('MOOSA_MODERN_INDEX movie='+movie.imdb+' year='+movie.year+' total='+all.length+
   ' validP2P='+p2p.length+' titleHintMatches='+titleMatched.length+' titleYearMatches='+correctYear.length+
   ' trialCount='+selected.length);
 let sawFrames=false, sawSound=false, sawSeek=false;
 for(let i=0;i<selected.length;i++){
  const result=await decodeOne(selected[i],i,movie);
  console.log('MOOSA_MODERN_TRIAL movie='+movie.imdb+' index='+i+
    ' status='+(result.status||'-')+' bytes='+(result.bytes||0)+' reason='+(result.reason||'none')+' diagnostic='+(result.diagnostic||'-')+
    ' videoFrames='+(result.videoFrames||0)+' audioTrack='+(result.audioTrack||false)+' audioDecoded='+(result.audioDecoded||false)+
    ' pixels='+(result.pixels||'unknown')+' seek30percent='+(result.middleSeek||false)+
   ' seekReason='+(result.seekReason||'none')+' seekBytes='+(result.seekBytes||0)+
   ' decodedContinuousSeconds='+(result.sustainedSeconds||0)+' sustainedFrames='+(result.sustainedFrames||0));
  if(result.ok){
   sawFrames=true;
   sawSound=!!result.audioDecoded;
   sawSeek=!!result.middleSeek;
   if(sawSound)break; // stop once both decoded without excessive fetching
  }
 }
 const ok=sawFrames&&sawSound;
 console.log('MOOSA_MODERN_RESULT movie='+movie.imdb+' year='+movie.year+
   ' decodedVideo='+sawFrames+' decodedAudio='+sawSound+' seek30percent='+sawSeek+' fullPass='+ok);
 return {movie:movie.label,ok,video:sawFrames,audio:sawSound,seek:sawSeek};
}
async function cleanup(){
 if(!account)return;
 try{
  const auth='Basic '+Buffer.from(account.uuid+':'+password).toString('base64');
  const res=await fetchJSON(HUB+'/api/v1/user',{method:'DELETE',headers:{Authorization:auth}},20000);
  console.log('MOOSA_MODERN_TEMP_ACCOUNT_REMOVED='+res.ok);
  if(!res.ok)process.exitCode=1;
 }catch{console.log('MOOSA_MODERN_TEMP_ACCOUNT_REMOVED=false');process.exitCode=1;}
}
async function main(){
 try{
  const ready=await fetchJSON(ENGINE+'/settings',{},18000);
  if(!ready.ok)throw Error('Stremio engine is not responding');
  console.log('MOOSA_MODERN_STREMIO_ENGINE_READY');
  const newUser=await fetchJSON(HUB+'/api/v1/user',{
    method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({config,password})},90000);
  if(!newUser.ok||!newUser.body?.data?.uuid||!newUser.body?.data?.encryptedPassword)
    throw Error('AIOStreams test account creation failed: '+newUser.status);
  account=newUser.body.data;
  const prefix=HUB+'/stremio/'+encodeURIComponent(account.uuid)+'/'+
    encodeURIComponent(account.encryptedPassword);
  const results=[];
  for(const movie of MOVIES){
    try{results.push(await inspectMovie(prefix,movie));}
    catch(e){
      console.log('MOOSA_MODERN_RESULT movie='+movie.imdb+' fullPass=false failure='+
        String(e.message).replace(/https?:\/\/\S+/g,'[redacted]').slice(0,120));
      results.push({movie:movie.label,ok:false});
    }
  }
  const passes=results.filter(x=>x.ok).length;
  console.log('MOOSA_MODERN_2025_FINAL '+JSON.stringify({mode:CONTINUITY_SECONDS>0?'sustained-'+CONTINUITY_SECONDS+'s':'startup-and-seek',tested:results.length,passed:passes,
    results:results.map(x=>({movie:x.movie,video:!!x.video,audio:!!x.audio,seek:!!x.seek,pass:x.ok}))}));
  console.log('NOT_NATIVE_IPHONE_TEST: verifies real decoded video/audio via official Linux Stremio service only.');
  if(passes!==results.length)process.exitCode=1;
 }finally{await cleanup();}
}
main().catch(e=>{console.error('MOOSA_MODERN_FATAL '+String(e.message).slice(0,145));process.exitCode=1;});
