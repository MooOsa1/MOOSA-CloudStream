/**
 * Real browser decoding of opaque same-origin media capability via
 * MOOSA's ordinary source selector. Local synthetic video ONLY.
 * No claims about source accessibility in CineStream.
 */
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {readFile} from 'node:fs/promises';
import {createServer} from '../server.mjs';
const app=createServer();
const local=await new Promise((resolve,reject)=>{
  app.once('error',reject);
  app.listen(0,'127.0.0.1',()=>resolve('http://127.0.0.1:'+app.address().port));
});
const site='https://moosa-web.onrender.com';
const capability='/api/media/'+('A'.repeat(48));
const bytes=await readFile(new URL('./playback-fixtures/clip.mp4',import.meta.url));
const hlsCapability='/api/media/'+('B'.repeat(48));
const rawPlaylist=await readFile(new URL('./playback-fixtures/playlist.m3u8',import.meta.url),'utf8');
const segments=[...new Set([...rawPlaylist.matchAll(/clip-\d+\.ts/g)].map(x=>x[0]))];
assert.ok(segments.length>0,'Actual HLS fixture must include MPEG-TS segments');
const segmentTokens=new Map(segments.map((name,i)=>[
  '/api/media/'+String.fromCharCode(67+i).repeat(48),name
]));
const tokenBySegment=new Map([...segmentTokens].map(([token,name])=>[name,token]));
const rewrittenPlaylist=rawPlaylist.replace(/clip-\d+\.ts/g,
  name=>tokenBySegment.get(name));
assert.ok(rewrittenPlaylist.startsWith('#EXTM3U'));

let browser;let mediaRequests=0;const errors=[];
async function fulfillFixture(route){
  mediaRequests++;
  const header=route.request().headers()['range']||'';
  const baseHeaders={'Content-Type':'video/mp4','Accept-Ranges':'bytes','Cache-Control':'no-store'};
  const match=header.match(/^bytes=(\d+)-(\d*)$/);
  if(match){
    const start=Number(match[1]);
    const end=match[2]?Math.min(Number(match[2]),bytes.length-1):bytes.length-1;
    if(start>=bytes.length||end<start)
      return route.fulfill({status:416,headers:{
        ...baseHeaders,'Content-Range':'bytes */'+bytes.length},body:''});
    return route.fulfill({status:206,headers:{
      ...baseHeaders,'Content-Range':'bytes '+start+'-'+end+'/'+bytes.length},
      body:bytes.subarray(start,end+1)});
  }
  return route.fulfill({status:200,headers:baseHeaders,body:bytes});
}
try{
  browser=await chromium.launch({headless:true,channel:'chrome',
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1280,height:720}});
  page.on('pageerror',e=>errors.push(e.message));
  await page.route(site+'/**',async route=>{
    const uri=new URL(route.request().url());
    if(uri.pathname===capability)return fulfillFixture(route);
    if(uri.pathname===hlsCapability)return route.fulfill({status:200,
      contentType:'application/vnd.apple.mpegurl',body:rewrittenPlaylist});
    const segName=segmentTokens.get(uri.pathname);
    if(segName)return route.fulfill({status:200,
      contentType:'video/mp2t',body:await readFile(
        new URL('./playback-fixtures/'+segName,import.meta.url))});

    const response=await fetch(local+uri.pathname+uri.search,
      {redirect:'manual',signal:AbortSignal.timeout(12_000)});
    return route.fulfill({status:response.status,headers:{
      'content-type':response.headers.get('content-type')||'application/octet-stream',
      'content-security-policy':response.headers.get('content-security-policy')||''
    },body:Buffer.from(await response.arrayBuffer())});
  });
  await page.route('https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.min.js',
    async route=>route.fulfill({status:200,contentType:'text/javascript',
      body:await readFile(new URL('../node_modules/hls.js/dist/hls.min.js',import.meta.url))}));
  await page.goto(site+'/',{waitUntil:'domcontentloaded',timeout:25_000});
  const accepted=await page.evaluate(async({site,capability})=>{
    const {showPlayer}=await import('/player.js');
    showPlayer({title:'Media capability transport — local test video',sources:[
      {url:site+capability,name:'Same-origin trusted backend relay',quality:0,
        gatewayIssued:true,format:'video/mp4'}
    ]});
    const picker=document.querySelector('#player-source');
    const count=picker.options.length;
    picker.value='0';
    picker.dispatchEvent(new Event('change',{bubbles:true}));
    return {count,choice:picker.value};
  },{site,capability});
  assert.deepEqual(accepted,{count:2,choice:'0'},
    'A genuine same-origin token must appear in the standard MOOSA source picker');
  await page.waitForFunction(()=>{
    const v=document.querySelector('#video');
    return v&&!v.error&&!v.paused&&v.currentTime>0.35&&
      v.videoWidth===320&&v.videoHeight===180;
  },null,{timeout:22000});
  const result=await page.locator('#video').evaluate(video=>({
    seconds:Math.round(video.currentTime*10)/10,
    width:video.videoWidth,height:video.videoHeight,
    duration:video.duration,readyState:video.readyState,
    mediaError:video.error?.code||null
  }));
  assert.ok(mediaRequests>0,'The media route was never requested');
  assert.equal(result.mediaError,null);
  assert.deepEqual(errors,[],'Real MOOSA browser script errors');
  console.log('MOOSA_CAPABILITY_REAL_BROWSER_DECODE '+JSON.stringify({
    sourcePickerSelected:accepted.choice,realDecodedFrames:true,
    decodedDimensions:result.width+'x'+result.height,
    secondsPlayed:result.seconds,mediaRequests,
    usedOpaqueExtensionlessHttpsCapability:true,
    cineStreamUpstreamProven:false,localFixtureOnly:true
  }));
  await page.evaluate(async({site,hlsCapability})=>{
    const {showPlayer}=await import('/player.js');
    showPlayer({title:'Tokenized HLS playlist through MOOSA',sources:[
      {url:site+hlsCapability,format:'m3u8',
        name:'MOOSA trusted HLS relay test',quality:0,gatewayIssued:true}
    ]});
    const picker=document.querySelector('#player-source');
    picker.value='0';
    picker.dispatchEvent(new Event('change',{bubbles:true}));
  },{site,hlsCapability});
  await page.waitForFunction(()=>{
    const v=document.querySelector('#video');
    return v&&!v.error&&!v.paused&&v.currentTime>0.35&&
      v.videoWidth===320&&v.videoHeight===180;
  },null,{timeout:30000});
  assert.equal(await page.locator('#player-source').inputValue(),'0');
  assert.ok(mediaRequests>0);
  assert.deepEqual(errors,[]);
  console.log('MOOSA_CAPABILITY_REAL_HLS_BROWSER_DECODE '+JSON.stringify({
    actualDecodedVideo:true,format:'hls',sameOrigin:true,
    rewrittenHlsSegments:segments.length,
    fallbackTestOnly:true,sourceOriginalCineStream:false
  }));
  await page.evaluate(async({capability})=>{
    const {showPlayer}=await import('/player.js');
    showPlayer({title:'Reject spoofed source',sources:[
      {url:'https://rogue.example'+capability,
        gatewayIssued:true,format:'video/mp4',quality:1080}
    ]});
  },{capability});
  assert.equal(await page.locator('#player-source option').count(),1);
  console.log('MOOSA_CAPABILITY_SPOOFED_ORIGIN_REJECTED=true');
}finally{
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>app.close(resolve));
}
