/**
 * Actual CineStream-derived full feature in real Chrome and MOOSA's normal
 * source selector. No synthetic media, no Archive/Wikimedia stand-in.
 *
 * A fresh Streamline (CineStream Kotlin provider port) result is served
 * through its ephemeral Paperframe media capability. Playwright only maps
 * the SAME-origin HTTPS URL to that real gateway, forwarding genuine MP4
 * Range responses in small chunks. Never share URL, token or file bytes.
 * Success requires real decoded frames AND the approx 96 min film length.
 * Experimental CI only: no deployment or public redistribution.
 */
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const site='https://moosa-web.onrender.com';
const service='http://127.0.0.1:4173';
const repo='https://raw.githubusercontent.com/itsmeadarsh2008/Streamline/27c2cb2c864a056003241c48a26ede5a7cff037a/manifest.json';
const response=await fetch(service+'/api/plugins/resolve',{
  method:'POST',
  headers:{'Content-Type':'application/json'},
  body:JSON.stringify({repositoryUrl:repo,mediaType:'movie',tmdbId:'10331'}),
  signal:AbortSignal.timeout(180000)
});
assert.equal(response.status,200,'Original CineStream movie query did not finish');
const data=await response.json();
const matches=(data.streams||[]).filter(x=>
  x.provider==='Vidlink' && /^\/api\/media\/[A-Za-z0-9_-]{32,128}$/.test(x.url||''));
console.log('MOOSA_CINESTREAM_FEATURE_BROWSER_CANDIDATES '+JSON.stringify({
  feature:'Night of the Living Dead (1968)',movieTmdbId:10331,
  originalProvider:'Vidlink',candidateCount:matches.length,
  model:'CineStream JS port, original provider callback',
  videoDecoded:false
}));
assert.ok(matches.length>0,'Vidlink produced no original CineStream candidate');
const selected=matches[0];
const orig=selected.url;
let preview;
try{
  preview=await fetch(service+orig,{
    headers:{Range:'bytes=0-8191'},redirect:'error',
    signal:AbortSignal.timeout(15000)
  });
  assert.equal(preview.status,206,'Actual original CDN must support video Range');
  assert.match(preview.headers.get('content-type')||'',/video\/mp4/i);
  const reader=preview.body.getReader();
  const bytes=Buffer.from((await reader.read()).value||[]);
  await reader.cancel().catch(()=>{});
  assert.equal(bytes.subarray(4,8).toString('latin1'),'ftyp',
    'Must be genuine MP4, not an HTML player/error');
}catch(error){
  console.log('MOOSA_CINESTREAM_REAL_FEATURE_PREFLIGHT '+JSON.stringify({
    result:'failed',kind:String(error?.name||'invalid_video').slice(0,40)}));
  throw error;
}
console.log('MOOSA_CINESTREAM_REAL_FEATURE_PREFLIGHT '+JSON.stringify({
  result:'real_mp4_byte_signature_confirmed',rangeStatus:206,format:'mp4'
}));

const app=createServer();
await new Promise((resolve,reject)=>{
  app.once('error',reject);app.listen(0,'127.0.0.1',resolve);
});
const local='http://127.0.0.1:'+app.address().port;
let browser;let rangedRequests=0;let rangedBytes=0;const MAX_REQUESTS=50;
const networkErrors=[];
async function mediaRoute(route){
  rangedRequests++;
  if(rangedRequests>MAX_REQUESTS)throw Error('Exceeded bounded video request count');
  const requestRange=String(route.request().headers().range||'bytes=0-');
  const m=requestRange.match(/^bytes=(\d+)-(\d*)$/);
  if(!m)throw Error('Unexpected browser media range');
  const start=Number(m[1]);
  const maxEnd=start+1024*1024-1;
  const requestedEnd=m[2]?Number(m[2]):maxEnd;
  const end=Math.min(requestedEnd,maxEnd);
  const res=await fetch(service+orig,{
    headers:{Range:'bytes='+start+'-'+end},
    redirect:'error',signal:AbortSignal.timeout(22000)
  });
  if(res.status!==206){
    await res.body?.cancel().catch(()=>{});
    return route.fulfill({status:502,contentType:'text/plain',body:'Video unavailable'});
  }
  const contentRange=res.headers.get('content-range')||'';
  if(!/^bytes \d+-\d+\/\d+$/.test(contentRange))
    throw Error('Upstream returned invalid Content-Range');
  const chunks=[];let total=0;
  const reader=res.body.getReader();
  try{
    while(total<1_200_000){
      const chunk=await reader.read();
      if(chunk.done)break;
      const data=Buffer.from(chunk.value);
      if(total+data.length>1_200_000)throw Error('Range body too large');
      total+=data.length;chunks.push(data);
    }
  }finally{await reader.cancel().catch(()=>{});}
  rangedBytes+=total;
  return route.fulfill({status:206,headers:{
    'Content-Type':'video/mp4','Content-Range':contentRange,
    'Content-Length':String(total),'Accept-Ranges':'bytes',
    'Cache-Control':'no-store'
  },body:Buffer.concat(chunks,total)});
}

