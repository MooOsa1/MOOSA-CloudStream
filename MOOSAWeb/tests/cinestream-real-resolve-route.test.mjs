import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from '../server.mjs';

// Exercise the ACTUAL /api/resolve handler and real adapter, not a fabricated
// response in the browser. Remote TMDB/provider replies and the media-prefix
// verifier are fixtures, so this regression runs without third-party network.
// The real Chrome Play flow is tested in the separate live CI workflow.
const SITE='https://moosa-web.onrender.com';
const FILM={id:19,title:'Metropolis',media:'movie',year:1927,imdbId:'tt0017136'};
const payload={qualities:{
  '720':{url:'https://hcdn3.hakunaymatata.com/test-720.mp4',type:'mp4'},
  '480':{url:'https://bcdn.hakunaymatata.com/test-480.mp4',type:'mp4'},
  '360':{url:'https://bcdn.hakunaymatata.com/test-360.mp4',type:'mp4'},
  '2160':{url:'https://bcdn.hakunaymatata.com/test-4k.mp4',type:'mp4'},
  '720-evil':{url:'https://bad.example/private.mp4',type:'mp4'}
}};
test('actual Play backend /api/resolve returns exact-title gateway sources and 3 fallback choices',async()=>{
  const native=globalThis.fetch;
  const previous=Object.fromEntries(['TMDB_API_KEY','TMDB_READ_ACCESS_TOKEN',
    'CINESTREAM_MULTIFILM_PILOT','CINESTREAM_MULTIFILM_RIGHTS_APPROVED']
    .map(x=>[x,process.env[x]]));
  let server;
  let lookupCalls=0,protocolCalls=0,issueCalls=0;
  try{
    process.env.TMDB_API_KEY='ISOLATED_TEST_KEY';
    delete process.env.TMDB_READ_ACCESS_TOKEN;
    process.env.CINESTREAM_MULTIFILM_PILOT='1';
    process.env.CINESTREAM_MULTIFILM_RIGHTS_APPROVED='1';
    globalThis.fetch=async(raw,opts)=>{
      const url=new URL(raw instanceof URL?raw.href:
        typeof raw==='string'?raw:raw.url);
      if(url.hostname==='api.themoviedb.org'){
        return Response.json({id:FILM.id,title:FILM.title,
          original_title:FILM.title,release_date:'1927-01-01',
          imdb_id:FILM.imdbId});
      }
      if(url.hostname==='enc-dec.app'){
        lookupCalls++;assert.equal(url.searchParams.get('text'),'19');
        return Response.json({result:'metropolis_fixture_key'});
      }
      if(url.hostname==='vidlink.pro'){
        protocolCalls++;return Response.json(payload);
      }
      return Response.json({blocked:true},{status:503});
    };
    const gateway={issueIfBytesVerified:async({url,rightsApproved,sourceVerified})=>{
      issueCalls++;
      assert.equal(rightsApproved,true);
      assert.equal(sourceVerified,true);
      const code=url.match(/test-(\d+)\.mp4$/)?.[1];
      assert.ok(['720','480','360'].includes(code),'Rejected 4K or unrelated source');
      return {source:{
        url:SITE+'/api/media/'+({720:'A',480:'B',360:'C'}[code]).repeat(43),
        gatewayIssued:true,format:'video/mp4'
      }};
    }};
    server=createServer({mediaGateway:gateway});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const address='http://127.0.0.1:'+server.address().port;
    const res=await native(address+'/api/resolve?media=movie&id=19');
    assert.equal(res.status,200);
    const data=await res.json();
    assert.equal(data.sources.length,3,'Actual backend did not offer 3 verified choices');
    assert.equal(data.count,3);
    assert.equal(data.extrasAvailable,true);
    assert.equal(lookupCalls,1);
    assert.equal(protocolCalls,1);
    assert.equal(issueCalls,3);
    assert.deepEqual(data.sources.map(x=>x.originalMovieTmdbId),[19,19,19]);
    assert.deepEqual(data.sources.map(x=>x.originalMovieImdbId),
      ['tt0017136','tt0017136','tt0017136']);
    assert.ok(data.sources.every(x=>x.gatewayIssued===true&&x.quality===0));
    assert.deepEqual(data.sources.map(x=>x.url),
      ['A','B','C'].map(ch=>SITE+'/api/media/'+ch.repeat(43)));
    const extras=await native(address+'/api/resolve?media=movie&id=19&phase=extras');
    assert.equal(extras.status,200);
    const extraData=await extras.json();
    assert.ok(Array.isArray(extraData.sources));
    assert.equal(protocolCalls,1,
      'Background extra search must never duplicate CineStream source requests');
    const unsupported=await native(address+'/api/resolve?media=movie&id=19&phase=untrusted');
    assert.equal(unsupported.status,400);
    // A second unrelated title must never reuse Metropolis sources.
    const res2=await native(address+'/api/resolve?media=movie&id=1234567');
    assert.equal(res2.status,200);
    const other=await res2.json();
    assert.deepEqual(other.sources,[]);
    assert.equal(protocolCalls,1,'Unapproved titles cannot query the original provider');
  }finally{
    if(server)await new Promise(resolve=>server.close(resolve));
    globalThis.fetch=native;
    for(const [key,value] of Object.entries(previous))
      if(value===undefined)delete process.env[key];else process.env[key]=value;
  }
});

