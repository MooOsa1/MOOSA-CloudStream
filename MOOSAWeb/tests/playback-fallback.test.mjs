import test from 'node:test';
import assert from 'node:assert/strict';
import {nextPlaybackSource} from '../public/playback-fallback.js';
const sources=[
  {url:'https://video.example/original.webm',quality:1080},
  {url:'https://video.example/compatible.webm',quality:1080,codecOptimized:true},
  {url:'https://video.example/safari.mp4',quality:720}
];
test('Safari-like capabilities prioritize supported MP4 over incompatible WebM',()=>{
  const next=nextPlaybackSource(sources,new Set([sources[0].url]),
    mime=>mime==='video/mp4'?'probably':'');
  assert.equal(next.index,2);
});
test('Compatible 4:2:0 transcode beats nonoptimized original when formats are supported',()=>{
  const next=nextPlaybackSource(sources,new Set([sources[0].url]),
    mime=>mime.startsWith('video/webm')?'maybe':'');
  assert.equal(next.index,1);
});
test('Retries never revisit a failed URL and terminate after the limit',()=>{
  assert.equal(nextPlaybackSource(sources,new Set(sources.map(s=>s.url))),null);
  assert.equal(nextPlaybackSource(sources,new Set([sources[0].url]),()=>'',1),null);
});
test('Reject insecure and malformed retry URLs, even if passed by bad provider',()=>{
  const next=nextPlaybackSource([
    {url:'http://evil.example/film.mp4'},
    {url:'https://evil.example@localhost/film.mp4'},
    {url:'javascript:alert(1)'},
    {url:'https://licensed.example/movie.mp4'}
  ],new Set(),()=>'' );
  assert.equal(next.source.url,'https://licensed.example/movie.mp4');
});

const mediaOrigin='https://moosa-web.onrender.com';
const capabilityToken=c=>mediaOrigin+'/api/media/'+c.repeat(43);
test('opaque same-origin media capability automatically falls back from failed 1080p to 720p',()=>{
  const candidates=[
    {url:capabilityToken('A'),format:'video/mp4',gatewayIssued:true,quality:0,name:'1080p measured'},
    {url:capabilityToken('B'),format:'video/mp4',gatewayIssued:true,quality:0,name:'720p measured'},
    {url:capabilityToken('C'),format:'video/mp4',gatewayIssued:true,quality:0,name:'480p measured'}
  ];
  const attempted=new Set([candidates[0].url]);
  const next=nextPlaybackSource(candidates,attempted,
    mime=>mime==='video/mp4'?'probably':'',4,mediaOrigin);
  assert.equal(next?.index,1);
  attempted.add(next.source.url);
  assert.equal(nextPlaybackSource(candidates,attempted,
    mime=>mime==='video/mp4'?'probably':'',4,mediaOrigin)?.index,2);
  attempted.add(candidates[2].url);
  assert.equal(nextPlaybackSource(candidates,attempted,()=>'',4,mediaOrigin),null);
});
test('opaque source fallback refuses forged, foreign, expired-format and altered tokens',()=>{
  const valid={url:capabilityToken('V'),format:'video/mp4',gatewayIssued:true};
  const malicious=[
    {...valid,url:'https://evil.example/api/media/'+('X'.repeat(43))},
    {...valid,url:capabilityToken('M')+'?redirect=1'},
    {...valid,url:capabilityToken('M')+'#secret'},
    {...valid,format:'text/html'},
    {...valid,gatewayIssued:false},
    {...valid,url:mediaOrigin+'/api/media/short'}
  ];
  assert.equal(nextPlaybackSource(malicious,new Set(),()=>'',4,mediaOrigin),null);
  assert.equal(nextPlaybackSource([...malicious,valid],new Set(),()=>'',4,mediaOrigin)?.source.url,
    valid.url);
  assert.equal(nextPlaybackSource([valid],new Set(),()=>'',4,'https://not-moosa.example'),null);
  assert.equal(nextPlaybackSource([valid],new Set(),()=>'',4),null);
});
