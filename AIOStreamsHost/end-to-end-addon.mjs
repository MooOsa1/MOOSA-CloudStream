#!/usr/bin/env node
// End-to-end disposable MOOSA Hub addon integration on the free community instance.
// Saves an EPHEMERAL test config, requests the real installed addon's manifest and
// stream resource, and always deletes the ephemeral account. No media is downloaded.
// Do not log UUID, encrypted password, manifest URL, source URLs or tokens.
import { readFile } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';

const HOST = 'https://aiostreams-nightly.fortheweak.cloud';
const endpoint = HOST + '/api/v1/user';
const template = JSON.parse(await readFile(new URL('./moosa-hub-template.json', import.meta.url), 'utf8'));
const config = {
  ...template.config,
  services: [],
  formatter: { id: 'gdrive' },
  addonName: 'MOOSA Hub Disposable CI Test',
  addonDescription: 'Disposable source-protocol verification only. Not a production addon.'
};
const password = randomBytes(36).toString('hex');
let uuid, encrypted;
async function request(path, init = {}, timeout = 50000) {
  const response = await fetch(path, {
    ...init,
    headers: { Accept: 'application/json', ...init.headers },
    signal: AbortSignal.timeout(timeout),
    redirect: 'follow'
  });
  const ct = response.headers.get('content-type') || '';
  const payload = ct.includes('json') ? await response.json() : {};
  return { status: response.status, ok: response.ok, payload };
}

try {
  const created = await request(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({config, password})
  }, 90000);
  if (!created.ok || !created.payload?.data?.uuid || !created.payload?.data?.encryptedPassword) {
    const code = created.payload?.error?.code || created.payload?.error?.message || created.payload?.detail || 'unknown';
    throw new Error('CONFIG_CREATE status=' + created.status + ' reason=' + String(code).slice(0,160));
  }
  uuid = created.payload.data.uuid;
  encrypted = created.payload.data.encryptedPassword;
  console.log('MOOSA_AIOSTREAMS_EPHEMERAL_CONFIG_CREATED status=' + created.status);
  const base = HOST + '/stremio/' + encodeURIComponent(uuid) + '/' + encodeURIComponent(encrypted);
  const manifest = await request(base + '/manifest.json');
  if (!manifest.ok || !manifest.payload?.resources) throw new Error('INSTALL_MANIFEST status=' + manifest.status);
  const types = manifest.payload.resources.map(r => typeof r === 'string' ? r : r.name);
  const hasStream = types.includes('stream');
  console.log('MOOSA_AIOSTREAMS_INSTALLED_MANIFEST status=' + manifest.status +
    ' validStreamResource=' + hasStream +
    ' resourceTypes=' + types.filter(x=>['catalog','meta','stream','subtitles'].includes(x)).join(','));
  if (!hasStream) throw new Error('Manifest missing stream resource');
  const stream = await request(base + '/stream/movie/tt0063350.json', {}, 90000);
  const streams = Array.isArray(stream.payload.streams) ? stream.payload.streams : [];
  const p2p = streams.filter(s => typeof s.infoHash === 'string' && s.infoHash).length;
  const http = streams.filter(s => typeof s.url === 'string' && /^https?:\/\//.test(s.url)).length;
  console.log('MOOSA_AIOSTREAMS_REAL_AGGREGATED_STREAM status=' + stream.status +
    ' count=' + streams.length + ' p2p=' + p2p + ' http_candidates=' + http);
  if (!stream.ok || !streams.length) throw new Error('No aggregated Stremio streams from installed addon');
  console.log('NOT_PLAYBACK_PROOF: P2P stream metadata confirmed through the full AIOStreams route, not actual video.');
} catch (err) {
  console.error('MOOSA_AIOSTREAMS_BACKEND_CHECK_FAILED ' + (err?.message || String(err)).slice(0,260));
  process.exitCode = 1;
} finally {
  if (uuid) {
    const auth = Buffer.from(uuid + ':' + password).toString('base64');
    try {
      const deleted = await request(endpoint, {
        method: 'DELETE',
        headers: { Authorization: 'Basic ' + auth }
      }, 20000);
      console.log('MOOSA_AIOSTREAMS_EPHEMERAL_CONFIG_DELETED status=' + deleted.status +
        ' success=' + deleted.ok);
      if (!deleted.ok) process.exitCode = 1;
    } catch {
      console.error('MOOSA_AIOSTREAMS_CLEANUP_FAILED; disposable config requires manual cleanup via private credentials');
      process.exitCode = 1;
    }
  }
}
