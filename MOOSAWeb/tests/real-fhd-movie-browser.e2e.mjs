/**
 * Real end-to-end proof of an opened complete Creative Commons movie
 * playing at native 1920x1080 or higher through MOOSA Web's OWN player.
 * No ad iframe, no remote player, no mock media, no Android, no relay.
 * This says NOTHING about access to commercial studio titles.
 */
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';
import {getCuratedFilm} from '../free-film-library.mjs';

const film=await getCuratedFilm('big-buck-bunny');
assert.equal(film?.available,true,'Licensed Commons film must resolve');
const fhd=film.sources.find(x=>x.quality===1080 &&
  x.encodedWidth>=1900&&x.encodedHeight>=1000);
assert.ok(fhd,'Must find a Commons-generated source marked official 1920x1080, not just 4K');
assert.equal(new URL(fhd.url).hostname,'upload.wikimedia.org');
assert.match(fhd.licenseUrl,/^https:\/\/creativecommons\.org\/licenses\/by\/3\.0\//);
assert.match(fhd.sourcePage,/^https:\/\/commons\.wikimedia\.org\/wiki\/File:/);

const app=createServer();
await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));
let browser;
try{
  browser=await chromium.launch({headless:true});
  const page=await browser.newPage({
    viewport:{width:1280,height:720},
    permissions:[],acceptDownloads:false
  });
  const invalidErrors=[];
  page.on('pageerror',e=>invalidErrors.push(e.message));
  const response=await page.goto('http://127.0.0.1:'+app.address().port+'/',{
    waitUntil:'domcontentloaded',timeout:15000
  });
  assert.equal(response.status(),200);
  const csp=response.headers()['content-security-policy'];
  assert.ok(csp.includes("frame-src 'none'"),'Blocked arbitrary ad iframes');
  assert.equal(await page.locator('iframe').count(),0);
  await page.evaluate(sources=>{
    document.querySelector('#overlay')?.classList.add('hidden');
    // Real user flow: open MOOSA player, append source list, auto-play first.
    // No document.createElement('video') outside app's own controls.
    return import('/player.js').then(({showPlayer,appendPlayerSources})=>{
      showPlayer({title:'Big Buck Bunny — Creative Commons',sources:[]});
      appendPlayerSources(sources);
    });
  },film.sources);
  await page.waitForFunction(()=>{
    const video=document.querySelector('#video');
    const picker=document.querySelector('#player-source');
    return video && !video.paused && video.readyState>=2 &&
      video.videoWidth>=1900 && video.videoHeight>=1000 &&
      !video.error && picker?.value!=='' &&
      document.querySelector('#player-panel')?.classList.contains('hidden')===false;
  },null,{timeout:25000});
  const result=await page.evaluate(async()=>{
    const video=document.querySelector('#video');
    const picked=document.querySelector('#player-source').selectedOptions?.[0]?.textContent||'';
    return new Promise(resolve=>{
      const watch=setTimeout(()=>resolve({decoded:false,error:'frame_timeout'}),9000);
      video.requestVideoFrameCallback((_time,meta)=>{
        clearTimeout(watch);
        resolve({
          decoded:true,width:video.videoWidth,height:video.videoHeight,
          presentedFrames:meta.presentedFrames,played:!video.paused,
          currentTime:video.currentTime,picked
        });
      });
    });
  });
  assert.equal(result.decoded,true);
  assert.equal(result.width,1920);
  assert.equal(result.height,1080);
  assert.ok(result.played,'FHD stream must be actively playing');
  assert.match(result.picked,/1080p/);
  assert.equal(await page.locator('iframe').count(),0);
  assert.deepEqual(invalidErrors,[]);
  console.log('MOOSA_REAL_FHD_PLAYER '+JSON.stringify({
    title:'Big Buck Bunny',source:'Wikimedia Commons, CC BY 3.0',
    codec:'VP9',actualWidth:result.width,actualHeight:result.height,
    decodedFrame:true,adsFromSiteOrIframes:0,commercialCatalogProof:false
  }));
}finally{
  await browser?.close();
  await new Promise(resolve=>app.close(resolve));
}
