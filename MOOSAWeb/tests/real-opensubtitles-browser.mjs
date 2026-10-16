import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const AR='https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952533551';
const EN='https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952537611';
const server=createServer();
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try{
  browser=await chromium.launch({headless:true});
  const page=await browser.newPage({viewport:{width:390,height:844},
    userAgent:'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1'});
  const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('https://video.example/**',r=>r.fulfill({status:404,body:'Controlled playback simulation'}));
  await page.goto('http://127.0.0.1:'+server.address().port+'/',{waitUntil:'domcontentloaded'});
  const cors=await page.evaluate(async links=>{
    const out=[];
    for(const url of links){
      const response=await fetch(url,{signal:AbortSignal.timeout(12000)});
      const txt=await response.text();
      out.push({
        status:response.status,length:txt.length,
        srt:/\d\d:\d\d:\d\d[,\.]\d\d\d\s*-->/.test(txt),
        arabic:/[\u0600-\u06FF]/.test(txt)
      });
    }
    return out;
  },[AR,EN]);
  assert.deepEqual(cors.map(x=>x.status),[200,200]);
  assert.ok(cors.every(x=>x.srt && x.length>800));
  assert.equal(cors[0].arabic,true);
  await page.evaluate(async ({ar,en})=>{
    const {showPlayer,appendExternalSubtitles,appendPlayerSources}
      =await import('/player.js');
    showPlayer({title:'Sintel',sources:[]});
    appendExternalSubtitles([
      {url:ar,language:'ar',source:'OpenSubtitles v3',label:'3868082'},
      {url:en,language:'en',source:'OpenSubtitles v3',label:'3871796'}
    ]);
    appendPlayerSources([{url:'https://video.example/sintel.mp4',
      name:'CORS-safe licensed sample',quality:1080}]);
  },{ar:AR,en:EN});
  await page.waitForFunction(()=>document.querySelector('#player-subtitles')?.options.length===3);
  const labels=await page.locator('#player-subtitles option').allTextContents();
  assert.match(labels[1],/Arabic/);
  assert.match(labels[2],/English/);
  await page.selectOption('#player-subtitles',AR);
  await page.waitForFunction(()=>document.querySelector('#video track')?.src.startsWith('blob:'),
    null,{timeout:10000});
  assert.equal(await page.locator('#player-subtitles').inputValue(),AR);
  assert.deepEqual(errors,[]);
  console.log('REAL_OPENSUBTITLES_BROWSER_PASS '+JSON.stringify({
    actualCORS: cors,visibleOptionCount:labels.length,
    selectedArabic:true,videoTitle:await page.locator('#player-title').textContent()
  }));
  await page.close();
}finally{
  await browser?.close();
  await new Promise(resolve=>server.close(resolve));
}
