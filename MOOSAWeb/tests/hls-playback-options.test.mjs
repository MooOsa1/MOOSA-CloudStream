import test from 'node:test';
import assert from 'node:assert/strict';
import {hlsPlayerOptions,keepHlsAutoAtLeastHd} from '../public/hls-playback-options.js';

test('HLS adaptive playback never caps 4K availability to small player viewport',()=>{
  const config=hlsPlayerOptions();
  assert.equal(config.capLevelToPlayerSize,false);
  assert.equal(config.enableWorker,true);
  assert.equal(config.backBufferLength,40);
  assert.equal('startLevel' in config,false,
    'Do not force an unbuffered 4K rendition; preserve bandwidth adaptation');
});

test('HLS auto ABR skips lower-resolution variants when a 720p+ level exists',()=>{
  const engine={config:{minAutoBitrate:0},levels:[
    {height:180,width:320,maxBitrate:300000},
    {height:540,width:960,maxBitrate:800000},
    {height:720,width:1280,maxBitrate:2000000},
    {height:2160,width:3840,maxBitrate:15000000}
  ]};
  assert.equal(keepHlsAutoAtLeastHd(engine),true);
  assert.equal(engine.config.minAutoBitrate,800001);
  // Both 720p and 4K remain eligible for adaptive selection.
  assert.ok(engine.levels.filter(x=>x.maxBitrate>=engine.config.minAutoBitrate)
    .every(x=>x.height>=720));
  assert.equal(engine.config.capLevelToPlayerSize,undefined);
});
test('HLS floor preserves stricter operator setting and avoids inconsistent metadata',()=>{
  const engine={config:{minAutoBitrate:2500000},levels:[
    {height:360,maxBitrate:300000},{height:720,maxBitrate:1600000}
  ]};
  assert.equal(keepHlsAutoAtLeastHd(engine),true);
  assert.equal(engine.config.minAutoBitrate,2500000);
  const invalid={config:{minAutoBitrate:0},levels:[
    {height:360,maxBitrate:4000000},{height:1080,maxBitrate:1800000}
  ]};
  assert.equal(keepHlsAutoAtLeastHd(invalid),false);
  assert.equal(invalid.config.minAutoBitrate,0);
});
test('HLS unknown bitrate and SD-only streams do not get falsely promoted to HD',()=>{
  assert.equal(keepHlsAutoAtLeastHd({config:{},levels:[
    {height:360,bitrate:80000},{height:720}
  ]}),false);
  assert.equal(keepHlsAutoAtLeastHd({config:{},levels:[
    {height:180,maxBitrate:100000},{height:360,maxBitrate:400000}
  ]}),false);
  assert.equal(keepHlsAutoAtLeastHd({config:{},levels:[
    {height:720,width:1280,maxBitrate:1400000},
    {height:1080,width:1920,maxBitrate:4000000}
  ]}),false);
  assert.equal(keepHlsAutoAtLeastHd(null),false);
});
