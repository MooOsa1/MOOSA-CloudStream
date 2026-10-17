import test from 'node:test';
import assert from 'node:assert/strict';
import {registry,configuredEngine,inspectRepositories,queryRemoteEngine,singleFilmPilotStatus,originalRuntimeTimeoutMs} from '../cloudstream-engine.mjs';
import {createServer} from '../server.mjs';

function response(json,status=200){
  return new Response(JSON.stringify(json),{status,headers:{'content-type':'application/json'}});
}
function makeFetcher() {
  const list=[
    {name:'CineStream',internalName:'CineStream',version:487,
      fileSize:321998,url:'https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CineStream.cs3',
      fileHash:'sha256-'+('a'.repeat(64))},
    {name:'StreamPlay',internalName:'StreamPlay',version:690,
      fileSize:98765,url:'https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/StreamPlay.cs3',
      fileHash:'sha256-'+('b'.repeat(64))}
  ];
  return async url=>{
    const u=new URL(url);
    if(u.pathname==='/SaurabhKaperwan/CSX/builds/CS.json')
      return response({pluginLists:['https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/plugins.json']});
    if(u.pathname==='/SaurabhKaperwan/CSX/builds/plugins.json')return response([list[0]]);
    if(u.pathname==='/phisher98/cloudstream-extensions-phisher/builds/repo.json')
      return response({pluginLists:['https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/plugins.json']});
    if(u.pathname==='/phisher98/cloudstream-extensions-phisher/refs/heads/builds/plugins.json')return response([list[1]]);
    return response({error:'missing'},404);
  };
}
test('CineStream and StreamPlay original repository index is discovered without loading plugin code',async()=>{
  assert.deepEqual(registry().map(x=>x.name),['CineStream','StreamPlay']);
  const result=await inspectRepositories({fetchImpl:makeFetcher(),force:true});
  assert.equal(result.length,2);
  assert.deepEqual(result.map(x=>x.version),[487,690]);
  assert.ok(result.every(x=>x.execution==='requires_android_runtime'&&x.state==='indexed'));
  assert.ok(result.every(x=>x.pluginUrl.endsWith('.cs3')));
});

test('accepts both official GitHub Raw spelling variants without following arbitrary repository URLs',async()=>{
  const actual=await inspectRepositories({fetchImpl:makeFetcher(),force:true});
  assert.equal(actual.find(x=>x.id==='streamplay').state,'indexed');
  const canonical=async url=>{
    const u=new URL(url);
    if(u.pathname.endsWith('/repo.json'))
      return response({pluginLists:['https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/plugins.json']});
    if(u.pathname.endsWith('/CS.json'))
      return response({pluginLists:['https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/plugins.json']});
    if(u.pathname.endsWith('/plugins.json'))
      return response([{name:u.pathname.includes('phisher98')?'StreamPlay':'CineStream',
        internalName:u.pathname.includes('phisher98')?'StreamPlay':'CineStream',
        version:1,fileHash:'sha256-'+('c'.repeat(64)),
        url:u.pathname.includes('phisher98')?
        'https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/StreamPlay.cs3':
        'https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CineStream.cs3'}]);
    return response({error:'missing'},404);
  };
  const second=await inspectRepositories({fetchImpl:canonical,force:true});
  assert.ok(second.every(x=>x.state==='indexed'));
});

test('unexpected index host fails closed instead of importing arbitrary plugins',async()=>{
  const fetchImpl=async url=>{
    if(url.endsWith('CS.json'))return response({pluginLists:['https://attacker.example/plugins.json']});
    if(url.endsWith('repo.json'))return response({pluginLists:['https://attacker.example/plugins.json']});
    return response([]);
  };
  const result=await inspectRepositories({fetchImpl,force:true});
  assert.ok(result.every(x=>x.state==='unavailable'));
});

