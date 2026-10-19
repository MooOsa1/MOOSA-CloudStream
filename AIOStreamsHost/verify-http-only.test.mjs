import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {isHttpsMediaCandidate,checkPreset} from './verify-http-only.mjs';

test('preset excludes torrent/P2P, paid services and unwanted providers',async()=>{
  const p=JSON.parse(await readFile(new URL('./moosa-direct-only.template.json',import.meta.url),'utf8'));
  const providers=checkPreset(p);
  assert.deepEqual(providers.map(p=>p.instanceId),['mhd1']);
  assert.deepEqual(p.config.requiredStreamTypes,['http']);
  assert.ok(p.config.excludedStreamTypes.includes('p2p'));
  assert.ok(!p.config.presets.some(p=>['torrentio','comet'].includes(p.type)));
});
test('strict filter accepts only public HTTPS media candidates',()=>{
  assert.equal(isHttpsMediaCandidate({url:'https://cdn.example.org/path/video.m3u8'}),true);
  assert.equal(isHttpsMediaCandidate({url:'https://cdn.example.org/video'}),true); // opaque HTTPS is unverified, not declared playable
  assert.equal(isHttpsMediaCandidate({url:'https://cdn.example.org/a',infoHash:'0'.repeat(40)}),false);
  assert.equal(isHttpsMediaCandidate({url:'magnet:?xt=urn:btih:xxx'}),false);
  assert.equal(isHttpsMediaCandidate({url:'https://cdn.example.org/movie.torrent'}),false);
  assert.equal(isHttpsMediaCandidate({url:'https://127.0.0.1/internal.mp4'}),false);
  assert.equal(isHttpsMediaCandidate({url:'https://10.0.1.2/internal.mp4'}),false);
  assert.equal(isHttpsMediaCandidate({url:'https://192.168.1.10/video.mp4'}),false);
  assert.equal(isHttpsMediaCandidate({url:'https://172.19.1.10/video.mp4'}),false);
  assert.equal(isHttpsMediaCandidate({url:'https://server.local/a.mp4'}),false);
  assert.equal(isHttpsMediaCandidate({url:'http://cdn.example.org/video.mp4'}),false);
  assert.equal(isHttpsMediaCandidate({externalUrl:'https://example.org/movie'}),false);
  assert.equal(isHttpsMediaCandidate({url:'https://x.org/video.mp4',sources:['tracker:https://tracker.example'] }),false);
});
