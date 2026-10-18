#!/usr/bin/env node
/**
 * MOOSA Hub live, read-only Stremio discovery audit.
 * This checks addon protocols and classifies stream *candidates*.
 * It DOES NOT download media, assert movie identity, or prove playback.
 * Tests 2025 mainstream releases (Sinners and F1) plus Dune Part Two (2024).
 * Only addon metadata is requested; no movie video/torrent payload is downloaded.
 */
import { readFile } from 'node:fs/promises';

const ADDONS = [
  { name: 'Torrentio', base: 'https://torrentio.strem.fun' },
  // Previous zero-result provider, kept for comparative diagnostics but not installed.
  { name: 'MediaFusion', base: 'https://mediafusion.elfhosted.com', exploratory: true },
  // Candidates are not enabled in MOOSA Hub until a live metadata audit succeeds.
  { name: 'Comet', base: 'https://comet.feels.legal' },
  { name: 'StremThru Torz', base: 'https://stremthru.13377001.xyz/stremio/torz', exploratory: true },
];
const MOVIES = [
  { imdb: 'tt31193180', title: 'Sinners (2025)' },
  { imdb: 'tt16311594', title: 'F1: The Movie (2025)' },
];
const RECENT_LISTINGS = [
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
  for (const required of ['torrentio', 'comet']) {
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
        const response = await getJson(endpoint, addon.exploratory ? 10000 : 20000);
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

  // The default Comet endpoint can work while AIOStreams' generated configuration does not.
  // Check the actual first-party Comet preset payload, with P2P explicitly enabled.
  const cometConfiguration = {
    maxResultsPerResolution: 0, maxSize: 0, cachedOnly: false,
    removeTrash: true, resultFormat: ['all'], debridServices: [],
    enableTorrent: true, scrapeDebridAccountTorrents: false,
    debridStreamProxyPassword: '',
    languages: { required: [], exclude: [], preferred: [] }, resolutions: {},
    options: {
      remove_ranks_under: -10000000000,
      allow_english_in_languages: false, remove_unknown_languages: false
    }
  };
  const cometSlug = Buffer.from(JSON.stringify(cometConfiguration)).toString('base64');
  let cometConfiguredCount = 0;
  for (const movie of [MOVIES[0], RECENT_LISTINGS[0]]) {
    try {
      const data = await getJson('https://comet.feels.legal/' + cometSlug +
        '/stream/movie/' + movie.imdb + '.json', 15000);
      const streams = Array.isArray(data.streams) ? data.streams : [];
      const p2p = streams.filter(x => classify(x) === 'p2p').length;
      cometConfiguredCount += p2p;
      console.log('COMET_CONFIGURED_P2P_CHECK imdb=' + movie.imdb + ' p2p=' + p2p);
    } catch (err) {
      console.log('COMET_CONFIGURED_P2P_CHECK imdb=' + movie.imdb + ' failed=' + err.message);
    }
  }
  console.log('MOOSA_COMET_CONFIGURED_TOTAL_P2P ' + cometConfiguredCount);
  if (!cometConfiguredCount) process.exitCode = 1;
  const status = { validStreamManifests, totalCandidates, totalP2p, totalHttp };
  console.log('EXPLORATORY_PROVIDERS: MediaFusion and StremThru Torz are disabled in MOOSA Hub; counts represent raw metadata only.');
  console.log('MOOSA_STREMIO_DISCOVERY ' + JSON.stringify(status));
  console.log('NOT_PLAYBACK_PROOF: Candidates may be unseeded, unreachable, wrong titles, or unsupported in iOS Safari.');
  if (process.env.GITHUB_STEP_SUMMARY) {
    await (await import('node:fs/promises')).appendFile(
      process.env.GITHUB_STEP_SUMMARY,
      `### MOOSA Stremio discovery only — NOT playback proof

| Stream manifests | Candidates | P2P candidates | HTTP candidates |
|---:|---:|---:|---:|
|${validStreamManifests}|${totalCandidates}|${totalP2p}|${totalHttp}|

Checked Sinners (2025), F1 (2025), and Dune: Part Two (2024) metadata only. No video downloaded or played.
`
    );
  }
  // A down provider or empty stream index is a *failed live discovery*, not a pass.
  if (validStreamManifests === 0 || totalCandidates === 0) process.exitCode = 1;
}

if (process.argv[1] && new URL('file://' + process.argv[1]).href === import.meta.url) {
  main().catch(err => { console.error('MOOSA_STREMIO_FATAL ' + err.message); process.exitCode = 1; });
}
