import test from 'node:test';
import assert from 'node:assert/strict';
import {gateway,exactFilm,approvedUrls,executeBootProbe,bootProbeStatus,makeSharedExtraction,parsePrivateCandidates} from '../jvm-pilot/index.mjs';
const film={media:'movie',tmdbId:100,imdbId:'tt5719076',
  title:'Dead Night',year:2018};
test('only exact movie is accepted by JVM executor',()=>{
  assert.equal(exactFilm(film),true);
  assert.equal(exactFilm({...film,title:'Wrong movie'}),false);
  assert.equal(exactFilm({...film,imdbId:'tt1234567'}),false);
  assert.equal(exactFilm({...film,media:'tv',season:1,episode:1}),false);
});
test('only allowlisted direct HTTPS video URLs survive callback sanitization',()=>{
  assert.deepEqual(approvedUrls([
    'https://img1.hscow.com/film.m3u8',
    'https://img1.hscow.com/film.m3u8',
    'http://img1.hscow.com/film.mp4',
    'https://img1.hscow.com.bad.com/film.m3u8',
    'https://user@img1.hscow.com/film.mp4',
    'https://img1.hscow.com/video.html',
    'https://img1.hscow.com/other.mp4'
  ],['img1.hscow.com']),[
    'https://img1.hscow.com/film.m3u8',
    'https://img1.hscow.com/other.mp4'
  ]);
});
test('unauthorized clients, wrong movie, and missing distribution approval are rejected',async()=>{
  const secret='long-test-token-for-internal-authorization-123';
  let invoked=0;
  const server=gateway({token:secret,hosts:['img1.hscow.com'],
    extract:async()=>{invoked++;return {state:'original_callbacks_returned',
      urls:['https://img1.hscow.com/film.m3u8']};}});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base='http://127.0.0.1:'+server.address().port;
  try{
    const health=await (await fetch(base+'/healthz')).json();
    assert.equal(health.status,'ok');
    assert.equal(health.distributionEnabled,false);
    const noToken=await fetch(base+'/v1/resolve',{
      method:'POST',body:JSON.stringify(film)});
    assert.equal(noToken.status,401);
    const request=body=>fetch(base+'/v1/resolve',{
      method:'POST',headers:{Authorization:'Bearer '+secret},
      body:JSON.stringify(body)});
    assert.equal((await request({...film,imdbId:'tt9999999'})).status,400);
    assert.equal(invoked,0);
    const allowed=await request(film);
    assert.equal(allowed.status,200);
    const response=await allowed.json();
    assert.deepEqual(response.sources,[]);
    assert.equal(response.distributionEnabled,false);
    assert.equal(invoked,1);
  }finally{
    await new Promise(resolve=>server.close(resolve));
  }
});

test('boot probe reports bounded source count but never signed CDN links',async()=>{
  await executeBootProbe(async()=>({state:'original_callbacks_returned',
    urls:['https://signed.cdn.example/file.m3u8?token=secret']}));
  const d=bootProbeStatus();
  assert.equal(d.state,'complete');
  assert.equal(d.candidateCount,1);
  assert.equal(d.result,'original_callbacks_returned');
  assert.ok(!JSON.stringify(d).includes('secret'));
});

test('JVM runtime files are not stored under Render temporary filesystem',async()=>{
  const m=await import('../jvm-pilot/index.mjs');
  assert.equal(typeof m.extractOriginal,'function');
});

test('separate slow Render extraction probe function exists for free CPU tier',async()=>{
  const {extractOriginal}=await import('../jvm-pilot/index.mjs');
  assert.equal(typeof extractOriginal,'function');
});

