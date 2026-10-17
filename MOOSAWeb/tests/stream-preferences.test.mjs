import test from 'node:test';
import assert from 'node:assert/strict';
import {comparePlaybackSources,compareSubtitleTracks} from '../public/stream-preferences.js';

test('MOOSA prefers 4K before 1440p, 1080p, and 720p despite preferred/codec flags',()=>{
  const sources=[
    {name:'HD optimized',quality:720,preferred:true,codecOptimized:true},
    {name:'FHD preferred',quality:1080,preferred:true},
    {name:'4K normal',quality:2160},
    {name:'1440p optimized',quality:1440,codecOptimized:true},
    {name:'FHD normal',quality:1080},
    {name:'HD normal',quality:720}
  ];
  assert.deepEqual(sources.sort(comparePlaybackSources).map(x=>x.name),
    ['4K normal','1440p optimized','FHD preferred','FHD normal','HD optimized','HD normal']);
});
test('same-resolution codec and preferred flags break ties without overriding resolution',()=>{
  const sources=[
    {quality:2160,name:'4K normal'},
    {quality:2160,name:'4K preferred',preferred:true},
    {quality:2160,name:'4K optimized',codecOptimized:true},
    {quality:1080,name:'1080 optimized',codecOptimized:true}
  ];
  assert.deepEqual(sources.sort(comparePlaybackSources).map(x=>x.name),
    ['4K optimized','4K preferred','4K normal','1080 optimized']);
});
test('unknown quality and mislabeled strings are never promoted to 4K',()=>{
  const sources=[{quality:'4K',name:'label only'},{quality:720,name:'HD'}];
  assert.deepEqual(sources.sort(comparePlaybackSources).map(x=>x.name),['HD','label only']);
});
test('Arabic subtitles precede English without modifying their identity',()=>{
  const tracks=[
    {language:'en',url:'https://a.test/sub-en.srt',label:'Version A'},
    {language:'ar',url:'https://a.test/sub-ar.srt',label:'Version B'},
    {language:'en',url:'https://b.test/sub-en.srt',label:'Version C'},
    {language:'ar',url:'https://b.test/sub-ar.srt',label:'Version D'}
  ];
  assert.deepEqual(tracks.sort(compareSubtitleTracks).map(x=>x.label),
    ['Version B','Version D','Version A','Version C']);
});
test('fallback without 4K follows available verified resolution descending',()=>{
  const options=[
    {quality:720,name:'720p'},
    {quality:1080,name:'1080p'},
    {quality:1440,name:'1440p'}
  ];
  assert.deepEqual(options.sort(comparePlaybackSources).map(x=>x.name),
    ['1440p','1080p','720p']);
});
