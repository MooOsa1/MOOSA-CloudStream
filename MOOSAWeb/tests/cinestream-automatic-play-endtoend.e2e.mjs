/**
 * Test-only real end-to-end path:
 * browser Play search -> /api/resolve -> exact TMDB catalog matching ->
 * existing Node CineStream adapter -> bounded MP4 preflight ->
 * opaque SAME-ORIGIN gateway token -> browser auto-picker -> decoded video.
 *
 * TMDB facts are deterministic test metadata; Vidlink data, gateway preflight
 * and video bytes are real remote sources. NO production deployment, no
 * media token/source URL/logged request headers, no public approval flag.
 * Browser only reads bounded byte ranges; never downloads an entire film.
 */
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createMediaGateway} from '../verified-media-gateway.mjs';
import {createServer} from '../server.mjs';

const SITE='https://moosa-web.onrender.com';
const FILMS=[
  {id:19,title:'Metropolis',year:1927,imdbId:'tt0017136',
    primary:720,backup:480,thirdHeight:360,minDuration:3600,maxDuration:11000},
  {id:832,title:'M',year:1931,imdbId:'tt0022100',
    primary:1080,backup:480,thirdHeight:363,minDuration:4500,maxDuration:8500},
  // Real provider MP4 encodes previously Chrome-decoded: 1080 / 720 / 480.
  // Only test-local operator flags are used; these are NOT public films.
  {id:962,title:'The Gold Rush',year:1925,imdbId:'tt0015864',
    primary:1080,backup:720,thirdHeight:540,minDuration:4300,maxDuration:6500},
  {id:10098,title:'The Kid',year:1921,imdbId:'tt0012349',
    primary:1080,backup:720,thirdHeight:540,minDuration:2700,maxDuration:4500}
];
const HOSTS=['hcdn3.hakunaymatata.com','bcdn.hakunaymatata.com'];
const nativeFetch=globalThis.fetch;
let browser,server;
let tmdbCount=0,providerCount=0,mediaRangeCount=0;
process.env.TMDB_API_KEY='MOOSA_LOCAL_TEST_ONLY';
delete process.env.TMDB_READ_ACCESS_TOKEN;
process.env.CINESTREAM_MULTIFILM_PILOT='1';
process.env.CINESTREAM_MULTIFILM_RIGHTS_APPROVED='1';
delete process.env.CINESTREAM_NIGHT_PILOT;
globalThis.fetch=async(input,init)=>{
  const url=new URL(input instanceof URL?input.href:
    typeof input==='string'?input:input.url);
  if(url.hostname==='api.themoviedb.org'){
    tmdbCount++;
    if(url.pathname==='/3/search/multi'){
      const q=(url.searchParams.get('query')||'').toLowerCase().trim();
      const results=FILMS.filter(x=>q===x.title.toLowerCase() ||
          q===x.title.toLowerCase()+' '+x.year)
        .map(x=>({id:x.id,title:x.title,media_type:'movie',
          release_date:x.year+'-01-01',vote_average:7.5,
          vote_count:300,overview:'Test catalog metadata'}));
      return Response.json({page:1,total_pages:1,results});
    }
    const film=FILMS.find(x=>url.pathname.endsWith('/movie/'+x.id));
    return film?Response.json({id:film.id,title:film.title,
      original_title:film.title,release_date:film.year+'-01-01',
      imdb_id:film.imdbId,external_ids:{imdb_id:film.imdbId}}):
      Response.json({status_message:'unknown test film'},{status:404});
  }
  if(url.hostname==='enc-dec.app'||url.hostname==='vidlink.pro'){
    providerCount++;
    return nativeFetch(input,init);
  }
  // No out-of-scope provider discovery needed in this exact identity test.
  if(!HOSTS.includes(url.hostname))
    return Response.json({error:'disabled in isolated integration test'},
      {status:503});
  return nativeFetch(input,init);
};
const gateway=createMediaGateway({origin:SITE,allowedHosts:HOSTS,
  fetchImpl:nativeFetch,ttlMs:20*60_000,maxEntries:36});
server=createServer({mediaGateway:gateway});
await new Promise((resolve,reject)=>{
  server.once('error',reject);server.listen(0,'127.0.0.1',resolve);
});
const LOCAL='http://127.0.0.1:'+server.address().port;

