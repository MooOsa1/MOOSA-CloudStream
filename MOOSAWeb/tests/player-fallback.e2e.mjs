import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const server=createServer();
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try{
  browser=await chromium.launch({headless:true});
  const page=await browser.newPage({
    viewport:{width:390,height:844},isMobile:true,hasTouch:true
  });
  const errors=[],network=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('https://retry.example/**',async route=>{
    network.push(new URL(route.request().url()).pathname);
    await route.fulfill({status:404,contentType:'text/plain',body:'Not found'});
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/',{
    waitUntil:'domcontentloaded'
  });
  await page.evaluate(async()=>{
    const {showPlayer,appendPlayerSources}=await import('/player.js');
    showPlayer({title:'One verified title',sources:[]});
    appendPlayerSources([
      {url:'https://retry.example/one.mp4',name:'Source one',quality:1080},
      {url:'https://retry.example/two.mp4',name:'Source two',quality:720},
      {url:'https://retry.example/three.mp4',name:'Source three',quality:480}
    ]);
  });
  await page.waitForFunction(()=>{
    const value=document.querySelector('#player-source')?.value;
    return value==='2' &&
      /تعذر تشغيل/.test(document.querySelector('#player-message')?.textContent||'');
  },null,{timeout:15000});
  assert.equal((await page.locator('#player-title').textContent()),
    'One verified title','A source retry cannot substitute a movie title');
  assert.equal(await page.locator('#player-source').inputValue(),'2');
  const tried=new Set(network.filter(p=>/\/(one|two|three)\.mp4/.test(p)));
  assert.deepEqual([...tried].sort(),
    ['/one.mp4','/three.mp4','/two.mp4']);
  assert.ok(network.length<=6,
    'Bounded retry prevents unending requests when every source is broken');

  // A new movie resets attempted URLs. It must not retry the previous title.
  const before=network.length;
  await page.evaluate(async()=>{
    const {showPlayer,appendPlayerSources}=await import('/player.js');
    showPlayer({title:'Another exact movie',sources:[]});
    appendPlayerSources([
      {url:'https://retry.example/new.mp4',name:'New film only',quality:1080}
    ]);
  });
  await page.waitForFunction(()=>{
    return /تعذر تشغيل/.test(document.querySelector('#player-message')?.textContent||'');
  },null,{timeout:15000});
  assert.equal((await page.locator('#player-title').textContent()),
    'Another exact movie');
  assert.equal(await page.locator('#player-source option').count(),2);
  assert.ok(network.slice(before).every(x=>x==='/new.mp4'),
    'Old movie sources must never be reused after changing titles');
  assert.deepEqual(errors,[]);
  console.log('PLAYER_FALLBACK_E2E_PASS '+JSON.stringify({
    distinctFirstTitle:[...tried].sort(),noInfiniteLoop:network.length<=7,
    newTitleRequestCount:network.slice(before).length
  }));
}finally{
  await browser?.close();
  await new Promise(resolve=>server.close(resolve));
}
