import test from 'node:test';
import assert from 'node:assert/strict';
import {exactExperimentalFilm,offeredVidlinkVariants,verifiedFilmVariants,findVerifiedMultiFilmVidlink}
  from '../vidlink-multifilm-candidates.mjs';
const general={id:961,media:'movie',year:1926,imdbId:'tt0017925',
  title:'The General',originalTitle:'The General'};
const provider={qualities:{
 '480':{url:'https://hcdn3.hakunaymatata.com/general-480.mp4',
   type:'mp4'},
 '360':{url:'https://hcdn3.hakunaymatata.com/general-360.mp4',
   type:'mp4'},
 '720':{url:'https://hcdn3.hakunaymatata.com/general-720.mp4',
   type:'mp4'},
 '1080':{url:'https://bad.host.example/film-1080.mp4',type:'mp4'},
 '2160':{url:'https://hcdn3.hakunaymatata.com/general-4k.mp4',type:'mp4'},
 '480-clone':{url:'https://hcdn3.hakunaymatata.com/general-480.mp4',type:'mp4'}
}};
test('verify identity is strict and cannot map the other 1926 film',()=>{
  assert.equal(exactExperimentalFilm(general).tmdbId,961);
  const girl={id:3085,imdbId:'tt0032599',year:1940,
    media:'movie',title:'His Girl Friday'};
  assert.equal(exactExperimentalFilm(girl).tmdbId,3085);
  for(const candidate of [
    {...general,id:3085}, {...general,media:'tv'},
    {...general,imdbId:'tt33333'},
    {...general,year:2026},
    {...general,title:'Other film',originalTitle:'Other movie'}
  ])assert.equal(exactExperimentalFilm(candidate),null);
});
test('only operator allowlisted source, dedupe and sort multiple quality labels',()=>{
  const out=offeredVidlinkVariants(provider);
  assert.deepEqual(out.map(x=>x.advertisedQuality),[720,480,360]);
  assert.ok(out.every(x=>x.url.startsWith('https://hcdn3.hakunaymatata.com/')));
  assert.ok(out.every(x=>x.advertisedQuality<=1080),
    'Do not probe deferred 4K variants');
});
test('second Chrome-tested CDN is allowed but other domains remain blocked',()=>{
  const result=offeredVidlinkVariants({qualities:{
    '1080':{url:'https://bcdn.hakunaymatata.com/film-1080.mp4',type:'mp4'},
    '720':{url:'https://hcdn3.hakunaymatata.com/film-720.mp4',type:'mp4'},
    '480':{url:'https://unapproved.example.net/film-480.mp4',type:'mp4'},
    '2160':{url:'https://bcdn.hakunaymatata.com/film-4k.mp4',type:'mp4'}
  }});
  assert.deepEqual(result.map(x=>x.advertisedQuality),[1080,720]);
  assert.deepEqual(result.map(x=>new URL(x.url).hostname),
    ['bcdn.hakunaymatata.com','hcdn3.hakunaymatata.com']);
});

test('feature flag OFF or unreviewed content issues zero links',async()=>{
  let calls=0;
  const mediaGateway={issueIfBytesVerified:async()=>{
    calls++;return {source:{url:'https://moosa-web.onrender.com/api/media/test'}};}
  };
  assert.deepEqual(await verifiedFilmVariants({meta:general,providerResponse:provider,
    mediaGateway,enabled:false,rightsReviewed:true}),[]);
  assert.deepEqual(await verifiedFilmVariants({meta:general,providerResponse:provider,
    mediaGateway,enabled:true,rightsReviewed:false}),[]);
  assert.equal(calls,0);
});
test('sources require actual bytes, preserve multiple distinct verified qualities',async()=>{
  const kinds=[];
  const mediaGateway={issueIfBytesVerified:async input=>{
    kinds.push(input.url);
    if(input.url.includes('360.mp4'))
      return {source:null,reason:'upstream_http_403'};
    return {source:{
      url:'https://moosa-web.onrender.com/api/media/'+
        (input.url.includes('720')?'A'.repeat(43):'B'.repeat(43)),
      gatewayIssued:true,format:'video/mp4'
    },reason:'media_bytes_verified'};
  }};
  const links=await verifiedFilmVariants({
    meta:general,providerResponse:provider,mediaGateway,
    enabled:true,rightsReviewed:true});
  assert.equal(kinds.length,3);
  assert.equal(links.length,2);
  assert.ok(links.every(x=>x.quality===0));
  assert.ok(links.every(x=>x.originalMovieImdbId==='tt0017925'));
  assert.notEqual(links[0].url,links[1].url);
});

