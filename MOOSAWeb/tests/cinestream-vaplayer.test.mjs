import test from 'node:test';
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
  segmentBody=new Uint8Array([0x47,...new Array(2047).fill(0)])}={}){
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
  assert.deepEqual(res,{ok:true,quality:1080,width:1920});
  assert.deepEqual(calls.map(x=>x.path),[
    '/video/master.m3u8','/video/1080.m3u8','/video/first.ts'
  ]);
});
test('Real-life VaPlayer symptom: valid master + variant but first segment 403 is NOT playable',async()=>{
  const {fetchImpl}=fake(403);
  assert.deepEqual(await probeHlsFirstSegment(master,{
    fetchImpl,allowedHosts:['cdn.example']
  }),{ok:false,reason:'segment_http_or_cors'});
  assert.deepEqual(await fromCineStreamVaPlayer(movie,{
    enabled:true,mediaHosts:['cdn.example'],fetchImpl
  }),[]);
});
test('Ported real CineStream VaPlayer accepts only first-segment-verified sources',async()=>{
  const {fetchImpl,calls}=fake();
  const sources=await fromCineStreamVaPlayer(movie,{
    enabled:true,mediaHosts:['cdn.example'],fetchImpl
  });
  assert.equal(sources.length,1);
  assert.equal(sources[0].quality,1080);
  assert.equal(sources[0].url,master);
  assert.match(sources[0].name,/CineStream.*VaPlayer/);
  assert.ok(calls.some(x=>x.host==='streamdata.vaplayer.ru'));
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
