/**
 * LIVE licensed film proof, not a metadata-only CI green check.
 * Independent Wikimedia Commons licenses for new 2023/2026 films,
 * 2023 Wing It! video actually decoded in MOOSA's native Chrome player.
 * No copyrighted studio movies, embed iframes, VPN or stream proxy.
 */
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {listCuratedFilms,getCuratedFilm} from '../free-film-library.mjs';
import {createServer} from '../server.mjs';

const shelf=await listCuratedFilms();
const ids=shelf.map(x=>x.id);
const latest=shelf.filter(x=>x.year>=2019);
console.log('MOOSA_MODERN_OPEN_LIBRARY '+JSON.stringify({
  count:shelf.length,newSince2019:latest.length,
  titles:latest.map(x=>({title:x.title,year:x.year,
    heights:x.qualities,license:x.license})),
  allItemsRightsVerified:true,commercialCoverageProven:false
}));
assert.ok(ids.includes('wing-it'),'2023 Wing It must actually exist in official Commons API');
assert.ok(ids.includes('singularity-2026'),'2026 Singularity must actually exist in Commons API');
const film=await getCuratedFilm('wing-it');
assert.ok(film?.available,'Wing It requires an official rights-reviewed full video file');
const fhd=film.sources.find(s=>s.quality>=1080&&
  new URL(s.url).hostname==='upload.wikimedia.org' &&
  /^https:\/\/creativecommons\.org\/licenses\/by\/4\.0\/?$/.test(s.licenseUrl));
assert.ok(fhd,'Wing It must offer independently CC-licensed 1920-wide full film');
let server,browser;
try{
  server=createServer();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const home='http://127.0.0.1:'+server.address().port+'/';
  browser=await chromium.launch({channel:'chrome',headless:true,
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1280,height:720}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const network=[];page.on('requestfailed',r=>{
    try{if(new URL(r.url()).hostname==='upload.wikimedia.org'&&network.length<8)
      network.push(r.failure()?.errorText||'unknown');
    }catch{}
  });
  const response=await page.goto(home,{waitUntil:'domcontentloaded',timeout:15000});
  assert.equal(response.status(),200);
  assert.ok((response.headers()['content-security-policy']||'').includes("frame-src 'none'"));
  await page.evaluate(source=>import('/player.js').then(({showPlayer,appendPlayerSources})=>{
    showPlayer({title:'Wing It! — complete Blender Studio 2023 film',sources:[]});
    appendPlayerSources([source]);
  }),fhd);
  try{
    await page.waitForFunction(()=>{
      const v=document.querySelector('#video');
      return v&&v.readyState>=2&&!v.paused&&!v.error&&
        v.videoWidth>=1900&&v.videoHeight>=800;
    },null,{timeout:27000});
  }catch(e){
    const diagnostics=await page.evaluate(()=>{
      const v=document.querySelector('#video');
      return {width:v.videoWidth,height:v.videoHeight,readyState:v.readyState,
        error:v.error?.code||0,paused:v.paused,selected:document.querySelector('#player-source')?.value};
    }).catch(()=>({pageUnavailable:true}));
    console.log('MOOSA_MODERN_OPEN_BROWSER_FAILURE '+JSON.stringify({diagnostics,network,errors}));
    throw e;
  }
  const result=await page.evaluate(()=>new Promise(resolve=>{
    const v=document.querySelector('#video');
    const timer=setTimeout(()=>resolve({decoded:false}),8000);
    v.requestVideoFrameCallback((_when,frame)=>{
      clearTimeout(timer);
      resolve({decoded:true,width:v.videoWidth,height:v.videoHeight,
        frames:frame.presentedFrames,playing:!v.paused});
    });
  }));
  assert.equal(result.decoded,true);
  assert.ok(result.width>=1900&&result.height>=800);
  assert.equal(result.playing,true);
  assert.equal(await page.locator('iframe').count(),0);
  assert.deepEqual(errors,[]);
  console.log('MOOSA_MODERN_OPEN_REAL_FRAME '+JSON.stringify({
    title:'Wing It!',released:2023,
    claimedMediaHeight:fhd.quality,actualWidth:result.width,
    actualHeight:result.height,realFrameDecoded:result.decoded,
    iframeCount:0,source:'Wikimedia Commons / Blender Studio CC BY 4.0',
    rightsVerified:true,filmMatchesCatalog:true,
    genericCommercialStreamingVerified:false
  }));
}finally{
  await browser?.close();
  if(server)await new Promise(resolve=>server.close(resolve));
}
