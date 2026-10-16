import test from 'node:test';
import assert from 'node:assert/strict';
import {comparePlaybackSources,compareSubtitleTracks} from '../public/stream-preferences.js';

test('4K is always above 1080p and 720p regardless of preferred and optimized flags',()=>{
  const sources=[
    {name:'HD preferred',quality:720,preferred:true,codecOptimized:true},
    {name:'FHD preferred',quality:1080,preferred:true},
    {name:'4K normal',quality:2160},
    {name:'FHD normal',quality:1080},
    {name:'HD normal',quality:720}
  ];
  assert.deepEqual(sources.sort(comparePlaybackSources).map(x=>x.name),
    ['4K normal','FHD preferred','FHD normal','HD preferred','HD normal']);
});
test('same-resolution codec compatibility flags may break ties only',()=>{
  const sources=[
    {quality:2160,name:'4K standard'},
    {quality:2160,name:'4K optimized',codecOptimized:true},
    {quality:1080,name:'1080 optimized',codecOptimized:true}
  ];
  assert.deepEqual(sources.sort(comparePlaybackSources).map(x=>x.name),
    ['4K optimized','4K standard','1080 optimized']);
});
test('unknown or invalid quality is never promoted to 4K',()=>{
  const sources=[{quality:'4K',name:'label only'},{quality:720,name:'HD'}];
  assert.deepEqual(sources.sort(comparePlaybackSources).map(x=>x.name),['HD','label only']);
});
test('Arabic tracks are listed before English without modifying their identity',()=>{
  const tracks=[
    {language:'en',url:'https://a.test/sub-en.srt',label:'Version A'},
    {language:'ar',url:'https://a.test/sub-ar.srt',label:'Version B'},
    {language:'en',url:'https://b.test/sub-en.srt',label:'Version C'},
    {language:'ar',url:'https://b.test/sub-ar.srt',label:'Version D'}
  ];
  assert.deepEqual(tracks.sort(compareSubtitleTracks).map(x=>x.label),
    ['Version B','Version D','Version A','Version C']);
});
