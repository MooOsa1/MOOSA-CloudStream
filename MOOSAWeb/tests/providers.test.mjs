import test from 'node:test';
import assert from 'node:assert/strict';
import {configuredAddons,fromArchive,fromWebAddons,resolveWebSources,selectStremioStreamId} from '../providers.mjs';

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
  assert.deepEqual(result.map(x=>x.quality),[1080,2160]);
  assert.equal(result[0].subtitles[0].language,'ar');
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

test('CineStream-style Stremio object resources respect supported type and IMDb prefix',async()=>{
  const calls=[];
  const f=mockFetch(u=>{
    calls.push(u.pathname);
    if(u.pathname==='/manifest.json')return {
      name:'Licensed CineStream HTTPS adapter',types:['movie','series'],
      resources:[{name:'stream',types:['movie'],idPrefixes:['tt']}],idPrefixes:['tt']
    };
    if(u.pathname.includes('/stream/movie/'))return {streams:[{
      name:'1080p',url:'https://licensed.example/video.mp4'
    }]};
  });
  const options={fetchImpl:f,addons:['https://licensed.example/manifest.json']};
  const film=await fromWebAddons(sample,options);
  assert.equal(film.length,1);
  const show=await fromWebAddons({...sample,media:'tv',season:2,episode:4},options);
  assert.deepEqual(show,[]);
  assert.equal(calls.filter(x=>x.includes('stream/series')).length,0,
    'Never ask a movie-only Stremio resource for a TV episode');
});
test('Stremio addon must not promise playback when stream requires protected native HTTP headers',async()=>{
  const f=mockFetch(u=>{
    if(u.pathname==='/manifest.json')return {
      name:'Licensed Protected Source',types:['movie'],resources:['stream']
    };
    if(u.pathname.includes('/stream/'))return {streams:[
      {name:'2160p',url:'https://licensed.example/secret.m3u8',
        behaviorHints:{proxyHeaders:{request:{Referer:'https://restricted.example/'}}}},
      {name:'1080p',url:'https://licensed.example/cookie.mp4',
        headers:{Authorization:'Bearer private'}},
      {name:'1080p',url:'https://licensed.example/headers.mp4',
        behaviorHints:{headers:{Origin:'https://restricted.example'}}},
      {name:'720p',url:'https://licensed.example/clear.mp4'},
      {name:'2160p',url:'https://licensed.example/drm.mp4',drm:{}}
    ]};
  });
  const sources=await fromWebAddons(sample,{
    fetchImpl:f,addons:['https://licensed.example/manifest.json']
  });
  assert.deepEqual(sources.map(x=>x.url),['https://licensed.example/clear.mp4']);
});

test('Reject externally indexed Archive videos for the wrong movie, even with matching IMDb',async()=>{
  const fetchImpl=mockFetch(u=>{
    if(u.pathname==='/manifest.json')return {
      name:'Archive External',types:['movie'],resources:['stream'],idPrefixes:['tt']
    };
    if(u.pathname==='/stream/movie/tt0000099.json')return {streams:[
      {name:'1080p',url:'https://archive.org/download/wrong/bunny-misidentified_1080p.mp4'},
      {name:'1080p',url:'https://archive.org/download/real/full_1080p.mp4'},
      {name:'2160p',url:'https://archive.org/download/sd/full_2160p.mp4'}
    ]};
    if(u.pathname==='/metadata/wrong')return {
      metadata:{title:'Other Film',year:'1942',
        licenseurl:'https://creativecommons.org/licenses/by/3.0/'},
      files:[{name:'bunny-misidentified_1080p.mp4',height:'1080',size:'1900000'}]
    };
    if(u.pathname==='/metadata/real')return {
      metadata:{title:'Test Example',year:'1942',
        licenseurl:'https://creativecommons.org/licenses/by/3.0/'},
      files:[{name:'full_1080p.mp4',height:'1080',size:'1900000'}]
    };
    if(u.pathname==='/metadata/sd')return {
      metadata:{title:'Test Example',year:'1942',
        licenseurl:'https://creativecommons.org/licenses/by/3.0/'},
      files:[{name:'full_2160p.mp4',height:'360',size:'1900000'}]
    };
  });
  const list=await fromWebAddons(sample,{
    fetchImpl,addons:['https://external.example/manifest.json']
  });
  assert.equal(list.length,1);
  assert.equal(list[0].url,'https://archive.org/download/real/full_1080p.mp4');
  assert.equal(list[0].quality,1080);
  assert.equal(list[0].name,'Archive verified • 1080p');
});