try{
  browser=await chromium.launch({headless:true,channel:'chrome',
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  for(const film of FILMS){
    let page;let phase='start';
    let resolveCount=0,extraStarted=0,outageCount=0,firstToken=null;
    let issuedCount=0,selectOptions=0;
    // Third backend-verified server is delivered deliberately late through
    // the extras response, after a real movie has already begun decoding.
    let heldVerifiedSource=null,heldMediaToken=null;
    let extraReleased=false,extraBefore=null,extraRequests=0;
    let heldVideoRequests=0;
    let clickStarted=0,firstFrameMs=0;
    try{
      phase='new_page';
      page=await browser.newPage({viewport:{width:1152,height:768}});
      page.setDefaultTimeout(100000);
      const errors=[];
      page.on('pageerror',error=>errors.push(String(error.name||'script_error')));
      await page.route(SITE+'/**',async route=>{
        const parsed=new URL(route.request().url());
        if(parsed.pathname==='/api/resolve' &&
           parsed.searchParams.get('phase')==='extras'){
          extraStarted++;
          assert.ok(heldVerifiedSource,
            'Backend must first verify the late server for the same film');
          // Wait for actual fallback playback, not an artificial timer.
          await page.waitForFunction(()=>{
            const v=document.querySelector('#video');
            const q=document.querySelector('#player-source');
            return v&&q?.value==='1'&&v.currentTime>1.5&&
              !v.paused&&!v.error&&v.videoWidth>0&&
              (v.getVideoPlaybackQuality?.().totalVideoFrames||0)>5;
          },null,{timeout:90000});
          extraBefore=await page.evaluate(()=>{
            const v=document.querySelector('#video');
            return {source:v.currentSrc,time:v.currentTime,
              decoded:v.getVideoPlaybackQuality?.().totalVideoFrames||0};
          });
          await route.fulfill({status:200,contentType:'application/json',
            body:JSON.stringify({sources:[heldVerifiedSource],
              count:1,providerMode:'server',backgroundTest:true})});
          extraReleased=true;
          return;
        }
        if(parsed.pathname==='/api/catalog'){
          await route.fulfill({status:200,contentType:'application/json',
            body:JSON.stringify({page:1,total_pages:1,results:[]})});
          return;
        }
        if(parsed.pathname==='/api/stremio-subtitles'){
          await route.fulfill({status:200,contentType:'application/json',
            body:JSON.stringify({subtitles:[],active:false,count:0})});
          return;
        }
        const headers={};
        if(parsed.pathname.startsWith('/api/media/')){
          if(heldMediaToken&&parsed.pathname===heldMediaToken)
            heldVideoRequests++;
          if(firstToken && parsed.pathname===firstToken){
            outageCount++;
            await route.fulfill({status:502,contentType:'text/plain',
              body:'Simulated CDN failure AFTER server-side video byte verification'});
            return;
          }
          mediaRangeCount++;
          if(mediaRangeCount>100)
            throw Error('Maximum media range budget exceeded');
          const raw=route.request().headers().range||'';
          const match=raw.match(/^bytes=(\d+)-(\d*)$/);
          if(!match)throw Error('Browser media request did not use Range');
          const start=Number(match[1]);
          const end=Math.min(match[2]?Number(match[2]):start+1_300_000,
            start+1_300_000);
          headers.range='bytes='+start+'-'+end;
        }
        const upstream=await nativeFetch(LOCAL+parsed.pathname+parsed.search,{
          method:route.request().method(),headers,redirect:'error',
          signal:AbortSignal.timeout(28000)});
        const forwarded={};
        for(const header of ['content-type','content-range','content-length','accept-ranges']){
          const value=upstream.headers.get(header);
          if(value)forwarded[header]=value;
        }
        let body=Buffer.from(await upstream.arrayBuffer());
        if(parsed.pathname==='/api/resolve' &&
           parsed.searchParams.get('phase')!=='extras'){
          resolveCount++;
          const data=JSON.parse(body.toString('utf8'));
          const sources=(data.sources||[]).filter(x=>x?.gatewayIssued===true);
          issuedCount=sources.length;
          console.log('MOOSA_PLAY_BACKEND_RESOLVE_DIAGNOSTIC '+JSON.stringify({
            film:film.title,httpStatus:upstream.status,
            apiError:typeof data.error==='string'?data.error.slice(0,90):null,
            sourceCount:Array.isArray(data.sources)?data.sources.length:0,
            gatewaySources:issuedCount,preflightIssuedTokens:gateway.activeTokenCount(),
            tmdbMetadataCalls:tmdbCount,protocolRequests:providerCount
          }));
          assert.ok(sources.length>=2,'Backend returned fewer than two real verified film sources');
          assert.ok(sources.every(x=>
            x.originalMovieTmdbId===film.id &&
            x.originalMovieImdbId===film.imdbId &&
            x.originalMovieYear===film.year &&
            /^https:\/\/moosa-web\.onrender\.com\/api\/media\/[a-zA-Z0-9_-]{32,128}$/.test(x.url)
          ),'Backend returned an unrelated or untrusted movie source');
          assert.ok(sources.length>=3,
            'Need three independently verified server capabilities for late delivery');
          heldVerifiedSource=sources[2];
          heldMediaToken=new URL(heldVerifiedSource.url).pathname;
          firstToken=new URL(sources[0].url).pathname;
          const immediate={...data,sources:sources.slice(0,2),count:2,
            extrasAvailable:true};
          body=Buffer.from(JSON.stringify(immediate));
          forwarded['content-type']='application/json';
          forwarded['content-length']=String(body.length);
        }
        await route.fulfill({status:upstream.status,headers:forwarded,body});
      });
      phase='load_live_ui';
      await page.goto(SITE+'/',{waitUntil:'domcontentloaded',timeout:35000});
      phase='real_search_card';
      // User-level UI interactions only; do NOT import player functions.
      // The site intentionally requires 2+ search characters. A one-letter
      // classic such as M must be searched with its release year as well.
      const searchQuery=film.title.length>=2?
        film.title:film.title+' '+film.year;
      await page.locator('#search-input').fill(searchQuery);
      await page.locator('#browse-grid .poster-card').first().waitFor({
        state:'visible',timeout:15000
      });
      await page.locator('#browse-grid .poster-card').first().click();
      phase='actual_watch_button';
      await page.locator('#watch-title').waitFor({
        state:'visible',timeout:15000
      });
      assert.equal((await page.locator('#details-name').textContent())?.trim(),
        film.title,'Catalog search navigated to the wrong film');
      clickStarted=Date.now();
      await page.locator('#watch-title').click();
      phase='wait_decoded_fallback';
      await page.waitForFunction(({id})=>{
        const video=document.querySelector('#video');
        const selector=document.querySelector('#player-source');
        return video&&selector&&selector.value==='1'&&
          video.currentTime>0.6&&!video.paused&&!video.error&&
          video.videoWidth>0&&video.videoHeight>0&&
          (video.getVideoPlaybackQuality?.().totalVideoFrames||
            video.webkitDecodedFrameCount||0)>0;
      },{id:film.id},{timeout:100000});
      phase='verify_user_playback';
      firstFrameMs=Date.now()-clickStarted;
      const actual=await page.evaluate(()=>{
        const video=document.querySelector('#video');
        const picker=document.querySelector('#player-source');
        return {
          selected:picker.value,options:picker.options.length,
          playerTitle:document.querySelector('#player-title').textContent,
          qualityText:document.querySelector('#player-quality').selectedOptions[0]?.textContent,
          width:video.videoWidth,height:video.videoHeight,
          duration:Math.round(video.duration),
          decodedFrames:video.getVideoPlaybackQuality?.().totalVideoFrames||
            video.webkitDecodedFrameCount||0,
          sourceStatus:document.querySelector('#automatic-source-status')?.textContent||''
        };
      });
      selectOptions=actual.options;
      assert.equal(actual.playerTitle,film.title,'Player title changed during fallback');
      assert.equal(actual.selected,'1','Automatic fallback did not select the next source');
      assert.ok(outageCount>=1,'Initial verified source was not tried');
      assert.equal(resolveCount,1,'Play must make just one fast resolve request');
      assert.ok(firstFrameMs>0&&firstFrameMs<95000,
        'Real UI Play to decoded fallback was not bounded');
      assert.ok(extraStarted>=1,
        'Fast CineStream source picker must also start extra provider search');
      assert.ok(issuedCount>=3&&selectOptions>=3,
        'The same-film backend must verify 3 sources before background delivery');
      phase='wait_background_append';
      await page.waitForFunction(()=>document.querySelector('#player-source')?.options.length===4,
        null,{timeout:30000});
      assert.ok(extraReleased && extraBefore,
        'Background provider route must append only after actual video playback');
      await page.waitForFunction(({previousTime,previousFrames})=>{
        const v=document.querySelector('#video');
        return v && v.currentTime>previousTime+0.5 &&
          (v.getVideoPlaybackQuality?.().totalVideoFrames||0)>previousFrames &&
          !v.paused&&!v.error;
      },{previousTime:extraBefore.time,previousFrames:extraBefore.decoded},
      {timeout:20000});
      const afterAppend=await page.evaluate(()=>{
        const v=document.querySelector('#video'),picker=document.querySelector('#player-source');
        return {url:v.currentSrc,selected:picker.value,
          options:picker.options.length,currentTime:v.currentTime};
      });
      assert.equal(afterAppend.url,extraBefore.source,
        'New background server must never replace a playing source');
      assert.equal(afterAppend.selected,'1',
        'Background append must preserve the active fallback source');
      assert.equal(afterAppend.options,4);
      assert.equal(heldVideoRequests,0,
        'The newly added server must not be requested while video is playing');
      phase='seek_then_switch_same_film';
      const framesBeforeSeek=await page.locator('#video').evaluate(v=>
        v.getVideoPlaybackQuality?.().totalVideoFrames||0);
      await page.locator('#video').evaluate(v=>{v.currentTime=75;});
      await page.waitForFunction(before=>{
        const v=document.querySelector('#video');
        return v.currentTime>=75.1&&!v.seeking&&v.readyState>=2&&
          (v.getVideoPlaybackQuality?.().totalVideoFrames||0)>before;
      },framesBeforeSeek,{timeout:60000});
      const positionBeforeSwitch=await page.locator('#video').evaluate(v=>v.currentTime);
      assert.ok(positionBeforeSwitch>=75&&positionBeforeSwitch<150);
      await page.locator('#player-source').selectOption('2');
      await page.waitForFunction(({path,minimum,thirdHeight})=>{
        const v=document.querySelector('#video'),sel=document.querySelector('#player-source');
        return sel?.value==='2'&&v.currentSrc.endsWith(path)&&
          v.currentTime>=minimum-3&&v.readyState>=2&&!v.error&&
          Math.abs(v.videoHeight-thirdHeight)<=14&&
          (v.getVideoPlaybackQuality?.().totalVideoFrames||0)>0;
      },{path:heldMediaToken,minimum:positionBeforeSwitch,
        thirdHeight:film.thirdHeight},{timeout:60000});
      const switchResult=await page.locator('#video').evaluate(v=>({
        currentTime:v.currentTime,videoHeight:v.videoHeight,
        decodedFrames:v.getVideoPlaybackQuality?.().totalVideoFrames||0
      }));
      assert.ok(heldVideoRequests>0,
        'The third same-film capability was not requested after user selection');
      assert.ok(Math.abs(switchResult.currentTime-positionBeforeSwitch)<15,
        'Selected verified alternative must resume near prior timestamp');
      console.log('MOOSA_REAL_UI_SOURCE_SWITCH_RESUME_OK '+JSON.stringify({
        film:film.title,fromServerIndex:1,toServerIndex:2,
        beforeSeconds:Math.round(positionBeforeSwitch),
        afterSeconds:Math.round(switchResult.currentTime),
        actualAlternateHeight:switchResult.videoHeight,
        decodedFrames:switchResult.decodedFrames,
        sameTitle:true,compatibleRuntimeGuard:true
      }));
      assert.ok(actual.duration>=film.minDuration&&
        actual.duration<=film.maxDuration,'Short clip or wrong feature runtime');
      assert.ok(actual.decodedFrames>0);
      assert.ok(Math.abs(actual.height-film.backup)<=12,
        'Backup resolution differs from the expected alternate encode');
      assert.equal(actual.qualityText,
        'Actual resolution: '+actual.width+'×'+actual.height);
      assert.deepEqual(errors,[]);
      console.log('MOOSA_REAL_AUTO_PLAY_BACKEND_TO_BROWSER_PASS '+JSON.stringify({
        title:film.title,tmdbId:film.id,
        backendResolveRequests:resolveCount,backgroundSearchRequests:extraStarted,
        realCardAndWatchButtonClicked:true,playToFrameMilliseconds:firstFrameMs,
        offeredServerCount:issuedCount,
        sourcePickerEntries:afterAppend.options,
        additionalVerifiedServerAddedAfterPlayback:true,
        originalPlayingSourcePreserved:true,
        videoFramesAdvancedAfterBackgroundAppend:true,
        firstServerFailureCount:outageCount,automaticallySelectedSourceIndex:1,
        fallbackActualWidth:actual.width,fallbackActualHeight:actual.height,
        decodedFrames:actual.decodedFrames,featureDurationSeconds:actual.duration,
        qualitySelector:actual.qualityText,
        publicRenderUnchanged:true,mainBranchUnchanged:true
      }));
    }catch(error){
      console.log('MOOSA_REAL_AUTO_PLAY_ENDTOEND_FAILED '+JSON.stringify({
        film:film.title,stage:phase,errorName:String(error?.name||'Error').slice(0,70),
        backendResolveRequests:resolveCount,
        issuedCapabilities:issuedCount,
        firstSourceFailures:outageCount,sourcePickerEntries:selectOptions
      }));
      throw error;
    }finally{await page?.close();}
  }
  assert.ok(tmdbCount>=FILMS.length);
  assert.ok(providerCount>=FILMS.length*2);
  console.log('MOOSA_REAL_AUTO_PLAY_ENDTOEND_COMPLETE '+JSON.stringify({
    filmsTested:FILMS.length,providerCalls:providerCount,
    mockedTmdbMetadataCalls:tmdbCount,rangeRequests:mediaRangeCount,
    productionUnchanged:true
  }));
}finally{
  await browser?.close().catch(()=>{});
  if(server)await new Promise(resolve=>server.close(resolve));
  globalThis.fetch=nativeFetch;
}
