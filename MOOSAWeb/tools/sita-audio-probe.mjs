/**
 * Original filmmaker-hosted direct 480p/720p MP4, as listed at
 * https://www.sitasingstheblues.com/watch.html
 * No uploads, no download of the whole file, no third-party scraper.
 * Verify real AAC track and duration. Don't ship dead links.
 */
import {spawn} from 'node:child_process';
const links=[
  ['480p','https://archive.org/download/Sita_Sings_the_Blues/Sita_Sings_the_Blues_480p_2150kbps.mp4'],
  ['720p','https://archive.org/download/Sita_Sings_the_Blues/Sita_Sings_the_Blues_720p.mp4'],
  ['1080p','https://archive.org/download/Sita_Sings_the_Blues/Sita_Sings_the_Blues_1080p.mp4']
];
function run(cmd,args,timeoutMs){
  return new Promise(resolve=>{
    const p=spawn(cmd,args,{stdio:['ignore','pipe','pipe']});
    let out='',err='';
    p.stdout.on('data',chunk=>{if(out.length<70000)out+=String(chunk)});
    p.stderr.on('data',chunk=>{if(err.length<30000)err+=String(chunk)});
    let timeout=false;
    const t=setTimeout(()=>{timeout=true;p.kill('SIGKILL')},timeoutMs);
    p.on('error',error=>{
      clearTimeout(t);resolve({code:-1,timeout,errorName:error.code||'spawn_failed',out,err});
    });
    p.on('close',code=>{
      clearTimeout(t);resolve({code,timeout,out,err});
    });
  });
}
const findings=[];
for(const [quality,url] of links){
  const info={
    quality,originalRightsHolder:'Nina Paley',
    mediaHost:'archive.org',
    directFromOfficialWatchPage:true,
    http:null,audio:null,video:null,durationMinutes:null,
    audioSampleDecodes:false
  };
  try{
    const response=await fetch(url,{
      method:'HEAD',redirect:'follow',signal:AbortSignal.timeout(16000)
    });
    info.http=response.status;
    info.mime=response.headers.get('content-type')?.split(';')[0]||null;
    info.finalHost=new URL(response.url).hostname;
    info.rangeSupported=response.headers.get('accept-ranges')==='bytes';
    info.byteSize=Number(response.headers.get('content-length')||0)||null;
  }catch(e){info.headError=String(e?.name||'network').slice(0,50);}
  if(info.http!==200 && info.http!==206){findings.push(info);
    console.log('SITA_AUDIO_CANDIDATE '+JSON.stringify(info));continue}
  const result=await run('ffprobe',[
    '-v','error','-rw_timeout','17000000','-probesize','250000',
    '-analyzeduration','1800000','-show_entries',
    'stream=codec_type,codec_name,width,height,sample_rate,channels:format=duration',
    '-of','json',url
  ],35000);
  info.ffprobeExit=result.code;
  info.ffprobeTimeout=result.timeout;
  if(result.code===0){
    try{
      const o=JSON.parse(result.out);
      const streams=Array.isArray(o.streams)?o.streams:[];
      const audios=streams.filter(x=>x.codec_type==='audio');
      const videos=streams.filter(x=>x.codec_type==='video');
      info.audio=audios.map(x=>({codec:x.codec_name,rate:x.sample_rate,channels:x.channels}));
      info.video=videos.map(x=>({codec:x.codec_name,width:x.width,height:x.height}));
      info.durationMinutes=Math.round(Number(o.format?.duration||0)/60);
      info.audioVerified=audios.some(x=>x.codec_name==='aac'&&Number(x.channels)>=1);
      info.playbackCompatible=info.audioVerified&&
        videos.some(x=>x.codec_name==='h264'&&Number(x.height)>=360)&&
        info.durationMinutes>=75;
      if(info.playbackCompatible){
        const sample=await run('ffmpeg',[
          '-v','error','-rw_timeout','14000000','-ss','35',
          '-i',url,'-t','0.45','-vn','-f','null','-'
        ],24000);
        info.audioSampleDecodes=sample.code===0;
      }
    }catch{info.parseError='invalid_ffprobe_json'}
  }else info.probeError=result.err.slice(-250).replace(/https?:\/\/\S+/g,'[media-url]');
  findings.push(info);
  console.log('SITA_AUDIO_CANDIDATE '+JSON.stringify(info));
  if(info.playbackCompatible&&info.audioSampleDecodes)break;
}
console.log('SITA_AUDIO_VERDICT '+JSON.stringify({
  verified:findings.some(f=>f.playbackCompatible&&f.audioSampleDecodes),
  winner:findings.find(f=>f.playbackCompatible&&f.audioSampleDecodes)?.quality||null,
  tested:findings.map(f=>({quality:f.quality,http:f.http,
    audioVerified:f.audioVerified||false,decoded:f.audioSampleDecodes}))
}));
if(!findings.some(f=>f.playbackCompatible&&f.audioSampleDecodes))process.exitCode=1;
