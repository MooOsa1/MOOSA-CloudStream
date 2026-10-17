/**
 * FULL GATEWAY PIPELINE: operator-issued private capability ->
 * real Node /api/media route -> HLS child tokens/MP4 byte ranges ->
 * real Chrome <video> via MOOSA's normal source picker.
 *
 * Only locally generated synthetic licensed fixture is used.
 * No original CineStream playback assertion here.
 */
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';
import {createMediaGateway} from '../verified-media-gateway.mjs';

const site='https://moosa-web.onrender.com';
const upstreamHost='synthetic.moosa-test.example';
const dir=new URL('./playback-fixtures/',import.meta.url);
const mp4=await readFile(new URL('clip.mp4',dir));
const playlist=await readFile(new URL('playlist.m3u8',dir),'utf8');
const segments=[...new Set([...playlist.matchAll(/clip-\d+\.ts/g)].map(x=>x[0]))];
assert.ok(segments.length>0,'Real HLS TS fixture required');
const files=new Map([['/clip.mp4',{bytes:mp4,mime:'video/mp4'}],
  ['/playlist.m3u8',{bytes:Buffer.from(playlist),mime:'application/vnd.apple.mpegurl'}]]);
for(const name of segments)files.set('/'+name,{
  bytes:await readFile(new URL(name,dir)),mime:'video/mp2t'
});
let upstreamCalls=0,rangeCalls=0;
const gateway=createMediaGateway({origin:site,
  allowedHosts:[upstreamHost],
  fetchImpl:async(url,request)=>{
    upstreamCalls++;
    const u=new URL(url);
    assert.equal(u.hostname,upstreamHost);
    const item=files.get(u.pathname);
    if(!item)return new Response('Not Found',{status:404});
    const bytes=item.bytes;
    const headers={'content-type':item.mime,'accept-ranges':'bytes'};
    const rawRange=request?.headers?.range;
    if(rawRange){
      rangeCalls++;
      const match=rawRange.match(/^bytes=(\d+)-(\d*)$/);
      assert.ok(match,'Range syntax');
      const start=Number(match[1]);
      const end=match[2]?Math.min(Number(match[2]),bytes.length-1):bytes.length-1;
      if(start>=bytes.length||start>end)return new Response(null,{status:416});
      headers['content-range']='bytes '+start+'-'+end+'/'+bytes.length;
      headers['content-length']=String(end-start+1);
      return new Response(request.method==='HEAD'?null:bytes.subarray(start,end+1),
        {status:206,headers});
    }
    headers['content-length']=String(bytes.length);
    return new Response(request.method==='HEAD'?null:bytes,{status:200,headers});
  }
});
const mp4Cap=gateway.issue({url:'https://'+upstreamHost+'/clip.mp4',
  format:'mp4',rightsApproved:true,sourceVerified:true});
const hlsCap=gateway.issue({url:'https://'+upstreamHost+'/playlist.m3u8',
  format:'hls',rightsApproved:true,sourceVerified:true});
const srv=createServer({mediaGateway:gateway});
await new Promise((resolve,reject)=>{
  srv.once('error',reject);srv.listen(0,'127.0.0.1',resolve);
});
const local='http://127.0.0.1:'+srv.address().port;
let browser;
try{
  browser=await chromium.launch({headless:true,channel:'chrome',
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  const page=await browser.newPage({viewport:{width:1280,height:720}});
  const pageErrors=[];
  page.on('pageerror',e=>pageErrors.push(String(e.message)));
  await page.route('https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.min.js',
    async route=>route.fulfill({status:200,contentType:'text/javascript',
      body:await readFile(new URL('../node_modules/hls.js/dist/hls.min.js',import.meta.url))}));
  // The browser sees the deployed HTTPS origin. Requests go through the
  // ACTUAL locally running MOOSA Node HTTP router, not Playwright fake media.
  await page.route(site+'/**',async route=>{
    const source=new URL(route.request().url());
    const upstream=await fetch(local+source.pathname+source.search,{
      method:route.request().method(),redirect:'manual',
      headers:route.request().headers()['range']
        ?{range:route.request().headers()['range']}:{},
      signal:AbortSignal.timeout(12000)
    });
    const headers={};
    for(const h of ['content-type','content-range','content-length',
      'accept-ranges','content-security-policy']){
      const v=upstream.headers.get(h);
      if(v)headers[h]=v;
    }
    await route.fulfill({status:upstream.status,headers,
      body:Buffer.from(await upstream.arrayBuffer())});
  });
  await page.goto(site+'/',{waitUntil:'domcontentloaded',timeout:25000});
  async function playAndVerify(cap,kind){
    const selected=await page.evaluate(async({cap,kind})=>{
      const {showPlayer}=await import('/player.js');
      showPlayer({title:'MOOSA real Node gateway '+kind,sources:[{
        ...cap,name:'Operator-approved video '+kind,quality:0
      }]});
      const picker=document.querySelector('#player-source');
      const options=picker.options.length;
      picker.value='0';
      picker.dispatchEvent(new Event('change',{bubbles:true}));
      return {options,selected:picker.value};
    },{cap,kind});
    assert.deepEqual(selected,{options:2,selected:'0'});
    await page.waitForFunction(()=>{
      const v=document.querySelector('#video');
      return !!v&&!v.paused&&!v.error&&v.currentTime>0.4&&
        v.videoWidth===320&&v.videoHeight===180&&v.readyState>=2;
    },null,{timeout:30000});
    console.log('MOOSA_REAL_NODE_MEDIA_GATEWAY_CHROME '+JSON.stringify({
      type:kind,decodedFrames:true,selectedSource:0,
      backendUpstreamRequests:upstreamCalls,
      sourceIsLocallyGeneratedFixture:true,cineStreamPlaying:false
    }));
  }
  await playAndVerify(mp4Cap,'mp4');
  await playAndVerify(hlsCap,'hls');
  assert.deepEqual(pageErrors,[]);
  assert.ok(upstreamCalls>=3,'Node must really fetch MP4, HLS manifest and segments');
  assert.ok(gateway.activeTokenCount()>2,'Server must mint new HLS child tokens');
  const old=await fetch(local+'/api/media/'+('a'.repeat(43)));
  assert.equal(old.status,404,'Unissued token must be unplayable');
  console.log('MOOSA_REAL_NODE_MEDIA_GATEWAY_SUCCESS '+JSON.stringify({
    mp4:true,hls:true,serverIssuedHlsChildTokens:true,
    upstreamCalls,rangeCalls,
    arbitraryUrlEndpointExists:false,sitePublished:false
  }));
}finally{
  await browser?.close().catch(()=>{});
  await new Promise(resolve=>srv.close(resolve));
}
