import test from 'node:test';
import assert from 'node:assert/strict';
import {findNightFromCineStream,isExactNight,vidlinkPilotHosts}
  from '../vidlink-original-night-pilot.mjs';

const exact={media:'movie',id:10331,imdbId:'tt0063350',
  title:'Night of the Living Dead',originalTitle:'Night of the Living Dead',
  year:1968};
const token='Z'.repeat(43);
const source={url:'https://moosa-web.onrender.com/api/media/'+token,
  gatewayIssued:true,format:'video/mp4'};

test('exact identity gate rejects wrong titles, years, ids and TV episodes',()=>{
  assert.equal(isExactNight(exact),true);
  for(const altered of [
    {...exact,media:'tv'}, {...exact,id:10332},
    {...exact,imdbId:'tt0000000'},{...exact,year:1990},
    {...exact,title:'Other',originalTitle:'Another'},
    {...exact,year:2026}
  ])assert.equal(isExactNight(altered),false,JSON.stringify(altered));
});
test('media host environment list accepts only sane lowercased hostnames',()=>{
  assert.deepEqual(vidlinkPilotHosts('CDN.example.org, cdn.example.org, 127.0.0.1,local,too:8000'),[
    'cdn.example.org'
  ]);
  assert.deepEqual(vidlinkPilotHosts(''),[]);
});
test('disabled by default and unrelated movie makes NO upstream request',async()=>{
  let requests=0;
  const gateway={issueIfBytesVerified:async()=>{throw Error('unexpected issue')}};
  const fetchImpl=async()=>{requests++;throw Error('unexpected request')};
  assert.deepEqual(await findNightFromCineStream({
    meta:exact,mediaGateway:gateway,enabled:false,fetchImpl
  }),[]);
  assert.deepEqual(await findNightFromCineStream({
    meta:{...exact,id:1234},mediaGateway:gateway,enabled:true,fetchImpl
  }),[]);
  assert.equal(requests,0);
});
test('original Vidlink HTTP protocol emits verified exact-film MOOSA capability only',async()=>{
  const requested=[];
  const verified=[];
  const fetchImpl=async(raw,opts)=>{
    const u=new URL(raw);requested.push(u.hostname);
    assert.equal(opts.redirect,'error');
    if(u.hostname==='enc-dec.app'){
      assert.equal(u.searchParams.get('text'),'10331');
      return Response.json({result:'AbC-123_'});
    }
    assert.equal(raw,'https://vidlink.pro/api/b/movie/AbC-123_');
    return Response.json({stream:{qualities:{
      '720':{url:'https://cdn.verified.example/film.html',type:'html'},
      '480':{url:'https://cdn.verified.example/film.mp4',
        type:'mp4',headers:{Referer:'https://filmboom.top/'}}
    }}});
  };
  const gateway={issueIfBytesVerified:async meta=>{
    verified.push(meta);
    assert.equal(meta.rightsApproved,true);
    assert.equal(meta.sourceVerified,true);
    assert.equal(meta.url,'https://cdn.verified.example/film.mp4');
    assert.equal(meta.headers.Referer,'https://filmboom.top/');
    return {source};
  }};
  const streams=await findNightFromCineStream({meta:exact,mediaGateway:gateway,
    enabled:true,fetchImpl});
  assert.deepEqual(requested,['enc-dec.app','vidlink.pro']);
  assert.equal(verified.length,1);
  assert.equal(streams.length,1);
  assert.deepEqual(streams[0],{
    ...source,name:'CineStream • Vidlink — single-film pilot',
    provider:'CineStream',quality:0,subtitles:[],
    originalMovieTmdbId:10331,originalMovieImdbId:'tt0063350',
    originalMovieYear:1968
  });
});
test('no link ever released when the real media range preflight fails',async()=>{
  const gateway={issueIfBytesVerified:async()=>({
    source:null,reason:'upstream_returned_nonvideo_mime'
  })};
  const fetchImpl=async raw=>new URL(raw).hostname==='enc-dec.app'
    ?Response.json({result:'AbC-123_'})
    :Response.json({qualities:{'480':{url:'https://cdn.example.com/not-video.mp4'}}});
  assert.deepEqual(await findNightFromCineStream({
    meta:exact,mediaGateway:gateway,enabled:true,fetchImpl
  }),[]);
});
test('non-JSON, bad encoded IDs and provider 403 do not show fake servers',async()=>{
  const gateway={issueIfBytesVerified:async()=>{throw Error('unexpected')}};
  for(const make of [
    ()=>new Response('<html>not a key</html>',{status:200}),
    ()=>Response.json({result:'../other/film'}),
    ()=>new Response('blocked',{status:403})
  ]){
    const found=await findNightFromCineStream({
      meta:exact,mediaGateway:gateway,enabled:true,
      fetchImpl:async()=>make()
    });
    assert.deepEqual(found,[]);
  }
});
