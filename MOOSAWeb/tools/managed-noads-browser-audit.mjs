/**
 * Real headless-Chrome read-only audit of fixed licensed Sintel embeds.
 * No clicks, no popup permissions, no auth, no user browsing, no bypasses.
 * Third-party domains and number of redirects are recorded WITHOUT URLs.
 * A static load with no ads is not proof that ads cannot appear later.
 */
import {existsSync} from 'node:fs';
import {createRequire} from 'node:module';
const require=createRequire('/tmp/moosa-browser/package.json');
const puppeteer=require('puppeteer-core');
const chrome=['/usr/bin/google-chrome','/usr/bin/google-chrome-stable',
  '/opt/google/chrome/chrome'].find(existsSync);
const CASES=[
  {name:'EmbedWave',origin:'embedwave.cc',url:'https://embedwave.cc/embed/movie/45745'},
  {name:'ezvidapi',origin:'ezvidapi.com',url:'https://ezvidapi.com/embed/movie/45745'}
];
function host(raw){try{return new URL(raw).hostname}catch{return 'invalid'}}
if(!chrome)throw Error('Chrome unavailable');
const browser=await puppeteer.launch({executablePath:chrome,headless:true,
  args:['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required']});
try {
  for(const sample of CASES){
    const page=await browser.newPage();
    const domains=new Map(),popupUrls=[];
    const failures=[];
    let frameResponseStatus=0,frameNavigations=0;
    page.on('request',r=>{
      const h=host(r.url());
      if(!['data:','about:'].includes(h))domains.set(h,(domains.get(h)||0)+1);
    });
    page.on('popup',p=>{popupUrls.push(host(p.url()));void p.close().catch(()=>{});});
    page.on('requestfailed',r=>{if(failures.length<15)failures.push(host(r.url()));});
    page.on('framenavigated',f=>{if(f!==page.mainFrame())frameNavigations++;});
    page.on('response',r=>{
      if(host(r.url())===sample.origin&&r.url().includes('/embed/movie/45745')&&
        !frameResponseStatus)frameResponseStatus=r.status();
    });
    try {
      await page.setViewport({width:1280,height:720});
      await page.setContent('<!doctype html><html><body><iframe id="media" '+
        'style="width:1100px;height:620px" '+
        'sandbox="allow-scripts allow-same-origin allow-presentation" '+
        'allow="autoplay; fullscreen; picture-in-picture" '+
        'src="'+sample.url+'"></iframe></body></html>');
      await new Promise(r=>setTimeout(r,12500));
      const frames=page.frames().filter(f=>f!==page.mainFrame());
      const frameInfo=[];
      for(const f of frames.slice(0,5)){
        try{
          const info=await f.evaluate(()=>{
            const videos=[...document.querySelectorAll('video')];
            return {
              name:document.title?.slice(0,80)||'',
              videoCount:videos.length,
              maxWidth:Math.max(0,...videos.map(v=>v.videoWidth)),
              maxHeight:Math.max(0,...videos.map(v=>v.videoHeight)),
              hasPlayButton:!!document.querySelector('button[aria-label*="Play"],button[title*="Play"]'),
              suspiciousAdTags:document.querySelectorAll('[id*="ad-"],[class*="advert"],script[src*="doubleclick"]').length,
              iframeCount:document.querySelectorAll('iframe').length
            };
          });
          frameInfo.push({host:host(f.url()),...info});
        }catch{frameInfo.push({host:host(f.url()),eval:'inaccessible'});}
      }
      const hosts=[...domains.entries()].sort((a,b)=>b[1]-a[1]).slice(0,12);
      console.log('MOOSA_NOADS_BROWSER name='+JSON.stringify(sample.name)+
        ' embedHttpStatus='+frameResponseStatus+
        ' frameNavigations='+frameNavigations+
        ' newWindowCount='+popupUrls.length+
        ' requestHosts='+JSON.stringify(hosts)+
        ' failedHosts='+JSON.stringify([...new Set(failures)])+
        ' activeFrameInfo='+JSON.stringify(frameInfo)+
        ' decoded1080Verified='+frameInfo.some(f=>f.maxHeight>=1080&&f.maxWidth>=1920)+
        ' adFreeGuaranteed=false');
    }catch(e){
      console.log('MOOSA_NOADS_BROWSER name='+JSON.stringify(sample.name)+
        ' status=probe_error errorClass='+JSON.stringify(e?.name||'unknown')+
        ' adFreeGuaranteed=false');
    }finally{await page.close().catch(()=>{});}
  }
}finally{await browser.close().catch(()=>{});}
