/**
 * Attempt real Chrome playback of the actual original CineStream URL found
 * in the same GitHub Actions run. The raw URL stays on an ephemeral runner.
 *
 * Serve checked-out MOOSA Web code under its real HTTPS origin using route
 * interception, so CORS conditions match moosa-web.onrender.com. Do not proxy
 * external video; hls.js contacts original media CDN directly from Chrome.
 */
import assert from 'node:assert/strict';
import {readFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const tokenFile='/tmp/moosa-jvm/verified-cinestream-hls.b64';
const raw=(await readFile(tokenFile,'utf8')).trim();
const streamUrl=Buffer.from(raw,'base64').toString('utf8');
if(!/^https:\/\/[a-z0-9.-]+\/[^\s]*\.m3u8(?:[?#]|$)/i.test(streamUrl))
  throw Error('A valid, private first-segment-verified HLS URL was not provided');
const errors=[],failed=[],responses=[];
const server=createServer();
await new Promise((resolve,reject)=>{
  server.once('error',reject);
  server.listen(0,'127.0.0.1',resolve);
});
const local='http://127.0.0.1:'+server.address().port;
let browser,page;
const out=path.resolve('browser-artifacts');
await mkdir(out,{recursive:true});
try{
  browser=await chromium.launch({
    headless:true,channel:'chrome',
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']
  });
  page=await browser.newPage({
    viewport:{width:1280,height:720},
    serviceWorkers:'block'
  });
  // Diagnostics strictly within the test: instrument the hls.js runtime,
  // never output manifest URLs, signed query strings or response bodies.
  await page.addInitScript(()=>{
    const errors=[];
    window.__moosaHlsDiagnostics=errors;
    // No browser override: exercise the actual production HLS selection.
    // The player should now choose hls.js despite Chrome native "maybe".
    let ctor;
    Object.defineProperty(window,'Hls',{
      configurable:true,
      get(){return ctor;},
      set(HlsClass){
        if(typeof HlsClass!=='function'){ctor=HlsClass;return;}
        class ObservedHls extends HlsClass {
          constructor(...args){
            super(...args);
            this.on(HlsClass.Events.ERROR,(_ev,data)=>{
              errors.push({
                fatal:Boolean(data?.fatal),
                type:String(data?.type||'').slice(0,70),
                details:String(data?.details||'').slice(0,100),
                responseCode:data?.response?.code??null,
                fragType:String(data?.frag?.type||'').slice(0,45),
                level:data?.level??null
              });
            });
            this.on(HlsClass.Events.MANIFEST_PARSED,(_ev,data)=>{
              window.__moosaManifestLevels=(data?.levels||[]).map(x=>({
                height:x.height??null,videoCodec:String(x.videoCodec||'').slice(0,50),
                audioCodec:String(x.audioCodec||'').slice(0,50)
              })).slice(0,8);
            });
          }
        }
        ctor=ObservedHls;
      }
    });
  });
  page.on('pageerror',e=>errors.push(e.name||'page_error'));
  page.on('response',res=>{
    try{
      const u=new URL(res.url());
      if(!['img.fcxmb.com','img1.fcxmb.com','cdn.jsdelivr.net'].includes(u.hostname))return;
      responses.push({
        host:u.hostname,status:res.status(),
        kind:res.request().resourceType(),
        mime:(res.headers()['content-type']||'').split(';')[0].slice(0,55),
        cors:res.headers()['access-control-allow-origin']||null
      });
    }catch{}
  });
  page.on('requestfailed',req=>{
    try{failed.push({
      host:new URL(req.url()).hostname,
      reason:String(req.failure()?.errorText||'failed').slice(0,80)
    });}catch{}
  });
  await page.route('https://moosa-web.onrender.com/**',async route=>{
    const u=new URL(route.request().url());
    const upstream=await fetch(local+u.pathname+u.search,{redirect:'manual'});
    const ct=upstream.headers.get('content-type')||'application/octet-stream';
    await route.fulfill({
      status:upstream.status,
      headers:{'Content-Type':ct,'Access-Control-Allow-Origin':'*'},
      body:Buffer.from(await upstream.arrayBuffer())
    });
  });
  await page.route('https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.min.js',
    async route=>route.fulfill({
      status:200,contentType:'text/javascript',
      body:await readFile(path.resolve('node_modules/hls.js/dist/hls.min.js'))
    }));
  await page.goto('https://moosa-web.onrender.com/',{
    waitUntil:'domcontentloaded',timeout:25000
  });
  await page.evaluate(async(url)=>{
    const {showPlayer}=await import('/player.js');
    showPlayer({title:'Sintel — licensed original CineStream stream',sources:[]});
    document.querySelector('#custom-stream').value=url;
    document.querySelector('#play-custom').click();
  },streamUrl);
  let timedOut=false;
  try{
    await page.waitForFunction(()=>{
      const v=document.querySelector('#video');
      return !!v && !v.paused && v.readyState>=2 &&
        v.currentTime>0.3 && v.videoWidth>0;
    },null,{timeout:35000});
  }catch{timedOut=true;}
  const state=await page.locator('#video').evaluate(v=>({
    secondsPlayed:Math.round(v.currentTime*10)/10,
    width:v.videoWidth,height:v.videoHeight,
    readyState:v.readyState,networkState:v.networkState,
    paused:v.paused,
    errorCode:v.error?.code||null,
    errorMessage:String(v.error?.message||'').slice(0,140),
    nativeHls:v.canPlayType('application/vnd.apple.mpegurl'),
    hasHlsjs:typeof window.Hls==='function',
    hlsjsSupported:typeof window.Hls==='function'&&window.Hls.isSupported(),
    hlsEvents:window.__moosaHlsDiagnostics||[],
    manifestLevels:window.__moosaManifestLevels||[],
    playerMessage:String(document.querySelector('#player-message')?.textContent||'').slice(0,200)
  }));
  const played=!timedOut&&state.secondsPlayed>0&&state.width>0;
  // Browser audioTracks is only container metadata; it does NOT prove PCM.
  // Measure actual decoded samples from the same original HLS source.
  // On MSE-backed hls.js this AudioContext attaches to the video's output,
  // never fetching media separately or sending the signed URL to our server.
  const audioProof=played?await page.evaluate(async()=>{
    const v=document.querySelector('#video');
    const r={status:'inconclusive',trackCount:
      typeof v?.audioTracks?.length==='number'?v.audioTracks.length:null,
      unmuted:!v.muted&&v.volume>0,maxRms:0,
      seekedToThreeMinutes:false,secondsAdvanced:0};
    const Context=window.AudioContext||window.webkitAudioContext;
    if(!Context){r.status='audio_context_unsupported';return r;}
    let ctx;
    try {
      ctx=new Context();
      const source=ctx.createMediaElementSource(v);
      const analyser=ctx.createAnalyser();
      analyser.fftSize=2048;
      source.connect(analyser);
      analyser.connect(ctx.destination);
      await ctx.resume();
      const samples=new Float32Array(analyser.fftSize);
      const measure=()=>{
        analyser.getFloatTimeDomainData(samples);
        let power=0;
        for(const sample of samples)power+=sample*sample;
        r.maxRms=Math.max(r.maxRms,Math.sqrt(power/samples.length));
      };
      // Check early soundtrack and again after seeking to minute three.
      for(let i=0;i<10;i++){
        await new Promise(resolve=>setTimeout(resolve,200));
        measure();
      }
      if(Number.isFinite(v.duration)&&v.duration>220){
        v.currentTime=180;
        await Promise.race([
          new Promise(resolve=>v.addEventListener('seeked',resolve,{once:true})),
          new Promise(resolve=>setTimeout(resolve,9000))
        ]);
        r.seekedToThreeMinutes=v.currentTime>=179;
      }
      const before=v.currentTime;
      for(let i=0;i<30;i++){
        await new Promise(resolve=>setTimeout(resolve,200));
        measure();
      }
      r.secondsAdvanced=Math.round(Math.max(0,v.currentTime-before)*10)/10;
      r.maxRms=Math.round(r.maxRms*100000)/100000;
      r.status=r.maxRms>0.002?'decoded_non_silent_pcm':'no_pcm_signal_measured';
    }catch(error){
      r.status='audio_probe_failed';
      r.errorName=String(error?.name||'unknown').slice(0,45);
    }finally{await ctx?.close().catch(()=>{});}
    return r;
  }):{status:'video_not_decoded'};
  console.log('ORIGINAL_CINESTREAM_AUDIO_PROOF '+JSON.stringify(audioProof));
  console.log('ORIGINAL_CINESTREAM_CHROME_PLAYBACK '+JSON.stringify({
    actuallyDecodedFrames:played,video:state,
    pageErrorTypes:errors.slice(0,5),
    networkFailures:failed.slice(0,7),
    mediaResponses:responses.slice(-18)
  }));
  if(played){
    // Screenshot only the video frame — do not capture hidden signed URL input.
    await page.locator('#video').screenshot({
      path:path.join(out,'original-cinestream-sintel-frame.png')
    });
  }
  assert.equal(played,true,
    'Original CineStream first segment did not produce frames in real Chrome');
}finally{
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>server.close(resolve));
}
