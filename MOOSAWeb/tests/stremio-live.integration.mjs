/**
 * Real external Wikimedia licensed-source readiness through the genuine
 * standalone MOOSA Web HTTP Stremio addon. No TMDB API key or Android pairing.
 */
import assert from 'node:assert/strict';
import {createServer} from '../server.mjs';

const app=createServer();
await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));
const base='http://127.0.0.1:'+app.address().port;
try{
  const manifestResp=await fetch(base+'/api/stremio/manifest.json');
  assert.equal(manifestResp.status,200);
  const manifest=await manifestResp.json();
  assert.ok(manifest.types.includes('movie'));
  assert.ok(manifest.resources.some(r=>r.name==='stream'));
  const ids=[
    ['tt1254207','Big Buck Bunny'],
    ['tt1727587','Sintel'],
    ['tt2285752','Tears of Steel'],
    ['tt0807840','Elephants Dream']
  ];
  const results=await Promise.all(ids.map(async([id,title])=>{
    const res=await fetch(base+'/api/stremio/stream/movie/'+id+'.json',
      {signal:AbortSignal.timeout(18000)});
    assert.equal(res.status,200,'Stremio route '+id);
    const body=await res.json();
    assert.ok(Array.isArray(body.streams));
    for(const stream of body.streams){
      assert.ok(stream.url.startsWith('https://upload.wikimedia.org/'));
      assert.ok(/\b(?:720|800|872|1080|1744|2160|2250)p\b/.test(stream.title),
        'Expected declared media height, not an arbitrary movie filename');
      assert.ok(!stream.infoHash && !stream.magnet && !stream.headers);
    }
    return {id,title,count:body.streams.length,
      variants:body.streams.map(s=>s.title.slice(0,95))};
  }));
  assert.ok(results.filter(x=>x.count>0).length>=2,
    'External Commons metadata temporarily unavailable for at least two films');
  const unknown=await fetch(base+'/api/stremio/stream/movie/tt99999999.json');
  assert.deepEqual(await unknown.json(),{streams:[]});
  console.log('LIVE_CINESTREAM_HTTP_INTEROP '+JSON.stringify(results));
}finally{
  await new Promise(resolve=>app.close(resolve));
}
