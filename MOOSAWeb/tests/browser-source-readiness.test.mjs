import test from 'node:test';
import assert from 'node:assert/strict';
import {inspectBrowserSource,checkApprovedSources} from '../browser-source-readiness.mjs';

const good={url:'https://upload.wikimedia.org/wikipedia/commons/a/ab/Big_Buck_Bunny_alt.webm',
  name:'CC BY verified Commons video',quality:720};
const response=(status,mime='video/webm',cors='*',size='93192761')=>
  new Response(null,{status,headers:{
    'Content-Type':mime,'Access-Control-Allow-Origin':cors,
    'Content-Length':size
  }});

test('licensed/allowlisted WebM HEAD 200+video+CORS is eligible',async()=>{
  let request;
  const result=await inspectBrowserSource(good,{fetchImpl:async(url,opt)=>{
    request={url,opt};
    return response(200);
  }});
  assert.equal(request.opt.method,'HEAD');
  assert.equal(request.opt.redirect,'manual');
  assert.equal(request.opt.headers.Origin,'https://moosa-web.onrender.com');
  assert.equal(result.ready,true);
  assert.equal(result.mime,'video/webm');
  assert.equal(result.bytes,93192761);
});
test('referer-restricted CDN cannot be preflighted even if it claims to have mp4',async()=>{
  let called=0;
  const result=await inspectBrowserSource({
    url:'https://bcdnxw.hakunaymatata.com/file.mp4',quality:1080
  },{fetchImpl:async()=>{called++;return response(200,'video/mp4')}});
  assert.equal(result.ready,false);
  assert.equal(result.reason,'unapproved_media_host_or_format');
  assert.equal(called,0);
});
test('429, HTML, missing CORS, unsupported HEAD, redirect must be rejected',async()=>{
  const tests=[
    [response(429,'text/html',null),'rate_limited'],
    [response(200,'text/html','*'),'not_media'],
    [response(200,'video/webm','https://other.example'),'browser_origin_not_allowed'],
    [response(405,'text/plain','*'),'head_unsupported'],
    [response(302,'text/html','*'),'redirect_blocked']
  ];
  for(const [res,reason] of tests){
    const result=await inspectBrowserSource(good,{fetchImpl:async()=>res});
    assert.equal(result.ready,false);
    assert.equal(result.reason,reason);
  }
});
test('untrusted CDN, localhost, userinfo, IPs, unknown format are never fetched',async()=>{
  const invalid=[
    'http://upload.wikimedia.org/video.webm',
    'https://user:pass@upload.wikimedia.org/video.webm',
    'https://127.0.0.1/private.mp4',
    'https://upload.wikimedia.org/video.mkv',
    'https://upload.wikimedia.org/some-html-page',
    'https://upload.wikimedia.org.evil.test/a.mp4',
    'https://[::1]/a.mp4'
  ];
  let calls=0;
  for(const url of invalid){
    const r=await inspectBrowserSource({...good,url},{
      fetchImpl:async()=>{calls++;return response(200)}
    });
    assert.equal(r.ready,false,url);
  }
  assert.equal(calls,0);
});
test('HLS manifest MIME+Origin grants readiness but text/html does not',async()=>{
  const source={...good,url:'https://upload.wikimedia.org/playlist.m3u8'};
  const ok=await inspectBrowserSource(source,{fetchImpl:async()=>response(200,'application/vnd.apple.mpegurl','*')});
  assert.equal(ok.ready,true);
  assert.equal(ok.format,'m3u8');
  const rejected=await inspectBrowserSource(source,{fetchImpl:async()=>response(200,'text/plain','*')});
  assert.equal(rejected.ready,false);
});
test('batch separates approved sources and rejected statuses without leaking signed URLs',async()=>{
  const bad={...good,url:'https://upload.wikimedia.org/protected.mp4?token=private123',quality:1080};
  const r=await checkApprovedSources([good,bad],{fetchImpl:async(url)=>{
    return url.includes('protected')?response(429,'text/html',''):response(200);
  }});
  assert.equal(r.ready.length,1);
  assert.equal(r.ready[0].preflight.verified,true);
  assert.equal(r.rejected.length,1);
  assert.equal(r.rejected[0].reason,'rate_limited');
  assert.equal(JSON.stringify(r.rejected).includes('private123'),false);
});
test('unresponsive origin produces bounded timeout / network_error and no accepted sources',async()=>{
  const r=await checkApprovedSources([good],{fetchImpl:async()=>{
    throw Object.assign(new Error('network unreachable'),{name:'TimeoutError'});
  }});
  assert.deepEqual(r.ready,[]);
  assert.equal(r.rejected[0].reason,'timeout');
});
