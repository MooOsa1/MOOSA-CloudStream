import test from 'node:test';
import assert from 'node:assert/strict';
import {comparePlaybackSources} from '../public/stream-preferences.js';

// Untrusted providers can supply numeric strings, including whitespace or
// exponential notation. Only canonical integer resolution values are safe
// as sorting hints; they still are NOT proof of decoded video dimensions.
test('untrusted numeric representations do not outrank verified-looking HD hints',()=>{
  const invalid=[' 2160 ','2.16e3','02160','2160.0','0x870','+2160','Infinity'];
  for(const value of invalid){
    const sources=[{name:'HD',quality:720},{name:'untrusted',quality:value}];
    assert.equal(sources.sort(comparePlaybackSources)[0].name,'HD',
      'invalid source quality '+value+' must not gain priority');
  }
});
test('canonical numeric resolution values remain ordered 4K first',()=>{
  const sources=[{quality:720},{quality:'1080'},{quality:2160}];
  assert.deepEqual(sources.sort(comparePlaybackSources).map(x=>x.quality),
    [2160,'1080',720]);
});
