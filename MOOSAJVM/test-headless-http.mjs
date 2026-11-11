#!/usr/bin/env node
/**
 * Real loopback integration smoke: Node HTTP -> child JVM -> SHA256 verified
 * converted original CineStreamProvider.load for licensed Sintel -> Node JSON.
 *
 * No access to media URLs, playback or arbitrary plugins; refuses any other
 * movie. Disposable CI-only execution, never a public Render endpoint.
 */
import assert from 'node:assert/strict';
import {createHeadlessCompatBridge} from './headless-http.mjs';

const [, ,jvmRuntime,libraryJar,pluginJar]=process.argv;
if(![jvmRuntime,libraryJar,pluginJar].every(x=>typeof x==='string'&&x.length>0)){
  throw Error('usage: node MOOSAJVM/test-headless-http.mjs <jvmRuntimeClasspath> <officialJvmJar> <verifiedPluginJar>');
}
const bridge=createHeadlessCompatBridge({jvmRuntime,libraryJar,pluginJar,timeoutMs:21000});
let origin;
try{
  ({origin}=await bridge.listen());
  assert.match(origin,/^http:\/\/127\.0\.0\.1:\d+$/);
  const health=await fetch(origin+'/health');
  assert.equal(health.status,200);
  assert.equal((await health.json()).scope,'experimental-licensed-metadata-only');

  const wrong=await fetch(origin+'/api/metadata',{
    method:'POST',headers:{'content-type':'application/json'},
    body:JSON.stringify({imdb:'tt0000000',media:'movie'})
  });
  assert.equal(wrong.status,422,'Unapproved catalog must be denied before JVM execution');
  assert.equal((await wrong.json()).error,'unsupported_media');

  const wrongMethod=await fetch(origin+'/api/metadata');
  assert.equal(wrongMethod.status,404);
  const missingField=await fetch(origin+'/api/metadata',{
    method:'POST',headers:{'content-type':'application/json'},
    body:JSON.stringify({imdb:'tt1727587',media:'movie',class:'java.lang.Runtime'})
  });
  assert.equal(missingField.status,422,'User must not control Java class loading');

  const started=Date.now();
  const response=await fetch(origin+'/api/metadata',{
    method:'POST',headers:{'content-type':'application/json'},
    body:JSON.stringify({imdb:'tt1727587',media:'movie'}),
    signal:AbortSignal.timeout(24000)
  });
  const result=await response.json();
  assert.equal(response.status,200,'Real original provider must finish behind loopback adapter: '+JSON.stringify(result));
  assert.deepEqual(result,{
    provider:'CineStream',
    movie:{imdb:'tt1727587',title:'Sintel'},
    metadataVerified:true,playbackVerified:false,streams:[]
  });
  console.log('MOOSA_HEADLESS_E2E status=verified original=converted_cinestream '+
    'http=200 exactImdb=tt1727587 playlistCount=0 playbackVerified=false '+
    'durationMs='+String(Date.now()-started));
}finally{
  await bridge.close();
}
