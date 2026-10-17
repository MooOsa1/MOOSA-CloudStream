import test from 'node:test';
import assert from 'node:assert/strict';
import {filmmakerSoundSources,SITA_AUDIO_720,FILMMAKER_LICENSE,
  FILMMAKER_WATCH} from '../sita-filmmaker-sound.mjs';
const commons={
  id:'sita-sings-the-blues',title:'Sita Sings the Blues',year:2008,
  available:true,sources:[{
    url:'https://upload.wikimedia.org/wikipedia/commons/0/00/Sita_Sings_the_Blues.webm',
    sourcePage:'https://commons.wikimedia.org/wiki/File:Sita_Sings_the_Blues.webm',
    licenseUrl:'https://creativecommons.org/publicdomain/zero/1.0/deed.en',
    quality:720
  }]
};
test('original H264/AAC soundtrack wins; original 2023 Wikimedia file clearly marked silent',()=>{
  const sources=filmmakerSoundSources(commons);
  assert.equal(sources.length,2);
  assert.equal(sources[0].url,SITA_AUDIO_720);
  assert.equal(sources[0].quality,720);
  assert.equal(sources[0].audioCodec,'aac');
  assert.equal(sources[0].videoCodec,'h264');
  assert.equal(sources[0].licenseUrl,FILMMAKER_LICENSE);
  assert.equal(sources[0].sourcePage,FILMMAKER_WATCH);
  assert.equal(sources[0].officialSoundtrack,true);
  assert.equal(sources[1].silent,true);
  assert.match(sources[1].name,/بدون صوت/);
  assert.equal(sources[0].preferred,true);
});
test('no arbitrary title or unverified Commons page can hijack the soundtrack link',()=>{
  assert.deepEqual(filmmakerSoundSources({...commons,id:'other-film'}),[]);
  assert.deepEqual(filmmakerSoundSources({...commons,year:2009}),[]);
  assert.deepEqual(filmmakerSoundSources({...commons,available:false}),[]);
  assert.deepEqual(filmmakerSoundSources({...commons,sources:[{
    ...commons.sources[0],sourcePage:'https://commons.wikimedia.org/wiki/File:Trailer.webm'
  }]}),[]);
  assert.deepEqual(filmmakerSoundSources({...commons,sources:[{
    ...commons.sources[0],licenseUrl:'https://illegal.example/unknown'
  }]}),[]);
});
