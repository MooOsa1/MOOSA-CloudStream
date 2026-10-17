/**
 * MOOSA Web browser playback smoke: synthetic H.264/AAC MP4 plus HLS/MPEG-TS.
 * All media is locally generated, not a stream from CineStream/StreamPlay.
 */
import assert from 'node:assert/strict';
import {readFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const fixture=path.resolve('tests/playback-fixtures');
const artifacts=path.resolve('browser-artifacts');
await mkdir(artifacts,{recursive:true});
const server=createServer();
const port=await new Promise((resolve,reject)=>{
  server.once('error',reject);
  server.listen(0,'127.0.0.1',()=>resolve(server.address().port));
});
let browser,page;
const failures=[];
const jsErrors=[];

async function serveMedia(route,name,mime){
  const bytes=await readFile(path.join(fixture,name));
  const range=route.request().headers()['range'];
  const headers={
    'Content-Type':mime,'Access-Control-Allow-Origin':'*',
    'Access-Control-Allow-Headers':'Range, Content-Type',
    'Access-Control-Expose-Headers':'Accept-Ranges, Content-Range, Content-Length',
    'Accept-Ranges':'bytes','Cache-Control':'no-store'
  };
  if(range && /^bytes=\d+-\d*$/.test(range)){
    const parts=range.slice(6).split('-');
    const start=Number(parts[0]);
    const end=parts[1]?Math.min(Number(parts[1]),bytes.length-1):bytes.length-1;
    if(start<bytes.length && start<=end){
      return route.fulfill({status:206,
        headers:{...headers,'Content-Range':'bytes '+start+'-'+end+'/'+bytes.length},
        body:bytes.subarray(start,end+1)});
    }
  }
  return route.fulfill({status:200,headers,body:bytes});
}
async function untilPlaying(label,timeout=24000){
  await page.waitForFunction(()=>{
    const v=document.querySelector('#video');
    return !!v && !v.paused && v.readyState>=2 &&
      v.currentTime>0.18 && v.videoWidth>0;
  },null,{timeout});
  const result=await page.locator('#video').evaluate(v=>({
    currentTime:v.currentTime,duration:v.duration,
    width:v.videoWidth,height:v.videoHeight,
    readyState:v.readyState,paused:v.paused}));
  console.log('PASS '+label+' '+JSON.stringify(result));
}

try {
  browser=await chromium.launch({
    headless:true,channel:'chrome',args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']
  });
  page=await browser.newPage({viewport:{width:1365,height:768}});
  // Reproduce the misleading native HLS "maybe" reported by Linux Chrome.
  // MOOSA Web must prefer hls.js on Chrome while preserving Safari native HLS.
  await page.addInitScript(()=>{
    const original=HTMLMediaElement.prototype.canPlayType;
    HTMLMediaElement.prototype.canPlayType=function(type){
      if(String(type||'').toLowerCase().startsWith('application/vnd.apple.mpegurl'))return 'maybe';
      return original.call(this,type);
    };
  });
  page.on('pageerror',e=>jsErrors.push(e.message));
  page.on('requestfailed',req=>{
    if(/moosa-e2e|interactive-examples|jsdelivr/i.test(req.url()))
      failures.push(req.url()+' '+(req.failure()?.errorText||'failed'));
  });
  await page.route('https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4',
    route=>serveMedia(route,'clip.mp4','video/mp4'));
  await page.route('https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.min.js',
    async route=>route.fulfill({status:200,contentType:'text/javascript',
      body:await readFile(path.resolve('node_modules/hls.js/dist/hls.min.js'))}));
  await page.route(/^https:\/\/video\.moosa-e2e\.invalid\/.*/,async route=>{
    const pathname=new URL(route.request().url()).pathname;
    if(pathname==='/master.m3u8'){
      return route.fulfill({status:200,
        headers:{'Content-Type':'application/vnd.apple.mpegurl',
          'Access-Control-Allow-Origin':'*'},
        body:'#EXTM3U\n'+
          '#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=320x180,CODECS="avc1.42E01E,mp4a.40.2"\n'+
          'playlist.m3u8\n'+
          '#EXT-X-STREAM-INF:BANDWIDTH=3500000,RESOLUTION=1280x720,CODECS="avc1.42E01E,mp4a.40.2"\n'+
          'playlist-hd.m3u8\n'});
    }
    if(pathname==='/playlist-hd.m3u8')
      return serveMedia(route,'playlist-hd.m3u8','application/vnd.apple.mpegurl');
    if(/^\/hd-\d+\.ts$/.test(pathname))
      return serveMedia(route,pathname.slice(1),'video/mp2t');
    if(pathname==='/playlist.m3u8'){
      // Valid but delayed playlist must not trigger false playback errors.
      await new Promise(resolve=>setTimeout(resolve,500));
      return serveMedia(route,'playlist.m3u8','application/vnd.apple.mpegurl');
    }
    if(/^\/clip-\d+\.ts$/.test(pathname))
      return serveMedia(route,pathname.slice(1),'video/mp2t');
    return route.fulfill({status:403,
      headers:{'Access-Control-Allow-Origin':'*'},
      contentType:'text/plain',body:'Media unavailable'});
  });
  await page.goto('http://127.0.0.1:'+port+'/',{
    waitUntil:'domcontentloaded',timeout:20000
  });
  await page.locator('#open-demo').click({timeout:15000});
  await untilPlaying('HTML5 MP4 demo');
  await page.screenshot({path:path.join(artifacts,'mp4-playing.png')});

  await page.locator('#custom-stream').fill('https://video.moosa-e2e.invalid/playlist.m3u8');
  await page.locator('#play-custom').click();
  await page.waitForTimeout(200);
  assert.ok(!/تعذر تشغيل HLS/.test(
    await page.locator('#player-message').textContent()),
    'Valid delayed manifest must not show a premature HLS failure');
  await untilPlaying('HLS M3U8 + MPEG-TS');
  assert.equal(await page.evaluate(()=>typeof window.Hls==='function'),true,
    'Chrome must use hls.js even when canPlayType(native HLS) returns maybe');
  assert.match(await page.locator('#player-message').textContent(),/يُشغّل الآن/,
    'Indicator reflects video actually playing');
  await page.screenshot({path:path.join(artifacts,'hls-playing.png')});
  // A genuine HLS master contains SD and HD levels. On a tiny player
  // hls.js must not begin in 180p and cause MOOSA to reject an available
  // 720p stream before adaptive selection stabilizes.
  await page.locator('#custom-stream').fill('https://video.moosa-e2e.invalid/master.m3u8');
  await page.locator('#play-custom').click();
  await untilPlaying('mixed-resolution HLS master starts in HD');
  const measuredHd=await page.locator('#video').evaluate(v=>({
    width:v.videoWidth,height:v.videoHeight
  }));
  assert.ok(measuredHd.width>=1280 && measuredHd.height>=720,
    'HLS must start a real 720p+ rendition instead of its 180p sibling');
  console.log('PASS mixed 180p/720p master selected actual HD video');

  // This SAME real 320x180 H.264 stream is now mislabeled by an automatic
  // source as 1080p. The player must reject it at decoded video metadata,
  // rather than playing a 180p stream while claiming HD.
  await page.evaluate(async()=>{
    const {showPlayer,appendPlayerSources}=await import('/player.js');
    showPlayer({title:'Single exact title / actual quality gate',sources:[]});
    appendPlayerSources([{
      url:'https://video.moosa-e2e.invalid/playlist.m3u8',
      name:'Misleading 1080p source',quality:1080
    }]);
  });
  await page.waitForFunction(()=>/رفضنا سيرفرًا بدقة فعلية/.test(
    document.querySelector('#player-message')?.textContent||''
  ),null,{timeout:26000});
  assert.match(await page.locator('#player-message').textContent(),/320×180/,
    'Actual decoded dimensions are reported instead of the claimed 1080p');
  assert.equal(await page.locator('#video').evaluate(v=>v.paused),true);
  console.log('PASS decoded-180p source automatically rejected despite claimed 1080p');

  await page.locator('#custom-stream').fill('https://video.moosa-e2e.invalid/broken.m3u8');
  await page.locator('#play-custom').click();
  await page.waitForFunction(()=>/تعذر تشغيل HLS/.test(
    document.querySelector('#player-message')?.textContent||''),null,{timeout:17000});
  console.log('PASS broken HLS displays an error');
  assert.deepEqual(jsErrors,[],'JavaScript page errors');
  console.log('E2E_BROWSER_PLAYER_SUCCESS (synthetic video only)');
} catch(error) {
  if(page){
    await page.screenshot({path:path.join(artifacts,'failed.png'),fullPage:true}).catch(()=>{});
    console.error('Browser codec support and video state:',await page.locator('#video').evaluate(v=>({
      src:v.currentSrc,paused:v.paused,time:v.currentTime,duration:v.duration,
      width:v.videoWidth,height:v.videoHeight,readyState:v.readyState,networkState:v.networkState,
      error:v.error?{code:v.error.code,message:v.error.message}:null,
      h264:v.canPlayType('video/mp4; codecs="avc1.42E01E"'),
      aac:v.canPlayType('audio/mp4; codecs="mp4a.40.2"'),
      hls:v.canPlayType('application/vnd.apple.mpegurl')
    })).catch(()=>null));
    console.error('Player message:',await page.locator('#player-message').textContent().catch(()=>null));
    console.error('Network errors:',JSON.stringify(failures.slice(-10)));
    console.error('JS errors:',JSON.stringify(jsErrors.slice(-10)));
  }
  throw error;
} finally {
  if(browser)await browser.close().catch(()=>{});
  await new Promise(resolve=>server.close(resolve));
}
