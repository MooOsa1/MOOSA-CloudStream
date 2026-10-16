import test from 'node:test';
import assert from 'node:assert/strict';
import {isIosWebKit,chooseBrowserSources} from '../public/source-compatibility.js';
const orig={
 url:'https://upload.wikimedia.org/wikipedia/commons/a/ab/Elephants_Dream_original.webm',
 name:'Wikimedia Commons • original',quality:1080,preferred:true,
 licenseUrl:'https://creativecommons.org/licenses/by/2.5',
 sourcePage:'https://commons.wikimedia.org/wiki/File:Elephants_Dream_(2006).webm'
};
const safari={
 ...orig,url:'https://upload.wikimedia.org/wikipedia/commons/transcoded/a/ab/Elephants_Dream_1080p.vp9.webm',
 name:'Wikimedia Commons • Safari-compatible VP9',
 codecOptimized:true
};
const unrelated={
 ...orig,url:'https://upload.wikimedia.org/wikipedia/commons/z/zz/Sintel.webm',
 sourcePage:'https://commons.wikimedia.org/wiki/File:Sintel.webm'
};
const ios='Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1';
const ipad='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1';
const desktop='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605.1.15';
test('iPhone, iOS Chrome and iPadOS WebKit are detected, but desktop Safari is not',()=>{
  assert.equal(isIosWebKit(ios),true);
  assert.equal(isIosWebKit(ios.replace('Safari/604.1','CriOS/137.0')),true);
  assert.equal(isIosWebKit(ipad),true);
  assert.equal(isIosWebKit(desktop),false);
});
test('iPhone keeps the proven transcode and hides only the unusable exact original',()=>{
  const actual=chooseBrowserSources([orig,safari,unrelated],ios);
  assert.deepEqual(actual,[safari,unrelated]);
  assert.equal(actual[0].codecOptimized,true);
});
test('desktop browsers keep both originals and compatible versions',()=>{
  assert.deepEqual(chooseBrowserSources([orig,safari],desktop),[orig,safari]);
});
test('iPhone must retain original when no verified matching derivative exists',()=>{
  assert.deepEqual(chooseBrowserSources([orig],ios),[orig]);
  assert.deepEqual(chooseBrowserSources([orig,{...safari,licenseUrl:'https://creativecommons.org/licenses/by/4.0/'}],ios),
    [orig,{...safari,licenseUrl:'https://creativecommons.org/licenses/by/4.0/'}]);
});
test('no false hiding for other films and self-claimed Safari links elsewhere',()=>{
  assert.deepEqual(chooseBrowserSources([unrelated,{...unrelated,codecOptimized:true}],ios),
    [unrelated,{...unrelated,codecOptimized:true}]);
  assert.deepEqual(chooseBrowserSources([{...orig,url:'https://othercdn.example/original.webm'},safari],ios),
    [{...orig,url:'https://othercdn.example/original.webm'},safari]);
  assert.deepEqual(chooseBrowserSources([orig,{...safari,codecOptimized:false}],ios),
    [orig,{...safari,codecOptimized:false}]);
});
test('empty sources stay empty and the input array is not mutated',()=>{
  const list=Object.freeze([orig,safari]);
  assert.deepEqual(chooseBrowserSources([],ios),[]);
  chooseBrowserSources(list,ios);
  assert.equal(list.length,2);
});