test('HTTP first-segment probe sees only approved HLS hosts and reports counts',async()=>{
  let called=0;
  const {executeBootProbe,bootProbeStatus}=await import('../jvm-pilot/index.mjs');
  await executeBootProbe(async()=>({
    state:'original_callbacks_returned',
    urls:[
      'https://img1.hscow.com/proof.m3u8',
      'https://unknown.example/blocked.m3u8'
    ],
    candidates:[
      {url:'https://img1.hscow.com/proof.m3u8',
        provider:'Castle',requiresHeaders:false},
      {url:'https://unknown.example/blocked.m3u8',
        provider:'Other provider',requiresHeaders:false},
      {url:'https://img1.hscow.com/protected.m3u8',
        provider:'Castle',requiresHeaders:true}
    ]
  }),{
    verifySegment:true,allowedHosts:['img1.hscow.com'],
    segmentProbe:async(url)=>{called++;assert.equal(new URL(url).hostname,'img1.hscow.com');
      return {ok:true,quality:720};}
  });
  const state=bootProbeStatus();
  assert.equal(called,2);
  assert.equal(state.mediaProbe.checked,2);
  assert.equal(state.mediaProbe.firstSegmentHttpVerified,2);
  // Both streams served bytes without adding Castle's native Referer,
  // though one declares it in its original CloudStream extractor link.
  assert.equal(state.mediaProbe.noCustomHeaderHttpVerified,1);
  assert.equal(state.mediaProbe.declaredHeaderButHeaderlessHttpVerified,1);
  assert.equal(state.mediaProbe.castleHeaderlessHttpVerified,2);
  assert.equal(state.castleCount,2);
  assert.equal(state.castleBrowserDirectCount,1);
  assert.equal(state.headerRequiredCount,1);
  assert.equal(JSON.stringify(state).includes('proof.m3u8'),false);
});

test('cold boot and authenticated Play reuse the same short-lived JVM extraction',async()=>{
  let calls=0,time=50;
  const fetcher=makeSharedExtraction(async()=>{
    calls++;
    await new Promise(resolve=>setTimeout(resolve,12));
    return {state:'original_callbacks_returned',
      urls:['https://licensed.example/feature.m3u8?ephemeral=1']};
  },{ttlMs:120000,clock:()=>time});
  const [a,b,c]=await Promise.all([fetcher(),fetcher(),fetcher()]);
  assert.equal(calls,1);
  assert.equal(a,b);
  assert.equal(b,c);
  const warm=await fetcher();
  assert.equal(warm.state,'original_callbacks_returned');
  assert.equal(calls,1);
  time+=120001;
  await fetcher();
  assert.equal(calls,2);
});
test('failed extractions are not cached',async()=>{
  let count=0;
  const shared=makeSharedExtraction(async()=>{
    count++;
    return {state:'no_verified_upstream_identity',urls:[]};
  });
  await shared();
  await shared();
  assert.equal(count,2);
});

test('original .cs3 callback metadata marks Referer/Cookie links as not browser direct',()=>{
  const record=(url,source,restricted)=>
    Buffer.from(url).toString('base64')+'\t'+
    Buffer.from(source).toString('base64')+'\t'+(restricted?'1':'0');
  const items=parsePrivateCandidates([
    record('https://cinema.example/movie.m3u8','Castle',true),
    record('https://cinema.example/public.mp4','Castle',false),
    record('https://cinema.example/other.m3u8','Other provider',false),
    record('https://cinema.example/public.mp4','Castle',true)
  ].join('\n'));
  assert.equal(items.length,3);
  assert.equal(items.filter(x=>x.provider==='Castle'&&!x.requiresHeaders).length,0);
  assert.ok(items.some(x=>x.provider==='Other provider'&&!x.requiresHeaders));
  assert.deepEqual(parsePrivateCandidates('invalid-data'),[]);
});
test('unlicensed callbacks cannot be published even when an extractor asserts browser compatibility',async()=>{
  let called=false;
  const server=gateway({token:'unit-test-private-token-keep-long-enough-12',
    hosts:['cinema.example'],
    extract:async()=>{
      called=true;
      return {state:'original_callbacks_returned',
        urls:['https://cinema.example/movie.m3u8'],
        candidates:[{url:'https://cinema.example/movie.m3u8',provider:'Castle',requiresHeaders:false}]};
    }});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const response=await fetch('http://127.0.0.1:'+server.address().port+'/v1/resolve',{
      method:'POST',headers:{Authorization:'Bearer unit-test-private-token-keep-long-enough-12'},
      body:JSON.stringify({media:'movie',title:'Dead Night',year:2018,
        imdbId:'tt5719076',tmdbId:123})
    });
    assert.equal(response.status,200);
    const d=await response.json();
    assert.ok(called);
    assert.deepEqual(d.sources,[]);
    assert.equal(d.distributionEnabled,false);
  }finally{await new Promise(resolve=>server.close(resolve));}
});
