import test from 'node:test';
import {gunzipSync} from 'node:zlib';
// Real FFmpeg/libx264-encoded 1280x720 MPEG-TS fixture. Old fake sync-byte
// packets cannot establish real quality and must never pass strict gating.
const real720=gunzipSync(Buffer.from('H4sIAAAAAAACA9VXTWgTQRR+W9taQW1atfXfqSKK2HQ3qaUtLrQNNr0UCkIRaV0mu5Nk6f5ldmMSFQnSg4h49KSg9OJRvXgq6MHfg1ereKgeREE0eBbim1WoF/G32H27vH0z72e+/eZl2KSH2mIwUtsL0j2AulQH6XN141i71Dw6ansst+Yo4ydNnclK6UzqSX1lSXoIYgA314XYQarBgVuNt+vRkPQEYm+42R5ifwU12CbUpvnxyQhgHwZ59QTA6bVnBe+LANVqUxesXqBiuKYmdG7k7q4X0lDH+8YhgFUQqrkHa28IX/5p6yN8NDfV68deHnk3/+b52J3LBxfI8z0fPpYTfb2km+guZ0RBkycVuZ8kFaYMZAfQMRbHgJ7xiSPp7l4yPJnCSIPp6Ei5XsVi2YAkZDnZnZATSZzMB4E32NNTKpXiJ02DuRZNS9DmxF2e6xErxfOBbWGc6wWm6/iDRKcZqqsy4SyrKsRgGcvVZ1R5EC9CHWpVfCZGxGaqYVLiFzNoycTzKxiOWuOGqsRlDEFFbLPMDE3UEhkap06OqUof0fPctamGqQoJOLMs08eI/nK/oQdo6AUbtcGoccp1mJpQDioKyVIfsbcHmufPmB4mfitS8DQ3m/WZSAzyHJN89RCxXHeG5nGgLc35Fv6QlyaUrxPC4/BwPd20aSAwmU7AuEUxGuczVpHTiqa7tkdDdEhUwKnpYC0M5FTEZDm1mXiJEjNz+cBDa4ZV0K0mDoUmYt+AQ802HbGyzhymF0W5sITgiDM/L5jXVZ1niZ1BYgS1OFATybhMCgKBKsf70PREmfBJy2rfABp+wDy1l5gesox7iZvQiztWUGWoYqux87ODbRegQSnchdSTfmhpevwbgtg3/kLYs+niyrsR+6biikT28xuxd6xAWIuioeD47q71s5kqXJzyIPbpEjRI/HtB7J38r+W/8b45oi3zDLFviR7ucthSiH1rd8v51PXN12BP53ZoPDxXherUlX8qV5eN920RbRlxRm6P8Bm544fuE2FbxV7vjr0d3Q8t9x+C1Lp/EUjnvn8rf877zgjzvivCZ2Tsj5PDzynoOPefekp8v0eY9/YIn5HJaVjOf5XLhr36BXsrMxvkEAAA','base64'));

import assert from 'node:assert/strict';
import {probeHlsFirstSegment} from '../real-hls-segment-probe.mjs';
import {exactVaPlayerMovie,fromCineStreamVaPlayer,
  configuredCineStreamHosts,cineStreamVaPlayerEnabled} from '../cinestream-vaplayer.mjs';

const movie={id:45745,media:'movie',originalTitle:'Sintel',title:'Sintel',
  year:2010,imdbId:'tt1727587'};
