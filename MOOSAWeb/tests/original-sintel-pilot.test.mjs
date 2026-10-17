import test from 'node:test';
import assert from 'node:assert/strict';
import {queryRemoteEngine,sintelPilotStatus,originalRuntimeTimeoutMs} from '../cloudstream-engine.mjs';
import {resolveWebSources} from '../providers.mjs';

const exact={media:'movie',id:45745,imdbId:'tt1727587',originalTitle:'Sintel',title:'Sintel',year:2010};
const approved={
  provider:'CineStream',name:'Castle original browser HLS',
  url:'https://cdn.example/verified-sintel.m3u8',quality:0,
  resolvedImdbId:'tt1727587',resolvedTitle:'Sintel',
  resolvedYear:2010,operatorAuthorized:true
};
const engine={url:'https://original-jvm.example',token:'operator-secret'};
const mocked=async (url)=>{
  if(url.endsWith('/v1/resolve'))return new Response(JSON.stringify({sources:[
    approved,
    {...approved,url:'https://cdn.example/wrong.mp4',resolvedTitle:'Other movie'},
    {...approved,url:'https://cdn.example/unauthorized.mp4',operatorAuthorized:false},
    {...approved,url:'http://127.0.0.1:3333/local.mp4'}
  ]}),{status:200,headers:{'content-type':'application/json'}});
  return new Response('not found',{status:404,headers:{'content-type':'text/plain'}});
};

test('original CineStream first film requires opt-in operator runtime',()=>{
  assert.equal(sintelPilotStatus({}).readyForQuery,false);
  assert.equal(sintelPilotStatus({
    CLOUDSTREAM_SINTEL_PILOT:'1',CLOUDSTREAM_RUNTIME_URL:engine.url,
    CLOUDSTREAM_RUNTIME_TOKEN:'secret'
  }).readyForQuery,true);
  assert.equal(originalRuntimeTimeoutMs(exact,{sintelEnabled:true}),65000);
  assert.equal(originalRuntimeTimeoutMs(exact,{sintelEnabled:false}),12000);
});

test('actual plugin quality unknown (0) is retained only for exact verified Sintel',async()=>{
  const opts={engine,sintelEnabled:true,pilotEnabled:false,fetchImpl:mocked};
  const actual=await queryRemoteEngine(exact,opts);
  assert.equal(actual.length,1);
  assert.equal(actual[0].quality,0);
  assert.equal(actual[0].originalCineStream,true);
  assert.equal(actual[0].verifiedFilmImdbId,'tt1727587');
  assert.equal(actual[0].singleFilmPilot,true);
  assert.equal(actual[0].url,approved.url);
  for(const bad of [
    {...exact,id:45746},
    {...exact,imdbId:'tt1234567'},
    {...exact,originalTitle:'Another Film'},
    {...exact,year:2009},
    {...exact,media:'tv',season:1,episode:1}
  ]) assert.deepEqual(await queryRemoteEngine(bad,opts),[]);
});

test('website source aggregator preserves exact low-res CineStream original',async()=>{
  const sources=await resolveWebSources(exact,{
    engine,sintelEnabled:true,pilotEnabled:false,
    fetchImpl:mocked,cineStreamEnabled:false,addons:[]
  });
  assert.ok(sources.some(x=>x.url===approved.url &&
    x.quality===0 && x.originalCineStream===true));
  assert.ok(!sources.some(x=>/wrong|unauthorized|127\.0\.0\.1/.test(x.url)));
});
