/**
 * DECISIVE browser cross-origin HTTP response diagnostic. No proxies,
 * extensions, CORS bypasses, DRM workarounds, user credentials or new sources.
 * Tests approved CC0 MDN video and independently rights-reviewed Commons
 * samples against the managed-provider Sintel video (openly licensed title).
 *
 * Real page: https://moosa-web.onrender.com. Outputs HTTP request/response
 * status/mime/CORP and decoded-frame dimensions; never logs signed URLs.
 */
import {existsSync} from 'node:fs';
import {createRequire} from 'node:module';
import {getCuratedFilm} from '../free-film-library.mjs';
const require=createRequire('/tmp/moosa-browser/package.json');
const puppeteer=require('puppeteer-core');
const chrome=['/usr/bin/google-chrome','/usr/bin/google-chrome-stable',
  '/opt/google/chrome/chrome'].find(existsSync);
const APP='https://moosa-web.onrender.com/';
const PLAVIO='https://plaviohd.vercel.app/stream/movie/tt1727587.json';
const CDNH='bcdn.hakunaymatata.com';
const MDN='https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4';
function host(raw){try{return new URL(raw).hostname}catch{return 'invalid'}}
function header(headers,name){
  for(const [k,v] of Object.entries(headers||{}))
    if(k.toLowerCase()===name.toLowerCase())return String(v).slice(0,95);
  return '';
}
async function plavioCandidate(){
  try{
    const response=await fetch(PLAVIO,{signal:AbortSignal.timeout(6500),redirect:'error'});
    if(!response.ok||Number(response.headers.get('content-length')||0)>250000)return null;
    const txt=await response.text();
    if(txt.length>250000)return null;
    const data=JSON.parse(txt);
    for(const s of Array.isArray(data?.streams)?data.streams:[]){
      if(s?.headers||s?.referer||s?.behaviorHints?.proxyHeaders)continue;
      if(!/(?:^|\D)1080p?(?:\D|$)/i.test(String(s.name||'')+' '+String(s.title||'')))continue;
      try{
        const u=new URL(s.url);
        if(u.protocol==='https:'&&u.hostname===CDNH&&!u.port&&!u.username&&
           !u.password&&u.pathname.toLowerCase().endsWith('.mp4'))return u.href;
      }catch{}
    }
  }catch{}
  return null;
}
async function commonsCandidates(){
  const candidates=[];
  for(const id of ['sintel','big-buck-bunny']){
    try{
      const f=await getCuratedFilm(id);
      if(!f?.available)continue;
      for(const s of f.sources){
        const u=new URL(s.url);
        if(u.protocol==='https:'&&u.hostname==='upload.wikimedia.org'&&
           /^https:\/\/creativecommons\.org\/licenses\//i.test(s.licenseUrl||''))
           candidates.push({name:'Commons '+id,quality:s.quality,url:u.href});
      }
    }catch{}
  }
  return candidates.sort((a,b)=>{
    // Pick a 1080p file before giant 4K masters and experimental variants.
    const rank=q=>q===1080?0:q===720?2:q>1080?1:3;
    return rank(a.quality)-rank(b.quality);
  }).slice(0,3);
}
async function exercise(page,name,url,expectFHD=false){
  const observed={responses:[],failure:[],requested:[]};
  const target=host(url);
  const session=await page.target().createCDPSession();
  const requestHosts=new Map();
  await session.send('Network.enable');
  session.on('Network.requestWillBeSent',p=>{
    requestHosts.set(p.requestId,host(p.request.url));
    if(host(p.request.url)===target && observed.requested.length<5)
       observed.requested.push({
         method:p.request.method,
         hasRange:!!header(p.request.headers,'range'),
         secFetchDest:header(p.request.headers,'sec-fetch-dest'),
         secFetchSite:header(p.request.headers,'sec-fetch-site')
       });
  });
  session.on('Network.responseReceived',p=>{
    if(host(p.response.url)!==target||observed.responses.length>=5)return;
    observed.responses.push({
      status:p.response.status,mime:p.response.mimeType,
      fromServiceWorker:p.response.fromServiceWorker||false,
      contentType:header(p.response.headers,'content-type'),
      contentRange:header(p.response.headers,'content-range'),
      corp:header(p.response.headers,'cross-origin-resource-policy'),
      contentDisposition:header(p.response.headers,'content-disposition'),
      xContentTypeOptions:header(p.response.headers,'x-content-type-options'),
      cors:header(p.response.headers,'access-control-allow-origin')
    });
  });
  session.on('Network.responseReceivedExtraInfo',p=>{
    if(requestHosts.get(p.requestId)!==target||observed.responses.length>=7)return;
    observed.responses.push({
      via:'extra_info',
      status:p.statusCode,
      contentType:header(p.headers,'content-type'),
      contentRange:header(p.headers,'content-range'),
      corp:header(p.headers,'cross-origin-resource-policy'),
      xContentTypeOptions:header(p.headers,'x-content-type-options'),
      cors:header(p.headers,'access-control-allow-origin')
    });
  });
  session.on('Network.loadingFailed',p=>{
    if(requestHosts.get(p.requestId)===target && observed.failure.length<8)
      observed.failure.push({
        errorText:p.errorText,
        blockedReason:p.blockedReason||'',
        corsError:p.corsErrorStatus?.corsError||''
      });
  });
  const result=await page.evaluate(url=>new Promise(resolve=>{
    const v=document.createElement('video');
    v.preload='metadata';v.playsInline=true;v.muted=true;v.crossOrigin=null;
    v.controls=false;
    v.style.width='320px';v.style.height='180px';
    let done=false,seenMetadata=false,w=0,h=0;
    const stop=status=>{
      if(done)return;done=true;
      clearTimeout(timer);
      const diagnostic={status,width:w,height:h,seenMetadata,
        mediaError:v.error?.code||0,networkState:v.networkState,
        readyState:v.readyState,playing:!v.paused};
      try{v.pause();v.removeAttribute('src');v.load();v.remove();}catch{}
      resolve(diagnostic);
    };
    const timer=setTimeout(()=>stop('timeout'),14000);
    v.addEventListener('error',()=>stop('media_error'),{once:true});
    v.addEventListener('loadedmetadata',()=>{
      seenMetadata=true;w=v.videoWidth;h=v.videoHeight;
      if('requestVideoFrameCallback' in v){
        v.requestVideoFrameCallback(()=>stop('decoded_frame'));
      }else v.addEventListener('timeupdate',()=>{if(v.currentTime>0)stop('timeupdate')},{once:true});
      v.play().catch(e=>stop('play_'+e.name));
    },{once:true});
    document.body.append(v);
    v.src=url;v.load();
  }),url);
  await session.detach().catch(()=>{});
  console.log('MOOSA_FHD_AB name='+JSON.stringify(name)+
    ' expectedFhd='+expectFHD+
    ' browser='+JSON.stringify(result)+
    ' http='+JSON.stringify(observed)+
    ' directSourceOnly=true rawUrlOmitted=true');
  return result;
}
if(!chrome)throw Error('Chrome binary unavailable');
const browser=await puppeteer.launch({executablePath:chrome,headless:true,
  args:['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required']});
try{
  const page=await browser.newPage();
  await page.goto(APP,{waitUntil:'domcontentloaded',timeout:14000});
  await exercise(page,'MDN CC0 control',MDN,false);
  const commons=await commonsCandidates();
  if(!commons.length)console.log('MOOSA_FHD_AB commons=not_discovered');
  for(const sample of commons.slice(0,2))
    await exercise(page,sample.name+' declared '+sample.quality+'p',sample.url,sample.quality>=1080);
  const plavio=await plavioCandidate();
  if(plavio)await exercise(page,'PlavioHD Sintel (CC)',plavio,true);
  else console.log('MOOSA_FHD_AB plavio=not_discovered');
  await page.close();
}finally{
  await browser.close().catch(()=>{});
}