const master='https://cdn.example/video/master.m3u8';
const variant='https://cdn.example/video/1080.m3u8';
const seg='https://cdn.example/video/first.ts';
const valid=()=>({
  status_code:'200',data:{title:'Sintel 2010',imdb_id:'tt1727587',
    file_name:'Sintel.2010.1080p.mp4',stream_urls:[master]}
});
const hlsMaster=[
 '#EXTM3U','#EXT-X-VERSION:3',
 '#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360',
 '360.m3u8',
 '#EXT-X-STREAM-INF:BANDWIDTH=4200000,RESOLUTION=1920x1080',
 '1080.m3u8'
].join('\n');
const hlsMedia=[
 '#EXTM3U','#EXT-X-VERSION:3','#EXT-X-TARGETDURATION:10',
 '#EXTINF:9.9,','first.ts','#EXT-X-ENDLIST'
].join('\n');
function response(body,status=200,contentType='application/vnd.apple.mpegurl'){
  return new Response(body,{status,headers:{
    'Content-Type':contentType,'Access-Control-Allow-Origin':'*'
  }});
}
function fake(mediaStatus=206,{identity=valid(),segmentHost='cdn.example',
  segmentBody=real720}={}){
  const calls=[];
  const fetchImpl=async(url,opts)=>{
    const u=new URL(url);calls.push({path:u.pathname,host:u.hostname});
    if(u.hostname==='streamdata.vaplayer.ru')return response(
      JSON.stringify(identity),200,'application/json');
    if(u.pathname==='/video/master.m3u8')return response(hlsMaster);
    if(u.pathname==='/video/1080.m3u8')return response(hlsMedia);
    if(u.hostname===segmentHost&&u.pathname==='/video/first.ts')
      return response(segmentBody,mediaStatus,'video/mp2t');
    return response('Not available',404,'text/plain');
  };
  return {fetchImpl,calls};
}
test('CineStream uses exact IMDb and original film year, never a similar title',()=>{
  assert.equal(exactVaPlayerMovie(movie,valid()),true);
  for(const meta of [
    {...movie,imdbId:'tt0000000'},{...movie,year:2011},
    {...movie,originalTitle:'Sintel 2'},{...movie,media:'tv',season:1,episode:1}
  ])assert.equal(exactVaPlayerMovie(meta,valid()),false);
  assert.equal(exactVaPlayerMovie(movie,{
    ...valid(),data:{...valid().data,title:'Other Film 2010'}
  }),false);
});
test('Approved host 1080 HLS playlist and first actual MPEG-TS video packet pass',async()=>{
  const {fetchImpl,calls}=fake();
  const res=await probeHlsFirstSegment(master,{
    fetchImpl,allowedHosts:['cdn.example']
  });
  assert.deepEqual(res,{ok:true,quality:1080,width:1920,playableUrl:variant});
  assert.deepEqual(calls.map(x=>x.path),[
    '/video/master.m3u8','/video/1080.m3u8','/video/first.ts'
  ]);
});
test('Real-life VaPlayer symptom: valid master + variant but first segment 403 is NOT playable',async()=>{
  const {fetchImpl}=fake(403);
  assert.deepEqual(await probeHlsFirstSegment(master,{
    fetchImpl,allowedHosts:['cdn.example']
  }),{ok:false,reason:'segment_http_403'});
  assert.deepEqual(await fromCineStreamVaPlayer(movie,{
    enabled:true,mediaHosts:['cdn.example'],fetchImpl
  }),[]);
});
test('CineStream VaPlayer uses actual 720p SPS even if HLS master claims 1080p',async()=>{
  const {fetchImpl,calls}=fake();
  const sources=await fromCineStreamVaPlayer(movie,{
    enabled:true,mediaHosts:['cdn.example'],fetchImpl
  });
  assert.equal(sources.length,1);
  assert.equal(sources[0].quality,720); // actual SPS beats claimed 1080p
  assert.equal(sources[0].url,variant);
  assert.match(sources[0].name,/CineStream.*VaPlayer/);
  assert.ok(calls.some(x=>x.host==='streamdata.vaplayer.ru'));
});
test('Blocked 1080p falls back to verified 720p, returning the working playlist',async()=>{
  const highest='https://cdn.example/video/1080.m3u8';
  const fallback='https://cdn.example/video/720.m3u8';
  const manifest=[
    '#EXTM3U','#EXT-X-STREAM-INF:RESOLUTION=1920x1080','1080.m3u8',
    '#EXT-X-STREAM-INF:RESOLUTION=1280x720','720.m3u8'
  ].join('\n');
  const playlist='#EXTM3U\n#EXTINF:9.9,\nfirst.ts\n#EXT-X-ENDLIST';
  const transport=real720;
  const seen=[];
  const fetchImpl=async (url)=>{
    seen.push(url);
    if(url.includes('streamdata.vaplayer.ru'))return response(
      JSON.stringify({...valid(),data:{...valid().data,stream_urls:[master]}}),
      200,'application/json');
    if(url===master)return response(manifest);
    if(url===highest||url===fallback)return response(playlist);
    if(url.endsWith('/video/first.ts'))return response('Forbidden',403);
    return response('missing',404);
  };
  // Give each rendition its own segment, so the 720p one can succeed.
  const first=async (url,opts)=>{
    if(url===fallback)return response(playlist.replace('first.ts','first-720.ts'));
    if(url.endsWith('/first-720.ts')){seen.push(url);return response(transport,206,'video/mp2t');}
    return fetchImpl(url,opts);
  };
  const check=await probeHlsFirstSegment(master,{
    fetchImpl:first,allowedHosts:['cdn.example']
  });
  assert.deepEqual(check,{ok:true,quality:720,width:1280,playableUrl:fallback});
  const streams=await fromCineStreamVaPlayer(movie,{
    enabled:true,mediaHosts:['cdn.example'],fetchImpl:first
  });
  assert.deepEqual(streams.map(x=>[x.quality,x.url]),[[720,fallback]]);
  assert.ok(seen.some(x=>x===highest));
});
test('Playlist without browser CORS is refused before trying media segments',async()=>{
  const calls=[];
  const fetchImpl=async url=>{
    calls.push(url);
    return new Response(hlsMaster,{status:200,
      headers:{'Content-Type':'application/vnd.apple.mpegurl'}});
  };
  const result=await probeHlsFirstSegment(master,{
    fetchImpl,allowedHosts:['cdn.example']
  });
  assert.deepEqual(result,{ok:false,reason:'invalid_master'});
  assert.deepEqual(calls,[master]);
});
test('Five HD master renditions still probe 1080p and 720p fallback',async()=>{
  const master='https://cdn.example/video/multi.m3u8';
  const variants=[4320,2160,1440,1080,720];
  const playlist=[
    '#EXTM3U',
    ...variants.flatMap(height=>[
      '#EXT-X-STREAM-INF:RESOLUTION='+Math.floor(height*16/9)+'x'+height,
      height+'.m3u8'
    ])
  ].join('\n');
  const calls=[];
  const transport=new Uint8Array([0x47,...new Array(2047).fill(0)]);
  const fetchImpl=async url=>{
    calls.push(url);
    if(url===master)return response(playlist);
    const suffix=url.split('/').at(-1);
    if(suffix==='720.m3u8')return response('#EXTM3U\n#EXTINF:8,\n720.ts');
    if(suffix==='1080.m3u8')return response('#EXTM3U\n#EXTINF:8,\n1080.ts');
    if(suffix==='4320.m3u8'||suffix==='2160.m3u8')
      return response('#EXTM3U\n#EXTINF:8,\nblocked.ts');
    if(suffix==='720.ts')return response(transport,206,'video/mp2t');
    if(suffix==='1080.ts'||suffix==='blocked.ts')
      return response('Forbidden',403,'text/plain');
    throw Error('Unexpected URL '+url);
  };
  const result=await probeHlsFirstSegment(master,{
    fetchImpl,allowedHosts:['cdn.example']
  });
  assert.deepEqual(result,{
    ok:true,quality:720,width:1280,
    playableUrl:'https://cdn.example/video/720.m3u8'
  });
  assert.ok(calls.some(x=>x.endsWith('1080.m3u8')),'1080p was examined');
  assert.ok(calls.some(x=>x.endsWith('720.ts')),'720p video was actually fetched');
  assert.equal(calls.some(x=>x.endsWith('1440.m3u8')),false,
    'Do not probe redundant 1440p when 1080p and 720p are available');
});
test('A wide, low-height variant is not mislabeled as 720p HD',async()=>{
  const master='https://cdn.example/video/low.m3u8';
  let requests=0;
  const fetchImpl=async()=>{requests++;return response(
    '#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1280x360\n360.m3u8'
  );};
  const result=await probeHlsFirstSegment(master,{
    fetchImpl,allowedHosts:['cdn.example']
  });
  assert.deepEqual(result,{ok:false,reason:'no_hd_variant'});
  assert.equal(requests,1);
});
test('Actual-sized 172KB media playlist is playable but resolution is NOT falsely claimed HD',async()=>{
  const hugeRows=Array.from({length:2600},(_,i)=>
    '#EXT-X-DATERANGE:ID="sample-'+i+'",CLASS="metadata",DURATION=9.99').join('\n');
  const mediaPlaylist='#EXTM3U\n'+hugeRows+
    '\n#EXTINF:9.9,\nsegment.ts\n#EXT-X-ENDLIST';
  assert.ok(Buffer.byteLength(mediaPlaylist)>160_000,
    'Regression must exceed original 160KB reader limit');
  assert.ok(Buffer.byteLength(mediaPlaylist)<512_000);
  const calls=[];
  const hls='https://cdn.example/video/large.m3u8';
  const fetchImpl=async url=>{
    calls.push(url);
    if(url===hls)return response(mediaPlaylist);
    if(url==='https://cdn.example/video/segment.ts')
      return response(new Uint8Array([0x47,...new Array(2047).fill(0)]),206,'video/mp2t');
    throw Error('Unexpected request');
  };
  const result=await probeHlsFirstSegment(hls,{
    fetchImpl,allowedHosts:['cdn.example']
  });
  assert.deepEqual(result,{
    ok:true,quality:0,width:0,playableUrl:hls
  });
  assert.equal(calls.length,2);
});
test('Playlist larger than the bounded 512KB cap is rejected before any video request',async()=>{
  const hls='https://cdn.example/video/oversize.m3u8';
  const tooBig='#EXTM3U\n'+'#EXT-X-COMMENT:'.repeat(44000)+
    '\n#EXTINF:10,\nsegment.ts';
  const calls=[];
  const fetchImpl=async url=>{
    calls.push(url);
    return response(tooBig);
  };
  const result=await probeHlsFirstSegment(hls,{
    fetchImpl,allowedHosts:['cdn.example']
  });
  assert.deepEqual(result,{ok:false,reason:'invalid_master'});
  assert.deepEqual(calls,[hls]);
});
test('Extensionless signed-path HLS segment passes only when real video bytes and CORS match',async()=>{
  const playlist='#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:8,\nsegment?id=abcd\n#EXT-X-ENDLIST';
  const hls='https://cdn.example/video/extensionless.m3u8';
  const ts=new Uint8Array([0x47,...new Array(2047).fill(0)]);
  const seen=[];
  const fetchImpl=async url=>{
    seen.push(url);
    if(url===hls)return response(playlist);
    if(url==='https://cdn.example/video/segment?id=abcd')
      return response(ts,206,'video/mp2t');
    throw Error('Unexpected request');
  };
  assert.deepEqual(await probeHlsFirstSegment(hls,{
    fetchImpl,allowedHosts:['cdn.example']
  }),{ok:true,quality:0,width:0,playableUrl:hls});
  assert.equal(seen.length,2);
});
test('Extensionless HLS segment is rejected if it switches to an unapproved CDN host',async()=>{
  const hls='https://cdn.example/video/master.m3u8';
  const content='#EXTM3U\n#EXTINF:9.9,\nhttps://other.example/media?id=1';
  const calls=[];
  const fetchImpl=async url=>{
    calls.push(url);
    if(url===hls)return response(content);
    throw Error('An unapproved host was fetched');
  };
  const result=await probeHlsFirstSegment(hls,{
    fetchImpl,allowedHosts:['cdn.example']
  });
  assert.deepEqual(result,{ok:false,reason:'unapproved_segment_host_or_url'});
  assert.deepEqual(calls,[hls]);
});
test('An HLS segment server refusing byte ranges can still play using ordinary GET',async()=>{
  const url='https://cdn.example/video/direct.m3u8';
  const checks=[];
  const playlist='#EXTM3U\n#EXTINF:9.9,\nchunk?token=abc';
  const video=new Uint8Array([0x47,...new Array(2047).fill(0)]);
  const fetchImpl=async (uri,options)=>{
    if(uri===url)return response(playlist);
    checks.push(options.headers);
    if(options.headers.Range)return response('Range refused',416,'text/plain');
    return response(video,200,'video/mp2t');
  };
  assert.deepEqual(await probeHlsFirstSegment(url,{
    fetchImpl,allowedHosts:['cdn.example']
  }),{ok:true,quality:0,width:0,playableUrl:url});
  assert.equal(checks.length,2);
  assert.equal(checks[0].Range,'bytes=0-2047');
  assert.equal(checks[1].Range,undefined);
});
test('Server returning HTTP 403 even without Range is rejected, not mislabeled CORS',async()=>{
  const url='https://cdn.example/video/forbidden.m3u8';
  const calls=[];
  const fetchImpl=async (uri,options)=>{
    if(uri===url)return response('#EXTM3U\n#EXTINF:9.9,\nchunk.ts');
    calls.push(options.headers);
    return response('Forbidden',403,'text/plain');
  };
  assert.deepEqual(await probeHlsFirstSegment(url,{
    fetchImpl,allowedHosts:['cdn.example']
  }),{ok:false,reason:'segment_http_403'});
  assert.equal(calls.length,2);
});
test('200 video segment without CORS is distinctly rejected',async()=>{
  const url='https://cdn.example/video/no-cors.m3u8';
  const video=new Uint8Array([0x47,...new Array(2000).fill(0)]);
  const fetchImpl=async uri=>{
    if(uri===url)return response('#EXTM3U\n#EXTINF:9.9,\nchunk.ts');
    return new Response(video,{status:200,headers:{'Content-Type':'video/mp2t'}});
  };
  assert.deepEqual(await probeHlsFirstSegment(url,{
    fetchImpl,allowedHosts:['cdn.example']
  }),{ok:false,reason:'segment_cors_missing'});
});
test('Standalone media playlist never becomes a 720p CineStream source without real resolution data',async()=>{
  const direct='https://cdn.example/video/direct.m3u8';
  const piece='https://cdn.example/video/actual.ts';
  const fetchImpl=async (url)=>{
    if(url==='https://streamdata.vaplayer.ru/api.php?imdb=tt1727587&type=movie')
      return response(JSON.stringify({...valid(),
        data:{...valid().data,stream_urls:[direct]}}),200,'application/json');
    if(url===direct)return response('#EXTM3U\n#EXTINF:9,\nactual.ts');
    if(url===piece)return response(new Uint8Array([0x47,...new Array(2047).fill(0)]),206,'video/mp2t');
    throw Error('Unexpected URL');
  };
  assert.deepEqual(await probeHlsFirstSegment(direct,{
    fetchImpl,allowedHosts:['cdn.example']
  }),{ok:true,quality:0,width:0,playableUrl:direct},
  'Segment is real, but its encoded dimensions remain unknown');
  assert.deepEqual(await fromCineStreamVaPlayer(movie,{
    enabled:true,mediaHosts:['cdn.example'],fetchImpl
  }),[],'Unknown height must not be promoted as 720p');
});
test('No arbitrary unapproved media hosts or dangerous HTTP URLs can be probed',async()=>{
  const {fetchImpl,calls}=fake();
  const rejected=await probeHlsFirstSegment('http://cdn.example/video/master.m3u8',{
    fetchImpl,allowedHosts:['cdn.example']
  });
  assert.equal(rejected.ok,false);
  assert.deepEqual(calls,[]);
  assert.equal((await probeHlsFirstSegment(master,{fetchImpl,
    allowedHosts:['unrelated.example']})).ok,false);
  assert.deepEqual(calls,[]);
});
test('Operator opt-in and explicit media host allowlist are required; fail fast',async()=>{
  const {fetchImpl,calls}=fake();
  assert.equal(cineStreamVaPlayerEnabled(''),false);
  assert.equal(cineStreamVaPlayerEnabled('1'),true);
  assert.deepEqual(configuredCineStreamHosts('cdn.example,cdn.example,127.0.0.1,localhost'),
    ['cdn.example']);
  assert.deepEqual(await fromCineStreamVaPlayer(movie,{
    enabled:false,mediaHosts:['cdn.example'],fetchImpl
  }),[]);
  assert.deepEqual(await fromCineStreamVaPlayer(movie,{
    enabled:true,mediaHosts:[],fetchImpl
  }),[]);
  assert.deepEqual(calls,[]);
});
test('Untrusted license, identity and episode metadata cannot produce cross-title video',async()=>{
  for(const candidate of [
    {...valid(),data:{...valid().data,imdb_id:'tt0555555'}},
    {...valid(),data:{...valid().data,title:'Sintel 2011'}},
    {...valid(),data:{...valid().data,title:'Entirely Different 2010'}},
    {...valid(),status_code:404}
  ]){
    const {fetchImpl,calls}=fake(206,{identity:candidate});
    const result=await fromCineStreamVaPlayer(movie,{
      enabled:true,mediaHosts:['cdn.example'],fetchImpl
    });
    assert.deepEqual(result,[]);
    assert.equal(calls.length,1,'Reject before contacting untrusted media host');
  }
});
test('DRM playlist and HTML masquerading as segment are rejected',async()=>{
  const drm=response(hlsMedia.replace('#EXT-X-VERSION:3',
    '#EXT-X-VERSION:3\n#EXT-X-KEY:METHOD=AES-128,URI="key"'));
  const fetchDrm=async(url)=>{
    const u=new URL(url);
    if(u.pathname.endsWith('master.m3u8'))return response(hlsMaster);
    if(u.pathname.endsWith('1080.m3u8'))return drm;
    throw Error('Unexpected fetch');
  };
  assert.equal((await probeHlsFirstSegment(master,{
    fetchImpl:fetchDrm,allowedHosts:['cdn.example']
  })).ok,false);
  const {fetchImpl}=fake(200,{segmentBody:new TextEncoder()
    .encode('<html><body>403 Forbidden</body></html>')});
  assert.deepEqual(await probeHlsFirstSegment(master,{
    fetchImpl,allowedHosts:['cdn.example']
  }),{ok:false,reason:'segment_not_video'});
});
