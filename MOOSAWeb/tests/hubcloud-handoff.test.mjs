import test from 'node:test';
import assert from 'node:assert/strict';
import {inspectHubCloudHandoff} from '../hubcloud-handoff.mjs';

test('Inception exact year/IMDb yields real HubCloud handoff counts without returning links',async()=>{
  const calls=[];
  const fetchImpl=async raw=>{
    const url=new URL(raw);calls.push(url.href);
    if(url.hostname==='search.pingora.fyi'){
      assert.equal(url.searchParams.get('q'),'tt1375666');
      return Response.json({hits:[{document:{
        post_title:'Inception 2010 1080p BluRay',
        imdb_id:'tt1375666',
        permalink:'https://hdhub4u.example.net/inception-2010/'
      }},{document:{
        post_title:'Different film (2010)',permalink:'https://random.example/not-this'
      }}]});
    }
    assert.equal(url.href,'https://new8.hdhub4u.cl/inception-2010/');
    return new Response('<html><a href="https://hubcloud.dad/drive/abc">HubCloud</a>'+
      '<a href="https://vcloud.lol/video/def">VCloud</a>'+
      '<a href="https://random-host.example/bad">Noise</a></html>',{
      headers:{'content-type':'text/html'}
    });
  };
  // A domain explicitly outside the trusted HDHub4u family cannot trigger
  // a movie-post fetch. Then test a verified domain separately.
  const noPost=await inspectHubCloudHandoff({title:'Inception',year:2010,
    imdbId:'tt1375666',fetchImpl});
  assert.equal(noPost.state,'no_exact_movie_post');
  assert.equal(noPost.videoLinksVerified,0);
  assert.equal(calls.length,1);
  const good=await inspectHubCloudHandoff({title:'Inception',year:2010,
    imdbId:'tt1375666',fetchImpl:async(raw,init)=>{
      const u=new URL(raw);
      if(u.hostname==='search.pingora.fyi')return Response.json({hits:[{document:{
        post_title:'Inception 2010 BluRay',imdb_id:'tt1375666',
        permalink:'https://hdhub4u.cl/inception-2010/'
      }}]});
      return fetchImpl(raw,init);
    }});
  assert.equal(good.state,'hubcloud_entry_found');
  assert.equal(good.hubcloudEntryPages,2);
  assert.equal(good.exactMoviePosts,1);
  assert.equal(good.videoLinksVerified,0);
  assert.equal(good.playerReady,false);
  assert.doesNotMatch(JSON.stringify(good),/https?:|hubcloud\.dad|vcloud\.lol/);
});
test('HubCloud index failures and wrong releases do not imply missing movie or video',async()=>{
  const denial=await inspectHubCloudHandoff({title:'Inception',year:2010,
    fetchImpl:async()=>new Response('blocked',{status:403})});
  assert.equal(denial.state,'index_http_403');
  const wrong=await inspectHubCloudHandoff({title:'Inception',year:2010,
    fetchImpl:async()=>Response.json({hits:[{document:{
      post_title:'Inception 2020',permalink:'https://hdhub4u.cl/inception-2020/'
    }}]})});
  assert.equal(wrong.state,'no_exact_movie_post');
  assert.equal(wrong.hubcloudEntryPages,0);
});
