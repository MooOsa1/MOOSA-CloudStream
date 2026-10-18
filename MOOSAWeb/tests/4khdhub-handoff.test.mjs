import test from 'node:test';
import assert from 'node:assert/strict';
import {probe4khdhubHandoff,extract4khdhubCards} from '../4khdhub-handoff.mjs';

test('Alternate HubCloud path follows exact Inception movie-card and never requests off-site media',async()=>{
  const card='<div class="card-grid"><a href="/inception-2010/">'+
    '<div class="movie-card-content">Inception (2010) BluRay</div></a></div>';
  const seen=[];
  const fetchImpl=async url=>{
    const u=new URL(url);seen.push(u.href);
    if(u.searchParams.has('s')){
      assert.equal(u.searchParams.get('s'),'Inception');
      return new Response(card,{headers:{'content-type':'text/html'}});
    }
    assert.equal(u.href,'https://4khdhub.one/inception-2010/');
    return new Response('<a href="https://hubcloud.dad/drive/token">Hub</a>'+
      '<a href="https://evil.example/foo.mp4">Offsite</a>',{
      headers:{'content-type':'text/html'}
    });
  };
  const r=await probe4khdhubHandoff({title:'Inception',year:2010,fetchImpl});
  assert.equal(r.state,'hubcloud_entry_found');
  assert.equal(r.hubcloudEntryPages,1);
  assert.equal(r.videoLinksVerified,0);
  assert.deepEqual(seen,[
    'https://4khdhub.one/?s=Inception','https://4khdhub.one/inception-2010/'
  ]);
  assert.doesNotMatch(JSON.stringify(r),/https:|drive\/token|\.mp4/);
  assert.deepEqual(extract4khdhubCards(card,'Inception',2011),[]);
  assert.deepEqual(extract4khdhubCards(
    card.replace('href="/inception','href="https://evil.example/inception'),
    'Inception',2010),[]);
});
test('Alternate HubCloud index 403 is not misreported as zero available films',async()=>{
  const r=await probe4khdhubHandoff({title:'Inception',year:2010,
    fetchImpl:async()=>new Response('Forbidden',{status:403})});
  assert.equal(r.state,'http_403');
  assert.equal(r.playerReady,false);
});
