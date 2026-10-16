import test from 'node:test';
import assert from 'node:assert/strict';
import {
 stremioProtocolManifest,pinnedStremioMovies,resolveStremioProtocolMovie
} from '../cloudstream-stremio-interop.mjs';
import {fromWebAddons} from '../providers.mjs';
import {createServer} from '../server.mjs';

const page='https://commons.wikimedia.org/wiki/File:Elephants_Dream_(2006).webm';
const license='https://creativecommons.org/licenses/by/2.5';
const orig={
  url:'https://upload.wikimedia.org/wikipedia/commons/a/ab/elephants-original.webm',
  sourcePage:page,licenseUrl:license,quality:1080,attribution:'Blender Foundation'
};
const optimized={...orig,codecOptimized:true,
 url:'https://upload.wikimedia.org/wikipedia/commons/transcoded/a/ab/elephants-1080p.vp9.webm'};
const getFilm=async id=>({id,available:true,sources:[orig,optimized]});

test('Published HTTP manifest has Stremio/CineStream compatible resources without claiming Android execution',()=>{
  const m=stremioProtocolManifest();
  assert.equal(m.id,'org.moosa.web.licensed');
  assert.equal(m.resources[0].name,'stream');
  assert.deepEqual(m.resources[0].types,['movie']);
  assert.deepEqual(m.idPrefixes,['tt']);
  assert.deepEqual(m.types,['movie']);
  assert.equal(m.behaviorHints.p2p,false);
  assert.deepEqual(pinnedStremioMovies().map(x=>x.imdbId),
    ['tt1254207','tt1727587','tt2285752','tt0807840']);
});
test('Elephants Dream returns Safari-compatible version first over real HTTP addon schema',async()=>{
  const {streams}=await resolveStremioProtocolMovie('tt0807840',{getFilm});
  assert.equal(streams.length,2);
  assert.match(streams[0].title,/Safari 4:2:0/);
  assert.equal(streams[0].url,optimized.url);
  assert.ok(streams.every(x=>x.name==='MOOSA Commons' && x.title.includes('1080p')));
});
test('Unknown or malicious IMDb IDs cannot turn bridge into arbitrary third-party resolver',async()=>{
  let calls=0;
  for(const id of ['tt0807840:1:1','tt08078400','tt99999999','../../evil',
    'https://evil.example/movie.mp4','tt0807840?url=evil']){
    const res=await resolveStremioProtocolMovie(id,{
      getFilm:async()=>{calls++;return getFilm('elephants-dream');}
    });
    assert.deepEqual(res,{streams:[]});
  }
  assert.equal(calls,0);
});
test('Independent rights and exact film identity are mandatory before any sources',async()=>{
  for(const response of [
    {id:'sintel',available:true,sources:[orig]},
    {id:'elephants-dream',available:false,sources:[orig]},
    {id:'elephants-dream',available:true,sources:[{
      ...orig,url:'https://streaming.evil.example/malware.mp4'
    }]},
    {id:'elephants-dream',available:true,sources:[{
      ...orig,licenseUrl:'https://evil.example/claimed-license'
    }]},
    {id:'elephants-dream',available:true,sources:[{
      ...orig,sourcePage:'https://evil.example/malware'
    }]},
    {id:'elephants-dream',available:true,sources:[{
      ...orig,quality:480
    }]}
  ]){
    const {streams}=await resolveStremioProtocolMovie('tt0807840',{
      getFilm:async()=>response
    });
    assert.deepEqual(streams,[]);
  }
});
test('CineStream-compatible Stremio HTTP addon can consume published movie source contract',async()=>{
  const adapterOrigin='https://moosa-web.example/api/stremio/';
  const manifest=stremioProtocolManifest();
  let requested=[];
  const fetchImpl=async url=>{
    const u=new URL(url);requested.push(u.pathname);
    let data;
    if(u.pathname==='/api/stremio/manifest.json')data=manifest;
    else if(u.pathname==='/api/stremio/stream/movie/tt0807840.json')
      data=await resolveStremioProtocolMovie('tt0807840',{getFilm});
    else throw new Error('Unexpected path '+u.pathname);
    return new Response(JSON.stringify(data),{
      status:200,headers:{'Content-Type':'application/json'}
    });
  };
  const resolved=await fromWebAddons({
    media:'movie',id:9761,title:'Elephants Dream',imdbId:'tt0807840'
  },{fetchImpl,addons:[adapterOrigin+'manifest.json']});
  assert.equal(resolved.length,2);
  assert.equal(resolved[0].quality,1080);
  assert.equal(resolved[1].quality,1080);
  assert.deepEqual(requested,[
    '/api/stremio/manifest.json',
    '/api/stremio/stream/movie/tt0807840.json'
  ]);
});
test('Public manifest works via actual Node app; unsupported series not exposed',async()=>{
  const app=createServer();
  await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));
  try{
    const base='http://127.0.0.1:'+app.address().port;
    const resp=await fetch(base+'/api/stremio/manifest.json');
    assert.equal(resp.status,200);
    assert.deepEqual((await resp.json()).resources,
      [{name:'stream',types:['movie'],idPrefixes:['tt']}]);
    assert.equal((await fetch(base+'/api/stremio/manifest.json?code=untrusted')).status,400);
    assert.equal((await fetch(base+'/api/stremio/stream/series/tt0807840:1:1.json')).status,404);
    const unknown=await fetch(base+'/api/stremio/stream/movie/tt999999999.json');
    assert.equal(unknown.status,200);
    assert.deepEqual(await unknown.json(),{streams:[]});
  }finally{await new Promise(resolve=>app.close(resolve));}
});
