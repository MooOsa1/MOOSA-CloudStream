import test from 'node:test';
import assert from 'node:assert/strict';
import {probeVidlinkContainerBytes} from '../vidlink-multifilm-candidates.mjs';

test('arbitrary movie candidate can verify real MP4 bytes without ever publishing playback URLs',async()=>{
  const calls=[];
  const get=async(raw,init={})=>{
    const u=new URL(raw);calls.push(u.hostname);
    if(u.hostname==='enc-dec.app')return Response.json({result:'abc123'});
    if(u.hostname==='vidlink.pro')return Response.json({stream:{qualities:{
      '1080p':{url:'https://hcdn3.hakunaymatata.com/title1080.mp4',type:'mp4'},
      '720p':{url:'https://bcdn.hakunaymatata.com/title720.mp4',type:'mp4'},
      '360p':{url:'https://bad.example/no.mp4',type:'mp4'}
    }}});
    assert.equal(init.headers.Range,'bytes=0-4095');
    const data=Buffer.concat([Buffer.from([0,0,0,24]),Buffer.from('ftypisom'),Buffer.alloc(30)]);
    return new Response(data,{status:206,headers:{'content-type':'video/mp4'}});
  };
  const out=await probeVidlinkContainerBytes({tmdbId:27205,fetchImpl:get,maxVariants:2});
  assert.equal(out.mp4ContainersVerified,2);
  assert.deepEqual(out.verifiedQualityLabels,[1080,720]);
  assert.equal(out.playbackVerified,false);
  assert.equal(out.rightsVerified,false);
  assert.doesNotMatch(JSON.stringify(out),/hakunaymatata|\.mp4|title1080/);
  assert.deepEqual(calls,['enc-dec.app','vidlink.pro',
    'hcdn3.hakunaymatata.com','bcdn.hakunaymatata.com']);
});
test('HTML masquerading as MP4 can never become a verified video source',async()=>{
  const get=async(raw)=>{
    const u=new URL(raw);
    if(u.hostname==='enc-dec.app')return Response.json({result:'abc'});
    if(u.hostname==='vidlink.pro')return Response.json({stream:{qualities:{
      '1080p':{url:'https://hcdn3.hakunaymatata.com/false.mp4'}
    }}});
    return new Response('<html>fake movie</html>',{status:200,headers:{'content-type':'text/html'}});
  };
  const out=await probeVidlinkContainerBytes({tmdbId:155,fetchImpl:get});
  assert.equal(out.mp4ContainersVerified,0);
  assert.equal(out.status,'no_valid_media_bytes');
  assert.equal(out.playbackVerified,false);
});
