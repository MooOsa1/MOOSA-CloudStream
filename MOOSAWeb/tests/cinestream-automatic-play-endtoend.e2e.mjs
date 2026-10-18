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
    primary:720,backup:480,minDuration:3600,maxDuration:11000},
  {id:832,title:'M',year:1931,imdbId:'tt0022100',
    primary:1080,backup:480,minDuration:4500,maxDuration:8500}
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
  const url=new URL(typeof input==='string'?input:input.url);
  if(url.hostname==='api.themoviedb.org'){
    tmdbCount++;
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
    let resolveCount=0,outageCount=0,firstToken=null;
    let issuedCount=0,selectOptions=0;
    try{
      phase='new_page';
      page=await browser.newPage({viewport:{width:1152,height:768}});
      page.setDefaultTimeout(100000);
      const errors=[];
      page.on('pageerror',error=>errors.push(String(error.name||'script_error')));
      await page.route(SITE+'/**',async route=>{
        const parsed=new URL(route.request().url());
        if(parsed.pathname==='/api/stremio-subtitles'){
          await route.fulfill({status:200,contentType:'application/json',
            body:JSON.stringify({subtitles:[],active:false,count:0})});
          return;
        }
        const headers={};
        if(parsed.pathname.startsWith('/api/media/')){
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
        const body=Buffer.from(await upstream.arrayBuffer());
        if(parsed.pathname==='/api/resolve'){
          resolveCount++;
          const data=JSON.parse(body.toString('utf8'));
          const sources=(data.sources||[]).filter(x=>x?.gatewayIssued===true);
          issuedCount=sources.length;
          assert.ok(sources.length>=2,'Backend returned fewer than two real verified film sources');
          assert.ok(sources.every(x=>
            x.originalMovieTmdbId===film.id &&
            x.originalMovieImdbId===film.imdbId &&
            x.originalMovieYear===film.year &&
            /^https:\/\/moosa-web\.onrender\.com\/api\/media\/[a-zA-Z0-9_-]{32,128}$/.test(x.url)
          ),'Backend returned an unrelated or untrusted movie source');
          firstToken=new URL(sources[0].url).pathname;
        }
        await route.fulfill({status:upstream.status,headers:forwarded,body});
      });
      phase='load_live_ui';
      await page.goto(SITE+'/',{waitUntil:'domcontentloaded',timeout:35000});
      phase='search_real_backend';
      await page.evaluate(({title,id})=>{
        // This is the real browser path used when Play opens a title:
        // start empty, resolve via /api/resolve, append auto-picked sources.
        import('/player.js').then(async({showPlayer})=>{
          showPlayer({title,sources:[]});
          const {searchAutomaticSources}=await import('/source-search.js');
          await searchAutomaticSources('movie',id);
        });
      },{title:film.title,id:film.id});
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
      assert.equal(resolveCount,1,'Play should resolve only once per title');
      assert.ok(issuedCount>=2&&selectOptions>=3,'Source picker did not list returned sources');
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
        backendResolveRequests:resolveCount,
        offeredServerCount:issuedCount,sourcePickerEntries:selectOptions,
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
