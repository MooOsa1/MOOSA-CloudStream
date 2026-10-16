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
