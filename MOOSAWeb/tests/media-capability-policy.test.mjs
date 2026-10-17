import test from 'node:test';
import assert from 'node:assert/strict';
import {trustedMediaCapability,playableMediaSource}
  from '../public/media-capability-policy.js';
const host='https://moosa-web.onrender.com';
const token='a'.repeat(32);
const path=host+'/api/media/'+token;

test('server-issued SAME-ORIGIN opaque HLS capability is accepted with type',()=>{
  assert.deepEqual(trustedMediaCapability({
    gatewayIssued:true,url:path,format:'m3u8'
  },host),{url:path,format:'hls'});
  assert.equal(playableMediaSource({
    gatewayIssued:true,url:path,format:'video/mp4'
  },host).format,'mp4');
  assert.equal(playableMediaSource({
    gatewayIssued:true,url:path,format:'webm'
  },host).format,'webm');
});

test('never accept a bare extensionless plugin URL or untrusted capability',()=>{
  for(const source of [
    {url:path,format:'hls'},
    {gatewayIssued:true,url:path,format:'mkv'},
    {gatewayIssued:true,url:path,format:'html'},
    {gatewayIssued:true,url:path+'?url=https://evil.example/media.mp4',format:'hls'},
    {gatewayIssued:true,url:path+'/anything',format:'hls'},
    {gatewayIssued:true,url:'https://somewhere.example/api/media/'+token,format:'hls'},
    {gatewayIssued:true,url:host+'/api/media/tiny',format:'hls'},
    {gatewayIssued:true,url:host.replace('https','http')+'/api/media/'+token,format:'hls'},
  ]){
    assert.equal(trustedMediaCapability(source,host),null,JSON.stringify(source));
  }
  assert.equal(playableMediaSource({url:path,format:'hls'},host),null);
});

test('old direct sources stay unchanged; fake embeds stay blocked',()=>{
  assert.deepEqual(playableMediaSource({
    url:'https://cdn.example.org/movie.m3u8?approved=1'
  },host),{url:'https://cdn.example.org/movie.m3u8?approved=1',format:'hls'});
  assert.equal(playableMediaSource({
    url:'https://site.example/embed/movie/tt1234'
  },host),null);
  assert.equal(playableMediaSource({
    url:'https://another.example/movie.mkv'
  },host),null);
});

test('mismatched gateway origin and credential-bearing URLs rejected',()=>{
  for(const origin of ['http://moosa-web.onrender.com','https://user@moosa-web.onrender.com',
    'https://moosa-web.onrender.com:8443']){
    assert.equal(trustedMediaCapability({gatewayIssued:true,url:path,format:'mp4'},origin),null);
  }
});
