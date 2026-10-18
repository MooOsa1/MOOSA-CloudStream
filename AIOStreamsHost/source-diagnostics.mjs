#!/usr/bin/env node
/**
 * MOOSA Hub live, read-only Stremio discovery audit.
 * This checks addon protocols and classifies stream *candidates*.
 * It DOES NOT download media, assert movie identity, or prove playback.
 * Tests two old public-domain US film titles plus recent movies' metadata listings.
 * No copyrighted movie video or torrent payload is fetched.
 */
import { readFile } from 'node:fs/promises';

const ADDONS = [
  { name: 'Torrentio', base: 'https://torrentio.strem.fun' },
  { name: 'MediaFusion', base: 'https://mediafusion.elfhosted.com' },
];
const MOVIES = [
  { imdb: 'tt0063350', title: 'Night of the Living Dead (1968)' },
  { imdb: 'tt0032599', title: 'His Girl Friday (1940)' },
];
const RECENT_LISTINGS = [
  { imdb: 'tt31193180', title: 'Sinners (2025)' },
  { imdb: 'tt15239678', title: 'Dune: Part Two (2024)' },
];
const META = [
  { name: 'Cinemeta', base: 'https://v3-cinemeta.strem.io' },
  { name: 'OpenSubtitles v3', base: 'https://opensubtitles-v3.strem.io' },
];

export function classify(stream) {
  if (!stream || typeof stream !== 'object') return 'unknown';
  if (typeof stream.infoHash === 'string' && stream.infoHash) return 'p2p';
  if (typeof stream.url === 'string') {
    if (/^magnet:/i.test(stream.url)) return 'p2p';
    if (/^https?:\/\//i.test(stream.url)) return 'http_candidate';
  }
  if (typeof stream.externalUrl === 'string') return 'external';
  return 'unknown';
}

export async function getJson(url, timeout = 14000) {
  const response = await fetch(url, {
    signal: AbortSignal.timeout(timeout),
    headers: { Accept: 'application/json' },
    redirect: 'follow',
  });
  if (!response.ok) throw new Error('HTTP ' + response.status);
  const length = Number(response.headers.get('content-length') || 0);
  if (length > 4_000_000) throw new Error('response too large');
  const data = await response.json();
  if (!data || typeof data !== 'object') throw new Error('non-object JSON');
  return data;
}

export async function main() {
  const file = new URL('./moosa-hub-template.json', import.meta.url);
  const config = JSON.parse(await readFile(file, 'utf8'));
  const types = (config.config?.presets || []).filter(p => p.enabled).map(p => p.type);
  for (const required of ['torrentio', 'mediafusion']) {
    if (!types.includes(required)) throw new Error('Missing configured provider: ' + required);
  }
  if (!Array.isArray(config.metadata?.services) || config.metadata.services.length) {
    throw new Error('This diagnostic requires the explicitly free/no-debrid preset');
  }
  if (!config.config?.preferredStreamTypes?.includes('p2p')) {
    throw new Error('Preset must explicitly retain P2P streams');
  }
  console.log('MOOSA_STREMIO_PRESET_VALID version=' + config.metadata.version);
  let validStreamManifests = 0;
  let totalCandidates = 0;
  let totalP2p = 0;
  let totalHttp = 0;
  for (const addon of [...META, ...ADDONS]) {
    try {
      const manifest = await getJson(addon.base + '/manifest.json');
      const resources = manifest.resources || [];
      const streamCapable = resources.some(r => r === 'stream' || r?.name === 'stream');
      console.log('MANIFEST ' + addon.name + ' valid=true streamResource=' + streamCapable);
      if (streamCapable) validStreamManifests++;
    } catch (err) {
      console.log('MANIFEST ' + addon.name + ' valid=false issue=' + err.message);
    }
  }
  for (const addon of ADDONS) {
    for (const movie of [...MOVIES, ...RECENT_LISTINGS]) {
      // No login, media URL fetch, magnet open, or video download.
      const endpoint = addon.base + '/stream/movie/' + movie.imdb + '.json';
      try {
        const response = await getJson(endpoint, 20000);
        if (!Array.isArray(response.streams)) throw new Error('no streams array');
        const counts = { p2p: 0, http_candidate: 0, external: 0, unknown: 0 };
        for (const stream of response.streams) counts[classify(stream)]++;
        totalCandidates += response.streams.length;
        totalP2p += counts.p2p;
        totalHttp += counts.http_candidate;
        console.log('STREAM ' + addon.name + ' imdb=' + movie.imdb +
          ' candidates=' + response.streams.length + ' p2p=' + counts.p2p +
          ' http_candidate=' + counts.http_candidate + ' external=' + counts.external +
          ' unknown=' + counts.unknown);
      } catch (err) {
        console.log('STREAM ' + addon.name + ' imdb=' + movie.imdb + ' failed=' + err.message);
      }
    }
  }
  const status = { validStreamManifests, totalCandidates, totalP2p, totalHttp };
  console.log('MOOSA_STREMIO_DISCOVERY ' + JSON.stringify(status));
  console.log('NOT_PLAYBACK_PROOF: Candidates may be unseeded, unreachable, wrong titles, or unsupported in iOS Safari.');
  if (process.env.GITHUB_STEP_SUMMARY) {
    await (await import('node:fs/promises')).appendFile(process.env.GITHUB_STEP_SUMMARY,
      '### MOOSA Stremio discovery only (NOT playback proof)

' +
      '| Stream manifests | Candidates | P2P candidates | HTTP candidates |
|---:|---:|---:|---:|
|' +
      [validStreamManifests, totalCandidates, totalP2p, totalHttp].join('|') + '|

' +
      'Checked two public-domain feature titles. No videos downloaded or verified.
');
  }
  // A down provider or empty stream index is a *failed live discovery*, not a pass.
  if (validStreamManifests === 0 || totalCandidates === 0) process.exitCode = 1;
}

if (process.argv[1] && new URL('file://' + process.argv[1]).href === import.meta.url) {
  main().catch(err => { console.error('MOOSA_STREMIO_FATAL ' + err.message); process.exitCode = 1; });
}
