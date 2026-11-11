/**
 * HUMAN UI real-world Play flow against the already deployed MOOSA site.
 * No mocked routes, no evaluate/import of site functions, no fake video.
 * Search -> matching 1968 title card -> details -> WATCH -> actual frames.
 */
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
const site='https://moosa-web.onrender.com';
const b=await chromium.launch({channel:'chrome',headless:true,
  args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
let page;
try{
  page=await b.newPage({viewport:{width:1366,height:768}});
  const http=[];
  page.on('response',r=>{
    try{
      const u=new URL(r.url());
      if(u.origin===site && 
        ['/api/search','/api/details','/api/resolve','/api/media'].some(p=>u.pathname.startsWith(p))){
          http.push({route:u.pathname.startsWith('/api/media/')?'/api/media/<token>':u.pathname,
            status:r.status()});
        }
    }catch{}
  });
  await page.goto(site+'/',{waitUntil:'domcontentloaded',timeout:90000});
  const search=page.locator('#search-input');
  await search.fill('Night of the Living Dead');
  const grid=page.locator('#browse-grid');
  await grid.locator('.poster-card').first().waitFor({timeout:30000});
  const target=grid.locator('.poster-card').filter({
    has:page.locator('.poster-year', {hasText:'1968'})
  }).filter({hasText:'Night of the Living Dead'}).first();
  await target.waitFor({timeout:30000});
  await target.click();
  await page.locator('#details-name').getByText('Night of the Living Dead',{exact:false}).waitFor({timeout:10000}).catch(async()=>{
    await page.locator('#details-name').waitFor({timeout:10000});
  });
  const year=await page.locator('#details-meta').textContent();
  assert.match(year||'',/1968/,'User clicked a different film year');
  const watch=page.locator('#watch-title');
  await watch.click();
  const select=page.locator('#player-source');
  await page.waitForFunction(()=>{
    const p=document.querySelector('#player-source');
    return p&&[...p.options].some(o=>/CineStream/i.test(o.textContent||''));
  },null,{timeout:110000});
  const selected=await select.evaluate(p=>({
    value:p.value,
    provider:[...p.options].some(o=>/CineStream.*Vidlink/i.test(o.textContent||'')),
    options:p.options.length
  }));
  console.log('MOOSA_PUBLISHED_REAL_UI_SOURCE '+JSON.stringify({
    searchWorked:true,filmYear:1968,
    titleDetailsOpened:true,watchButtonClicked:true,...selected
  }));
  assert.equal(selected.provider,true,'User-facing Play did not find CineStream');
  await page.waitForFunction(()=>{
    const video=document.querySelector('#video');
    return !!video && !video.error && !video.paused && video.currentTime>0.7 &&
      video.videoWidth>0&&video.videoHeight>0 &&
      Number.isFinite(video.duration);
  },null,{timeout:70000});
  const result=await page.locator('#video').evaluate(v=>({
    dimensions:v.videoWidth+'x'+v.videoHeight,
    playedSeconds:Math.round(v.currentTime*10)/10,
    durationSeconds:Math.round(v.duration),
    errorCode:v.error?.code||null
  }));
  const rightFilm=result.durationSeconds>=5400&&result.durationSeconds<=6150;
  console.log('MOOSA_PUBLISHED_HUMAN_CLICK_TO_PLAY '+JSON.stringify({
    realSite:true,realSearch:true,realWatchButton:true,
    CineStreamProvider:true,realVideoDecoded:true,
    correctFilmRuntime:rightFilm,video:result,
    httpCalls:http.slice(0,15)
  }));
  assert.ok(rightFilm,'User-facing watch opened wrong content');
  console.log('MOOSA_PUBLIC_CLICK_WATCH_CINESTREAM_SUCCESS=true');
}finally{await b.close();}
