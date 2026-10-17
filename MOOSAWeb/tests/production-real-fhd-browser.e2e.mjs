/**
 * PRODUCTION-only read/write-free real playback smoke on MOOSA's live Render
 * service. Complete openly licensed Blender Foundation Big Buck Bunny only.
 * No copyrighted feature URLs, no ads, no iframe, no proxy, no pairing.
 *
 * This tests whether Render resolves the real official Commons FHD derivative,
 * and whether Chrome actually decodes frames via the deployed player.
 */
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
const BASE='https://moosa-web.onrender.com';
const response=await fetch(BASE+'/api/free-film?id=big-buck-bunny',{
  headers:{Accept:'application/json'},signal:AbortSignal.timeout(30000)
});
assert.equal(response.status,200,'Live Render film API must respond successfully');
const film=await response.json();
const sources=Array.isArray(film.sources)?film.sources:[];
const fhd=sources.find(s=>Number(s.quality)===1080 &&
  new URL(s.url).hostname==='upload.wikimedia.org' &&
  /^https:\/\/creativecommons\.org\/licenses\/by\/3\.0\/?$/.test(s.licenseUrl));
assert.ok(fhd,'Live Render must expose official 1080p licensed full-film source');
let browser;
try{
  browser=await chromium.launch({headless:true});
  const page=await browser.newPage({viewport:{width:1280,height:720}});
  const errors=[];page.on('pageerror',e=>errors.push(String(e.message).slice(0,180)));
  const home=await page.goto(BASE+'/',{waitUntil:'domcontentloaded',timeout:20000});
  assert.equal(home.status(),200);
  assert.match(home.headers()['content-security-policy'],/frame-src 'none'/);
  await page.evaluate(videoSources=>import('/player.js').then(({showPlayer,appendPlayerSources})=>{
    showPlayer({title:'Big Buck Bunny — Licensed Complete Movie',sources:[]});
    appendPlayerSources(videoSources);
  }),sources);
  await page.waitForFunction(()=>{
    const v=document.querySelector('#video');
    return v && v.videoWidth===1920 && v.videoHeight===1080 &&
      v.readyState>=2 && !v.paused && !v.error;
  },null,{timeout:30000});
  const result=await page.evaluate(()=>new Promise(resolve=>{
    const video=document.querySelector('#video');
    const time=setTimeout(()=>resolve({decoded:false}),15000);
    video.requestVideoFrameCallback((_now,info)=>{
      clearTimeout(time);
      resolve({decoded:true,presentedFrames:info.presentedFrames,
        width:video.videoWidth,height:video.videoHeight,
        playing:!video.paused});
    });
  }));
  assert.equal(result.decoded,true);
  assert.equal(result.width,1920);assert.equal(result.height,1080);
  assert.equal(result.playing,true);
  assert.equal(await page.locator('iframe').count(),0);
  assert.deepEqual(errors,[]);
  console.log('MOOSA_PRODUCTION_REAL_FHD '+JSON.stringify({
    url:BASE,title:film.title,decodedFrames:true,
    width:result.width,height:result.height,
    websiteIframes:0,adsFromSiteCode:false,
    source:'Wikimedia Commons CC BY 3.0',
    copyrightedCatalogResolved:false
  }));
}finally{await browser?.close()}
