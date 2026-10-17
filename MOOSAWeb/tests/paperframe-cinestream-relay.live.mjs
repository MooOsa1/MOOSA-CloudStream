/**
 * Live, limited, RIGHTS-APPROVED Sintel film test of the independent
 * Paperframe QuickJS plugin executor + same-origin HLS/MP4 media relay.
 * Runs in an isolated GitHub Actions worker; DOES NOT ship or store media.
 * Signed URLs, source headers and capability tokens are never logged.
 */
import assert from 'node:assert/strict';
import {execFile as nativeExecFile} from 'node:child_process';
import {promisify} from 'node:util';
const execFile=promisify(nativeExecFile);
const BASE='http://127.0.0.1:4173';
const REPO='https://raw.githubusercontent.com/itsmeadarsh2008/Streamline/27c2cb2c864a056003241c48a26ede5a7cff037a/manifest.json';
const APPROVED_FILM={title:'Sintel',imdb:'tt1727587',tmdb:'45745',year:2010};
const totalStart=Date.now();

async function readLimited(response,maxBytes=100_000){
  const reader=response.body?.getReader();
  if(!reader)return Buffer.alloc(0);
  const parts=[];let len=0;
  try{
    for(;;){
      const {done,value}=await reader.read();
      if(done)break;
      len+=value.byteLength;
      if(len>maxBytes)throw Error('upstream_body_larger_than_probe');
      parts.push(value);
    }
    return Buffer.concat(parts,len);
  }finally{await reader.cancel().catch(()=>{});}
}
function nextHlsChild(manifest){
  if(!manifest.startsWith('#EXTM3U'))return null;
  if(/#EXT-X-(?:SESSION-)?KEY:/i.test(manifest))return null;
  const next=manifest.split(/\r?\n/).map(x=>x.trim())
    .find(x=>x && !x.startsWith('#'));
  if(!next||!/^\/api\/media\/[\w-]+$/.test(next))return null;
  return next;
}
function matroskaSignature(bytes,mime){
  const type=String(mime||'').split(';')[0].trim().toLowerCase();
  return ['video/mkv','video/x-matroska','application/x-matroska'].includes(type) &&
    bytes?.length>=4 && bytes[0]===0x1a && bytes[1]===0x45 &&
    bytes[2]===0xdf && bytes[3]===0xa3;
}
function videoSignature(bytes,mime){
  if(!bytes || bytes.length<8)return false;
  const type=String(mime||'').split(';')[0].trim().toLowerCase();
  if(type.includes('html')||type.includes('json')||type.includes('xml'))return false;
  // ISO-BMFF box type is at EXACTLY bytes 4..7. A loose substring search
  // falsely accepted a 200 text/html upstream anti-bot page as MP4.
  const box=Buffer.from(bytes).subarray(4,8).toString('latin1');
  if(['ftyp','styp','moof'].includes(box) &&
      ['video/mp4','application/octet-stream','video/quicktime'].includes(type))
    return true;
  // MPEG-TS sync packets repeat every 188 bytes; one leading 0x47 is not
  // sufficient to classify arbitrary HTML or opaque text as video.
  return bytes.length>=377 && bytes[0]===0x47 &&
    bytes[188]===0x47 && bytes[376]===0x47 &&
    ['video/mp2t','application/octet-stream'].includes(type);
}

// Regression: an HTML anti-bot response containing “ftyp” is NOT video.
assert.equal(videoSignature(Buffer.from('<html><body>ftyp</body></html>'),'text/html'),false);
assert.equal(matroskaSignature(Buffer.from([0x1a,0x45,0xdf,0xa3,0x42]),'video/mkv'),true);
assert.equal(matroskaSignature(Buffer.from('<html>'),'video/mkv'),false);
assert.equal(videoSignature(Buffer.from([0,0,0,16,102,116,121,112,0,0,0,0]),'video/mp4'),true);
const response=await fetch(BASE+'/api/plugins/resolve',{
  method:'POST',
  headers:{'Content-Type':'application/json'},
  body:JSON.stringify({repositoryUrl:REPO,tmdbId:APPROVED_FILM.tmdb,
    mediaType:'movie'}),
  signal:AbortSignal.timeout(320_000)
});
assert.equal(response.status,200,'The real sandbox plugin resolver must succeed');
const document=await response.json();
const sources=Array.isArray(document?.streams)?document.streams:[];
const diagnostics=Array.isArray(document?.diagnostics)?document.diagnostics:[];
const providerCounts={};
for(const item of diagnostics){
  const key=String(item.scraperId||item.scraperName||'unknown').slice(0,36);
  providerCounts[key]={status:item.status,count:Number(item.streamCount)||0};
}
console.log('MOOSA_PAPERFRAME_ORIGINAL_PLUGIN_EXECUTION '+JSON.stringify({
  exactFilm:APPROVED_FILM.title,
  upstreamPluginCount:diagnostics.length,
  resolvedSources:sources.length,
  providerCounts,
  seconds:Math.round((Date.now()-totalStart)/1000),
  audioProven:false,
  videoProven:false
}));
const relevant=sources.filter(x=>{
  const label=String(x.provider||x.name||'').toLowerCase();
  return /vidlink|vaplayer|hdhub4u/.test(label) &&
    /^\/api\/media\/[A-Za-z0-9_-]+$/.test(String(x.url||''));
}).slice(0,8);
console.log('MOOSA_PAPERFRAME_APPROVED_CAPABILITIES '+JSON.stringify({
  candidates:relevant.length,
  temporarySameOriginTokenized:relevant.length>0,
  noThirdPartyMediaExposed:true
}));
let segmentValid=0,manifestValid=0,mkvContainerValid=0,mkvFilmLengthMatches=0,mkvDecodedFrames=0;
for(let i=0;i<relevant.length;i++){
  const item=relevant[i];
  let path=String(item.url);
  const entry={attempt:i+1,provider:String(item.provider||item.name||'').slice(0,50),
    status:null,format:'unknown',verifiedSegment:false,
    matroskaContainerVerified:false,headersRelayedByBackend:true};
  try {
    for(let depth=0;depth<3;depth++){
      const r=await fetch(BASE+path,{
        headers:{Range:'bytes=0-2047'},
        redirect:'error',signal:AbortSignal.timeout(18_000)
      });
      entry.status=r.status;
      if(![200,206].includes(r.status)){
        await r.body?.cancel().catch(()=>{});
        entry.format='upstream_denied_or_unavailable';
        break;
      }
      const type=(r.headers.get('content-type')||'').toLowerCase();
      if(type.includes('mpegurl')){
        // A lightweight manifest never contains the video itself.
        const body=(await readLimited(r,100_000)).toString('utf8');
        if(!body.startsWith('#EXTM3U')){entry.format='not_hls';break;}
        manifestValid++;entry.format='rewritten_hls';
        const next=nextHlsChild(body);
        if(!next)break;
        path=next;
      }else{
        const reader=r.body?.getReader();
        const first=reader?await reader.read():{};
        const data=first.value||new Uint8Array();
        entry.format=type.split(';')[0]||'binary';
        if(type.includes('text/html')||type.includes('application/json'))entry.format='upstream_returned_non_media';
        entry.verifiedSegment=videoSignature(data,type);
        entry.matroskaContainerVerified=matroskaSignature(data,type);
        if(entry.verifiedSegment)segmentValid++;
        if(entry.matroskaContainerVerified)mkvContainerValid++;
        await reader?.cancel().catch(()=>{});
        break;
      }
    }
  }catch(error){
    entry.format=String(error?.name||'probe_failed').slice(0,30);
  }
  if(/HDHub4u/i.test(entry.provider) && !entry.matroskaContainerVerified){
    // Range probing gave 400 after a prior video/mkv response. Compare a
    // no-Range GET against the SAME fresh capability, read only one chunk.
    const normal={status:null,mime:'unknown',ebml:false,html:false};
    try{
      const res=await fetch(BASE+item.url,{
        redirect:'error',signal:AbortSignal.timeout(12000)
      });
      normal.status=res.status;
      normal.mime=String(res.headers.get('content-type')||'')
        .split(';')[0].slice(0,55);
      const reader=res.body?.getReader();
      const first=reader?await reader.read():{};
      const bytes=first.value||new Uint8Array();
      normal.ebml=matroskaSignature(bytes,normal.mime);
      normal.html=bytes.length>0 && /^\s*(?:<!doctype|<html)/i.test(
        Buffer.from(bytes.subarray(0,100)).toString('utf8')
      );
      await reader?.cancel().catch(()=>{});
      if(normal.ebml){
        entry.matroskaContainerVerified=true;
        mkvContainerValid++;
      }
    }catch(error){normal.error=String(error?.name||'network').slice(0,36);}
    console.log('MOOSA_HDHUB_RANGE_VS_NO_RANGE '+JSON.stringify({
      rangeStatus:entry.status,
      normalStatus:normal.status,
      normalMime:normal.mime,
      validMatroskaHeader:normal.ebml,
      htmlInsteadOfVideo:normal.html,
      errorName:normal.error||null
    }));
  }
  console.log('MOOSA_PAPERFRAME_MEDIA_RELAY_PROOF '+JSON.stringify(entry));
  if(entry.matroskaContainerVerified){
    // Do not guess from a .mkv filename: ask a real demuxer for embedded
    // duration + first video codec of this *exact* approved movie.
    const source=BASE+item.url;
    let acceptedDuration=false;
    try{
      const {stdout}=await execFile('ffprobe',[
        '-v','error','-rw_timeout','12000000','-probesize','262144',
        '-analyzeduration','3000000','-show_entries',
        'format=duration,format_name:format_tags=title:stream=codec_name,width,height',
        '-select_streams','v:0','-of','json',source
      ],{timeout:19_000,maxBuffer:80_000});
      const doc=JSON.parse(stdout);
      const duration=Number(doc?.format?.duration);
      const video=doc?.streams?.[0]||{};
      acceptedDuration=Number.isFinite(duration)&&duration>=860&&duration<=930;
      if(acceptedDuration)mkvFilmLengthMatches++;
      console.log('MOOSA_PAPERFRAME_MKV_REAL_FFPROBE '+JSON.stringify({
        attempt:i+1,format:String(doc?.format?.format_name||'').slice(0,40),
        durationSeconds:Number.isFinite(duration)?Math.round(duration):null,
        filmDurationMatches:acceptedDuration,
        videoCodec:String(video.codec_name||'unknown').slice(0,30),
        width:Number(video.width)||null,height:Number(video.height)||null
      }));
    }catch(error){
      console.log('MOOSA_PAPERFRAME_MKV_REAL_FFPROBE '+JSON.stringify({
        attempt:i+1,result:'unavailable',
        failureType:String(error?.name||'ffprobe_failed').slice(0,35)
      }));
    }
    if(acceptedDuration){
      try{
        await execFile('ffmpeg',[
          '-hide_banner','-nostdin','-loglevel','error',
          '-rw_timeout','12000000','-i',source,
          '-an','-sn','-dn','-frames:v','1','-f','null','-'
        ],{timeout:25_000,maxBuffer:16_000});
        mkvDecodedFrames++;
        console.log('MOOSA_PAPERFRAME_MKV_DECODED_FRAME '+JSON.stringify({
          attempt:i+1,decodedWithFfmpeg:true,
          browserPlaybackVerified:false
        }));
      }catch(error){
        console.log('MOOSA_PAPERFRAME_MKV_DECODED_FRAME '+JSON.stringify({
          attempt:i+1,decodedWithFfmpeg:false,
          failureType:String(error?.name||'decode_failed').slice(0,35)
        }));
      }
    }
  }
}
console.log('MOOSA_PAPERFRAME_EXPERIMENT_RESULT '+JSON.stringify({
  approvedFilm:'Sintel',providerCount:diagnostics.length,
  mediaCandidates:relevant.length,hlsPlaylistsRead:manifestValid,
  videoByteSignatures:segmentValid,
  matroskaHeadersVerified:mkvContainerValid,
  matchingFilmMkvDurations:mkvFilmLengthMatches,
  matchingFilmDecodedFfmpegFrames:mkvDecodedFrames,
  noMediaURLsLogged:true,
  actualFullMovieNotYetProven:true,
  readyToDeploy:false
}));
assert.ok(diagnostics.length>0,'Original plugin repository did not load in QuickJS');
// Do not mark a returned URL as playback success. Real Chrome frames and
// movie duration verification are necessary before enabling in production.
assert.ok(relevant.length>0,'No original CineStream plugin produced media capabilities');
assert.ok(segmentValid>0 || (mkvFilmLengthMatches>0 && mkvDecodedFrames>0),
  'No correctly identified movie video was decoded: HLS/MP4 failed and MKV duration/FFmpeg proof failed; browser playback is NOT established');
