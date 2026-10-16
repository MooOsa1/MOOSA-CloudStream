import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from '../server.mjs';

async function runApiChecks(callback){
  const app=createServer();
  await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));
  try{
    await callback('http://127.0.0.1:'+app.address().port);
  }finally{
    await new Promise(resolve=>app.close(resolve));
  }
}

test('ChatGPT link trackers do not break public runtime status without making providers configurable',async()=>{
  await runApiChecks(async base=>{
    for(const query of [
      '', '?utm_source=chatgpt.com',
      '?utm_source=chatgpt.com&utm_medium=referral',
      '?utm_campaign=share&utm_content=link&utm_term=test'
    ]){
      const response=await fetch(base+'/api/web-native/status'+query);
      assert.equal(response.status,200,query);
      const result=await response.json();
      assert.equal(result.engine,'node_js');
      assert.equal(result.httpStremioSubtitles.supported,true);
      assert.equal(result.cloudstreamCs3Supported,false);
    }
    for(const query of [
      '?provider=https://evil.example',
      '?utm_source=chatgpt.com&provider=https://evil.example',
      '?url=https://evil.example',
      '?plugin=custom',
      '?source=remote'
    ]){
      const response=await fetch(base+'/api/web-native/status'+query);
      assert.equal(response.status,400,query);
      assert.equal((await response.json()).error,'No arbitrary provider parameters');
    }
  });
});

test('Tracking appended by chat links never breaks the exact-movie subtitle request',async()=>{
  await runApiChecks(async base=>{
    for(const query of [
      '?media=movie&id=45745',
      '?media=movie&id=45745&utm_source=chatgpt.com',
      '?media=movie&id=45745&utm_medium=referral&utm_campaign=share',
    ]){
      const response=await fetch(base+'/api/stremio-subtitles'+query);
      assert.equal(response.status,200,query);
      assert.deepEqual(await response.json(),{
        subtitles:[],active:false,count:0
      });
    }
    for(const query of [
      '?media=movie&id=45745&utm_source=chatgpt.com&provider=https://evil.example',
      '?media=movie&id=45745&url=https://evil.example/movie.mp4',
      '?media=movie&id=45745&id=99999&utm_source=chatgpt.com',
      '?media=tv&id=45745&season=1',
      '?media=movie%26id=45745&utm_source=chatgpt.com'
    ]){
      const response=await fetch(base+'/api/stremio-subtitles'+query);
      assert.equal(response.status,400,query);
      const payload=await response.json();
      assert.ok(['Unsupported subtitle parameters','Invalid movie or episode'].includes(payload.error));
    }
  });
});
