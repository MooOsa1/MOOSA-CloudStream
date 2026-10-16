import test from 'node:test';
import assert from 'node:assert/strict';
import {configuredAddons,fromArchive,fromWebAddons,resolveWebSources} from '../providers.mjs';

function mockFetch(router) {
  return async input=>{
    const url=new URL(input);
    const body=router(url);
    if(body===undefined)return new Response(JSON.stringify({error:'not found'}),{
      status:404,headers:{'Content-Type':'application/json'}});
    return new Response(JSON.stringify(body),{
      status:200,headers:{'Content-Type':'application/json'}});
  };
}
const sample={media:'movie',id:99,title:'Test Example',originalTitle:'Test Example',
  imdbId:'tt0000099',year:1942};

test('Website needs no pairing and only accepts operator-declared HTTPS addon manifests',()=>{
  assert.deepEqual(configuredAddons('["https://trusted.example/manifest.json","http://localhost/manifest.json","https://trusted.example/manifest.json","https://127.0.0.1/manifest.json"]'),
    ['https://trusted.example/manifest.json']);
  assert.deepEqual(configuredAddons('{invalid json}'),[]);
});

test('Server fetches only direct quality-declared media from a matching addon',async()=>{
  const f=mockFetch(u=>{
    if(u.pathname==='/manifest.json')return {name:'Licensed Source',types:['movie','series'],
      resources:[{name:'stream',types:['movie','series']}],idPrefixes:['tt']};
    if(u.pathname==='/stream/movie/tt0000099.json')return {streams:[
      {name:'1080p WEB',url:'https://cdn.example/test.mp4',
        subtitles:[{url:'https://cdn.example/ar.vtt',lang:'ara',label:'Arabic'}]},
      {name:'2160p',url:'https://cdn.example/fourk.m3u8'},
      {name:'480p',url:'https://cdn.example/sd.mp4'},
      {name:'2160p download',url:'https://cdn.example/download.m3u8'},
      {name:'2160p',infoHash:'0'.repeat(40)},
      {name:'2160p',url:'http://192.168.1.1/file.mp4'}
    ]};
  });
  const result=await fromWebAddons(sample,{fetchImpl:f,addons:['https://trusted.example/manifest.json']});
  assert.deepEqual(result.map(x=>x.quality),[2160,1080]);
  assert.equal(result[1].subtitles[0].language,'ar');
  assert.equal(result[0].name.startsWith('Licensed Source'),true);
});

test('For television, server passes the exact season and episode in the IMDb request',async()=>{
  const requested=[];
  const f=mockFetch(u=>{
    requested.push(u.pathname);
    if(u.pathname==='/manifest.json')return {name:'TV Licensed',types:['series'],
      resources:['stream'],idPrefixes:['tt']};
    if(u.pathname==='/stream/series/tt0012345%3A2%3A7.json' ||
       u.pathname==='/stream/series/tt0012345:2:7.json')
      return {streams:[{name:'720p',url:'https://cdn.example/show/s02e07.mp4'}]};
    return {streams:[]};
  });
  const media={media:'tv',id:777,title:'A Show',imdbId:'tt0012345',season:2,episode:7};
  const result=await fromWebAddons(media,{fetchImpl:f,addons:['https://trusted.example/manifest.json']});
  assert.equal(result.length,1);
  assert.equal(result[0].quality,720);
  assert.equal(requested.filter(x=>x.includes('/stream/')).length,1);
  assert.match(requested.find(x=>x.includes('/stream/')),/2(%3A|:)7/);
});

test('Archive source requires exact title and explicit Creative Commons/public-domain rights',async()=>{
  const router=u=>{
    if(u.hostname==='archive.org'&&u.pathname==='/advancedsearch.php')
      return {response:{docs:[
        {identifier:'licensed-film',title:'Test Example'},
        {identifier:'wrong-film',title:'Some Other Film'},
        {identifier:'bad-license',title:'Test Example'}
      ]}};
    if(u.pathname==='/metadata/licensed-film')return {
      metadata:{title:'Test Example',licenseurl:'https://creativecommons.org/licenses/by/4.0/'},
      files:[{name:'original_1080p.mp4',size:'2500000',height:'1080'},
        {name:'trailer_2160p.mp4',size:'5000000',height:'2160'},
        {name:'sd_480p.mp4',size:'2500000',height:'480'}]};
    if(u.pathname==='/metadata/bad-license')return {
      metadata:{title:'Test Example',licenseurl:'https://example.invalid/madeup-license'},
      files:[{name:'pirate_2160p.mp4',size:'5000000',height:'2160'}]};
  };
  const result=await fromArchive(sample,{fetchImpl:mockFetch(router)});
  assert.equal(result.length,1);
  assert.equal(result[0].quality,1080);
  assert.match(result[0].url,/licensed-film\/original_1080p.mp4$/);
  const notTv=await fromArchive({...sample,media:'tv'},{fetchImpl:mockFetch(router)});
  assert.deepEqual(notTv,[]);
});

test('Server resolver returns empty results rather than fabricating sources',async()=>{
  const f=mockFetch(()=>({response:{docs:[]}}));
  const empty=await resolveWebSources({media:'movie',id:2001,title:'Not Found',
    imdbId:'tt2001222'}, {fetchImpl:f,addons:[]});
  assert.deepEqual(empty,[]);
});
