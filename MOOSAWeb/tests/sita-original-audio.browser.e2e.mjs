/**
 * Actual full-feature sound-capable MOVIE from filmmaker-linked Internet
 * Archive, selected by the standard MOOSA player. Do not substitute Commons'
 * explicitly muted WebM. This is not a codec-only synthetic fixture.
 */
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';
import {SITA_AUDIO_720,FILMMAKER_LICENSE} from '../sita-filmmaker-sound.mjs';
const server=createServer();
await new Promise((resolve,reject)=>{
  server.once('error',reject);
  server.listen(0,'127.0.0.1',resolve);
});
const origin='http://127.0.0.1:'+server.address().port;
const results={
  mediaSource:'filmmaker-archive-h264-aac',
  expectedAudio:'aac-stereo',
  sourceVerified:false,played:false,mp4DurationMinutes:null,
  decoderWidth:0,decoderHeight:0,muted:null,volume:null
};
let browser;
try{
  const res=await fetch(origin+'/api/free-film?id=sita-sings-the-blues',{
    signal:AbortSignal.timeout(25000)});
  assert.equal(res.status,200);
  const film=await res.json();
  assert.equal(film.sourceEngine,'filmmaker_official_archive_h264_aac');
  assert.equal(film.sources[0].url,SITA_AUDIO_720);
  assert.equal(film.sources[0].audioCodec,'aac');
  assert.equal(film.sources[0].quality,720);
  assert.equal(film.sources[0].licenseUrl,FILMMAKER_LICENSE);
  assert.equal(film.sources[1].silent,true);
  results.sourceVerified=true;
  browser=await chromium.launch({channel:'chrome',headless:true,
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1240,height:780}});
  page.on('requestfailed',req=>{
    if(/archive\.org|\/download\//.test(req.url()))
      results.networkFailure=String(req.failure()?.errorText||'unknown').slice(0,90);
  });
  await page.goto(origin,{waitUntil:'domcontentloaded',timeout:30000});
  await page.locator('[data-free-film="sita-sings-the-blues"]').click({timeout:32000});
  await page.waitForFunction(()=>{
    const video=document.querySelector('#video');
    return video&&!video.paused&&video.readyState>=2&&video.currentTime>0.5&&
      video.videoWidth>=1280&&video.videoHeight>=720&&
      Number.isFinite(video.duration)&&video.duration>4700;
  },null,{timeout:105000});
  const state=await page.locator('#video').evaluate(v=>({
    src:v.currentSrc,duration:v.duration,paused:v.paused,
    width:v.videoWidth,height:v.videoHeight,time:v.currentTime,
    muted:v.muted,volume:v.volume,readyState:v.readyState
  }));
  assert.equal(state.src,SITA_AUDIO_720);
  assert.equal(await page.locator('#player-source').inputValue(),'0');
  assert.equal(state.muted,false);
  assert.ok(state.volume>0);
  results.played=true;
  results.mp4DurationMinutes=Math.round(state.duration/60);
  results.decoderWidth=state.width;
  results.decoderHeight=state.height;
  results.muted=state.muted;
  results.volume=state.volume;
  results.sourceIndex='0';
  await page.locator('#video').evaluate(v=>v.pause());
}finally{
  console.log('MOOSA_SITA_WITH_REAL_AAC_AUDIO '+JSON.stringify(results));
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>server.close(resolve));
}
