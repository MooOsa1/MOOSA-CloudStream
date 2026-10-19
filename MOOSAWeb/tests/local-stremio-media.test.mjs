import test from 'node:test';
import assert from 'node:assert/strict';
import {localMediaPath,makeLocalMediaUrl,rewriteStremioPlaylist,MEDIA_PREFIX} from '../local-stremio-media.mjs';
import {localStremioProxyHls} from '../public/local-stremio-client.js';
const hash='0123456789abcdef0123456789abcdef01234567',idx=2;
const base='http://127.0.0.1:11470/'+hash+'/'+idx+'/hls.m3u8';
test('same-origin gateway maps a vetted torrent HLS manifest route only',()=>{
 const path='/'+hash+'/'+idx+'/hls.m3u8';
 assert.equal(localMediaPath(MEDIA_PREFIX+path),path);
 assert.equal(localMediaPath(MEDIA_PREFIX+'/'+hash+'/2/../../etc/passwd'),null);
 assert.equal(localMediaPath(MEDIA_PREFIX+'/'+hash+'/2/proxy?from=http://127.0.0.1'),null);
 assert.equal(localMediaPath(MEDIA_PREFIX+'/'+hash+'/2/download.exe'),null);
 assert.equal(localMediaPath(MEDIA_PREFIX+'/bad/2/hls.m3u8'),null);
 assert.equal(localStremioProxyHls({infoHash:hash,fileIdx:2}),MEDIA_PREFIX+path);
});
test('rewrites nested playlist segments and stream variant URLs to same origin',()=>{
 const input=['#EXTM3U','#EXT-X-VERSION:3',
  '#EXT-X-STREAM-INF:BANDWIDTH=2800000','stream-q-720.m3u8',
  '#EXT-X-MAP:URI="stream-q-720/0.mp4"',
  '#EXTINF:8.0,','stream-q-720/5.ts',''].join('\n');
 const out=rewriteStremioPlaylist(input,base);
 assert.ok(out);
 assert.match(out,/^#EXTM3U/);
 assert.ok(out.includes(MEDIA_PREFIX+'/'+hash+'/'+idx+'/stream-q-720.m3u8'));
 assert.ok(out.includes(MEDIA_PREFIX+'/'+hash+'/'+idx+'/stream-q-720/5.ts'));
 assert.ok(out.includes('URI="'+MEDIA_PREFIX+'/'+hash+'/'+idx+'/stream-q-720/0.mp4"'));
});
test('blocks playlist entries that escape torrent/fileIdx or local engine',()=>{
 for(const bad of [
  '#EXTM3U\nhttp://evil.example/hls.m3u8',
  '#EXTM3U\nhttp://127.0.0.1:11470/settings',
  '#EXTM3U\n../../other.mp4',
  '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="https://evil.example/key"\nstream-q-720.m3u8',
  '#EXTM3U\nhttp://127.0.0.1:11470/'+hash+'/99999/hls.m3u8'
 ])assert.equal(rewriteStremioPlaylist(bad,base),null);
});
test('rejects traversal, query string, non-loopback endpoints and host mismatches',()=>{
 for(const x of [
  'http://127.0.0.2:11470/'+hash+'/'+idx+'/hls.m3u8',
  'https://127.0.0.1:11470/'+hash+'/'+idx+'/hls.m3u8',
  'http://localhost:11470/'+hash+'/'+idx+'/hls.m3u8',
  'http://127.0.0.1:11470/'+hash+'/'+idx+'/hls.m3u8?from=evil',
  'http://127.0.0.1:11470/'+hash+'/'+idx+'/hls.m3u8#x'
 ])assert.equal(makeLocalMediaUrl(x,base),null);
 assert.equal(makeLocalMediaUrl('stream-q-720.m3u8',base),
  MEDIA_PREFIX+'/'+hash+'/'+idx+'/stream-q-720.m3u8');
});