test('CloudStream runtime is opt-in and requires a valid HTTPS endpoint with a token',()=>{
  assert.equal(configuredEngine({}),null);
  assert.equal(configuredEngine({CLOUDSTREAM_RUNTIME_URL:'http://localhost:8080',CLOUDSTREAM_RUNTIME_TOKEN:'test'}),null);
  assert.equal(configuredEngine({CLOUDSTREAM_RUNTIME_URL:'https://engine.example/v2/',CLOUDSTREAM_RUNTIME_TOKEN:'test'}).url,'https://engine.example/v2');
});

test('adapter passes exact movie and episode metadata and filters non-browser sources',async()=>{
  const requests=[];
  const fetchImpl=async (url,init)=>{
    requests.push({url,init,request:JSON.parse(init.body)});
    return response({sources:[
      {provider:'CineStream',name:'2160p',quality:2160,url:'https://cdn.example/video.m3u8',
        subtitles:[{url:'https://cdn.example/sub.vtt',language:'ar',label:'Arabic'}]},
      {provider:'StreamPlay',name:'1080p',quality:1080,url:'https://cdn.example/video.mp4'},
      {provider:'StreamPlay',name:'480p',quality:480,url:'https://cdn.example/low.mp4'},
      {provider:'Other',name:'2160p',quality:2160,url:'https://cdn.example/foreign.mp4'},
      {provider:'CineStream',name:'2160p Download',quality:2160,url:'https://cdn.example/download.mp4'},
      {provider:'CineStream',name:'2160p',quality:2160,url:'https://cdn.example/watch.html'},
      {provider:'CineStream',name:'2160p',quality:2160,url:'http://127.0.0.1:8000/file.mp4'}
    ]});
  };
  const result=await queryRemoteEngine({media:'tv',id:800,title:'Example',imdbId:'tt1234567',season:2,episode:4},{
    fetchImpl,engine:{url:'https://engine.example',token:'private-secret'},pilotEnabled:false
  });
  assert.deepEqual(result.map(x=>x.quality),[2160,1080]);
  assert.equal(result[0].subtitles[0].language,'ar');
  assert.equal(requests.length,1);
  assert.equal(requests[0].request.season,2);
  assert.equal(requests[0].request.episode,4);
  assert.deepEqual(requests[0].request.plugins,['CineStream','StreamPlay']);
  assert.equal(requests[0].init.headers.Authorization,'Bearer private-secret');
});

test('unconfigured runtime never makes network requests or supplies imaginary streams',async()=>{
  let contacted=false;
  const result=await queryRemoteEngine({media:'movie',id:100,title:'Unknown',imdbId:'tt0010100'},
    {fetchImpl:async()=>{contacted=true;return response({sources:[]});},engine:null});
  assert.deepEqual(result,[]);
  assert.equal(contacted,false);
});

test('public registry API reports index status without claiming the .cs3 engine works',async()=>{
  const server=createServer();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const address='http://127.0.0.1:'+server.address().port;
  try{
    const request=fetch(address+'/api/cloudstream/repositories',{signal:AbortSignal.timeout(20_000)});
    // Real network indexing may be unavailable in CI: the endpoint should
    // report a truthful unavailable status rather than inventing plugins.
    const r=await request;
    assert.equal(r.status,200);
    const data=await r.json();
    // CI defaults and Render's configured production environment both need to pass.
    const engineIsConfigured=!!configuredEngine();
    assert.equal(data.engine,engineIsConfigured?'configured':'not_configured');
    assert.equal(data.supportsOriginalPlugins,engineIsConfigured);
    assert.deepEqual(data.repositories.map(x=>x.id),['cinestream','streamplay']);
    assert.ok(data.repositories.every(x=>['indexed','unavailable'].includes(x.state)));
  }finally{await new Promise(resolve=>server.close(resolve));}
});

