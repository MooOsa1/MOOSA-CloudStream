import test from 'node:test';
import assert from 'node:assert/strict';
import {
  stremioSubtitleIdentity,canonicalSubtitleImdbId,selectStremioSubtitles,collectStremioAddonSubtitles
} from '../web-stremio-subtitles.mjs';
import {resolveWebSources} from '../providers.mjs';
import {createServer} from '../server.mjs';

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
test('Optional subtitle HTTP response is independent from fast original movie sources',async()=>{
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
  const [sources,subTracks]=await Promise.all([
    resolveWebSources({...movie,id:980007},{
      addons:[licensedAddon],fetchImpl,engine:null
    }),
    collectStremioAddonSubtitles(movie,{addons:[licensedAddon],fetchImpl})
  ]);
  assert.equal(sources.length,1);
  assert.equal(sources[0].quality,1080);
  assert.deepEqual(sources[0].subtitles.map(x=>x.language),['en'],
    'Optional subtitles do not block or alter fast /api/resolve');
  assert.deepEqual(subTracks.map(x=>x.language),['ar','en']);
  assert.equal(subTracks.length,2);
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
    collectStremioAddonSubtitles(meta,{addons:[licensedAddon],fetchImpl})
  ));
  assert.equal(a[0].url,'https://sub.example/show.s02e4.vtt');
  assert.equal(b[0].url,'https://sub.example/show.s02e5.vtt');
  assert.ok(!a.some(s=>s.url.includes('e5.')));
  assert.ok(!b.some(s=>s.url.includes('e4.')));
});

test('public runtime reports Stremio subtitles are supported but not necessarily active',async()=>{
  const server=createServer();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const url='http://127.0.0.1:'+server.address().port+'/api/web-native/status';
    const response=await fetch(url);
    assert.equal(response.status,200);
    const json=await response.json();
    assert.equal(json.httpStremioSubtitles.supported,true);
    assert.equal(json.httpStremioSubtitles.protocol,'stremio_subtitles_http');
    assert.deepEqual(json.httpStremioSubtitles.languages,['ar','en']);
    assert.equal(json.httpStremioSubtitles.active,true);
    assert.equal(json.httpStremioSubtitles.includedOfficialAddon,'OpenSubtitles v3');
    assert.equal(json.cloudstreamCs3Supported,false);
    assert.equal(json.cineStreamAndStreamPlayOriginals,false);
  }finally{await new Promise(resolve=>server.close(resolve));}
});

test('Subtitle API is strict and rejects arbitrary providers and wrong episodes',async()=>{
  const app=createServer();
  await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));
  const base='http://127.0.0.1:'+app.address().port;
  try{
    for(const suffix of [
      '?media=movie&id=12&provider=https://evil.example',
      '?media=tv&id=12&season=1',
      '?media=tv&id=12&season=1&episode=0',
      '?media=movie&id=12&season=1',
      '?media=movie&id=12&id=13'
    ]){
      assert.equal((await fetch(base+'/api/stremio-subtitles'+suffix)).status,400,suffix);
    }
    // Real subtitle availability is verified in separate live API probes.
    // Unit tests avoid depending on TMDB credentials or external endpoints.
  }finally{await new Promise(resolve=>app.close(resolve));}
});

test('Tears of Steel uses the independently confirmed original IMDb title only on exact film identity',()=>{
  const verified={
    media:'movie',id:133701,title:'Tears of Steel',
    originalTitle:'Tears of Steel',year:2012,imdbId:'tt5129250'
  };
  assert.equal(canonicalSubtitleImdbId(verified),'tt2285752');
  assert.deepEqual(stremioSubtitleIdentity(verified),{type:'movie',id:'tt2285752'});
  for(const candidate of [
    {...verified,id:999},
    {...verified,year:2013},
    {...verified,title:'Different movie'},
    {...verified,originalTitle:'Different movie'},
    {...verified,media:'tv',season:1,episode:1},
    {...verified,imdbId:'tt7777777'}
  ])assert.equal(canonicalSubtitleImdbId(candidate),candidate.imdbId);
});
test('Tears of Steel query hits correct official Stremio movie IMDb endpoint, not wrong TMDB identifier',async()=>{
  const verified={
    media:'movie',id:133701,title:'Tears of Steel',
    originalTitle:'Tears of Steel',year:2012,imdbId:'tt5129250'
  };
  const urls=[];
  const fetchImpl=fakeFetch(u=>{
    urls.push(u.pathname);
    if(u.pathname==='/manifest.json')return{
      name:'OpenSubtitles v3',types:['movie','series'],
      resources:['subtitles'],idPrefixes:['tt']
    };
    if(u.pathname==='/subtitles/movie/tt2285752.json')
      return {subtitles:[
        {lang:'ara',url:'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1953862780'},
        {lang:'eng',url:'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1955318576'}
      ]};
    return undefined;
  });
  const tracks=await collectStremioAddonSubtitles(verified,{
    addons:['https://opensubtitles-v3.strem.io/manifest.json'],fetchImpl
  });
  assert.deepEqual(urls,['/manifest.json','/subtitles/movie/tt2285752.json']);
  assert.deepEqual(tracks.map(x=>x.language),['ar','en']);
});
