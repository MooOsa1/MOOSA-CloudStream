import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { classify } from './source-diagnostics.mjs';

// A source listing is NOT itself proof that a media player can decode a movie.
test('P2P torrent metadata is not mistaken for browser-playable HTTP', () => {
  assert.equal(classify({ infoHash: 'abcdef', sources: ['tracker:udp://test.invalid'] }), 'p2p');
  assert.equal(classify({ url: 'magnet:?xt=urn:btih:deadbeef' }), 'p2p');
});
test('HTTP URL remains only an unverified candidate', () => {
  assert.equal(classify({ url: 'https://example.org/movie.mp4' }), 'http_candidate');
  assert.equal(classify({ url: 'https://example.org/wrong-page.html' }), 'http_candidate');
});
test('unknown or external links do not become playable', () => {
  assert.equal(classify({ name: 'No URL' }), 'unknown');
  assert.equal(classify({ externalUrl: 'https://example.org/page' }), 'external');
  assert.equal(classify(null), 'unknown');
});

test('MOOSA preset prioritizes playable HTTP candidates without dropping P2P', async () => {
  const { readFile } = await import('node:fs/promises');
  const d = JSON.parse(await readFile(new URL('./moosa-hub-template.json', import.meta.url), 'utf8'));
  assert.deepEqual(d.config.preferredStreamTypes, ['http', 'p2p']);
  assert.equal(d.config.sortCriteria.global[0].key, 'streamType');
  assert.deepEqual(d.metadata.services, []);
  assert.deepEqual(d.config.preferredSubtitles, ['Arabic', 'English']);
  for (const resolution of ['576p', '480p', '360p', '240p', '144p']) {
    assert.ok(d.config.excludedResolutions.includes(resolution));
  }
  assert.equal(d.config.presets.find(p => p.type === 'torrentio')?.enabled, true);
  assert.equal(d.config.presets.find(p => p.type === 'comet')?.enabled, true);
  assert.equal(d.config.presets.find(p => p.type === 'comet')?.options?.includeP2P, true);
  assert.equal(d.config.presets.some(p => p.type === 'mediafusion'), false);
});
