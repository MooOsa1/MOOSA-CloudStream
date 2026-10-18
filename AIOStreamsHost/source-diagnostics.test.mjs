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
