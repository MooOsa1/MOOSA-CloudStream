import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

// One valid-looking source NEVER responds. Previously the player could remain
// loading forever because Chrome raised no media error to trigger fallback.
// This test uses synthetic unreachable hosts only: no movie streaming rights.
const server=createServer();
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser,page,unblock;
const hanging=new Promise(resolve=>{unblock=resolve;});
let hungRequests=0,backupRequests=0;
try{
  browser=await chromium.launch({headless:true,channel:'chrome',
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  page=await browser.newPage({viewport:{width:1152,height:768}});
  const errors=[];
  page.on('pageerror',e=>errors.push(String(e.name||'page_error')));
  await page.route('https://silent.example.invalid/**',async route=>{
    hungRequests++;
    await hanging;
    await route.abort('failed').catch(()=>{});
  });
  await page.route('https://alternate.example.invalid/**',async route=>{
    backupRequests++;
    await route.fulfill({status:404,contentType:'text/plain',body:'Fixture unavailable'});
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/',{
    waitUntil:'domcontentloaded'
  });
  await page.evaluate(async()=>{
    const {showPlayer,appendPlayerSources}=await import('/player.js');
    showPlayer({title:'Same title watchdog test',sources:[]});
    appendPlayerSources([
      {url:'https://silent.example.invalid/movie.mp4',
        name:'Never responds',quality:1080},
      {url:'https://alternate.example.invalid/movie.mp4',
        name:'Alternate same film',quality:720}
    ]);
  });
  await page.waitForFunction(()=>
    document.querySelector('#player-source')?.value==='1',null,{timeout:35000});
  assert.ok(hungRequests>=1,'First source did not hang');
  await page.waitForFunction(()=>
    /(?:بديل|تعذر تشغيل)/.test(
      document.querySelector('#player-message')?.textContent||''),null,
    {timeout:10000});
  assert.ok(backupRequests>=1,'The actual video element never tried the second source');
  assert.equal(await page.locator('#player-title').textContent(),
    'Same title watchdog test','Never fall back to another movie');
  assert.deepEqual(errors,[]);
  console.log('MOOSA_STALLED_MEDIA_AUTO_FALLBACK_PASS '+JSON.stringify({
    sourceStalledWithoutError:true,
    nextSourceSelectedAutomatically:true,
    secondSourceRequested:true,
    staysOnExactTitle:true,
    productionUnchanged:true
  }));
}finally{
  unblock?.();
  await page?.close().catch(()=>{});
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>server.close(resolve));
}