test('single-film pilot disabled by default, and requires both URL and token',()=>{
  assert.equal(singleFilmPilotStatus({}).enabled,false);
  assert.equal(singleFilmPilotStatus({CLOUDSTREAM_SINGLE_FILM_PILOT:'1'}).readyForQuery,false);
  assert.equal(singleFilmPilotStatus({
    CLOUDSTREAM_SINGLE_FILM_PILOT:'1',
    CLOUDSTREAM_RUNTIME_URL:'https://jvm.example',
    CLOUDSTREAM_RUNTIME_TOKEN:'private'
  }).readyForQuery,true);
});
test('pilot accepts lower quality ONLY with exact remote identity and explicit authorization',async()=>{
  const meta={media:'movie',id:20144,imdbId:'tt5719076',title:'Dead Night',
    originalTitle:'Dead Night',year:2017};
  const responseSource={
    provider:'CineStream',name:'original HLS',quality:480,
    url:'https://cdn.example/original.m3u8',
    resolvedImdbId:'tt5719076',resolvedTitle:'Dead Night',
    resolvedYear:2017,operatorAuthorized:true
  };
  const fetchImpl=async()=>response({sources:[
    responseSource,
    {...responseSource,url:'https://cdn.example/wrong-film.m3u8',resolvedTitle:'Other film'},
    {...responseSource,url:'https://cdn.example/not-authorized.m3u8',operatorAuthorized:false},
    {...responseSource,url:'https://cdn.example/low-144.m3u8',quality:144}
  ]});
  const engine={url:'https://executor.example',token:'secret'};
  const without=await queryRemoteEngine(meta,{fetchImpl,engine,pilotEnabled:false});
  assert.deepEqual(without,[]);
  const withPilot=await queryRemoteEngine(meta,{fetchImpl,engine,pilotEnabled:true});
  assert.deepEqual(withPilot.map(x=>x.quality),[480,144]);
  assert.ok(withPilot.every(x=>x.singleFilmPilot&&x.verifiedFilmImdbId==='tt5719076'));
  const other=await queryRemoteEngine({...meta,imdbId:'tt1234567'},{
    fetchImpl,engine,pilotEnabled:true});
  assert.deepEqual(other,[]);
});

test('single-film theatrical release metadata still matches original premiere',async()=>{
  const meta={media:'movie',id:123,imdbId:'tt5719076',title:'Dead Night',
    originalTitle:'Dead Night',year:2018};
  const engine={url:'https://executor.example',token:'private'};
  const result=await queryRemoteEngine(meta,{
    engine,pilotEnabled:true,
    fetchImpl:async()=>response({sources:[{
      provider:'CineStream',name:'checked 480p',quality:480,
      url:'https://cdn.example/movie.m3u8',resolvedImdbId:'tt5719076',
      resolvedTitle:'Dead Night',resolvedYear:2017,operatorAuthorized:true
    }]})
  });
  assert.equal(result.length,1);
  assert.equal(result[0].singleFilmPilot,true);
});

test('slow original JVM is allowed only for the exact pilot film',()=>{
 const exact={media:'movie',id:100,imdbId:'tt5719076',
  originalTitle:'Dead Night',year:2018};
 assert.equal(originalRuntimeTimeoutMs(exact,{pilotEnabled:true}),65000);
 assert.equal(originalRuntimeTimeoutMs(exact,{pilotEnabled:false}),12000);
 assert.equal(originalRuntimeTimeoutMs({...exact,imdbId:'tt1234567'},
  {pilotEnabled:true}),12000);
 assert.equal(originalRuntimeTimeoutMs({...exact,media:'tv',season:1,episode:1},
  {pilotEnabled:true}),12000);
});

test('pilot never contacts the single-film JVM for unrelated movies or episodes',async()=>{
  let calls=0;
  const fetchImpl=async()=>{calls++;return response({sources:[]});};
  const engine={url:'https://jvm.example',token:'test-secret'};
  const film={media:'movie',id:123,imdbId:'tt5719076',
    title:'Dead Night',originalTitle:'Dead Night',year:2018};
  assert.deepEqual(await queryRemoteEngine({...film,imdbId:'tt1234567'},{
    engine,pilotEnabled:true,fetchImpl}),[]);
  assert.deepEqual(await queryRemoteEngine({...film,media:'tv',season:1,episode:1},{
    engine,pilotEnabled:true,fetchImpl}),[]);
  assert.equal(calls,0);
});
