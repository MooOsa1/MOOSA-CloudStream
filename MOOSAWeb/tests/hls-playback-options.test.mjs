import test from 'node:test';
import assert from 'node:assert/strict';
import {hlsPlayerOptions} from '../public/hls-playback-options.js';

test('HLS adaptive playback never caps 4K availability to small player viewport',()=>{
  const config=hlsPlayerOptions();
  assert.equal(config.capLevelToPlayerSize,false);
  assert.equal(config.enableWorker,true);
  assert.equal(config.backBufferLength,40);
  assert.equal('startLevel' in config,false,
    'Do not force an unbuffered 4K rendition; preserve bandwidth adaptation');
});
