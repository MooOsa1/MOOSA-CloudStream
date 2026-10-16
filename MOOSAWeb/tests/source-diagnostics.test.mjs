import test from 'node:test';
import assert from 'node:assert/strict';
import {describeSourceResolution} from '../public/source-diagnostics.js';
import {createServer} from '../server.mjs';

test('empty results explain missing CloudStream Android runtime truthfully',()=>{
  const message=describeSourceResolution({sources:[],cloudstreamEngineConfigured:false});
  assert.match(message,/غير مشغّل/);
  assert.match(message,/CineStream وStreamPlay/);
  assert.doesNotMatch(message,/بحثنا في|تم تشغيل/);
});

test('configured-but-unverified runtime does not promise reachable providers',()=>{
  const msg=describeSourceResolution({sources:[],cloudstreamEngineConfigured:true});
  assert.match(msg,/غير مؤكدة/);
});

test('declared sources are not claimed to be browser-tested',()=>{
  const msg=describeSourceResolution({
    cloudstreamEngineConfigured:false,
    sources:[{url:'https://licensed.example/video.mp4'}]
  });
  assert.match(msg,/مصدر مشاهدة/);
  assert.match(msg,/صلاحية الرابط/);
});

test('unknown runtime status falls back to a non-misleading no-sources notice',()=>{
  assert.match(describeSourceResolution(null),/ما حصلنا رابط/);
});

test('public configuration explicitly reports whether a remote CloudStream executor is configured',async()=>{
  const previousURL=process.env.CLOUDSTREAM_RUNTIME_URL;
  const previousToken=process.env.CLOUDSTREAM_RUNTIME_TOKEN;
  delete process.env.CLOUDSTREAM_RUNTIME_URL;
  delete process.env.CLOUDSTREAM_RUNTIME_TOKEN;
  const app=createServer();
  try{
    await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));
    const response=await fetch('http://127.0.0.1:'+app.address().port+'/api/config');
    assert.equal(response.status,200);
    const body=await response.json();
    assert.equal(body.cloudstreamEngineConfigured,false);
    assert.equal(body.automaticSources,true);
  }finally{
    await new Promise(resolve=>app.close(resolve));
    if(previousURL===undefined)delete process.env.CLOUDSTREAM_RUNTIME_URL;
    else process.env.CLOUDSTREAM_RUNTIME_URL=previousURL;
    if(previousToken===undefined)delete process.env.CLOUDSTREAM_RUNTIME_TOKEN;
    else process.env.CLOUDSTREAM_RUNTIME_TOKEN=previousToken;
  }
});
