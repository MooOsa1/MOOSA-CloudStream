import test from 'node:test';
import assert from 'node:assert/strict';
import {movieBoxInventory} from '../moviebox-inventory.mjs';
function response(body,extras={}){
  return Response.json(body,extras);
}
test('MovieBox exact title enumerates HLS and MP4 without leaking any media URLs or guest token',async()=>{
  const calls=[];
  const fetchImpl=async(raw,opts={})=>{
    const u=new URL(raw);calls.push(u.pathname);
    if(u.pathname.includes('get-latest-app-pkgs'))
      return new Response('{}',{status:200,headers:{'content-type':'application/json',
        'x-user':JSON.stringify({token:'local_guest_token_abc123'})}});
    if(u.pathname.endsWith('/subject/search')){
      assert.equal(opts.method,'POST');
      assert.equal(JSON.parse(opts.body).keyword,'M');
      assert.equal(opts.headers.Authorization,'Bearer local_guest_token_abc123');
      return response({data:{data:{items:[
        {subjectId:'id-one',title:'M',year:1931,lanName:'Original'},
        {subjectId:'id-wrong',title:'M',year:2010},
        {subjectId:'wrong-film',title:'Memento',year:2000}
      ]}}});
    }
    if(u.hostname==='h5.aoneroom.com')
      return response({data:{items:[{subject:{detailPath:'movie-m'}}]}});
    if(u.pathname.endsWith('/subject/play'))
      return response({data:{streams:[
        {url:'https://video.example/movie.m3u8',resolutions:'1080'},
        {url:'https://video.example/movie-480.mp4',resolution:480},
        {url:'https://video.example/not-allowed.mp4',resolution:1080,vipLocked:true}
      ],dash:[{url:'https://video.example/manifest.mpd',resolution:1080}]}});
    if(u.pathname.endsWith('/subject/download'))
      return response({data:{downloads:[
        {url:'https://video.example/movie-720.mp4',resolution:720}
      ]}});
    throw Error('Unexpected host or path '+u);
  };
  const answer=await movieBoxInventory({title:'M',year:1931,fetchImpl});
  assert.equal(answer.state,'candidates_found');
  assert.equal(answer.found,1);
  assert.equal(answer.sourceVariants,4);
  assert.deepEqual(answer.offeredQualities,[1080,720,480]);
  assert.deepEqual(answer.formats,{hls:1,mp4:2,dash:1});
  assert.equal(answer.verifiedPlayable,false);
  assert.equal(answer.rightsVerified,false);
  assert.doesNotMatch(JSON.stringify(answer),/guest_token|video\.example|\.m3u8|manifest\.mpd/);
  assert.equal(calls.filter(x=>x.includes('subject/search')).length,1);
});
test('MovieBox rejects unrelated search hit and wrong release year rather than choosing first result',async()=>{
  let deep=0;
  const fetchImpl=async(url)=>{
    const p=new URL(url).pathname;
    if(p.includes('get-latest-app-pkgs'))
      return new Response('{}',{headers:{'content-type':'application/json',
        'x-user':'{"token":"local_test_123456789"}'}});
    if(p.endsWith('/subject/search'))return response({data:{items:[
      {subjectId:'abc',title:'Another Film',year:1931},
      {subjectId:'def',title:'M',year:2010}
    ]}});
    deep++;throw Error('Should not make play requests');
  };
  const a=await movieBoxInventory({title:'M',year:1931,fetchImpl});
  assert.equal(a.state,'no_exact_title');
  assert.equal(a.sourceVariants,0);
  assert.equal(deep,0);
});
test('MovieBox fails closed for bad metadata, guest sessions and missing provider',async()=>{
  let calls=0;const fetching=async()=>{calls++;throw Error('offline');};
  assert.equal((await movieBoxInventory({title:'',year:1931,fetchImpl:fetching})).state,
    'invalid_title_or_year');
  assert.equal(calls,0);
  assert.equal((await movieBoxInventory({title:'M',year:1931,fetchImpl:fetching})).state,
    'provider_unavailable');
  const noToken=await movieBoxInventory({title:'M',year:1931,fetchImpl:async()=>
    new Response('{}',{headers:{'content-type':'application/json'}})});
  assert.equal(noToken.state,'guest_session_unavailable');
});

test('actual HTTP 403 on both MovieBox routes is reported as denied, not missing movie',async()=>{
  const fetchImpl=async raw=>{
    const url=new URL(raw);
    if(url.pathname.includes('get-latest-app-pkgs'))
      return new Response('{}',{headers:{'content-type':'application/json',
        'x-user':'{"token":"test_guest_123456789"}'}});
    if(url.pathname.endsWith('/subject/search'))
      return Response.json({data:{items:[{subjectId:'abc',title:'Inception',year:2010}]}});
    if(url.hostname==='h5.aoneroom.com')
      return Response.json({data:{items:[{subject:{detailPath:'inception'}}]}});
    return new Response('Denied',{status:403});
  };
  const out=await movieBoxInventory({title:'Inception',year:2010,fetchImpl});
  assert.equal(out.state,'provider_access_denied');
  assert.equal(out.sourceVariants,0);
  assert.equal(out.diagnostics.plays.reason,'http_403');
  assert.equal(out.diagnostics.downloads.reason,'http_403');
});

test('MovieBox exact identity handles a release-year suffix without matching another year',async()=>{
  const fixture=async raw=>{
    const u=new URL(raw);
    if(u.pathname.includes('get-latest-app-pkgs'))
      return new Response('{}',{headers:{'content-type':'application/json',
        'x-user':'{"token":"fixture_guest_123456789"}'}});
    if(u.pathname.endsWith('/subject/search'))
      return Response.json({data:{items:[
        {subjectId:'id-1931',title:'M',year:1931},
        {subjectId:'id-2010',title:'M',year:2010}
      ]}});
    if(u.hostname==='h5.aoneroom.com')
      return Response.json({data:{items:[{subject:{detailPath:'m-movie'}}]}});
    if(u.pathname.endsWith('/subject/play')||u.pathname.endsWith('/subject/download'))
      return Response.json({data:{streams:[],downloads:[]}});
    throw Error('unexpected request');
  };
  const result=await movieBoxInventory({title:'M (1931)',year:1931,fetchImpl:fixture});
  assert.equal(result.found,1);
  assert.equal(result.subjectsProbed,1);
  const wrong=await movieBoxInventory({title:'M (1931)',year:1925,fetchImpl:fixture});
  assert.equal(wrong.state,'no_exact_title');
  assert.equal(wrong.searchHits,2);
  assert.equal(wrong.exactTitleHits,0);
  assert.equal(wrong.exactTitleAndYearHits,0);
});