test('Nuvio/Stremio manifest picks explicit TMDB movie ID when IMDb is missing or not supported',async()=>{
  const requested=[];
  const fetchImpl=mockFetch(u=>{
    requested.push(u.pathname);
    if(u.pathname==='/manifest.json')return {
      name:'Official CC film provider',types:['movie'],
      resources:[{name:'stream',types:['movie'],idPrefixes:['tmdb:']}],
      idPrefixes:['tmdb:']
    };
    if(u.pathname==='/stream/movie/tmdb%3A45745.json' ||
       u.pathname==='/stream/movie/tmdb:45745.json')return {streams:[{
        name:'Sintel 1080p',url:'https://upload.wikimedia.org/verified-sintel-1080p.webm',
        subtitles:[{language:'ar',url:'https://example.org/sintel.ar.vtt'}]
    }]};
    return {streams:[]};
  });
  const meta={media:'movie',id:45745,title:'Sintel',
    originalTitle:'Sintel',year:2010,imdbId:null};
  const result=await fromWebAddons(meta,{fetchImpl,addons:['https://example.org/manifest.json']});
  assert.equal(result.length,1);
  assert.equal(result[0].quality,1080);
  assert.equal(result[0].subtitles[0].language,'ar');
  assert.ok(requested.some(x=>x.includes('tmdb')));
  assert.equal(requested.filter(x=>x.includes('/stream/')).length,1);
});
test('Manifest chooses IMDb before TMDB when both schemes are supported',()=>{
  const manifest={types:['movie'],resources:['stream'],idPrefixes:['tt','tmdb:']};
  assert.deepEqual(selectStremioStreamId(manifest,{
    media:'movie',id:45745,imdbId:'tt1727587'
  }),{type:'movie',id:'tt1727587',scheme:'imdb'});
  assert.deepEqual(selectStremioStreamId(manifest,{
    media:'movie',id:45745
  }),{type:'movie',id:'tmdb:45745',scheme:'tmdb'});
});
test('A generic Stremio addon cannot silently receive a TMDB ID it never advertised',()=>{
  const manifest={types:['movie'],resources:['stream']};
  assert.equal(selectStremioStreamId(manifest,{media:'movie',id:45745}),null);
  assert.deepEqual(selectStremioStreamId(manifest,{
    media:'movie',id:45745,imdbId:'tt1727587'
  }),{type:'movie',id:'tt1727587',scheme:'imdb'});
});
test('Stremio resource prefixes override manifest defaults without overriding allowed movie/series types',()=>{
  const m={types:['movie','series'],idPrefixes:['tt','tmdb:'],
    resources:[{name:'stream',types:['movie'],idPrefixes:['tmdb:']}]};
  assert.deepEqual(selectStremioStreamId(m,{
    media:'movie',id:45745,imdbId:'tt1727587'
  }),{type:'movie',id:'tmdb:45745',scheme:'tmdb'});
  assert.equal(selectStremioStreamId(m,{
    media:'tv',id:777,imdbId:'tt0000777',season:1,episode:3
  }),null);
  assert.deepEqual(selectStremioStreamId({...m,idPrefixes:['tt']},{
    media:'movie',id:45745,imdbId:'tt1727587'
  }),{type:'movie',id:'tmdb:45745',scheme:'tmdb'},
  'Specific stream resource prefix overrides global tt-only default');
  assert.deepEqual(selectStremioStreamId({
    ...m,idPrefixes:['tmdb:'],
    resources:[{name:'stream',types:['movie'],idPrefixes:['tt']}]
  },{media:'movie',id:45745,imdbId:'tt1727587'}),
  {type:'movie',id:'tt1727587',scheme:'imdb'},
  'Specific resource tt prefix overrides global TMDB-only default');
});
test('Stremio TMDB series requires exact valid season and episode, not a generic show link',async()=>{
  const manifest={name:'Licensed episodes',types:['series'],
    resources:[{name:'stream',types:['series'],idPrefixes:['tmdb:']}],
    idPrefixes:['tmdb:']};
  const calls=[];
  const fetchImpl=mockFetch(u=>{
    calls.push(u.pathname);
    if(u.pathname==='/manifest.json')return manifest;
    if(u.pathname.includes('/stream/'))return {streams:[{
      name:'720p',url:'https://cdn.example/episode720.mp4'
    }]};
  });
  const meta={media:'tv',id:777,season:2,episode:9};
  const sources=await fromWebAddons(meta,{fetchImpl,addons:['https://series.example/manifest.json']});
  assert.equal(sources.length,1);
  assert.equal(sources[0].quality,720);
  assert.ok(calls.some(x=>/tmdb(%3A|:)777(%3A|:)2(%3A|:)9/.test(x)));
  const count=calls.length;
  const noEpisode=await fromWebAddons({...meta,episode:null},
    {fetchImpl,addons:['https://series.example/manifest.json']});
  assert.deepEqual(noEpisode,[]);
  assert.equal(calls.length,count+1,'Only the manifest may be fetched for invalid episode');
});
test('TMDB ID support does not turn external pages, torrents or protected links into HTML5 streams',async()=>{
  const fetchImpl=mockFetch(u=>u.pathname==='/manifest.json'
    ?{name:'Mixed links',types:['movie'],resources:['stream'],idPrefixes:['tmdb:']}
    :{streams:[
      {name:'1080p',externalUrl:'https://provider.example/watch'},
      {name:'1080p',infoHash:'a'.repeat(40)},
      {name:'1080p',url:'https://cdn.example/movie.mp4',
        headers:{Referer:'https://private.example/'}},
      {name:'1080p',url:'https://cdn.example/trailer.html'},
      {name:'720p',url:'https://cdn.example/approved-full.mp4'}
    ]});
  const streams=await fromWebAddons({media:'movie',id:45745},{
    fetchImpl,addons:['https://provider.example/manifest.json']});
  assert.deepEqual(streams.map(x=>x.url),['https://cdn.example/approved-full.mp4']);
});

