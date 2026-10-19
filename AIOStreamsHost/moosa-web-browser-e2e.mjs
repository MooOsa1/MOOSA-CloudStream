#!/usr/bin/env node
/**
 * Native Chromium MOOSA Web page integration test (not a real user's device).
 * Opens the ACTUAL MOOSA Web site on port 3000 and accesses the official
 * Stremio streaming server on loopback. Uses a disposable AIOStreams config.
 * Only media-title and pass/fail facts go to CI logs. Never print URL tokens,
 * credential-bearing network requests, torrent hashes or private IPs.
 */
import {spawn} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {chromium}=require('./node_modules/playwright');
const AIO='https://aiostreams-nightly.fortheweak.cloud';
const SITE='http://127.0.0.1:3000';
const template=JSON.parse(await readFile(new URL('./moosa-hub-template.json',import.meta.url),'utf8'));
const config={...template.config,services:[],formatter:{id:'gdrive'},addonName:'MOOSA Browser P2P E2E CI'};
const password=randomBytes(40).toString('hex');
let uuid,token,server,browser;
async function getJSON(url,init={},ms=80000){
 const res=await fetch(url,{...init,signal:AbortSignal.timeout(ms)});
 return {ok:res.ok,status:res.status,body:await res.json().catch(()=>({}))};
}
try{
 server=spawn(process.execPath,['MOOSAWeb/server.mjs'],{
  env:{...process.env,PORT:'3000'},stdio:'ignore'
 });
 for(let i=0;i<50;i++){
  try{if((await fetch(SITE+'/healthz',{signal:AbortSignal.timeout(1000)})).ok)break;}
  catch{}
  await new Promise(r=>setTimeout(r,250));
 }
 if(!(await fetch(SITE+'/healthz',{signal:AbortSignal.timeout(2000)})).ok)
   throw Error('MOOSA website server did not start');
 const c=await getJSON(AIO+'/api/v1/user',{method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify({password,config})});
 uuid=c.body?.data?.uuid; token=c.body?.data?.encryptedPassword;
 if(!uuid||!token||!c.ok)throw Error('AIOStreams disposable profile unavailable');
 const privateManifest=AIO+'/stremio/'+encodeURIComponent(uuid)+'/'+encodeURIComponent(token)+'/manifest.json';
 browser=await chromium.launch({headless:true,args:['--autoplay-policy=no-user-gesture-required']});
 const page=await browser.newPage({viewport:{width:1280,height:800},permissions:[]});
 const errors=[];
 page.on('pageerror',e=>{errors.push(e.name||'PageError');});
 await page.goto(SITE+'/stremio-lab.html',{waitUntil:'domcontentloaded',timeout:30000});
 await page.fill('#addon-url',privateManifest);
 await page.fill('#imdb','tt31193180');
 await page.click('#find');
 const choose=page.locator('#source-select');
 await choose.waitFor({state:'visible'});
 await page.waitForFunction(()=>document.querySelector('#source-select').options.length>0 &&
   document.querySelector('#play').disabled===false,{timeout:65000});
 const status=await page.locator('#lookup-status').textContent();
 const n=await choose.locator('option').count();
 console.log('MOOSA_BROWSER_REAL_SITE_OPEN=true privateAddonAccepted=true sourceChoices='+n+
   ' lookupOk='+/candidate/.test(status));
 await page.click('#play');
 const accepted=await page.waitForFunction(()=>{
   const video=document.querySelector('#stremio-video');
   return (video.readyState>=2&&video.videoWidth>0&&video.videoHeight>0&&
     (video.currentTime>0.2 || !video.paused));
 },{timeout:160000}).then(()=>true).catch(()=>false);
 const stats=await page.evaluate(()=>{
   const v=document.querySelector('#stremio-video');
   return {width:v.videoWidth,height:v.videoHeight,playSeconds:Math.round(v.currentTime*10)/10,
     status:document.querySelector('#play-status').textContent.slice(0,100)};
 });
 console.log('MOOSA_BROWSER_SITE_VIDEO_STARTED='+accepted+' width='+stats.width+
   ' height='+stats.height+' playerTime='+stats.playSeconds+
   ' jsErrors='+errors.length+' stall='+/stalled|failed|timeout/i.test(stats.status));
 console.log('NOT_IPHONE: Chromium+Linux test only; a website with local P2P engine is not standalone iPhone playback.');
 if(!accepted||errors.length)process.exitCode=1;
}catch(e){
 console.log('MOOSA_BROWSER_SITE_FAILED '+String(e?.message||e).replace(/https?:\/\/\S+/g,'[hidden]').slice(0,200));
 process.exitCode=1;
}finally{
 try{await browser?.close();}catch{}
 if(server){server.kill();}
 if(uuid){
  const auth='Basic '+Buffer.from(uuid+':'+password).toString('base64');
  try{const d=await getJSON(AIO+'/api/v1/user',{method:'DELETE',headers:{Authorization:auth}},14000);
   console.log('MOOSA_BROWSER_TEMP_CONFIG_DELETED='+d.ok);
  }catch{console.log('MOOSA_BROWSER_TEMP_CONFIG_DELETED=false');process.exitCode=1;}
 }
}
