import test from 'node:test';
import assert from 'node:assert/strict';
import {gateway,exactFilm,approvedUrls,executeBootProbe,bootProbeStatus} from '../jvm-pilot/index.mjs';
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
