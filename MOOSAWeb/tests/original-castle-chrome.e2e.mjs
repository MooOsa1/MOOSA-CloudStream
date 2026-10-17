/**
 * Private real Chrome trial of the ORIGINAL CineStream->Castle callback:
 * Kino .cs3 -> JVM callbacks -> MOOSA source picker -> hls.js -> real decoder.
 *
 * Never print or upload signed URLs, manifest bodies, or copyrighted frames.
 * This is a transient integration diagnostic, not permission to distribute.
 */
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const input=process.env.MOOSA_PRIVATE_CASTLE_FILE;
assert.ok(input,'An isolated runtime candidate file is required');
const candidates=JSON.parse(await readFile(input,'utf8'));
const selected=candidates.find(x=>{
  try{
    const u=new URL(x.url);
    return x.provider==='Castle' && u.protocol==='https:' &&
      u.hostname==='img1.hscow.com' && u.pathname.endsWith('.m3u8') &&
      !u.port&&!u.username&&!u.password;
  }catch{return false;}
});
assert.ok(selected,'No exact Castle HLS callback for the browser trial');
const report={realOriginalCineStream:true,castleCallbackMatched:true,
  customPluginHeadersApplied:false,actualDecodedFrames:false,
  filmDurationMinutes:null,dimensions:null,selectedViaMoosaPicker:false,
  sourceAccess:[],networkFailures:[],playerMessage:null};
const server=createServer();
await new Promise((resolve,reject)=>{
  server.once('error',reject);
  server.listen(0,'127.0.0.1',resolve);
});
const local='http://127.0.0.1:'+server.address().port;
let browser;
try{
  browser=await chromium.launch({headless:true,channel:'chrome',
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1280,height:720},
    serviceWorkers:'block'});
  page.on('response',res=>{
    try{
      const u=new URL(res.url());
      if(u.hostname!=='img1.hscow.com')return;
      report.sourceAccess.push({
        http:res.status(),
        mime:String(res.headers()['content-type']||'').split(';')[0].slice(0,50),
        kind:res.request().resourceType()
      });
    }catch{}
  });
  page.on('requestfailed',req=>{
    try{
      const host=new URL(req.url()).hostname;
      if(host==='img1.hscow.com')
        report.networkFailures.push(String(req.failure()?.errorText||'unknown').slice(0,100));
    }catch{}
  });
  // Exact production HTTPS origin. Only OUR HTML/JS is served from the
  // checked-out working tree. Media requests go straight to the original
  // third-party CDN, without a proxy or custom Referer.
  await page.route('https://moosa-web.onrender.com/**',async route=>{
    const u=new URL(route.request().url());
    const source=await fetch(local+u.pathname+u.search,{redirect:'manual'});
    await route.fulfill({status:source.status,
      headers:{'Content-Type':source.headers.get('content-type')||'text/plain'},
      body:Buffer.from(await source.arrayBuffer())});
  });
  await page.route('https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.min.js',
    async route=>route.fulfill({status:200,contentType:'text/javascript',
      body:await readFile('node_modules/hls.js/dist/hls.min.js')}));
  await page.goto('https://moosa-web.onrender.com/',{
    waitUntil:'domcontentloaded',timeout:30000});
  await page.evaluate(async url=>{
    const {showPlayer,appendPlayerSources}=await import('/player.js');
    showPlayer({title:'Dead Night',sources:[]});
    appendPlayerSources([{
      url,name:'CineStream • Castle',
      quality:480,
      singleFilmPilot:true,
      verifiedFilmImdbId:'tt5719076'
    }]);
  },selected.url);
  report.selectedViaMoosaPicker=(await page.locator('#player-source').inputValue())==='0';
  assert.equal(report.selectedViaMoosaPicker,true);
  let reachedDecodedPlayback=false;
  try{
    await page.waitForFunction(()=>{
      const v=document.querySelector('#video');
      return v && !v.paused && v.readyState>=2 &&
        v.currentTime>0.7 && v.videoWidth>0 && v.videoHeight>0;
    },null,{timeout:85000});
    reachedDecodedPlayback=true;
  }catch{report.decoderTimedOut=true;}
  const state=await page.locator('#video').evaluate(v=>({
    elapsedSeconds:Math.round(v.currentTime*1000)/1000,
    duration:Number.isFinite(v.duration)?Math.round(v.duration):null,
    width:v.videoWidth,height:v.videoHeight,
    readyState:v.readyState,networkState:v.networkState,
    errorCode:v.error?.code||null,
    paused:v.paused,
    hasHlsjs:typeof window.Hls==='function',
    playerMessage:String(document.querySelector('#player-message')?.textContent||'').slice(0,100)
  }));
  // Use the boolean established by real playback observation, not the
  // rounded timestamp: 0.71 s rounds to 0.7 s and caused a false failure.
  report.actualDecodedFrames=Boolean(reachedDecodedPlayback &&
    !report.decoderTimedOut && state.width>0&&state.height>0 &&
    state.errorCode===null && !state.paused);
  report.measuredPlaybackSeconds=state.elapsedSeconds;
  report.filmDurationMinutes=state.duration===null?null:Math.round(state.duration/60);
  report.dimensions=[state.width,state.height];
  report.playerMessage=state.playerMessage;
  report.videoErrorCode=state.errorCode;
  report.hlsjs=state.hasHlsjs;
  report.sourceAccess=report.sourceAccess.slice(-12);
  report.networkFailures=report.networkFailures.slice(-6);
  await page.locator('#video').evaluate(v=>v.pause());
  assert.equal(report.actualDecodedFrames,true,
    'The original Castle HLS failed to decode in actual MOOSA Chrome playback');
  assert.ok(state.duration>=70*60 && state.duration<=100*60,
    'The clip did not identify as an approximately 82-minute feature');
}finally{
  console.log('MOOSA_ORIGINAL_CASTLE_PRIVATE_CHROME '+JSON.stringify(report));
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>server.close(resolve));
}
