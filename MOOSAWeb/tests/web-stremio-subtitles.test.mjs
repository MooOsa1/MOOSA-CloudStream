import test from 'node:test';
import assert from 'node:assert/strict';
import {
  stremioSubtitleIdentity,selectStremioSubtitles,collectStremioAddonSubtitles
} from '../web-stremio-subtitles.mjs';
import {resolveWebSources} from '../providers.mjs';

const licensedAddon='https://authorized.example/addons/open/manifest.json';
const movie={media:'movie',id:980005,title:'Demo Licensed Movie',
  originalTitle:'Demo Licensed Movie',year:2012,imdbId:'tt1234567'};
const tv={media:'tv',id:980006,title:'Demo Licensed Series',
  originalTitle:'Demo Licensed Series',year:2020,imdbId:'tt7654321',
  season:2,episode:4};
function fakeFetch(router){
  return async url=>{
    const u=new URL(url);
    const data=router(u);
    if(data instanceof Error)throw data;
    if(data===undefined)return new Response(JSON.stringify({error:'not found'}),{
      status:404,headers:{'Content-Type':'application/json'}});
    return new Response(JSON.stringify(data),{
      headers:{'Content-Type':'application/json'}});
  };
}
const subtitles=[
  {lang:'en',id:'English',url:'https://sub.example/film.en.vtt'},
  {lang:'ar',id:'العربية',url:'https://sub.example/film.ar.srt'},
  {lang:'fr',id:'Français',url:'https://sub.example/film.fr.srt'},
  {lang:'ar',id:'Duplicate',url:'https://sub.example/film.ar.srt'},
  {lang:'ar',id:'Malware',url:'http://localhost/hack.srt'},
  {lang:'en',id:'ZIP',url:'https://sub.example/bundle.zip'},
  {lang:'en',id:'Protected',url:'https://sub.example/private.vtt',
    headers:{Authorization:'Bearer secret'}}
];
test('Original CineStream/StreamPlay movie and exact TV episode Stremio ids',()=>{
  assert.deepEqual(stremioSubtitleIdentity(movie),{
    type:'movie',id:'tt1234567'});
  assert.deepEqual(stremioSubtitleIdentity(tv),{
    type:'series',id:'tt7654321:2:4'});
  assert.equal(stremioSubtitleIdentity({...tv,episode:null}),null);
  assert.equal(stremioSubtitleIdentity({...tv,episode:0}),null);
  assert.equal(stremioSubtitleIdentity({...movie,imdbId:'../evil'}),null);
});
test('Only direct HTTPS Arabic/English SRT and VTT tracks with safe headers',()=>{
  const result=selectStremioSubtitles({subtitles},{sourceName:'Upstream Stremio'});
  assert.equal(result.length,2);
  assert.deepEqual(result.map(x=>x.language),['ar','en']);
  assert.deepEqual(result.map(x=>x.url),[
    'https://sub.example/film.ar.srt','https://sub.example/film.en.vtt'
  ]);
  assert.equal(result[0].source,'Upstream Stremio');
});
test('Subtitle-only addon works even with no playable stream resource',async()=>{
  const calls=[];
  const fetchImpl=fakeFetch(u=>{
    calls.push(u.pathname);
    if(u.pathname.endsWith('/manifest.json'))return{
      name:'Official subtitle addon',types:['movie'],
      resources:[{name:'subtitles',types:['movie'],idPrefixes:['tt']}],
      idPrefixes:['tt']
    };
    if(u.pathname.endsWith('/subtitles/movie/tt1234567.json'))
      return {subtitles};
  });
  const result=await collectStremioAddonSubtitles(movie,{
    addons:[licensedAddon],fetchImpl
  });
  assert.deepEqual(result.map(x=>x.language),['ar','en']);
  assert.deepEqual(calls,[
    '/addons/open/manifest.json',
    '/addons/open/subtitles/movie/tt1234567.json'
  ]);
});
test('CineStream TV subtitles request must contain the exact original season/episode',async()=>{
  const seen=[];
  const fetchImpl=fakeFetch(u=>{
    seen.push(u.pathname);
    if(u.pathname.endsWith('manifest.json'))return{
      name:'TV Subs',types:['series'],resources:['subtitles'],
      idPrefixes:['tt']
    };
    return {subtitles:[{lang:'ar',url:'https://sub.example/show.s02e04.vtt'}]};
  });
  const result=await collectStremioAddonSubtitles(tv,{
    addons:[licensedAddon],fetchImpl
  });
  assert.equal(result.length,1);
  assert.ok(seen[1].endsWith('tt7654321%3A2%3A4.json')||
            seen[1].endsWith('tt7654321:2:4.json'));
  assert.equal(await collectStremioAddonSubtitles({...tv,episode:0},{
    addons:[licensedAddon],fetchImpl
  }).then(x=>x.length),0);
});
test('Wrong subtitle resource type, IMDb prefix or malformed addon URL do not leak external requests',async()=>{
  const calls=[];
  const fetchImpl=fakeFetch(u=>{
    calls.push(u.pathname);
    return {name:'Movie-only metadata addon',types:['movie','series'],
      resources:[{name:'subtitles',types:['movie'],idPrefixes:['tt9']}],
      idPrefixes:['tt9']};
  });
  for(const meta of [movie,tv]){
    const result=await collectStremioAddonSubtitles(meta,{
      addons:[licensedAddon,'http://private.example/manifest.json',
        'https://127.0.0.1/manifest.json'],fetchImpl
    });
    assert.deepEqual(result,[]);
  }
  assert.equal(calls.length,2);
});
test('Unreachable or corrupt optional subtitles never remove playable movie source',async()=>{
  const fetchImpl=fakeFetch(u=>{
    if(u.pathname.endsWith('/manifest.json'))return{
      name:'Bad Subs',types:['movie'],resources:['stream','subtitles']
    };
    if(u.pathname.endsWith('/stream/movie/tt1234567.json'))return{
      streams:[{name:'1080p',url:'https://video.example/film.mp4'}]
    };
    if(u.pathname.includes('/subtitles/'))return new Error('HTTP 429');
  });
  const results=await resolveWebSources(movie,{
    addons:[licensedAddon],fetchImpl,engine:null
  });
  assert.equal(results.length,1);
  assert.equal(results[0].url,'https://video.example/film.mp4');
  assert.deepEqual(results[0].subtitles,[]);
});
test('Ordinary catalog merges Arabic + English Stremio subtitles into selected movie source',async()=>{
  const fetchImpl=fakeFetch(u=>{
    if(u.pathname.endsWith('/manifest.json'))return{
      name:'Licensed Sources and Subs',types:['movie'],
      resources:[{name:'stream',types:['movie']},
        {name:'subtitles',types:['movie']}],idPrefixes:['tt']
    };
    if(u.pathname.endsWith('/stream/movie/tt1234567.json'))return{
      streams:[{name:'1080p licensed',url:'https://video.example/demo.mp4',
        subtitles:[{url:'https://sub.example/film.en.vtt',
          lang:'eng',label:'English in stream'}]}]
    };
    if(u.pathname.endsWith('/subtitles/movie/tt1234567.json'))
      return {subtitles};
  });
  const sources=await resolveWebSources({...movie,id:980007},{
    addons:[licensedAddon],fetchImpl,engine:null
  });
  assert.equal(sources.length,1);
  assert.equal(sources[0].quality,1080);
  assert.deepEqual(sources[0].subtitles.map(x=>x.language),['en','ar']);
  assert.equal(new Set(sources[0].subtitles.map(x=>x.url)).size,2);
});
test('Exact TV episode subtitles do not cross to adjacent season or episode',async()=>{
  const paths=[];
  const fetchImpl=fakeFetch(u=>{
    paths.push(u.pathname);
    if(u.pathname.endsWith('/manifest.json'))return {
      name:'TV licensed',types:['series'],resources:['stream','subtitles'],
      idPrefixes:['tt']
    };
    const match=u.pathname.match(/tt7654321(?::|%3A)2(?::|%3A)(\d+)\.json/i);
    if(!match)return undefined;
    const ep=Number(match[1]);
    if(u.pathname.includes('/stream/'))return {
      streams:[{name:'1080p',url:'https://video.example/show.s02e'+ep+'.mp4'}]
    };
    if(u.pathname.includes('/subtitles/'))return {
      subtitles:[{lang:'ar',url:'https://sub.example/show.s02e'+ep+'.vtt'}]
    };
  });
  const [a,b]=await Promise.all([tv,{...tv,episode:5}].map(meta=>
    resolveWebSources({...meta,id:meta.episode===4?980008:980009},{
      addons:[licensedAddon],fetchImpl,engine:null
    })
  ));
  assert.equal(a[0].subtitles[0].url,'https://sub.example/show.s02e4.vtt');
  assert.equal(b[0].subtitles[0].url,'https://sub.example/show.s02e5.vtt');
  assert.ok(!a[0].subtitles.some(s=>s.url.includes('e5.')));
  assert.ok(!b[0].subtitles.some(s=>s.url.includes('e4.')));
});