test('NuvioStreams-style manifest prefix "tmdb" without colon is valid and explicitly declared',()=>{
  const meta={media:'movie',id:45745,imdbId:null};
  const m={types:['movie'],resources:['stream'],idPrefixes:['tmdb']};
  assert.deepEqual(selectStremioStreamId(m,meta),
    {type:'movie',id:'tmdb:45745',scheme:'tmdb'});
  assert.equal(selectStremioStreamId({...m,idPrefixes:['tmd']},meta),null,
    'Partial prefixes never authorize TMDB fallback');
  assert.equal(selectStremioStreamId({...m,idPrefixes:['IMDb']},meta),null);
});

test('Real Archive Stremio add-on can return an authentic unlabeled Sintel 818p MP4 verified from item metadata',async()=>{
  const endpoint='https://archive-source.example/manifest.json';
  const path='/stream/movie/tt1727587.json';
  const file='https://archive.org/download/sintel-open-film/Sintel_full_movie.mp4';
  const seen=[];
  const fetchImpl=mockFetch(u=>{
    seen.push(u.href);
    if(u.pathname==='/manifest.json')return {
      id:'org.archive.clean',name:'Archive.org',types:['movie'],
      resources:['stream'],idPrefixes:['tt']
    };
    if(u.pathname===path)return {streams:[
      {url:file,title:'Archive.org',name:'Full movie',subtitles:[
        {url:'https://example.org/subs/sintel-ar.vtt',lang:'ar',label:'Arabic'},
        {url:'https://example.org/subs/sintel-en.vtt',lang:'en',label:'English'}
      ]},
      {name:'1080p',url:'https://archive.org/download/sintel-open-film/trailer_1080p.mp4'},
      {name:'2160p',url:'https://cdn.example/hosted/movie.html'}
    ]};
    if(u.pathname==='/metadata/sintel-open-film')return {
      metadata:{
        title:'Sintel',year:'2010',
        licenseurl:'https://creativecommons.org/licenses/by/3.0/'
      },
      files:[
        {name:'Sintel_full_movie.mp4',height:'818',size:'310000000'},
        {name:'trailer_1080p.mp4',height:'1080',size:'12500000'}
      ]
    };
  });
  const sources=await fromWebAddons({
    media:'movie',id:45745,title:'Sintel',originalTitle:'Sintel',year:2010,
    imdbId:'tt1727587'
  },{fetchImpl,addons:[endpoint]});
  assert.equal(sources.length,1);
  assert.equal(sources[0].url,file);
  assert.equal(sources[0].quality,818);
  assert.equal(sources[0].subtitles.length,2);
  assert.deepEqual(sources[0].subtitles.map(t=>t.language),['ar','en']);
  assert.match(sources[0].name,/Archive verified/);
  assert.deepEqual(seen.map(x=>new URL(x).pathname),[
    '/manifest.json','/stream/movie/tt1727587.json',
    '/metadata/sintel-open-film'
  ]);
});

