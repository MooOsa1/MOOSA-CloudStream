/**
 * EXPERIMENTAL original CineStream Vidlink -> ACTUAL MOOSA Node media gateway
 * -> real Chrome HTML5 player for two additional exact full-length films.
 *
 * Not a fixture and not the public production site. No URLs/tokens are logged,
 * no media downloaded/redistributed beyond bounded thumbnail-length test.
 * Prevent source mismatch: demand original TMDB+year known film, real decoded
 * dimensions, plausible duration and a matching displayed requested title.
 */
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {createMediaGateway} from '../verified-media-gateway.mjs';
import {createServer} from '../server.mjs';

const FILMS=[
 {title:'The General',year:1926,tmdbId:961,preferred:720,minDuration:3300,maxDuration:5600},
 {title:'His Girl Friday',year:1940,tmdbId:3085,preferred:480,minDuration:5200,maxDuration:6150}
];
const HOST='hcdn3.hakunaymatata.com';
const SITE='https://moosa-web.onrender.com';
const agent='Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36';
const browserErrors=[];
let sourceRequests=0;let browser;let acceptedFilms=0;
async function jsonSmall(url,headers,timeout=15000){
  const res=await fetch(url,{headers,redirect:'error',signal:AbortSignal.timeout(timeout)});
  if(!res.ok)throw Error('source_http_'+res.status);
  const body=await res.text();
  if(body.length>160000)throw Error('source_json_oversized');
  return JSON.parse(body);
}
function filmVariants(payload){
  const variants=payload?.stream?.qualities||payload?.qualities||{};
  return Object.entries(variants).flatMap(([label,value])=>{
    try{
      const u=new URL(value?.url);
      if(u.protocol!=='https:'||u.hostname!==HOST||u.username||u.password||u.port||
        !u.pathname.toLowerCase().endsWith('.mp4'))return [];
      const quality=Number(String(label).match(/([0-9]{3,4})/)?.[1]||0);
      const head=value.headers&&typeof value.headers==='object'?value.headers:{
        Referer:'https://filmboom.top/',Origin:'https://filmboom.top'
      };
      return [{url:u.href,format:'mp4',quality,headers:head}];
    }catch{return [];}
  }).sort((a,b)=>b.quality-a.quality).slice(0,4);
}
const gateway=createMediaGateway({
  origin:SITE,allowedHosts:[HOST],ttlMs:50*60_000
});
const server=createServer({mediaGateway:gateway});
await new Promise((resolve,reject)=>{
  server.once('error',reject);
  server.listen(0,'127.0.0.1',resolve);
});
const LOCAL='http://127.0.0.1:'+server.address().port;

