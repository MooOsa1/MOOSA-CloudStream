import assert from 'node:assert/strict';
import {createHeadlessCompatBridge} from './headless-http.mjs';

/**
 * Exercises HTTP -> Node -> real converted CineStream cs3 JVM -> JSON.
 * Runs only within isolated GitHub CI; does not contact media stream hosts.
 */
const [runtime,libraryJar,pluginJar]=process.argv.slice(2);
assert.ok(runtime&&libraryJar&&pluginJar);
const bridge=createHeadlessCompatBridge({jvmRuntime:runtime,libraryJar,pluginJar,
  timeoutMs:25000});
const {origin}=await bridge.listen();
try{
  const get=async(path)=>await fetch(origin+path);
  const post=async(json)=>await fetch(origin+'/api/metadata',{
    method:'POST',headers:{'content-type':'application/json'},
    body:JSON.stringify(json)
  });
  const health=await get('/health');
  assert.equal(health.status,200);
  assert.equal((await health.json()).scope,'experimental-licensed-metadata-only');
  assert.equal((await get('/api/metadata')).status,404);
  assert.equal((await post({imdb:'tt1375666',media:'movie'})).status,422);
  assert.equal((await post({imdb:'tt1727587',media:'movie',plugin:'https://x.test/test.cs3'})).status,422);
  assert.equal((await post({imdb:'tt1727587',media:'tv'})).status,422);
  const ready=await post({imdb:'tt1727587',media:'movie'});
  const data=await ready.json();
  assert.equal(ready.status,200,'JVM metadata bridge failed: '+JSON.stringify(data));
  assert.deepEqual(data,{
    provider:'CineStream',movie:{imdb:'tt1727587',title:'Sintel'},
    metadataVerified:true,playbackVerified:false,streams:[]
  });
  console.log('MOOSA_NODE_JVM_BRIDGE status=passed mode=loopback-only type=exact_licensed_metadata provider=CineStream streams=0 playbackVerified=false');
}finally{await bridge.close()}
