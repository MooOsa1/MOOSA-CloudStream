import test from 'node:test';
import assert from 'node:assert/strict';
import {probeBingeBangMovie} from '../bingebang-inventory.mjs';

test('BingeBang probes exact TMDB movie page but never returns a CDN URL',async()=>{
  const urls=[];
  const sample='<html><script>file: "https://media.example/film-720.m3u8";</script>'+
    '<button>Vidy Miami</button><button>Vidy Boise</button></html>';
  const result=await probeBingeBangMovie({tmdbId:27205,fetchImpl:async(url,req)=>{
    urls.push(url);
    assert.equal(req.redirect,'error');
    return new Response(sample,{headers:{'content-type':'text/html'}});
  }});
  assert.deepEqual(urls,['https://bingebang.cc/movie/27205']);
  assert.equal(result.state,'embedded_candidates_found');
  assert.equal(result.embeddedMediaCandidates,1);
  assert.deepEqual(result.observedVidyServers,['Miami','Boise']);
  assert.equal(result.playbackVerified,false);
  assert.doesNotMatch(JSON.stringify(result),/media\.example|m3u8|mp4/i);
});
test('BingeBang HTML-only player is not misreported as 1080p playback',async()=>{
  const result=await probeBingeBangMovie({tmdbId:27205,
    fetchImpl:async()=>new Response('<html><video src="/player/27205"></video></html>',
      {headers:{'content-type':'text/html'}})});
  assert.equal(result.state,'page_no_direct_media');
  assert.equal(result.embeddedMediaCandidates,0);
  assert.equal(result.playbackVerified,false);
});
test('Invalid movie ID and blocked provider never look like successful streams',async()=>{
  let calls=0;
  for(const id of [-1,0,'abc',1e12]){
    const result=await probeBingeBangMovie({tmdbId:id,
      fetchImpl:async()=>{calls++;throw Error('Do not fetch');}});
    assert.equal(result.state,'invalid_id');
  }
  assert.equal(calls,0);
  const denial=await probeBingeBangMovie({tmdbId:27205,
    fetchImpl:async()=>new Response('blocked',{status:403})});
  assert.equal(denial.state,'http_403');
  assert.equal(denial.playbackVerified,false);
});
