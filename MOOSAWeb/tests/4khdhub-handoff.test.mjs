import test from 'node:test';
import assert from 'node:assert/strict';
import {probe4khdhubHandoff,extract4khdhubCards,classifyCineStreamAnchors} from '../4khdhub-handoff.mjs';

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

test('real CineStream selector recognizes intermediate links without direct HubCloud URLs',async()=>{
  const post='<div class="mod">'+
    '<a data-wpel-link="external" href="https://greenmountmotors.net/re?id=1">Server A</a>'+
    '<a href="https://hubcdn.example/path" data-wpel-link="external">Server B</a>'+
    '<a data-wpel-link="external" href="javascript:alert(1)">Ignored</a>'+
    '<a href="https://other.example/advert">Ad</a></div>';
  const counts=classifyCineStreamAnchors(post);
  assert.equal(counts.moduleFound,true);
  assert.equal(counts.externalSourceAnchors,2);
  assert.equal(counts.redirectorHints,2);
  assert.equal(counts.directHubHints,0);
  const search='<a href="/inception-2010/"><div class="movie-card-content">Inception (2010)</div></a>';
  const seen=[];
  const fetchImpl=async url=>{
    seen.push(String(url));
    return new Response(new URL(url).searchParams.has('s')?search:post,{
      headers:{'content-type':'text/html'}
    });
  };
  const result=await probe4khdhubHandoff({title:'Inception',year:2010,fetchImpl});
  assert.equal(result.state,'cinestream_source_anchors');
  assert.equal(result.cineStreamAnchors.externalSourceAnchors,2);
  assert.equal(result.videoLinksVerified,0);
  assert.equal(seen.length,2,'must not open the discovered intermediate links');
  assert.doesNotMatch(JSON.stringify(result),/greenmountmotors|hubcdn\.example|\/re\?id/);
});
