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
    assert.equal(body.verifiedCineStreamPilotReady,false,
      'Ordinary public site must not advertise an unapproved CineStream pilot');
  }finally{
    await new Promise(resolve=>app.close(resolve));
    if(previousURL===undefined)delete process.env.CLOUDSTREAM_RUNTIME_URL;
    else process.env.CLOUDSTREAM_RUNTIME_URL=previousURL;
    if(previousToken===undefined)delete process.env.CLOUDSTREAM_RUNTIME_TOKEN;
    else process.env.CLOUDSTREAM_RUNTIME_TOKEN=previousToken;
  }
});

test('reviewed CineStream lookup time activates only when gateway and both operator flags are set',async()=>{
  const keys=['CINESTREAM_MULTIFILM_PILOT','CINESTREAM_MULTIFILM_RIGHTS_APPROVED'];
  const saved=Object.fromEntries(keys.map(x=>[x,process.env[x]]));
  const gateway={handle:()=>{throw Error('Gateway endpoint not requested')}};
  let server;
  try{
    process.env.CINESTREAM_MULTIFILM_PILOT='1';
    process.env.CINESTREAM_MULTIFILM_RIGHTS_APPROVED='1';
    server=createServer({mediaGateway:gateway});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const url='http://127.0.0.1:'+server.address().port+'/api/config';
    assert.equal((await (await fetch(url)).json()).verifiedCineStreamPilotReady,true);
    delete process.env.CINESTREAM_MULTIFILM_RIGHTS_APPROVED;
    assert.equal((await (await fetch(url)).json()).verifiedCineStreamPilotReady,false);
    process.env.CINESTREAM_MULTIFILM_RIGHTS_APPROVED='1';
    delete process.env.CINESTREAM_MULTIFILM_PILOT;
    assert.equal((await (await fetch(url)).json()).verifiedCineStreamPilotReady,false);
  }finally{
    if(server)await new Promise(resolve=>server.close(resolve));
    for(const [k,v] of Object.entries(saved))
      if(v===undefined)delete process.env[k];else process.env[k]=v;
  }
});