test('fast vetted Play response does not await unrelated slow provider services',async()=>{
  const native=globalThis.fetch;
  const original=Object.fromEntries(['TMDB_API_KEY','TMDB_READ_ACCESS_TOKEN',
    'CINESTREAM_MULTIFILM_PILOT','CINESTREAM_MULTIFILM_RIGHTS_APPROVED']
    .map(x=>[x,process.env[x]]));
  let server,releaseSlow,unrelatedCount=0,realProbes=0;
  const paused=new Promise(resolve=>{releaseSlow=resolve;});
  try{
    process.env.TMDB_API_KEY='ISOLATED_TEST_KEY';
    delete process.env.TMDB_READ_ACCESS_TOKEN;
    process.env.CINESTREAM_MULTIFILM_PILOT='1';
    process.env.CINESTREAM_MULTIFILM_RIGHTS_APPROVED='1';
    globalThis.fetch=async(raw)=>{
      const url=new URL(raw instanceof URL?raw.href:
        typeof raw==='string'?raw:raw.url);
      if(url.hostname==='api.themoviedb.org')
        return Response.json({id:832,title:'M',
          original_title:'M',release_date:'1931-01-01',
          imdb_id:'tt0022100'});
      if(url.hostname==='enc-dec.app')
        return Response.json({result:'m_fast_key'});
      if(url.hostname==='vidlink.pro')
        return Response.json(payload);
      unrelatedCount++;
      await paused;
      return Response.json({response:{docs:[]}});
    };
    const gateway={issueIfBytesVerified:async({url})=>{
      const q=url.match(/test-(\d+)\.mp4$/)?.[1];
      if(!['720','480','360'].includes(q))
        return {source:null,reason:'invalid_source'};
      realProbes++;
      return {source:{gatewayIssued:true,format:'video/mp4',
        url:SITE+'/api/media/'+({720:'A',480:'B',360:'C'}[q]).repeat(43)}};
    }};
    server=createServer({mediaGateway:gateway});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const started=performance.now();
    const resp=await native('http://127.0.0.1:'+server.address().port+
      '/api/resolve?media=movie&id=832',{signal:AbortSignal.timeout(1500)});
    const elapsed=performance.now()-started;
    assert.equal(resp.status,200);
    const result=await resp.json();
    assert.equal(result.count,3);
    assert.equal(result.extrasAvailable,true);
    assert.equal(realProbes,3);
    assert.ok(unrelatedCount>0,'The ordinary search should be running concurrently');
    assert.ok(elapsed<1200,'Play must not wait for stalled unrelated providers; ms='+elapsed);
  }finally{
    releaseSlow?.();
    if(server)await new Promise(resolve=>server.close(resolve));
    globalThis.fetch=native;
    for(const [k,v] of Object.entries(original))
      if(v===undefined)delete process.env[k];else process.env[k]=v;
  }
});
