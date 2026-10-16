import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';
const server=createServer();
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const root='http://127.0.0.1:'+server.address().port;
const original={
 url:'https://upload.wikimedia.org/wikipedia/commons/a/ab/elephants-original.webm',
 name:'Wikimedia Commons • Original VP9',quality:1080,preferred:true,
 licenseUrl:'https://creativecommons.org/licenses/by/2.5',
 sourcePage:'https://commons.wikimedia.org/wiki/File:Elephants_Dream_(2006).webm'
};
const optimized={
 ...original,url:'https://upload.wikimedia.org/wikipedia/commons/transcoded/a/ab/elephants-optimized.webm',
 name:'Wikimedia Commons • Safari-compatible VP9',codecOptimized:true
};
const iosUA='Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';
const desktopUA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15';
let browser;
try {
  browser=await chromium.launch({headless:true});
  for(const [platform,userAgent,expectedCount] of [
    ['iPhone',iosUA,2],['desktop',desktopUA,3]
  ]){
    const page=await browser.newPage({viewport:{width:390,height:844},userAgent});
    const requested=[],errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.route('https://upload.wikimedia.org/**',route=>{
      requested.push(route.request().url());
      return route.fulfill({status:404,body:'Test-only simulated codec/CDN failure'});
    });
    await page.goto(root,{waitUntil:'domcontentloaded'});
    await page.evaluate(async sources=>{
      const {showPlayer,appendPlayerSources}=await import('/player.js');
      showPlayer({title:'Elephants Dream',sources:[]});
      appendPlayerSources(sources);
    },[original,optimized]);
    const picker=page.locator('#player-source');
    assert.equal(await picker.locator('option').count(),expectedCount,
      platform+' must present only appropriately compatible options');
    assert.ok((await picker.locator('option').allTextContents()).some(x=>x.includes('Safari-compatible')));
    assert.match(await page.locator('#player-title').textContent(),/Elephants Dream/);
    const attrs=page.locator('#source-attribution-link');
    assert.equal(await attrs.getAttribute('href'),original.sourcePage,
      'Creative Commons attribution must survive source presentation');
    if(platform==='iPhone'){
      assert.equal(await picker.inputValue(),'0');
      assert.ok((await page.locator('#video').getAttribute('src')).includes('elephants-optimized.webm'));
      assert.ok(!(await picker.locator('option').allTextContents()).some(x=>x.includes('Original VP9')));
      // A failed transcode on iOS must NOT automatically fall back to known
      // incompatible 4:4:4 master simply because both are 1080p WebM.
      await page.waitForTimeout(350);
      assert.ok(requested.every(u=>!u.includes('elephants-original.webm')),
        'Do not waste bandwidth on the known failing iPhone original');
    }else{
      assert.ok((await picker.locator('option').allTextContents()).some(x=>x.includes('Original VP9')));
    }
    assert.deepEqual(errors,[]);
    await page.close();
    console.log('ELEPHANTS_BROWSER_COMPAT_PASS '+platform+' options='+expectedCount);
  }
}finally{
  await browser?.close();
  await new Promise(resolve=>server.close(resolve));
}
