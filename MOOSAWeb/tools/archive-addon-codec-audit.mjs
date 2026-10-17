/**
 * Forensic read-only legal CC film external add-on media diagnosis.
 * Separately validates Archive item identity/license/file; reads at most
 * the first media chunk and asks ffprobe for codec information with strict
 * process+network timeouts. No proxy, full video download or DRM bypass.
 */
import {spawn} from 'node:child_process';
import {parseArchiveFileUrl,validateExternalArchiveStream}
  from '../archive-stream-verifier.mjs';

const movie={media:'movie',id:45745,title:'Sintel',originalTitle:'Sintel',
  year:2010,imdbId:'tt1727587'};
const base='https://dev.nebulawp.org/stremio/archive.org-addon/';
function trustedStreamUrl(s){
  try{
    const p=parseArchiveFileUrl(s?.url);
    return p?{p,url:s.url}:null;
  }catch{return null}
}
const res=await fetch(base+'stream/movie/'+movie.imdbId+'.json',{
  headers:{Accept:'application/json'},redirect:'error',
  signal:AbortSignal.timeout(12000)
});
if(!res.ok)throw Error('External addon unavailable HTTP '+res.status);
const streams=(await res.json()).streams||[];
let target;
for(const s of streams.slice(0,5)){
  const p=trustedStreamUrl(s);
  if(p && await validateExternalArchiveStream(p.url,movie)){target=p;break}
}
if(!target){
  console.log('ARCHIVE_CODEC_AUDIT verdict=no_verified_film_url');
  process.exit(0);
}
const metaResp=await fetch('https://archive.org/metadata/'+target.p.identifier,{
  headers:{Accept:'application/json'},redirect:'error',
  signal:AbortSignal.timeout(12000)
});
if(!metaResp.ok)throw Error('Archive metadata unavailable');
const metadata=await metaResp.json();
const approvedFile=metadata.files.find(f=>f.name===target.p.filename);
const mediaFiles=metadata.files.filter(f=>
  /\.(?:mp4|m4v|webm|mov)$/i.test(f.name||'') && Number(f.height)>=720 &&
  Number(f.size)>=400000 && !/(?:trailer|preview|sample|teaser)/i.test(f.name||'')
).slice(0,16);
console.log('ARCHIVE_CODEC_METADATA '+JSON.stringify({
  rightsVerified:!!metadata.metadata?.licenseurl,
  itemTitle:metadata.metadata?.title,
  originalFile:{
    name:target.p.filename.slice(0,130),
    width:approvedFile?.width,height:approvedFile?.height,
    format:approvedFile?.format,source:approvedFile?.source,
    size:approvedFile?.size
  },
  availableMediaVariants:mediaFiles.map(f=>({
    name:String(f.name).slice(0,95),
    width:f.width,height:f.height,format:f.format,source:f.source,
    sizeMB:Math.round(Number(f.size||0)/1000000)
  }))
}));
try{
  const r=await fetch(target.url,{
    headers:{Range:'bytes=0-32767',Accept:'video/mp4'},
    redirect:'follow',signal:AbortSignal.timeout(18000)
  });
  const reader=r.body?.getReader();
  let chunk=new Uint8Array();
  if(reader){
    const item=await reader.read();
    if(item.value)chunk=item.value.subarray(0,Math.min(item.value.length,32768));
    await reader.cancel().catch(()=>{});
  }
  const ascii=new TextDecoder('latin1').decode(chunk.slice(0,32768));
  console.log('ARCHIVE_CODEC_GET_RANGE '+JSON.stringify({
    status:r.status,finalHost:new URL(r.url).hostname,
    contentType:r.headers.get('content-type'),
    contentRange:r.headers.get('content-range'),
    contentLength:r.headers.get('content-length'),
    acceptRanges:r.headers.get('accept-ranges'),
    contentEncoding:r.headers.get('content-encoding'),
    contentDisposition:r.headers.get('content-disposition')?.slice(0,100),
    bytesInspected:chunk.length,
    mp4FtypFound:ascii.slice(0,32).includes('ftyp'),
    mp4MoovInFirstChunk:ascii.includes('moov'),
    mp4MdatInFirstChunk:ascii.includes('mdat'),
    firstAtomType:ascii.slice(4,8),
    htmlErrorPage:ascii.slice(0,64).toLowerCase().includes('<html')
  }));
}catch(e){
  console.log('ARCHIVE_CODEC_GET_RANGE '+JSON.stringify({error:e?.name||'unknown'}));
}
const probe=spawn('ffprobe',[
  '-v','error','-rw_timeout','8000000',
  '-probesize','131072','-analyzeduration','1000000',
  '-show_entries','stream=codec_name,codec_type,width,height,pix_fmt,profile:format=format_name,duration',
  '-select_streams','v:0','-of','json',target.url
],{stdio:['ignore','pipe','pipe']});
let out='',err='';
probe.stdout.on('data',b=>{if(out.length<18000)out+=String(b).slice(0,18000)});
probe.stderr.on('data',b=>{if(err.length<1500)err+=String(b).slice(0,1500)});
const timer=setTimeout(()=>probe.kill('SIGKILL'),14500);
const exitCode=await new Promise(resolve=>probe.on('close',resolve));
clearTimeout(timer);
let parsed=null;
try{parsed=JSON.parse(out)}catch{}
console.log('ARCHIVE_CODEC_FFPROBE '+JSON.stringify({
  exitCode,
  videoStreams:(parsed?.streams||[]).slice(0,3),
  format:parsed?.format?.format_name||null,
  failureClass:exitCode===0?'none':(err.includes('Invalid data')?'invalid_data':
    err.includes('unsupported')?'codec_unsupported':'remote_or_probe_error'),
  sourceRawUrlOmitted:true,attemptIsMediaDiagnosisNotPlayback:true
}));
