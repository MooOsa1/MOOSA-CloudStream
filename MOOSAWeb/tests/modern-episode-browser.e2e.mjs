/**
 * Experimental real-browser check on an ACTUAL 2024 television episode
 * resolved by the public MaxStream metadata worker. Tests the existing MOOSA
 * HTML5 player and original remote MP4, with no CDN proxy, forged headers,
 * persistent media file, screenshots, or production source registration.
 *
 * This is a network/decoder diagnostic, NOT a distribution-rights audit.
 * A working stream must NOT be published without permission.
 */
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const ENDPOINT='https://maxstream-api.maxstream123.workers.dev/v1/stream?tmdb=106379&type=tv&season=1&episode=1&server=vidlink';
const EXPECTED_HOST='bcdn.hakunaymatata.com';
const ORIGIN='https://moosa-web.onrender.com';
const failLabel=(e)=>String(e?.name||'network_failure').slice(0,80);

async function approvedSource() {
  const reply=await fetch(ENDPOINT,{
    headers:{Accept:'application/json'},redirect:'error',
    signal:AbortSignal.timeout(14000)
  });
  if(!reply.ok || Number(reply.headers.get('content-length')||0)>180000)
    throw Error('source_api_unavailable_'+reply.status);
  const json=await reply.json();
  if(json?.ok!==true || typeof json.url!=='string')
    throw Error('no_actual_episode_link');
  if(json.headers && Object.keys(json.headers).length)
    throw Error('custom_request_headers_required');
  const u=new URL(json.url);
  if(u.protocol!=='https:' || u.hostname!==EXPECTED_HOST ||
    u.port || u.username || u.password || u.hash ||
    !/\.mp4$/i.test(u.pathname))throw Error('not_direct_expected_media_host');
  return u.href;
}

const server=createServer();
await new Promise((resolve,reject)=>{
  server.once('error',reject);
  server.listen(0,'127.0.0.1',resolve);
});
const local='http://127.0.0.1:'+server.address().port;
const observed={sourceApi:'not_checked',videoRequestStatus:[],requestFailures:[],
  decoder:{},actualPlayback:false,verified720p:false,rightsVerified:false};
let browser;
try{
  const url=await approvedSource();
  observed.sourceApi='returned_direct_mp4_without_custom_headers';
  browser=await chromium.launch({headless:true,channel:'chrome',
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1280,height:720},
    serviceWorkers:'block'});
  page.on('response',r=>{
    try{
      if(new URL(r.url()).hostname!==EXPECTED_HOST)return;
      observed.videoRequestStatus.push({
        status:r.status(),
        mime:String(r.headers()['content-type']||'').split(';')[0].slice(0,48),
        resourceType:r.request().resourceType()
      });
    }catch{}
  });
  page.on('requestfailed',r=>{
    try{
      if(new URL(r.url()).hostname!==EXPECTED_HOST)return;
      observed.requestFailures.push({
        host:EXPECTED_HOST,
        reason:String(r.failure()?.errorText||'request_failed').slice(0,80)
      });
    }catch{}
  });
  // Keep the real production origin and real site's player JS, but serve
  // checked-out MOOSA files. The third-party MP4 is NOT intercepted/proxied.
  await page.route(ORIGIN+'/**',async route=>{
    const u=new URL(route.request().url());
    const upstream=await fetch(local+u.pathname+u.search,{redirect:'manual'});
    await route.fulfill({status:upstream.status,
      headers:{'Content-Type':upstream.headers.get('content-type')||'text/plain'},
      body:Buffer.from(await upstream.arrayBuffer())});
  });
  await page.goto(ORIGIN+'/',{waitUntil:'domcontentloaded',timeout:18000});
  await page.evaluate(async realMediaUrl=>{
    const {showPlayer}=await import('/player.js');
    showPlayer({title:'2024 TV episode original CDN diagnostic',sources:[]});
    document.querySelector('#custom-stream').value=realMediaUrl;
    document.querySelector('#play-custom').click();
  },url);
  try{
    await page.waitForFunction(()=>{
      const v=document.querySelector('#video');
      return Boolean(v && !v.paused && v.readyState>=2 &&
        v.currentTime>=0.35 && v.videoWidth>0 && v.videoHeight>0);
    },null,{timeout:26000});
  }catch{observed.timedOut=true}
  observed.decoder=await page.locator('#video').evaluate(v=>({
    currentTimeSeconds:Math.round(v.currentTime*10)/10,
    width:v.videoWidth,height:v.videoHeight,
    readyState:v.readyState,networkState:v.networkState,paused:v.paused,
    errorCode:v.error?.code||null,
    nativeMp4Support:v.canPlayType('video/mp4')
  }));
  observed.actualPlayback=Boolean(!observed.timedOut &&
    observed.decoder.currentTimeSeconds>=0.3 &&
    observed.decoder.width>0 && observed.decoder.height>0);
  observed.verified720p=observed.actualPlayback &&
    observed.decoder.width>=1280 && observed.decoder.height>=720;
  await page.locator('#video').evaluate(v=>v.pause());
}catch(e){
  observed.error=String(e.message||failLabel(e)).slice(0,105);
}finally{
  console.log('MOOSA_MODERN_TV_REAL_CHROME '+JSON.stringify(observed));
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>server.close(resolve));
}
assert.equal(observed.verified720p,true,
  'Modern TV candidate has not yet demonstrated 720p+ real MOOSA Chrome playback');
