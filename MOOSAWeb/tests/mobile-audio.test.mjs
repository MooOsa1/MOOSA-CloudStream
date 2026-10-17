import test from 'node:test';
import assert from 'node:assert/strict';
import {
  needsIosSoundGesture,audioPlaybackState,resumeWithSound
} from '../public/mobile-audio.js';

test('iPhone and iPad browser variants request user-gesture sound',()=>{
  assert.equal(needsIosSoundGesture('Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X)'),true);
  assert.equal(needsIosSoundGesture('Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X)'),true);
  assert.equal(needsIosSoundGesture('Mozilla/5.0 (Macintosh)', 'MacIntel',5),true);
  assert.equal(needsIosSoundGesture('Mozilla/5.0 (Windows NT 10.0)', 'Win32',0),false);
});

test('sound resume un-mutes BEFORE synchronous user-activated play()',async()=>{
  const order=[];
  const media={src:'https://example.org/authorized.m3u8',muted:true,volume:0,paused:true,readyState:1,
    play(){order.push({muted:this.muted,volume:this.volume});this.paused=false;return Promise.resolve();}};
  const request=resumeWithSound(media);
  assert.deepEqual(order,[{muted:false,volume:1}], 'play() cannot run after asynchronous setup');
  await request;
});

test('refuse play with no selected video and avoid false audio confidence',async()=>{
  const v={src:'',muted:true,volume:1,readyState:0,paused:true,
    play(){throw Error('should not run')}};
  await assert.rejects(resumeWithSound(v),/missing_media_source/);
  assert.deepEqual(audioPlaybackState(v),{
    sourceLoaded:false,playing:false,unmuted:false,trackCount:null
  });
  const playing={src:'blob:https://example.org/media',muted:false,volume:1,
    paused:false,readyState:4};
  assert.deepEqual(audioPlaybackState(playing),{
    sourceLoaded:true,playing:true,unmuted:true,trackCount:null
  },'No track API means audio is UNKNOWN, not proven audible');
  playing.audioTracks={length:1};
  assert.equal(audioPlaybackState(playing).trackCount,1);
});