test('A mislabeled 2160p source cannot override wrong-film, unlicensed or 480p Archive metadata',async()=>{
  const file='https://archive.org/download/random-film/full_4k.mp4';
  const original={
    media:'movie',id:45745,title:'Sintel',originalTitle:'Sintel',year:2010,
    imdbId:'tt1727587'
  };
  const doc={metadata:{
    title:'Sintel',year:'2010',
    licenseurl:'https://creativecommons.org/licenses/by/3.0/'
  },files:[{name:'full_4k.mp4',height:'1080',size:'50000000'}]};
  for(const changed of [
    {metadata:{...doc.metadata,title:'Some other movie'}},
    {metadata:{...doc.metadata,year:'2012'}},
    {metadata:{...doc.metadata,licenseurl:'https://not-authorized.example/license'}},
    {files:[{...doc.files[0],height:'480'}]},
    {files:[{...doc.files[0],name:'different-film.mp4'}]}
  ]){
    const fetchImpl=mockFetch(u=>{
      if(u.pathname==='/manifest.json')return {
        name:'Untrusted Archive Addon',types:['movie'],resources:['stream'],idPrefixes:['tt']
      };
      if(u.pathname.includes('/stream/'))return {
        streams:[{name:'2160p BEST',url:file}]
      };
      if(u.pathname==='/metadata/random-film')return {...doc,...changed};
    });
    const sources=await fromWebAddons(original,{fetchImpl,addons:[
      'https://another-addon.example/manifest.json'
    ]});
    assert.deepEqual(sources,[],JSON.stringify(changed));
  }
  const goodFetch=mockFetch(u=>{
    if(u.pathname==='/manifest.json')return {
      name:'Verified',types:['movie'],resources:['stream'],idPrefixes:['tt']
    };
    if(u.pathname.includes('/stream/'))return {
      streams:[{name:'2160p allegedly',url:file}]
    };
    if(u.pathname==='/metadata/random-film')return doc;
  });
  const verified=await fromWebAddons(original,{
    fetchImpl:goodFetch,addons:['https://another-addon.example/manifest.json']
  });
  assert.equal(verified.length,1);
  assert.equal(verified[0].quality,1080,'Archive video file height overrides deceptive source label');
});
