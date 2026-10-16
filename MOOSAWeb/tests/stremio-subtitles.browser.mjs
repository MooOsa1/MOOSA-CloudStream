import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const server=createServer();
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try {
  browser=await chromium.launch({headless:true});
  const page=await browser.newPage({
    viewport:{width:390,height:844},isMobile:true,hasTouch:true,
    userAgent:'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1'
  });
  const errors=[];
  page.on('pageerror',err=>errors.push(err.message));
  await page.route('https://video.example/**',route=>
    route.fulfill({status:404,contentType:'text/plain',body:'Simulated unavailable video'}));
  let subtitleResolve=0,videoResolve=0;
  await page.route('**/api/resolve?*',async route=>{
    videoResolve++;
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({
      sources:[{url:'https://video.example/film.mp4',quality:1080,name:'Test movie'}],
      count:1,providerMode:'server'
    })});
  });
  await page.route('**/api/stremio-subtitles?*',async route=>{
    subtitleResolve++;
    await new Promise(resolve=>setTimeout(resolve,1150));
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({
      subtitles:[
        {url:'https://subs.example/film.ar.vtt',language:'ar',label:'Arabic',source:'HTTP Stremio'},
        {url:'https://subs.example/film.en.srt',language:'en',label:'English',source:'HTTP Stremio'}
      ],count:2,active:true
    })});
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/',{
    waitUntil:'domcontentloaded'
  });
  await page.evaluate(async()=>{
    const {showPlayer}=await import('/player.js');
    const {searchAutomaticSources}=await import('/source-search.js');
    showPlayer({title:'Licensed sample title',sources:[]});
    void searchAutomaticSources('movie',111);
  });
  await page.waitForFunction(()=>
    document.querySelector('#player-source option')?.length===2 &&
    document.querySelector('#player-source')?.value==='0',null,{timeout:700});
  const early=await page.locator('#player-subtitles option').allTextContents();
  assert.equal(early.length,1,'video source must appear before slow subtitles finish');
  assert.equal(videoResolve,1);
  await page.waitForFunction(()=>document.querySelector('#player-subtitles option')?.length===3,
    null,{timeout:5000});
  const tracks=await page.locator('#player-subtitles option').allTextContents();
  assert.ok(tracks[1].includes('Arabic'));
  assert.ok(tracks[2].includes('English'));
  assert.equal(subtitleResolve,1);
  assert.equal(await page.locator('#player-title').textContent(),'Licensed sample title');
  assert.ok((await page.locator('#video').getAttribute('src')).includes('/film.mp4'));

  await page.evaluate(async()=>{
    const {showPlayer,appendPlayerSources}=await import('/player.js');
    showPlayer({title:'Different exact TV episode',sources:[]});
    appendPlayerSources([{url:'https://video.example/other.mp4',
      name:'Episode 2',quality:720}]);
  });
  assert.equal((await page.locator('#player-subtitles option').count()),1,
    'subtitle results from a previous movie must be cleared on a new title');
  assert.equal(await page.locator('#player-title').textContent(),'Different exact TV episode');
  assert.deepEqual(errors,[]);
  console.log('STREMIO_SUBTITLE_MOBILE_BROWSER_PASS '+JSON.stringify({
    videoRequests:videoResolve,optionalSubtitleRequests:subtitleResolve,
    extraTracks:tracks.slice(1),newTitleIsolated:true
  }));
  await page.close();
}finally{
  await browser?.close();
  await new Promise(resolve=>server.close(resolve));
}
