#!/usr/bin/env node
/**
 * Read-only official Stremio iOS release check.
 * Consults the first-party dl.strem.io AltStore source, never a mirror.
 * HEAD checks a corresponding first-party .ipa; no app bytes downloaded.
 */
const INDEX = 'https://dl.strem.io/apple/altstore/source.json';
const res = await fetch(INDEX, { signal: AbortSignal.timeout(12000) });
if (!res.ok) throw new Error('Official AltStore source HTTP ' + res.status);
const source = await res.json();
const app = source.apps?.find(a => a.bundleIdentifier === 'com.stremio.pal');
if (!app?.versions?.length) throw new Error('Full iOS Stremio app not advertised in official source');
const v = [...app.versions].sort((a,b) => new Date(b.date) - new Date(a.date))[0];
if (!/^\d+\.\d+\.\d+$/.test(v.version) || !/^\d+$/.test(String(v.buildVersion))) {
  throw new Error('Invalid release metadata');
}
const manifest = new URL(v.downloadURL);
if (manifest.hostname !== 'dl.strem.io' || manifest.protocol !== 'https:') {
  throw new Error('Latest manifest must use official Stremio CDN');
}
console.log('MOOSA_STREMIO_IOS_OFFICIAL_LATEST version=' + v.version +
  ' build=' + v.buildVersion + ' published=' + v.date);
const ipa = 'https://dl.strem.io/apple/' + v.version + 'b' + v.buildVersion +
  '/ios/stremio_iOS.ipa';
const head = await fetch(ipa, { method: 'HEAD', signal: AbortSignal.timeout(18000) });
console.log('MOOSA_STREMIO_IOS_OFFICIAL_IPA_HEAD status=' + head.status +
  ' bytes=' + (head.headers.get('content-length') || 'unknown'));
// HEAD on a CDN is not an iPhone installation or playback test.
if (!head.ok) process.exitCode = 1;
