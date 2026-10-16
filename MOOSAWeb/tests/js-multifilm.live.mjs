/**
 * Read-only external probe: live HTML endpoint -> locally pinned JS worker
 * -> Wikimedia Commons metadata -> direct CDN HEAD with CORS/MIME.
 * Never download a movie, execute a third-party provider or imply Safari decode.
 */
import assert from 'node:assert/strict';
import {createServer} from '../server.mjs';
const ids=['big-buck-bunny','sintel','tears-of-steel','elephants-dream'];
const titles=['Big Buck Bunny','Sintel','Tears of Steel','Elephants Dream'];
const server=createServer();
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
try{
  const base='http://127.0.0.1:'+server.address().port;
  const findings=[];
  // Sequential probes avoid hammering the public Commons API.
  for(let i=0;i<ids.length;i++){
    const t=Date.now();
    const res=await fetch(base+'/api/js-engine/demo?id='+ids[i],{
      signal:AbortSignal.timeout(17000)
    });
    assert.equal(res.status,200);
    const result=await res.json();
    assert.equal(result.filmId,ids[i]);
    assert.equal(result.sample,titles[i]);
    assert.equal(result.engine,'reviewed_js_worker');
    assert.equal(result.originalCloudStreamPlugins,false);
    assert.equal(result.count,result.sources.length);
    assert.ok(result.sources.every(s=>
      new URL(s.url).hostname==='upload.wikimedia.org'&&
      s.preflight?.verified===true));
    findings.push({
      id:ids[i],state:result.state,count:result.count,
      qualities:result.sources.map(s=>s.quality),
      elapsedMs:Date.now()-t
    });
  }
  console.log('MULTIFILM_LIVE_JS_RESULTS '+JSON.stringify(findings));
  const anyExtra=findings.slice(1).filter(x=>x.count>0);
  console.log('ADDITIONAL_LICENSED_TITLES_VERIFIED '+anyExtra.length+'/3');
  const invalid=await fetch(base+'/api/js-engine/demo?id=sintel&url=https://evil.example/');
  assert.equal(invalid.status,400);
}finally{
  await new Promise(resolve=>server.close(resolve));
}
