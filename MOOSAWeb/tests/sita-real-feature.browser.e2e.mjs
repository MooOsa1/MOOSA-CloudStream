/**
 * Direct licensed full-feature film E2E. NOT Blender and NOT a synthetic clip.
 * The whole path is checked out MOOSA site -> /api/free-library ->
 * /api/free-film -> verified Commons CC0 URL -> MOOSA <video> decoder.
 *
 * No link guessing, site proxy, arbitrary CDN, or claims about .cs3.
 */
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';
const site=createServer();
const root=await new Promise((resolve,reject)=>{
  site.once('error',reject);
  site.listen(0,'127.0.0.1',()=>resolve('http://127.0.0.1:'+site.address().port));
});
let browser;
const report={
  film:'Sita Sings the Blues',fullFeatureRuntimeExpected:true,
  verifiedOpenLicense:false,correctTitle:false,decoderPlayed:false,
  decodedDurationMinutes:0,decodedWidth:0,decodedHeight:0
};
try{
  const res=await fetch(root+'/api/free-film?id=sita-sings-the-blues',{
    signal:AbortSignal.timeout(28000)
  });
  assert.equal(res.status,200,'MOOSA failed to resolve the exact licensed film metadata');
  const film=await res.json();
  assert.equal(film.title,'Sita Sings the Blues');
  assert.equal(film.year,2008);
  assert.ok(film.sources.length>=1,'Wikimedia Commons did not verify the original full video');
  report.sourceRights=film.sources.map(s=>String(s.licenseUrl||'none').slice(0,110));
  assert.ok(film.sources.every(s=>
    /^https:\/\/creativecommons\.org\/(?:publicdomain\/zero\/1\.0\/(?:deed\.en)?|licenses\/by-sa\/3\.0\/)$/.test(
      String(s.licenseUrl||''))),'Sita source needs verified CC0 or CC BY-SA 3.0 rights');
  assert.ok(film.sources.every(s=>new URL(s.url).hostname==='upload.wikimedia.org'));
  report.correctTitle=true;report.verifiedOpenLicense=true;
  browser=await chromium.launch({channel:'chrome',headless:true,
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1280,height:800}});
  await page.goto(root,{waitUntil:'domcontentloaded',timeout:28000});
  const card=page.locator('[data-free-film="sita-sings-the-blues"]');
  await card.waitFor({state:'visible',timeout:30000});
  await card.click();
  await page.waitForFunction(()=>{
    const v=document.querySelector('#video');
    return v&&!v.paused&&v.readyState>=2&&v.videoWidth>0&&
      v.videoHeight>0&&v.currentTime>0.75&&
      Number.isFinite(v.duration)&&v.duration>4500;
  },null,{timeout:85000});
  const play=await page.locator('#video').evaluate(v=>({
    seconds:v.currentTime,
    duration:v.duration,
    width:v.videoWidth,height:v.videoHeight,
    paused:v.paused
  }));
  report.decoderPlayed=play.seconds>0.75;
  report.decodedDurationMinutes=Math.round(play.duration/60);
  report.decodedWidth=play.width;
  report.decodedHeight=play.height;
  report.sourceSelected=await page.locator('#player-source').inputValue();
  assert.ok(report.decoderPlayed && play.duration>4500);
  assert.equal(report.sourceSelected,'0');
  await page.locator('#video').evaluate(v=>v.pause());
}finally{
  console.log('MOOSA_REAL_LICENSED_FEATURE_CHROME '+JSON.stringify(report));
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>site.close(resolve));
}
