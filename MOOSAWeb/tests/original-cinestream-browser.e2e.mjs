/**
 * Attempt real Chrome playback of the actual original CineStream URL found
 * in the same GitHub Actions run. The raw URL stays on an ephemeral runner.
 *
 * Serve checked-out MOOSA Web code under its real HTTPS origin using route
 * interception, so CORS conditions match moosa-web.onrender.com. Do not proxy
 * external video; hls.js contacts original media CDN directly from Chrome.
 */
import assert from 'node:assert/strict';
import {readFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const tokenFile='/tmp/moosa-jvm/verified-cinestream-hls.b64';
const raw=(await readFile(tokenFile,'utf8')).trim();
const streamUrl=Buffer.from(raw,'base64').toString('utf8');
if(!/^https:\/\/[a-z0-9.-]+\/[^\s]*\.m3u8(?:[?#]|$)/i.test(streamUrl))
  throw Error('A valid, private first-segment-verified HLS URL was not provided');
const errors=[],failed=[];
const server=createServer();
await new Promise((resolve,reject)=>{
  server.once('error',reject);
  server.listen(0,'127.0.0.1',resolve);
});
const local='http://127.0.0.1:'+server.address().port;
let browser,page;
const out=path.resolve('browser-artifacts');
await mkdir(out,{recursive:true});
try{
  browser=await chromium.launch({
    headless:true,channel:'chrome',
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']
  });
  page=await browser.newPage({
    viewport:{width:1280,height:720},
    serviceWorkers:'block'
  });
  page.on('pageerror',e=>errors.push(e.name||'page_error'));
  page.on('requestfailed',req=>{
    try{failed.push({
      host:new URL(req.url()).hostname,
      reason:String(req.failure()?.errorText||'failed').slice(0,80)
    });}catch{}
  });
  await page.route('https://moosa-web.onrender.com/**',async route=>{
    const u=new URL(route.request().url());
    const upstream=await fetch(local+u.pathname+u.search,{redirect:'manual'});
    const ct=upstream.headers.get('content-type')||'application/octet-stream';
    await route.fulfill({
      status:upstream.status,
      headers:{'Content-Type':ct,'Access-Control-Allow-Origin':'*'},
      body:Buffer.from(await upstream.arrayBuffer())
    });
  });
  await page.route('https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.min.js',
    async route=>route.fulfill({
      status:200,contentType:'text/javascript',
      body:await readFile(path.resolve('node_modules/hls.js/dist/hls.min.js'))
    }));
  await page.goto('https://moosa-web.onrender.com/',{
    waitUntil:'domcontentloaded',timeout:25000
  });
  await page.evaluate(async(url)=>{
    const {showPlayer}=await import('/player.js');
    showPlayer({title:'Sintel — licensed original CineStream stream',sources:[]});
    document.querySelector('#custom-stream').value=url;
    document.querySelector('#play-custom').click();
  },streamUrl);
  let timedOut=false;
  try{
    await page.waitForFunction(()=>{
      const v=document.querySelector('#video');
      return !!v && !v.paused && v.readyState>=2 &&
        v.currentTime>0.3 && v.videoWidth>0;
    },null,{timeout:35000});
  }catch{timedOut=true;}
  const state=await page.locator('#video').evaluate(v=>({
    secondsPlayed:Math.round(v.currentTime*10)/10,
    width:v.videoWidth,height:v.videoHeight,
    readyState:v.readyState,
    paused:v.paused,
    errorCode:v.error?.code||null
  }));
  const played=!timedOut&&state.secondsPlayed>0&&state.width>0;
  console.log('ORIGINAL_CINESTREAM_CHROME_PLAYBACK '+JSON.stringify({
    actuallyDecodedFrames:played,video:state,
    pageErrorTypes:errors.slice(0,5),
    networkFailures:failed.slice(0,7)
  }));
  if(played){
    // Screenshot only the video frame — do not capture hidden signed URL input.
    await page.locator('#video').screenshot({
      path:path.join(out,'original-cinestream-sintel-frame.png')
    });
  }
  assert.equal(played,true,
    'Original CineStream first segment did not produce frames in real Chrome');
}finally{
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>server.close(resolve));
}
