/**
 * Nonproduction Chrome smoke test of one openly licensed Sintel video only.
 * A metadata/HEAD success is NOT proof of playable FHD. Try a single decoded
 * frame through ordinary HTML5 <video>, no privileged headers, cookies,
 * proxy, user token, or stream URL logging.
 */
import {existsSync} from 'node:fs';
import {createRequire} from 'node:module';
const require=createRequire('/tmp/moosa-browser/package.json');
const puppeteer=require('puppeteer-core');
const MANIFEST='https://plaviohd.vercel.app/stream/movie/tt1727587.json';
const APPROVED_HOST='bcdn.hakunaymatata.com';
const chrome=['/usr/bin/google-chrome','/usr/bin/google-chrome-stable',
  '/opt/google/chrome/chrome'].find(existsSync);
function safeStream(record){
  if(record?.headers||record?.behaviorHints?.proxyHeaders||record?.referer||
    typeof record?.url!=='string')return null;
  const desc=String(record?.name||'')+' '+String(record?.title||'');
  if(!/(?:^|\D)1080p?(?:\D|$)/.test(desc))return null;
  try{
    const u=new URL(record.url);
    if(u.protocol!=='https:'||u.hostname!==APPROVED_HOST||u.username||u.password||
       u.port||!u.pathname.toLowerCase().endsWith('.mp4'))return null;
    return u.href;
  }catch{return null;}
}
async function go(){
  if(!chrome){console.log('MOOSA_FHD_BROWSER result=chrome_unavailable');return;}
  let data;
  try{
    const response=await fetch(MANIFEST,{redirect:'error',signal:AbortSignal.timeout(9000)});
    if(!response.ok)throw Error('provider_http');
    if(Number(response.headers.get('content-length')||0)>250000)throw Error('response_too_large');
    const raw=await response.text();
    if(raw.length>250000)throw Error('response_too_large');
    data=JSON.parse(raw);
  }catch{console.log('MOOSA_FHD_BROWSER result=source_metadata_failed');return;}
  const selected=(Array.isArray(data?.streams)?data.streams:[]).map(safeStream).find(Boolean);
  if(!selected){console.log('MOOSA_FHD_BROWSER result=no_1080_sintel_candidate');return;}
  let browser;
  try{
    browser=await puppeteer.launch({executablePath:chrome,headless:true,
      args:['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required']});
    const page=await browser.newPage();
    const networkCodes=[],failed=[];
    page.on('response',response=>{
      try{
        if(new URL(response.url()).hostname!==APPROVED_HOST)return;
        if(networkCodes.length<8)networkCodes.push({
          status:response.status(),
          type:String(response.headers()['content-type']||'').split(';')[0].slice(0,45)
        });
      }catch{}
    });
    page.on('requestfailed',req=>{
      try{
        if(new URL(req.url()).hostname!==APPROVED_HOST)return;
        if(failed.length<8)failed.push(
          String(req.failure()?.errorText||'unknown').replace(/[^a-zA-Z0-9_:]/g,'_').slice(0,80)
        );
      }catch{}
    });
    await page.setRequestInterception(true);
    page.on('request',request=>{
      let permitted=false;
      try{
        const u=new URL(request.url());
        permitted=u.protocol==='https:'&&
          (u.hostname===APPROVED_HOST||u.hostname==='moosa-web.onrender.com');
      }catch{}
      if(permitted)void request.continue().catch(()=>{});
      else void request.abort().catch(()=>{});
    });
    await page.goto('https://moosa-web.onrender.com/',{
      waitUntil:'domcontentloaded',timeout:15000
    });
    const result=await page.evaluate(url=>new Promise(resolve=>{
      const v=document.createElement('video');
      v.muted=true;v.preload='metadata';v.playsInline=true;v.autoplay=false;
      v.style.width='320px';v.style.height='180px';
      let ended=false,seenMetadata=false,width=0,height=0;
      const stop=(status)=>{
        if(ended)return;ended=true;
        clearTimeout(watch);
        v.pause();v.removeAttribute('src');v.load();v.remove();
        resolve({status,width,height,seenMetadata,
          mediaErrorCode:v.error?.code||0,
          mediaNetworkState:v.networkState,
          readyState:v.readyState});
      };
      const watch=setTimeout(()=>stop('timeout'),18000);
      v.addEventListener('error',()=>stop('media_error'),{once:true});
      v.addEventListener('loadedmetadata',()=>{
        seenMetadata=true;width=v.videoWidth;height=v.videoHeight;
        if(width<1920||height<1080){stop('metadata_below_FHD');return;}
        const frame=()=>stop('decoded_frame');
        if('requestVideoFrameCallback' in v)v.requestVideoFrameCallback(frame);
        else v.addEventListener('timeupdate',()=>{if(v.currentTime>0)frame()},{once:true});
        v.play().catch(()=>stop('play_rejected'));
      },{once:true});
      document.body.appendChild(v);v.src=url;
      v.load();
    }),selected);
    console.log('MOOSA_FHD_BROWSER result='+result.status+
      ' width='+result.width+' height='+result.height+
      ' metadata='+result.seenMetadata+' decodedFhd='+(
        result.status==='decoded_frame'&&result.width>=1920&&result.height>=1080)+
      ' errorCode='+result.mediaErrorCode+
      ' networkState='+result.mediaNetworkState+
      ' network='+JSON.stringify(networkCodes)+
      ' failed='+JSON.stringify(failed)+
      ' titleIdentityVerified=false sourceUrlOmitted=true');
  }catch(e){
    console.log('MOOSA_FHD_BROWSER result=browser_probe_failed reason='+String(e?.name||'unknown')
      .replace(/[^a-zA-Z]/g,'').slice(0,30));
  }finally{await browser?.close().catch(()=>{})}
}
await go();
