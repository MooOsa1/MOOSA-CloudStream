/**
 * Browser proof of MP4 URL emitted by the independently ported CineStream
 * Vidlink extractor. Uses real MOOSA HTML5 player + ordinary dropdown.
 * Candidate file is PRIVATE to CI runner, never an artifact or log.
 */
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const privateFile='/tmp/moosa-js-port/sintel-vidlink-candidates.b64';
let urls=[];
try{
  const raw=await readFile(privateFile,'utf8');
  urls=raw.split(/\r?\n/).map(x=>x.trim()).filter(Boolean)
    .slice(0,3).map(x=>Buffer.from(x,'base64').toString('utf8'))
    .filter(x=>{
      try{
        const u=new URL(x);
        return u.protocol==='https:' && u.hostname==='bcdnxw.hakunaymatata.com' &&
          u.pathname.toLowerCase().endsWith('.mp4');
      }catch{return false;}
    });
}catch{}
assert.ok(urls.length>0,'Upstream CineStream JS Vidlink yielded no genuine MP4 for browser test');
const server=createServer();
await new Promise((resolve,reject)=>{
  server.once('error',reject);
  server.listen(0,'127.0.0.1',resolve);
});
const local='http://127.0.0.1:'+server.address().port;
let browser;
let success=false;
try{
  browser=await chromium.launch({
    headless:true,channel:'chrome',
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']
  });
  for(let index=0;index<urls.length;index++){
    const page=await browser.newPage({viewport:{width:1280,height:720}});
    const failures=[];
    page.on('pageerror',e=>failures.push(String(e.name||'error').slice(0,55)));
    page.on('requestfailed',request=>{
      try{failures.push(new URL(request.url()).hostname+':'+
        String(request.failure()?.errorText||'failed').slice(0,50));}catch{}
    });
    await page.route('https://moosa-web.onrender.com/**',async route=>{
      const u=new URL(route.request().url());
      const res=await fetch(local+u.pathname+u.search,{redirect:'manual'});
      await route.fulfill({
        status:res.status,
        contentType:res.headers.get('content-type')||'application/octet-stream',
        body:Buffer.from(await res.arrayBuffer())
      });
    });
    let diagnostic={};
    try {
      await page.goto('https://moosa-web.onrender.com/',{
        waitUntil:'domcontentloaded',timeout:25000
      });
      await page.evaluate(async(url)=>{
        const v=document.querySelector('#video');
        window.__vidlinkObserved={duration:null,width:0,height:0,played:false};
        v.addEventListener('loadedmetadata',()=>{
          window.__vidlinkObserved.duration=Number.isFinite(v.duration)
            ?Math.round(v.duration):null;
        });
        v.addEventListener('loadeddata',()=>{
          window.__vidlinkObserved.width=v.videoWidth;
          window.__vidlinkObserved.height=v.videoHeight;
        });
        v.addEventListener('timeupdate',()=>{
          if(v.currentTime>0.4)window.__vidlinkObserved.played=true;
        });
        const {showPlayer}=await import('/player.js');
        showPlayer({title:'Sintel',sources:[{
          url,name:'CineStream JS • Vidlink experimental original',
          quality:480,provider:'CineStream',licenseUrl:
            'https://creativecommons.org/licenses/by/3.0/'
        }]});
        document.querySelector('#player-source').value='0';
        document.querySelector('#player-source').dispatchEvent(
          new Event('change',{bubbles:true})
        );
      },urls[index]);
      await page.waitForTimeout(15000);
      diagnostic=await page.evaluate(()=>{
        const v=document.querySelector('#video');
        const o=window.__vidlinkObserved||{};
        return {...o,currentTime:Math.round(v.currentTime*10)/10,
          paused:v.paused,readyState:v.readyState,
          errorCode:v.error?.code||null,
          playerMessage:String(document.querySelector('#player-message')?.textContent||'').slice(0,155)};
      });
      const durationMatch=diagnostic.duration>=860&&diagnostic.duration<=930;
      const decoded=diagnostic.played&&diagnostic.width>0;
      console.log('MOOSA_JS_VIDLINK_CHROME_FILM '+JSON.stringify({
        attempt:index+1,correctLength:durationMatch,decoded,
        durationSeconds:diagnostic.duration||null,
        dimensions:decoded?diagnostic.width+'x'+diagnostic.height:null,
        paused:diagnostic.paused,errorCode:diagnostic.errorCode,
        failures:failures.slice(0,5)
      }));
      if(durationMatch&&decoded){success=true;break;}
    }catch(e){
      console.log('MOOSA_JS_VIDLINK_CHROME_ERROR '+JSON.stringify({
        attempt:index+1,name:String(e?.name||'error').slice(0,55),
        requestFailureHosts:failures.slice(0,4)}));
    }finally{await page.close();}
  }
}finally{
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>server.close(resolve));
}
console.log('MOOSA_JS_CINESTREAM_CORRECT_FILM_IN_WEBSITE_PLAYER='+success);
assert.equal(success,true,'CineStream JS Vidlink did not decode the correct Sintel movie inside MOOSA');