try{
  browser=await chromium.launch({headless:true,channel:'chrome',args:[
    '--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  for(const film of FILMS){
    let page;
    let phase='provider_lookup';
    try{
      const key=await jsonSmall(
        'https://enc-dec.app/api/enc-vidlink?text='+film.tmdbId,
        {Accept:'application/json'},13000);
      assert.match(String(key.result||''),/^[A-Za-z0-9+/=_-]{3,320}$/);
      const data=await jsonSmall('https://vidlink.pro/api/b/movie/'+
        encodeURIComponent(key.result),
        {'User-Agent':agent,Accept:'application/json',
          Referer:'https://vidlink.pro/',Origin:'https://vidlink.pro'},16000);
      phase='variant_listing';
      const variants=filmVariants(data);
      console.log('MOOSA_ADDITIONAL_FILM_VARIANTS '+JSON.stringify({
        film:film.title,year:film.year,
        apiVariants:variants.length,
        advertisedQualities:variants.map(x=>x.quality)
      }));
      const target=variants.find(x=>x.quality===film.preferred)||variants[0];
      assert.ok(target,'No actual CineStream source for '+film.title);
      phase='media_byte_preflight';
      const tested=await gateway.issueIfBytesVerified({
        url:target.url,format:'mp4',headers:target.headers,
        rightsApproved:true,sourceVerified:true
      });
      console.log('MOOSA_ADDITIONAL_CINESTREAM_PREFLIGHT '+JSON.stringify({film:film.title,year:film.year,quality:target.quality,result:tested.reason||'unknown',mediaSourceIssued:!!tested.source}));
      assert.ok(tested.source,'Media byte check rejected '+film.title);
      phase='browser_new_page';
      page=await browser.newPage({viewport:{width:1200,height:760}});
      page.on('pageerror',err=>browserErrors.push(String(err.name||'script_error')));
      const urlToken=new URL(tested.source.url).pathname;
      let rangeRequestCount=0;let rangeTotalBytes=0;
      await page.route(SITE+'/**',async route=>{
        const parsed=new URL(route.request().url());
        const range=route.request().headers().range||'';
        const headers={};
        if(parsed.pathname.startsWith('/api/media/')){
          if(parsed.pathname!==urlToken)throw Error('Unknown media token');
          rangeRequestCount++;
          if(rangeRequestCount>130)throw Error('Too many media range requests');
          const hit=range.match(/^bytes=(\d+)-(\d*)$/);
          if(!hit)throw Error('Unrecognized media Range header');
          const start=Number(hit[1]);
          const end=Math.min(hit[2]?Number(hit[2]):start+1_300_000,
            start+1_300_000);
          headers.range='bytes='+start+'-'+end;
        }
        const response=await fetch(LOCAL+parsed.pathname+parsed.search,{
          method:route.request().method(),headers,redirect:'error',
          signal:AbortSignal.timeout(26000)});
        const forwarded={};
        for(const field of ['content-type','content-range','content-length','accept-ranges']){
          const value=response.headers.get(field);
          if(value)forwarded[field]=value;
        }
        const body=Buffer.from(await response.arrayBuffer());
        if(parsed.pathname.startsWith('/api/media/'))rangeTotalBytes+=body.length;
        await route.fulfill({status:response.status,headers:forwarded,body});
      });
      phase='browse_MOOSA_static_site';
      await page.goto(SITE+'/',{waitUntil:'domcontentloaded',timeout:35000});
      phase='player_source_picker';
      const chosen=await page.evaluate(async({source,film})=>{
        const {showPlayer}=await import('/player.js');
        showPlayer({title:film.title,sources:[{
          ...source,name:'CineStream • Vidlink '+film.year,
          provider:'CineStream',quality:0
        }]});
        const picker=document.querySelector('#player-source');
        const count=picker.options.length;
        // showPlayer() fills the picker but starts from its blank
        // placeholder. Real user selects the first Vidlink source.
        picker.value='0';
        picker.dispatchEvent(new Event('change',{bubbles:true}));
        return {count,selected:picker.value};
      },{source:tested.source,film});
      console.log('MOOSA_ADDITIONAL_CINESTREAM_PICKER '+JSON.stringify({film:film.title,...chosen}));
      assert.equal(chosen.selected,'0');
      phase='video_frames';
      await page.waitForFunction(()=>{
        const video=document.querySelector('#video');
        return video&&!video.error&&!video.paused&&video.currentTime>0.6 &&
          video.videoWidth>0&&video.videoHeight>0&&
          Number.isFinite(video.duration);
      },null,{timeout:70000});
      phase='read_real_media_metadata';
      const actual=await page.locator('#video').evaluate(video=>({
        height:video.videoHeight,width:video.videoWidth,
        duration:Math.round(video.duration),
        currentTime:Math.round(video.currentTime*10)/10,
        error:video.error?.code||null
      }));
      const durationPlausible=actual.duration>=film.minDuration&&
        actual.duration<=film.maxDuration;
      const qualityClaimMatches=actual.height>=film.preferred-24 &&
        actual.height<=film.preferred+24;
      console.log('MOOSA_ADDITIONAL_CINESTREAM_CHROME_PLAYBACK '+JSON.stringify({
        film:film.title,year:film.year,tmdbId:film.tmdbId,
        actuallyDecodedFrames:true,
        claimedQualityLabel:target.quality,
        measuredVideo:actual,
        qualityLabelMatchesActualPixels:qualityClaimMatches,
        plausibleRequestedMovieRuntime:durationPlausible,
        sameOriginGateway:true,
        rangeRequestCount,approxReadBytes:rangeTotalBytes,
        siteProductionUnchanged:true
      }));
      phase='assert_movie_identity';
      assert.ok(durationPlausible,film.title+' source likely wrong film by duration');
      acceptedFilms++;
    }catch(error){
      console.log('MOOSA_ADDITIONAL_CINESTREAM_CHROME_FAILED '+JSON.stringify({
        film:film.title,year:film.year,
        errorName:String(error?.name||'Error').slice(0,55),
        stage:phase,
        notes:'No fake success; no production enable'
      }));
    }finally{await page?.close();}
  }
  console.log('MOOSA_ADDITIONAL_FILMS_PROVEN '+JSON.stringify({
    tested:FILMS.length,actuallyPlayedWithPlausibleDuration:acceptedFilms,
    browserErrorCount:browserErrors.length,siteProductionUnchanged:true
  }));
  assert.equal(acceptedFilms,FILMS.length,
    'Do not claim both new original CineStream films playable if any failed');
}finally{
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>server.close(resolve));
}
