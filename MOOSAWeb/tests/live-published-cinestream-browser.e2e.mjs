/**
 * The ONLY success gate for the publicly deployed MOOSA CineStream pilot.
 * No mock server, local fixture, proxy override or paperframe sidecar.
 * Uses real Render HTTPS, /api/resolve, actual operator-minted /api/media
 * capability, and real Google Chrome video frames through standard player.
 */
import assert from 'node:assert/strict';
import {chromium} from 'playwright';

const base='https://moosa-web.onrender.com';
const film='/api/resolve?media=movie&id=10331';
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let source=null;
for(let attempt=1;attempt<=5;attempt++){
  try{
    const response=await fetch(base+film,{
      headers:{Accept:'application/json'},
      redirect:'error',signal:AbortSignal.timeout(82000)
    });
    if(response.status!==200){
      console.log('MOOSA_PUBLISHED_ORIGINAL_LOOKUP '+JSON.stringify({
        attempt,status:response.status,result:'HTTP_error'}));
      continue;
    }
    const result=await response.json();
    source=(Array.isArray(result.sources)?result.sources:[]).find(s=>
      s?.gatewayIssued===true&&s?.provider==='CineStream'&&
      s?.originalMovieImdbId==='tt0063350'&&
      s?.originalMovieTmdbId===10331&&
      s?.format==='video/mp4'&&
      typeof s?.url==='string'&&
      /^https:\/\/moosa-web\.onrender\.com\/api\/media\/[a-zA-Z0-9_-]{32,128}$/.test(s.url));
    console.log('MOOSA_PUBLISHED_ORIGINAL_LOOKUP '+JSON.stringify({
      attempt,status:response.status,allSources:result.sources?.length||0,
      genuineCineStreamSameOriginCapability:!!source,
      defaultSiteIsServingExactFilmPilot:!!source
    }));
    if(source)break;
  }catch(e){
    console.log('MOOSA_PUBLISHED_ORIGINAL_LOOKUP '+JSON.stringify({
      attempt,errorName:String(e?.name||'fetch_error').slice(0,45)
    }));
  }
  await sleep(6000);
}
assert.ok(source,'PUBLISHED MOOSA /api/resolve does not return genuine original CineStream Vidlink source');

// Follow the real opaque media capability with a bounded Range to prove
// the actual Render server returns real MP4, not an error or redirect.
const response=await fetch(source.url,{
  headers:{Range:'bytes=0-16383'},
  redirect:'error',signal:AbortSignal.timeout(24000)});
const mime=String(response.headers.get('content-type')||'').split(';')[0];
const reader=response.body?.getReader();
const first=reader?await reader.read():{};
const bytes=Buffer.from(first.value||[]);
await reader?.cancel().catch(()=>{});
const signature=bytes.length>=12&&
  ['ftyp','styp','moof'].includes(bytes.subarray(4,8).toString('latin1'));
console.log('MOOSA_PUBLISHED_MEDIA_FROM_RENDER '+JSON.stringify({
  httpStatus:response.status,mime,realMp4Signature:signature,
  rangePresent:!!response.headers.get('content-range'),
  mediaURLNeverLogged:true
}));
assert.equal(response.status,206,'Published gateway refuses byte range');
assert.equal(mime,'video/mp4','Published gateway did not return video');
assert.ok(signature,'Published gateway gave HTML/garbage instead of video');

let browser;
try{
  browser=await chromium.launch({channel:'chrome',headless:true,args:[
    '--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1366,height:768}});
  const errors=[];
  page.on('pageerror',e=>errors.push(String(e.name||'ScriptError').slice(0,35)));
  const requests=[];
  page.on('response',r=>{
    try{const u=new URL(r.url());
      if(u.pathname.startsWith('/api/media/'))
        requests.push({status:r.status(),type:(r.headers()['content-type']||'').split(';')[0]});
    }catch{}
  });
  await page.goto(base+'/',{waitUntil:'domcontentloaded',timeout:70000});
  const picker=await page.evaluate(async()=>{
    const {showPlayer}=await import('/player.js');
    const {searchAutomaticSources}=await import('/source-search.js');
    showPlayer({title:'Night of the Living Dead',sources:[]});
    await searchAutomaticSources('movie','10331');
    const select=document.querySelector('#player-source');
    const options=[...select.options].map((item,i)=>({
      index:i,label:String(item.textContent||'').slice(0,85)
    }));
    const movie=options.find(x=>/CineStream.*Vidlink/i.test(x.label));
    if(!movie)return {found:false,options:options.length};
    select.value=String(movie.index);
    select.dispatchEvent(new Event('change',{bubbles:true}));
    return {found:true,sourceIndex:movie.index,options:options.length};
  });
  console.log('MOOSA_PUBLISHED_PLAYER_SOURCE_PICKER '+JSON.stringify(picker));
  assert.equal(picker.found,true,'CineStream missing from the standard live site source picker');
  await page.waitForFunction(()=>{
    const v=document.querySelector('#video');
    return !!v && !v.error && !v.paused && v.currentTime>0.6 &&
      v.videoWidth>0 && v.videoHeight>0 && Number.isFinite(v.duration);
  },null,{timeout:70000});
  const result=await page.locator('#video').evaluate(v=>({
    videoWidth:v.videoWidth,videoHeight:v.videoHeight,
    durationSeconds:Math.round(v.duration),
    playedSeconds:Math.round(v.currentTime*10)/10,
    mediaError:v.error?.code||null,readyState:v.readyState
  }));
  const filmMatches=result.durationSeconds>=5400&&
    result.durationSeconds<=6150;
  const decoded=result.videoWidth>0&&result.videoHeight>0&&
    result.playedSeconds>0.6;
  console.log('MOOSA_PUBLISHED_CINESTREAM_MOVIE_PLAYED '+JSON.stringify({
    realRenderWebsite:true,correctFeatureDuration:filmMatches,
    actualDecodedFrames:decoded,video:result,
    mediaRequests:requests.slice(0,8),scriptErrorCount:errors.length,
    sourceIndex:picker.sourceIndex,
    originalCineStreamSource:true
  }));
  assert.ok(decoded,'No actual video frames on public MOOSA Web');
  assert.ok(filmMatches,'Wrong movie/source runtime on public MOOSA Web');
  assert.equal(result.mediaError,null);
  console.log('MOOSA_PUBLIC_SITE_REAL_CINESTREAM_SUCCESS=true');
}finally{
  await browser?.close().catch(()=>{});
}
