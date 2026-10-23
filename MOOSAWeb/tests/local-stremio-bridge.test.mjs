import test from 'node:test';
import assert from 'node:assert/strict';
import {parsePrivateAddonManifest, safeP2PSources,lookupAIOStreams} from '../local-stremio-bridge.mjs';
import {localStremioHls,validMovieId} from '../public/local-stremio-client.js';

const U='https://aiostreams-nightly.fortheweak.cloud/stremio/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/'+
  'a'.repeat(64)+'/manifest.json';
const HASH='0123456789abcdef0123456789abcdef01234567';

test('only allows exact private AIOStreams manifest route',()=>{
  assert.equal(parsePrivateAddonManifest(U)?.hostname,'aiostreams-nightly.fortheweak.cloud');
  for(const bad of [
    'https://evil.test/stremio/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/'+('a'.repeat(64))+'/manifest.json',
    U+'?x=y',U.replace('https','http'),U.replace('aiostreams-nightly.fortheweak.cloud','127.0.0.1'),
    U.replace('manifest.json','../../manifest.json'),
    U.replace('aiostreams-nightly.fortheweak.cloud','aiostreams-nightly.fortheweak.cloud.evil.org'),
    'file:///etc/passwd'
  ])assert.equal(parsePrivateAddonManifest(bad),null);
});
test('returns only deduplicated torrent metadata and filters low quality',()=>{
 const streams=safeP2PSources({streams:[
   {infoHash:HASH,fileIdx:0,name:'Sinners 2025 1080p'},
   {infoHash:HASH.toUpperCase(),fileIdx:0,name:'Duplicate 1080p'},
   {infoHash:'zz',fileIdx:0,name:'invalid'},
   {url:'https://example.com/path',name:'direct'},
   {infoHash:'e'.repeat(40),name:'Movie.CAM.2025'},
   {infoHash:'d'.repeat(40),name:'Movie.480p'},
   {infoHash:'c'.repeat(40),fileIdx:100000,name:'720p'}
 ]});
 assert.equal(streams.length,2);
 assert.deepEqual(streams.map(x=>x.fileIdx),[0,-1]);
 assert.equal(streams[0].name,'Sinners 2025 1080p');
});
test('local only: cannot inject different host/path/port',()=>{
 assert.equal(localStremioHls({infoHash:HASH,fileIdx:0}),
   'http://127.0.0.1:11470/'+HASH+'/0/hls.m3u8');
 assert.equal(localStremioHls({infoHash:HASH,fileIdx:-1}),
   'http://127.0.0.1:11470/'+HASH+'/-1/hls.m3u8');
 assert.equal(localStremioHls({infoHash:HASH, fileIdx:'../../etc/passwd'}),null);
 assert.equal(localStremioHls({infoHash:'nope',fileIdx:1}),null);
 assert.equal(validMovieId('tt31193180'),true);
 assert.equal(validMovieId('tt31193180?x=1'),false);
});
test('makes a single bounded lookup with no token echoed in result',async()=>{
 const calls=[];
 const out=await lookupAIOStreams({manifestUrl:U,imdbId:'tt31193180',
  fetcher:async (url,options)=>{
   calls.push({url,options});
   return {ok:true,status:200,headers:{get:()=> '105'},
    text:async()=>JSON.stringify({streams:[{infoHash:HASH,name:'Sinners.2025.1080p',fileIdx:1}]})};
  }});
 assert.equal(calls.length,1);
 assert.match(calls[0].url,/\/stream\/movie\/tt31193180\.json$/);
 assert.equal(calls[0].options.redirect,'error');
 assert.deepEqual(out.sources,[{infoHash:HASH,fileIdx:1,name:'Sinners.2025.1080p',quality:'1080'}]);
 assert.equal(JSON.stringify(out).includes('aaaaaaaa-bbbb-cccc'),false);
});
test('rejects arbitrary IMDb ids before making any network request',async()=>{
 await assert.rejects(lookupAIOStreams({manifestUrl:U,imdbId:'tt000?url=http://127.0.0.1'}),/Invalid/);
});
