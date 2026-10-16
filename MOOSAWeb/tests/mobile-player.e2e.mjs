import assert from 'node:assert/strict';
import path from 'node:path';
import {mkdir} from 'node:fs/promises';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const server=createServer();
const port=await new Promise(resolve=>server.listen(0,'127.0.0.1',()=>resolve(server.address().port)));
const artifactDir=path.resolve('browser-artifacts');
await mkdir(artifactDir,{recursive:true});
let browser;
try {
  browser=await chromium.launch({headless:true});
  const page=await browser.newPage({
    viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true
  });
  const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/resolve?*',async route=>route.fulfill({
    status:200,contentType:'application/json',
    body:JSON.stringify({sources:[],count:0,cloudstreamEngineConfigured:false})
  }));
  await page.goto('http://127.0.0.1:'+port+'/',{waitUntil:'domcontentloaded'});
  await page.evaluate(async()=>{
    const {showPlayer}=await import('/player.js');
    showPlayer({title:'Animals',sources:[]});
  });
  const selector=page.locator('#player-source');
  assert.equal(await selector.isDisabled(),true,'No available sources should disable native iOS picker');
  assert.equal(await selector.locator('option').count(),1);
  const settings=await page.locator('.player-settings').boundingBox();
  const select=await selector.boundingBox();
  assert.ok(settings&&select);
  assert.ok(select.width/settings.width>.92,'Source selector should use full mobile row');
  await page.evaluate(async()=>{
    const {searchAutomaticSources}=await import('/source-search.js');
    await searchAutomaticSources('movie',42);
  });
  const status=await page.locator('#automatic-source-status').innerText();
  assert.match(status,/محرك CineStream وStreamPlay غير مشغّل/);
  assert.equal(await selector.isDisabled(),true);
  await page.screenshot({path:path.join(artifactDir,'ios-empty-source.png'),fullPage:true});
  // A genuine new source should immediately unlock the source dropdown;
  // no real movie URL is fetched in this synthetic test.
  await page.route('https://licensed-demo.invalid/video.mp4',route=>route.fulfill({
    status:403,body:'Not a real demo stream',
    headers:{'Access-Control-Allow-Origin':'*'}
  }));
  await page.evaluate(async()=>{
    const {appendPlayerSources}=await import('/player.js');
    appendPlayerSources([{url:'https://licensed-demo.invalid/video.mp4',name:'Testing UI',quality:1080}]);
  });
  assert.equal(await selector.isEnabled(),true);
  assert.ok((await selector.locator('option').count())>=2);
  await page.evaluate(async()=>{
    const {showPlayer}=await import('/player.js');
    showPlayer({title:'Another movie',sources:[]});
  });
  assert.equal(await selector.isDisabled(),true);
  assert.deepEqual(errors,[]);
  console.log('MOBILE_PLAYER_UI_PASS: empty iOS selector blocked, missing Android runner explained, real source unlocks picker');
}finally {
  await browser?.close();
  await new Promise(resolve=>server.close(resolve));
}
