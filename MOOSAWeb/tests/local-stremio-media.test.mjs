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


import http from 'node:http';
import {once} from 'node:events';
import {handleLocalMedia} from '../local-stremio-media.mjs';
test('real local HTTP proxy rewrites master playlist and streams binary segment from pinned host',async()=>{
  const fetched=[];
  const mockFetcher=async (url,options)=>{
    fetched.push({url,method:options.method});
    if(url.endsWith('.m3u8'))return new Response('#EXTM3U\n#EXTINF:8,\nstream-q-720/0.ts\n',{
      status:200,headers:{'content-type':'application/vnd.apple.mpegurl'}});
    if(url.endsWith('.ts'))return new Response(new Uint8Array([71,0,71,1,71]),{
      status:200,headers:{'content-type':'video/mp2t','content-length':'5'}});
    throw Error('invalid URL');
  };
  const server=http.createServer((req,res)=>
    handleLocalMedia(req,res,new URL(req.url,'http://127.0.0.1'),{fetcher:mockFetcher}));
  server.listen(0,'127.0.0.1');
  await once(server,'listening');
  const origin='http://127.0.0.1:'+server.address().port;
  try{
    const a=await fetch(origin+MEDIA_PREFIX+'/'+hash+'/2/hls.m3u8');
    assert.equal(a.status,200);
    const text=await a.text();
    assert.ok(text.includes(MEDIA_PREFIX+'/'+hash+'/2/stream-q-720/0.ts'));
    const b=await fetch(origin+MEDIA_PREFIX+'/'+hash+'/2/stream-q-720/0.ts');
    assert.equal(b.status,200);
    assert.deepEqual([...new Uint8Array(await b.arrayBuffer())],[71,0,71,1,71]);
    assert.equal(fetched.length,2);
    assert.deepEqual(fetched.map(x=>new URL(x.url).hostname),['127.0.0.1','127.0.0.1']);
  }finally{await new Promise(resolve=>server.close(resolve));}
});


test('accepts real Stremio hlsv2 playlists and media while rejecting mismatched streams',()=>{
  const id=hash+'-'+idx;
  const original='http://127.0.0.1:11470/'+hash+'/'+idx;
  const engineMaster='http://127.0.0.1:11470/hlsv2/'+id+'/master.m3u8?mediaURL='+encodeURIComponent(original);
  const proxied=makeLocalMediaUrl(engineMaster,base);
  assert.equal(proxied,MEDIA_PREFIX+'/hlsv2/'+id+'/master.m3u8?mediaURL='+encodeURIComponent(original));
  assert.equal(localMediaPath(MEDIA_PREFIX+'/hlsv2/'+id+'/video0.m3u8'),'/hlsv2/'+id+'/video0.m3u8');
  assert.equal(localMediaPath(MEDIA_PREFIX+'/hlsv2/'+id+'/audio0/segment12.m4s'),
    '/hlsv2/'+id+'/audio0/segment12.m4s');
  assert.equal(localMediaPath(MEDIA_PREFIX+'/hlsv2/'+id+'/video0/init.mp4'),
    '/hlsv2/'+id+'/video0/init.mp4');
  // Real Stremio HLSv2 audio/video playlists AND media segments retain
  // mediaURL in the query. Previously the proxy returned 502 for these.
  for(const resource of ['video0.m3u8','audio0.m3u8','video0/init.mp4',
      'audio0/segment1.m4s','video0/segment22.m4s']){
    const originalURL='http://127.0.0.1:11470/hlsv2/'+id+'/'+resource+
      '?mediaURL='+encodeURIComponent(original);
    const proxiedURL=makeLocalMediaUrl(originalURL,base);
    assert.ok(proxiedURL,resource+' must be accepted');
    assert.equal(proxiedURL,MEDIA_PREFIX+'/hlsv2/'+id+'/'+resource+
      '?mediaURL='+encodeURIComponent(original));
  }

  const playlist='#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3500000\n'+
    engineMaster+'\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="audio0.m3u8"\n';
  const rewritten=rewriteStremioPlaylist(playlist,base);
  assert.ok(rewritten?.includes(proxied));
  assert.ok(rewritten?.includes(MEDIA_PREFIX+'/'+hash+'/'+idx+'/audio0.m3u8'));
  // Stremio sometimes forwards multiple codec hints on each HLSv2 track.
  const withHints=engineMaster+'&videoCodecs=h264&videoCodecs=hevc&audioCodecs=aac&maxAudioChannels=2';
  assert.ok(makeLocalMediaUrl(withHints,base));
  assert.equal(makeLocalMediaUrl(withHints+'&videoCodecs=../../evil',base),null);
  assert.equal(makeLocalMediaUrl(withHints+'&profile=bad%20token',base),null);
  for(const bad of [
    'http://127.0.0.1:11470/hlsv2/'+id+'/master.m3u8?mediaURL=http://evil.test/private',
    'http://127.0.0.1:11470/hlsv2/'+id+'/master.m3u8?mediaURL='+encodeURIComponent('http://127.0.0.1:11470/'+hash+'/4'),
    'http://127.0.0.1:11470/hlsv2/'+id+'/master.m3u8?mediaURL='+encodeURIComponent(original)+'&x=evil',
    'http://127.0.0.1:11470/hlsv2/'+id+'/destroy',
    'http://127.0.0.1:11470/hlsv2/'+id+'/master.m3u8?mediaURL='+encodeURIComponent(original)+'&maxAudioChannels=999'
  ])assert.equal(makeLocalMediaUrl(bad,base),null);
});