try{
  browser=await chromium.launch({headless:true,channel:'chrome',
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1366,height:768}});
  page.on('pageerror',e=>networkErrors.push('script:'+String(e.name||'error')));
  page.on('requestfailed',r=>{
    if(r.url().includes('/api/media/'))
      networkErrors.push('media:'+String(r.failure()?.errorText||'failed').slice(0,65));
  });
  await page.route(site+'/**',async route=>{
    const url=new URL(route.request().url());
    if(url.pathname===orig)return mediaRoute(route);
    const res=await fetch(local+url.pathname+url.search,{
      redirect:'manual',signal:AbortSignal.timeout(12000)
    });
    return route.fulfill({
      status:res.status,headers:{
        'content-type':res.headers.get('content-type')||'application/octet-stream',
        'content-security-policy':res.headers.get('content-security-policy')||''
      },body:Buffer.from(await res.arrayBuffer())
    });
  });
  await page.goto(site+'/',{waitUntil:'domcontentloaded',timeout:25000});
  const picker=await page.evaluate(async({site,mediaToken})=>{
    const {showPlayer}=await import('/player.js');
    showPlayer({title:'Night of the Living Dead (1968)',sources:[{
      url:site+mediaToken,format:'video/mp4',name:'CineStream • Vidlink',
      provider:'CineStream',quality:0,gatewayIssued:true
    }]});
    const input=document.querySelector('#player-source');
    const n=input.options.length;
    input.value='0';
    input.dispatchEvent(new Event('change',{bubbles:true}));
    return {optionCount:n,value:input.value};
  },{site,mediaToken:orig});
  assert.deepEqual(picker,{optionCount:2,value:'0'});
  await page.waitForFunction(()=>{
    const video=document.querySelector('#video');
    return video&&!video.error&&!video.paused&&video.currentTime>0.7 &&
      video.videoWidth>0 && video.videoHeight>0 &&
      Number.isFinite(video.duration);
  },null,{timeout:70000});
  const actual=await page.locator('#video').evaluate(v=>({
    width:v.videoWidth,height:v.videoHeight,
    seconds:Math.round(v.currentTime*10)/10,
    duration:Math.round(v.duration),
    readyState:v.readyState,paused:v.paused,
    mediaError:v.error?.code||null
  }));
  const match=actual.duration>=5400&&actual.duration<=6150;
  console.log('MOOSA_CINESTREAM_ACTUAL_FEATURE_CHROME '+JSON.stringify({
    film:'Night of the Living Dead (1968)',
    filmDurationMatches:match,video:actual,
    requests:rangedRequests,approxBytesRanged:rangedBytes,
    actualOriginalProvider:'Vidlink',sourceIndex:picker.value,
    videoFramesDecoded:!actual.paused && actual.width>0,
    networkFailureTypes:networkErrors.slice(0,4),
    deployedToPublicMOOSA:false
  }));
  assert.equal(match,true,
    'Original CineStream stream was playable but NOT the expected full feature runtime');
  assert.equal(actual.mediaError,null);
  assert.ok(actual.width>0&&actual.height>0);
  console.log('MOOSA_CINESTREAM_MOVIE_PLAYED_IN_WEB_EXPERIMENT=true');
}finally{
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>app.close(resolve));
}
