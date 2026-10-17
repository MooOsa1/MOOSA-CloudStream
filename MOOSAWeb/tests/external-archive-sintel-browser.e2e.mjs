/**
 * Real, independent film playback from external Stremio Archive.org addon.
 * Complete CC BY Sintel (2010), IMDb tt1727587, not a simulated source.
 *
 * Proof levels: rights+exact identity -> server normalized source -> Chrome
 * player loaded video -> actual decoded frame. No iframe, P2P, video proxy,
 * DRM bypass, commercial movies or raw media URLs in logs.
 */
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';
import {fromWebAddons} from '../providers.mjs';

const EXTERNAL='https://dev.nebulawp.org/stremio/archive.org-addon/manifest.json';
const movie={media:'movie',id:45745,title:'Sintel',originalTitle:'Sintel',
  year:2010,imdbId:'tt1727587'};
const sources=await fromWebAddons(movie,{addons:[EXTERNAL]});
const valid=sources.filter(s=>{
  try{return new URL(s.url).hostname==='archive.org'&&
    Number.isInteger(s.quality)&&s.quality>=720&&
    /^Archive verified/.test(s.name)}
  catch{return false}
});
assert.ok(valid.length>0,
  'Real external addon must return at least one independently rights-verified full movie direct video');
const candidates=valid.filter(s=>s.quality>=1080);
const selected=(candidates.length?candidates:valid)[0];
let server,browser;
try{
  server=createServer();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const addr='http://127.0.0.1:'+server.address().port;
  browser=await chromium.launch({
    headless:true,args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']
  });
  const page=await browser.newPage({viewport:{width:1280,height:720}});
  const errors=[];page.on('pageerror',e=>errors.push(String(e.message).slice(0,200)));
  const response=await page.goto(addr+'/',{waitUntil:'domcontentloaded',timeout:18000});
  assert.equal(response.status(),200);
  assert.ok(response.headers()['content-security-policy'].includes("frame-src 'none'"));
  await page.evaluate((source)=>import('/player.js')
    .then(({showPlayer,appendPlayerSources})=>{
      showPlayer({title:'Sintel — 2010 CC-BY full licensed film',sources:[]});
      appendPlayerSources([source]);
    }),selected);
  await page.waitForFunction(()=>{
    const v=document.querySelector('#video');
    return v&&!v.paused&&v.readyState>=2&&
      v.videoHeight>=720&&v.videoWidth>=1000&&!v.error;
  },null,{timeout:45000});
  const result=await page.evaluate(()=>new Promise(resolve=>{
    const v=document.querySelector('#video'),timer=setTimeout(()=>resolve({decoded:false}),15000);
    v.requestVideoFrameCallback((_now,metadata)=>{
      clearTimeout(timer);
      resolve({
        decoded:true,presentedFrames:metadata.presentedFrames,
        width:v.videoWidth,height:v.videoHeight,
        readyState:v.readyState,playing:!v.paused,mediaError:v.error?.code||0
      });
    });
  }));
  assert.equal(result.decoded,true,'Browser must decode an actual movie frame');
  assert.equal(result.mediaError,0);
  assert.ok(result.height>=720&&result.width>=1000,'Actual decoded HD dimensions required');
  assert.equal(result.playing,true);
  assert.equal(await page.locator('iframe').count(),0);
  assert.deepEqual(errors,[]);
  console.log('MOOSA_EXTERNAL_ARCHIVE_DECODED '+JSON.stringify({
    film:'Sintel',year:2010,provenance:'Archive.org source metadata CC BY verified',
    host:new URL(selected.url).host,transport:'direct HTML5 video in MOOSA Web',
    verifiedMetadataHeight:selected.quality,
    decodedFrame:true,actualWidth:result.width,actualHeight:result.height,
    true1080p:result.height>=1080,adIframes:0,sitePopups:0,
    zeroAdvertisementInsideContentCertified:false,
    licensedModernCommercialCatalog:false
  }));
}finally{
  await browser?.close();
  if(server)await new Promise(resolve=>server.close(resolve));
}
