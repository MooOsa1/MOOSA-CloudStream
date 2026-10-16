import test from 'node:test';
import assert from 'node:assert/strict';
import {adaptNuvioStreams} from '../nuvio-js-adapter.mjs';
const sample={
  title:'Big Buck Bunny 720p',
  quality:'720p',
  url:'https://upload.wikimedia.org/wikipedia/commons/a/b/Big_Buck_Bunny_alt.webm',
  subtitles:[{language:'en',url:'https://captions.example.org/test.vtt'}]
};
const opts={provider:'Streamline:Vidlink',licensedMedia:true,
 permittedHosts:['upload.wikimedia.org']};
test('refuses every Nuvio plugin result without explicit rights and CDN allowlist',()=>{
  assert.deepEqual(adaptNuvioStreams([sample]).sources,[]);
  assert.deepEqual(adaptNuvioStreams([sample,{...sample,url:'https://other.example/movie.mp4'}],
    {...opts,licensedMedia:false}).sources,[]);
  assert.deepEqual(adaptNuvioStreams([sample],{...opts,permittedHosts:[]}).sources,[]);
});
test('normalizes a rights-approved 720p browser-playable sample with English subtitle',()=>{
  const r=adaptNuvioStreams([sample],opts);
  assert.equal(r.sources.length,1);
  assert.equal(r.sources[0].quality,720);
  assert.equal(r.sources[0].subtitles[0].language,'en');
  assert.match(r.sources[0].name,/Streamline:Vidlink/);
});
test('blocks protected links needing Referer or Cookie instead of silently dropping headers',()=>{
  const r=adaptNuvioStreams([
    {...sample,headers:{Referer:'https://example.com/watch'}},
    {...sample,url:'https://upload.wikimedia.org/wikipedia/commons/a/b/movie.mp4',headers:{Cookie:'private=test'}},
    {...sample,url:'https://upload.wikimedia.org/wikipedia/commons/a/b/movie2.mp4',headers:{Authorization:'Bearer secret'}}
  ],opts);
  assert.equal(r.sources.length,0);
  assert.equal(r.blockedHeaders,3);
});
test('rejects SSRF candidates, mismatched media types, torrents, quality <720',()=>{
  const r=adaptNuvioStreams([
    {...sample,url:'http://127.0.0.1/secret.mp4'},
    {...sample,url:'https://localhost/secret.mp4'},
    {...sample,url:'https://upload.wikimedia.org/watch.html'},
    {...sample,url:'https://other.example/media.mp4'},
    {...sample,quality:'CAM'},
    {...sample,quality:'480p'},
    {...sample,infoHash:'abc'},
    {...sample,magnet:'magnet:?xt=1'}
  ],opts);
  assert.deepEqual(r.sources,[]);
});
test('quality sort, dedup and subtitle language restrictions',()=>{
  const mk=(s,q,url)=>({...sample,title:s,quality:q,url});
  const r=adaptNuvioStreams([
    mk('HD', '720p',sample.url),
    mk('duplicate','720p',sample.url),
    mk('4K','4K','https://upload.wikimedia.org/film_4k.mp4'),
    mk('FHD','1080p','https://upload.wikimedia.org/film_1080.mp4'),
    {...mk('HD-variant','720p','https://upload.wikimedia.org/hd_720.m3u8'),
     subtitles:[{language:'es',url:'https://x.example/es.vtt'},
                {language:'ar',url:'https://x.example/ar.srt'},
                {language:'en',url:'http://127.0.0.1/secret.srt'}]}
  ],opts);
  assert.deepEqual(r.sources.map(x=>x.quality),[2160,1080,720,720]);
  assert.equal(r.sources[3].subtitles.length,1);
  assert.equal(r.sources[3].subtitles[0].language,'ar');
});
