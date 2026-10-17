import test from 'node:test';
import assert from 'node:assert/strict';
import {exactExperimentalFilm,offeredVidlinkVariants,verifiedFilmVariants}
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