test('exact-film multi-source adapter refuses all requests when OFF or rights not reviewed',async()=>{
  let calls=0;
  const fetchImpl=async()=>{calls++;throw Error('unexpected external fetch');};
  const mediaGateway={issueIfBytesVerified:async()=>{throw Error('unexpected')}};
  assert.deepEqual(await findVerifiedMultiFilmVidlink({
    meta:general,mediaGateway,enabled:false,rightsReviewed:true,fetchImpl
  }),[]);
  assert.deepEqual(await findVerifiedMultiFilmVidlink({
    meta:general,mediaGateway,enabled:true,rightsReviewed:false,fetchImpl
  }),[]);
  assert.deepEqual(await findVerifiedMultiFilmVidlink({
    meta:{...general,imdbId:'tt999999'},mediaGateway,
    enabled:true,rightsReviewed:true,fetchImpl
  }),[]);
  assert.equal(calls,0);
});

test('multi-source provider HTTPS protocol returns distinct server-issued capabilities',async()=>{
  const requests=[];
  const fakeFetch=async(url,options)=>{
    requests.push({host:new URL(url).hostname,redirect:options.redirect});
    if(new URL(url).hostname==='enc-dec.app'){
      assert.equal(new URL(url).searchParams.get('text'),'961');
      return Response.json({result:'the_general_id'});
    }
    assert.equal(new URL(url).hostname,'vidlink.pro');
    return Response.json(provider);
  };
  let issued=0;
  const mediaGateway={issueIfBytesVerified:async({url,format,rightsApproved,sourceVerified})=>{
    issued++;
    assert.equal(rightsApproved,true);
    assert.equal(sourceVerified,true);
    assert.equal(format,'mp4');
    assert.equal(new URL(url).hostname,'hcdn3.hakunaymatata.com');
    const suffix=(url.match(/general-(\d+)/)||[])[1];
    return {source:{
      url:'https://moosa-web.onrender.com/api/media/'+
        (suffix==='720'?'A':suffix==='480'?'B':'C').repeat(43),
      gatewayIssued:true,format:'video/mp4'
    }};
  }};
  const actual=await findVerifiedMultiFilmVidlink({
    meta:general,mediaGateway,enabled:true,
    rightsReviewed:true,fetchImpl:fakeFetch
  });
  assert.equal(issued,3);
  assert.equal(actual.length,3);
  assert.ok(actual.every(x=>x.quality===0));
  assert.ok(actual.every(x=>x.originalMovieYear===1926));
  assert.deepEqual(requests.map(r=>r.host),['enc-dec.app','vidlink.pro']);
  assert.ok(requests.every(r=>r.redirect==='error'));
});

test('upstream rejection never yields fake sources',async()=>{
  const mediaGateway={issueIfBytesVerified:async()=>{throw Error('should not issue')}};
  const fetchImpl=async()=>new Response('<html>forbidden</html>',{
    status:403,headers:{'content-type':'text/html'}
  });
  assert.deepEqual(await findVerifiedMultiFilmVidlink({
    meta:general,mediaGateway,enabled:true,
    rightsReviewed:true,fetchImpl
  }),[]);
});
