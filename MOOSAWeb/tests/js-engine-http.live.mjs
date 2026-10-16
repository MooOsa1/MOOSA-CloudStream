/**
 * Full live proof of our existing Node HTTP server talking to its real
 * independent locally reviewed JavaScript plugin process:
 *
 * GET /api/js-engine/demo -> child Nuvio-style getStreams ->
 * actual Wikimedia CC file -> browser CORS/MIME preflight -> JSON.
 *
 * No Android or VPN, no commercial movie servers, no video download.
 */
import assert from 'node:assert/strict';
import {createServer} from '../server.mjs';
const server=createServer();
const port=await new Promise(resolve=>server.listen(0,'127.0.0.1',
  ()=>resolve(server.address().port)));
try{
  const base='http://127.0.0.1:'+port;
  const t=Date.now();
  const res=await fetch(base+'/api/js-engine/demo',{
    signal:AbortSignal.timeout(18000)
  });
  assert.equal(res.status,200);
  const data=await res.json();
  console.log('NODE_HTTP_JS_ENGINE_LIVE',JSON.stringify({
    httpStatus:res.status,
    engine:data.engine,
    state:data.state,
    sources:data.count,
    qualities:data.sources?.map(s=>s.quality),
    mime:data.sources?.map(s=>s.preflight?.mime),
    durationMs:Date.now()-t,
    originalPluginsRunning:data.originalCloudStreamPlugins
  }));
  assert.equal(data.engine,'reviewed_js_worker');
  assert.equal(data.originalCloudStreamPlugins,false);
  assert.equal(data.state,'browser_preflight_passed');
  assert.ok(data.count>=1,'Must find at least one CC-BY licensed video');
  assert.ok(data.sources.some(s=>s.quality===720&&
    s.preflight?.verified===true&&s.preflight.mime==='video/webm'));
  assert.ok(data.sources.every(s=>new URL(s.url).hostname==='upload.wikimedia.org'));
  const replay=await fetch(base+'/api/js-engine/demo');
  const again=await replay.json();
  assert.deepEqual(again,data,'Second request reuses stable cache');
  // Keep the user-facing existing free-film route working. Whenever the
  // JS verification completed quickly, it may promote an exactly matched
  // Commons file; otherwise metadata-verified sources remain available.
  const filmResponse=await fetch(base+'/api/free-film?id=big-buck-bunny',{
    signal:AbortSignal.timeout(14000)
  });
  assert.equal(filmResponse.status,200);
  const film=await filmResponse.json();
  assert.equal(film.title,'Big Buck Bunny');
  assert.ok(film.count>=1);
  assert.ok(['commons_metadata','reviewed_js_worker_preflight'].includes(film.sourceEngine));
  assert.ok(film.sources.every(s=>new URL(s.url).hostname==='upload.wikimedia.org'));
  console.log('FREE_FILM_JS_ENGINE_FALLBACK_OK '+JSON.stringify({
    count:film.count,sourceEngine:film.sourceEngine
  }));
  const bad=await fetch(base+'/api/js-engine/demo?url=https://attacker.example/movie.mp4');
  assert.equal(bad.status,400,'Public request cannot select arbitrary media URLs');
  console.log('NODE_HTTP_JS_ENGINE_LIVE_SUCCESS: real site HTTP -> JS worker -> verified CDN source (no Android)');
} finally {
  await new Promise(resolve=>server.close(resolve));
}
