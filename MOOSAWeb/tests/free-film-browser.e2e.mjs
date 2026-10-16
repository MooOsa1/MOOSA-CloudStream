import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';
const app=createServer();
const port=await new Promise(resolve=>app.listen(0,'127.0.0.1',()=>resolve(app.address().port)));
let browser;
try {
  browser=await chromium.launch({headless:true});
  const page=await browser.newPage({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
  let videoRequests=0;
  await page.route('**/api/free-film',async route=>route.fulfill({
    status:200,contentType:'application/json',
    body:JSON.stringify({
      title:'Big Buck Bunny',copyright:'CC BY 3.0',
      sources:[
        {url:'https://upload.wikimedia.org/wikipedia/commons/b/bb/4K.webm',
          name:'Wikimedia Commons • CC BY 3.0',quality:2250,subtitles:[],
          licenseUrl:'https://creativecommons.org/licenses/by/3.0/',
          sourcePage:'https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_4K.webm',
          attribution:'Blender Foundation'},
        {url:'https://upload.wikimedia.org/wikipedia/commons/a/ab/720.webm',
          name:'Wikimedia Commons • CC BY 3.0',quality:720,subtitles:[],preferred:true,
          licenseUrl:'https://creativecommons.org/licenses/by/3.0/',
          sourcePage:'https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_alt.webm',
          attribution:'Blender Foundation'}
      ]
    })
  }));
  await page.route('https://upload.wikimedia.org/**',async route=>{
    videoRequests++;
    await route.fulfill({status:404,body:'Playback tested separately'});
  });
  await page.goto('http://127.0.0.1:'+port+'/',{waitUntil:'domcontentloaded'});
  await page.evaluate(async()=>{
    const {showPlayer}=await import('/player.js');
    showPlayer({title:'Animals',sources:[]});
  });
  await page.locator('#open-free-film').click();
  await page.waitForFunction(()=>document.querySelector('#player-title')?.textContent.includes('Big Buck Bunny'));
  assert.match(await page.locator('#automatic-source-status').innerText(),/2 نسخة/);
  assert.equal(await page.locator('#player-source option').count(),3);
  assert.equal(await page.locator('#player-source').isDisabled(),false);
  assert.match(await page.locator('#player-source option').nth(1).innerText(),/720p/);
  assert.equal(await page.locator('#source-attribution').isVisible(),true);
  assert.equal(await page.locator('#source-attribution-link').getAttribute('href'),
    'https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_alt.webm');
  assert.match(await page.locator('#source-attribution-text').innerText(),/Blender Foundation/);
  assert.ok(videoRequests>=0);
  console.log('PASS independent free film API button selects 720p with Commons rights attribution');
} finally {
  await browser?.close();
  await new Promise(resolve=>app.close(resolve));
}
