#!/usr/bin/env node
/**
 * Modern film: direct-HTTPS only, no P2P/debrid/Android/torrent engine.
 * Downloads max 10 MiB start of a media file into a transient tmp dir,
 * uses real FFprobe/FFmpeg to decode frame+audio on Linux. NOT iOS playback.
 */
import {readFile,mkdtemp,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {isHttpsMediaCandidate,checkPreset} from './verify-http-only.mjs';
import {probeMedia} from './probe-media-kind.mjs';
const exec=promisify(execFile);
console.log('HDHUB_FFMPEG_CHECK ffprobeExecutable='+(await exec('ffprobe',['-version']).then(()=>true).catch(()=>false)));
const LIMIT=10*1024*1024;
const BASE='https://hdhub.thevolecitor.qzz.io';
const FILMS=[
 {id:'tt31193180',label:'Sinners (2025)',title:/\bsinners\b/i,year:/\b2025\b/},
 {id:'tt16311594',label:'F1 (2025)',title:/\bf1\b|formula.?1/i,year:/\b2025\b/}
];
const config=JSON.parse(await readFile(new URL('./moosa-direct-only.template.json',import.meta.url),'utf8'));
const active=checkPreset(config);
if(active.some(a=>a.instanceId!=='mhd1'))throw Error('unexpected provider enabled');
async function getJSON(url,timeout){
 const response=await fetch(url,{redirect:'error',signal:AbortSignal.timeout(timeout),
  headers:{Accept:'application/json'}});
 if(!response.ok)throw Error('HTTP_'+response.status);
 const raw=await response.text();if(raw.length>4_000_000)throw Error('oversized-json');
 return JSON.parse(raw);
}
function hints(x){return [x.behaviorHints?.filename,x.filename,x.name,x.title,x.description]
 .filter(s=>typeof s==='string').join(' ');}
function rank(x){
 const str=hints(x);let r=0;
 if(/\b1080p\b/i.test(str))r+=30;
 else if(/\b720p\b/i.test(str))r+=20;
 else if(/\b2160p\b/i.test(str))r+=10;
 if(/WEB.?DL|Blu.?Ray/i.test(str))r+=4;
 if(/(?:^|\W)(?:download|dwnld|CAM|TS|TRAILER|SAMPLE)(?:\W|$)/i.test(str))r-=100;
 return -r;
}
function allowFilm(x,m){
 const h=hints(x);
 if(!m.title.test(h)||!m.year.test(h))return false;
 if(/(?:^|\W)(?:download|dwnld|CAM|TS|TRAILER|SAMPLE)(?:\W|$)/i.test(h))return false;
 return isHttpsMediaCandidate(x);
}
async function boundedDownload(u){
 const first=await probeMedia(u);
 if(first.kind!=='webm_or_mkv_container'&&first.kind!=='mp4_container')return {kind:first.kind,buffer:null};
 const res=await fetch(u,{method:'GET',redirect:'error',
  headers:{Range:'bytes=0-'+(LIMIT-1)},signal:AbortSignal.timeout(30000)});
 if(res.status!==206)throw Error('no_byte_range_'+res.status);
 const ct=(res.headers.get('content-type')||'').toLowerCase();
 if(/html|json|text\/plain/.test(ct))throw Error('unexpected_content_type');
 const chunks=[];let got=0;const reader=res.body?.getReader();
 if(!reader)throw Error('no_response_body');
 try{
  while(got<LIMIT){
   const v=await reader.read();if(v.done)break;
   const p=Buffer.from(v.value).subarray(0,LIMIT-got);
   chunks.push(p);got+=p.length;
  }
 }finally{await reader.cancel().catch(()=>{});}
 if(got<500000)throw Error('too_short');
 return {kind:first.kind,buffer:Buffer.concat(chunks,got)};
}
async function checkTrack(src,film,i){
 const temp=await mkdtemp(join(tmpdir(),'moosa-http-'));
 try{
  const data=await boundedDownload(src.url);
  if(!data.buffer)return {pass:false,reason:data.kind};
  const pathname=join(temp,'head.'+(data.kind==='mp4_container'?'mp4':'mkv'));
  await writeFile(pathname,data.buffer);
  let tracks=[];
  try{
   const r=await exec('ffprobe',['-v','error','-show_entries',
    'stream=codec_type,codec_name,width,height','-of','json',pathname],
    {timeout:12000,maxBuffer:500000});
   tracks=JSON.parse(r.stdout).streams||[];
  }catch(e){
    const err=String(e.stderr||'');
    const category=/Invalid data found/i.test(err)?'invalid_media':
     /EBML header parsing failed/i.test(err)?'ebml_error':
     /truncat|end of file|partial file|File ended prematurely/i.test(err)?'truncated':
     /Protocol not found/i.test(err)?'protocol':
     e.code==='ENOENT'?'ffprobe_unavailable':
     /Duration: N\/A/i.test(err)?'no_duration': 'other_decode_error';
    return {pass:false,reason:'ffprobe_failed',detail:category,bytes:data.buffer.length};
  }
  const video=tracks.find(s=>s.codec_type==='video'&&s.width>=640&&s.height>=360);
  const audio=tracks.some(s=>s.codec_type==='audio');
  if(!video||!audio)return {pass:false,reason:'missing_video_or_audio',
   bytes:data.buffer.length,video:!!video,audio};
  let frames=false,sound=false;
  try{
   await exec('ffmpeg',['-nostdin','-hide_banner','-loglevel','error',
    '-i',pathname,'-map','0:v:0','-frames:v','15','-an','-sn','-dn','-f','null','-'],
    {timeout:23000,maxBuffer:150000});
   frames=true;
  }catch{}
  try{
   await exec('ffmpeg',['-nostdin','-hide_banner','-loglevel','error',
    '-i',pathname,'-map','0:a:0','-vn','-sn','-dn','-t','1',
    '-f','null','-'],{timeout:16000,maxBuffer:150000});
   sound=true;
  }catch{}
  return {pass:frames&&sound,video:frames,audio:sound,
   pixels:video.width+'x'+video.height,codec:video.codec_name,
   bytes:data.buffer.length};
 }finally{await rm(temp,{recursive:true,force:true}).catch(()=>{});}
}
let passed=0;
for(const film of FILMS){
 let ok=false;
 try{
  const res=await getJSON(BASE+'/stream/movie/'+film.id+'.json',30000);
  const list=Array.isArray(res.streams)?res.streams:[];
  const direct=list.filter(isHttpsMediaCandidate);
  const qualified=direct.filter(x=>allowFilm(x,film)).sort((a,b)=>rank(a)-rank(b));
  console.log('HDHUB_FILM '+film.label+' listed='+list.length+
   ' rawHttps='+direct.length+' titleYearQualified='+qualified.length);
  for(let i=0;i<Math.min(3,qualified.length);i++){
    try{
     const r=await checkTrack(qualified[i],film,i);
     console.log('HDHUB_DECODE '+film.label+' trial='+i+
      ' pass='+r.pass+' video='+Boolean(r.video)+' audio='+Boolean(r.audio)+
      ' pixels='+(r.pixels||'-')+' codec='+(r.codec||'-')+
      ' bytes='+(r.bytes||0)+' reason='+(r.reason||'-')+' detail='+(r.detail||'-'));
     if(r.pass){ok=true;break;}
    }catch(e){console.log('HDHUB_DECODE '+film.label+' trial='+i+
     ' fail='+String(e.message).slice(0,45));}
  }
 }catch(e){console.log('HDHUB_FILM '+film.label+' failed='+String(e.message).slice(0,70));}
 if(ok)passed++;
 console.log('HDHUB_RESULT '+film.label+' firstSecondsDecoded='+ok+
  ' nativeIOSProven=false fullFilmProven=false');
}
console.log('HDHUB_FINAL films='+FILMS.length+' decodedBothTracks='+passed);
if(passed!==FILMS.length)process.exitCode=1;
