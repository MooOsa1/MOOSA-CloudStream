#!/usr/bin/env node
/**
 * Real SOURCE -> official Stremio streaming server -> video bytes proof.
 * Tests a public-domain-era 1968 film only, small bounded HTTP Range requests.
 * No signed URLs, tokens, raw filenames, passwords, IPs or hashes in logs.
 * The server is local-only and the disposable AIOStreams config is always deleted.
 * Playback on iOS is NOT established by this Linux service test.
 */
import { randomBytes } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const execFileAsync = promisify(execFile);
import { readFile } from 'node:fs/promises';

const HUB = 'https://aiostreams-nightly.fortheweak.cloud';
const SERVER = 'http://127.0.0.1:11470';
const MOVIE = 'tt0063350'; // Night of the Living Dead (1968)
const limit = 2;
const json = async (u, opts={}, timeout=45000) => {
  const resp = await fetch(u, {...opts, signal: AbortSignal.timeout(timeout)});
  const body = await resp.json().catch(()=>({}));
  return {ok:resp.ok,status:resp.status,body};
};
const templ = JSON.parse(await readFile(new URL('./moosa-hub-template.json',import.meta.url),'utf8'));
const config = {...templ.config, services:[],formatter:{id:'gdrive'},addonName:'MOOSA Movie Runtime Audit'};
const pass = randomBytes(32).toString('hex');
let id=null;
async function cleanup() {
  if(!id) return;
  const auth='Basic '+Buffer.from(id+':'+pass).toString('base64');
  try {
    const d=await json(HUB+'/api/v1/user',{method:'DELETE',headers:{Authorization:auth}},12000);
    console.log('MOOSA_RUNTIME_CLEANUP success='+d.ok);
    if(!d.ok) process.exitCode=1;
  }catch(e){console.log('MOOSA_RUNTIME_CLEANUP success=false'); process.exitCode=1;}
}
async function main(){
  try {
    const ready = await json(SERVER+'/settings',{},12000);
    if(!ready.ok) throw Error('Official Stremio streaming engine did not start');
    console.log('MOOSA_RUNTIME_OFFICIAL_ENGINE ready=true');
    const c=await json(HUB+'/api/v1/user',{method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({config,password:pass})},85000);
    id=c.body?.data?.uuid;
    const token=c.body?.data?.encryptedPassword;
    if(!c.ok||!id||!token) throw Error('AIOStreams temporary config not created, status='+c.status);
    const path=HUB+'/stremio/'+encodeURIComponent(id)+'/'+encodeURIComponent(token);
    const results=await json(path+'/stream/movie/'+MOVIE+'.json',{},60000);
    if(!results.ok || !Array.isArray(results.body.streams)) throw Error('AIOStreams real source query failed: '+results.status);
    const torrents = results.body.streams
      .filter(x=> /^[a-f0-9]{40}$/i.test(x.infoHash||''))
      .filter(x=>!/720p|480p|360p|CAM|TS|TELESYNC|TRAILER|RESTORED/i.test(x.name||'') || /1080p|2160p/i.test(x.name||''))
      .slice(0,limit);
    console.log('MOOSA_RUNTIME_AIOSTREAMS count='+results.body.streams.length+' trialCount='+torrents.length);
    if(!torrents.length) throw Error('No safely classifiable P2P source');
    let played=false;
    for(let i=0;i<torrents.length;i++){
      const t=torrents[i];
      const idx=Number.isInteger(t.fileIdx) && t.fileIdx>=0 ? t.fileIdx : -1;
      const fileRoute=SERVER+'/'+t.infoHash.toLowerCase()+'/'+idx;
      try {
        // P2P metadata acquisition can be slow; never retry endlessly.
        const resp=await fetch(fileRoute,{headers:{Range:'bytes=0-1048575'},
          signal:AbortSignal.timeout(70000)});
        if(resp.status!==206&&resp.status!==200) {
          console.log('MOOSA_RUNTIME_SOURCE_'+i+' status='+resp.status+' media=false');
          await resp.body?.cancel();
          continue;
        }
        const reader=resp.body.getReader();let size=0;let prefix=Buffer.alloc(0);
        try{
          while(size<1048576) {
            const item=await reader.read();
            if(item.done)break;
            size+=item.value.byteLength;
            if(prefix.byteLength<8192)prefix=Buffer.concat([prefix,Buffer.from(item.value)]).subarray(0,8192);
          }
        }finally{await reader.cancel().catch(()=>{});}
        const bmff=prefix.subarray(4,8).toString('ascii')==='ftyp';
        const ebml=prefix.subarray(0,4).equals(Buffer.from([0x1a,0x45,0xdf,0xa3]));
        const riff=prefix.subarray(0,4).toString('ascii')==='RIFF';
        const transport=resp.status===206 && size>=65536 && (bmff||ebml||riff);
        console.log('MOOSA_RUNTIME_SOURCE_'+i+' status='+resp.status+' bytes='+size+
          ' mediaContainer='+(bmff?'MP4':ebml?'Matroska':riff?'RIFF':'unknown')+' playableTransport='+transport);
        if(transport){
          try {
            // Prove demuxable audio/video tracks, not just an MKV/MP4 signature.
            const probe = await execFileAsync('ffprobe', [
              '-v','error','-rw_timeout','14000000','-probesize','4000000',
              '-analyzeduration','4000000','-show_entries',
              'stream=codec_type,codec_name,width,height','-of','json',fileRoute
            ], {timeout:58000,maxBuffer:200000});
            const tracks=JSON.parse(probe.stdout).streams||[];
            const video=tracks.find(x=>x.codec_type==='video'&&x.width>0&&x.height>0);
            const audio=tracks.some(x=>x.codec_type==='audio');
            if(!video){ console.log('MOOSA_RUNTIME_DECODE_PROBE video=false'); continue; }
            console.log('MOOSA_RUNTIME_TRACKS video=true audio='+audio+
              ' pixels='+video.width+'x'+video.height+' codec='+video.codec_name);
            // FFmpeg must DECODE real frames, not simply extract container metadata.
            await execFileAsync('ffmpeg', [
              '-nostdin','-hide_banner','-loglevel','error','-rw_timeout','14000000',
              '-analyzeduration','4000000','-probesize','4000000',
              '-i',fileRoute,'-map','0:v:0','-frames:v','2',
              '-an','-sn','-dn','-f','null','-'
            ],{timeout:85000,maxBuffer:200000});
            console.log('MOOSA_RUNTIME_DECODED_VIDEO_FRAMES=2');
            if(audio) {
              // Verify audio is decodable too, without writing or retaining media.
              try {
                await execFileAsync('ffmpeg', [
                  '-nostdin','-hide_banner','-loglevel','error','-rw_timeout','14000000',
                  '-i',fileRoute,'-map','0:a:0','-vn','-sn','-dn','-t','0.4',
                  '-f','null','-'
                ],{timeout:60000,maxBuffer:200000});
                console.log('MOOSA_RUNTIME_DECODED_AUDIO=true');
              }catch {
                console.log('MOOSA_RUNTIME_DECODED_AUDIO=false');
              }
            }
            played=true;
            break;
          } catch (decodeErr) {
            const why=decodeErr?.killed?'time-limit':'decode-error';
            console.log('MOOSA_RUNTIME_DECODE_TRIAL_'+i+' result='+why);
          }
        }
      }catch(e){
        const error=(e?.name==='TimeoutError'||e?.name==='AbortError')?'timeout':'network-error';
        console.log('MOOSA_RUNTIME_SOURCE_'+i+' status='+error+' media=false');
      }
    }
    if(!played)throw Error('No real media bytes from selected indexed titles through official Stremio engine');
    console.log('MOOSA_RUNTIME_P2P_FRAMES_DECODED=true');
    console.log('NOT_FULL_PLAYBACK: Decoded startup frames on Linux with FFmpeg, not a full film viewing and NOT native iOS/PC app playback.');
  } finally {await cleanup();}
}
main().catch(err=>{console.error('MOOSA_RUNTIME_FAILED '+String(err?.message||err).slice(0,190));process.exitCode=1;});
