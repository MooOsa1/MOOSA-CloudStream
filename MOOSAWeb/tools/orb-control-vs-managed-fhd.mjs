/**
 * Compare zero-privilege HTML5 video navigation from actual MOOSA origin.
 * CC0 Flower is a known good browser control; CC BY Sintel is a strictly
 * host-allowlisted managed 1080p candidate. Do NOT fetch copyrighted films,
 * set referer/cookies, bypass anti-hotlink rules, or proxy media.
 * Logs request/response status/headers and decoded dimensions, NEVER URLs.
 */
import {createRequire} from 'node:module';
import {existsSync} from 'node:fs';
const require=createRequire('/tmp/moosa-browser/package.json');
const puppeteer=require('puppeteer-core');
const CHROME=['/usr/bin/google-chrome','/usr/bin/google-chrome-stable','/opt/google/chrome/chrome'].find(existsSync);
const MANIFEST='https://plaviohd.vercel.app/stream/movie/tt1727587.json';
const CDN='bcdn.hakunaymatata.com';
const BASELINE='https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4';
function safeUrl(raw) {
 try{
  const u=new URL(raw);
  return u.protocol==='https:'&&u.hostname===CDN&&u.port===''&&!u.username&&!u.password&&
    u.pathname.toLowerCase().endsWith('.mp4')?u.href:null;
 }catch{return null}
}
async function eligibleFhd(){
 try{
  const r=await fetch(MANIFEST,{redirect:'error',signal:AbortSignal.timeout(9000)});
  if(!r.ok||Number(r.headers.get('content-length')||0)>240000)return null;
  const raw=await r.text();if(raw.length>240000)return null;
  const data=JSON.parse(raw);
  const streams=Array.isArray(data.streams)?data.streams:[];
  return streams.filter(s=>!s.headers&&!s.referer&&!s.cookie&&
      !s.behaviorHints?.proxyHeaders&&
      /(?:^|\D)1080p?(?:\D|$)/i.test(String(s.name||'')+' '+String(s.title||'')))
    .map(s=>safeUrl(s.url)).find(Boolean)||null;
 }catch{return null}
}
async function evaluateVideo(page,cdp,label,url){
 const network=[],watchIds=new Map();
 const domain=raw=>{try{return new URL(raw).hostname}catch{return 'other'}};
 const allowed=new Set([CDN,'interactive-examples.mdn.mozilla.net']);
 const collect=(event,detail)=>{if(network.length<16)network.push({event,...detail})};
 const req=p=>{
  const h=domain(p.request.url);
  if(!allowed.has(h))return;
  watchIds.set(p.requestId,h);
  collect('req',{host:h,method:p.request.method,type:p.type||''});
 };
 const reqExtra=p=>{
  if(!watchIds.has(p.requestId))return;
  const h=p.headers||{};
  collect('reqHeaders',{
    host:watchIds.get(p.requestId),
    mode:String(h['sec-fetch-mode']||'').slice(0,35),
    dest:String(h['sec-fetch-dest']||'').slice(0,35),
    rangePresent:!!h.range,
    originPresent:!!h.origin,
    refererHost:domain(h.referer)
  });
 };
 const response=p=>{
  if(!watchIds.has(p.requestId))return;
  collect('response',{host:watchIds.get(p.requestId),
    status:p.response.status,mime:p.response.mimeType});
 };
 const extra=p=>{
  if(!watchIds.has(p.requestId))return;
  const h=p.headers||{};
  collect('respHeaders',{host:watchIds.get(p.requestId),
   status:p.statusCode,mime:String(h['content-type']||'').slice(0,60),
   cors:!!h['access-control-allow-origin'],
   corp:String(h['cross-origin-resource-policy']||'').slice(0,50),
   nosniff:String(h['x-content-type-options']||'').slice(0,30),
   range:!!h['content-range']});
 };
 const failed=p=>{
  if(!watchIds.has(p.requestId))return;
  collect('failed',{host:watchIds.get(p.requestId),
    reason:String(p.errorText||'').slice(0,90),
    blockedReason:String(p.blockedReason||'').slice(0,60),
    corsError:String(p.corsErrorStatus?.corsError||'').slice(0,70)});
 };
 cdp.on('Network.requestWillBeSent',req);
 cdp.on('Network.requestWillBeSentExtraInfo',reqExtra);
 cdp.on('Network.responseReceived',response);
 cdp.on('Network.responseReceivedExtraInfo',extra);
 cdp.on('Network.loadingFailed',failed);
 try{
  const playback=await page.evaluate(url=>new Promise(resolve=>{
   const v=document.createElement('video');
   v.muted=true;v.preload='auto';v.playsInline=true;
   let settled=false;
   const finish=status=>{
    if(settled)return;settled=true;clearTimeout(timeout);
    const data={status,width:v.videoWidth,height:v.videoHeight,
      errorCode:v.error?.code||0,networkState:v.networkState,readyState:v.readyState};
    v.pause();v.removeAttribute('src');v.load();v.remove();resolve(data);
   };
   const timeout=setTimeout(()=>finish('timeout'),16000);
   v.addEventListener('error',()=>finish('media_error'),{once:true});
   v.addEventListener('loadedmetadata',()=>{
    if('requestVideoFrameCallback' in v)
     v.requestVideoFrameCallback(()=>finish('decoded_frame'));
    else v.addEventListener('timeupdate',()=>finish('decoded_frame'),{once:true});
    void v.play().catch(e=>finish(e.name==='NotAllowedError'?'autoplay_denied':'play_rejected'));
   },{once:true});
   document.body.appendChild(v);v.src=url;v.load();
  }),url);
  await new Promise(resolve=>setTimeout(resolve,250));
  console.log('MOOSA_ORB_CONTROL '+JSON.stringify({
    label,...playback,
    decodedFhd:playback.status==='decoded_frame'&&playback.width>=1920&&playback.height>=1080,
    network,originalVideoUrlOmitted:true
  }));
 }catch(e){console.log('MOOSA_ORB_CONTROL '+JSON.stringify({label,result:'browser_test_failed',
   errorClass:String(e?.name||'unknown').slice(0,45)}))}
 finally{
  cdp.off('Network.requestWillBeSent',req);
  cdp.off('Network.requestWillBeSentExtraInfo',reqExtra);
  cdp.off('Network.responseReceived',response);
  cdp.off('Network.responseReceivedExtraInfo',extra);
  cdp.off('Network.loadingFailed',failed);
 }
}
if(!CHROME){console.log('MOOSA_ORB_CONTROL Chrome_not_available');process.exit(0)}
const fhd=await eligibleFhd();
console.log('MOOSA_ORB_CONTEXT '+JSON.stringify({approvedSourceFound:!!fhd}));
const browser=await puppeteer.launch({
 executablePath:CHROME,headless:true,
 args:['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required']
});
try{
 const page=await browser.newPage();
 const cdp=await page.createCDPSession();
 await cdp.send('Network.enable');
 await page.goto('https://moosa-web.onrender.com/',{waitUntil:'domcontentloaded',timeout:17000});
 await evaluateVideo(page,cdp,'CC0 baseline flower',BASELINE);
 if(fhd)await evaluateVideo(page,cdp,'licensed Sintel claimed FHD',fhd);
}finally{await browser.close()}
