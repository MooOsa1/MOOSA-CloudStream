import test from 'node:test';
import assert from 'node:assert/strict';
import {discoverVidlinkInventory} from '../vidlink-multifilm-candidates.mjs';

test('arbitrary valid movie ID reaches real generic Vidlink protocol, not fixed pilot list',async()=>{
  const requested=[];
  const stub=async(url,init)=>{
    const u=new URL(url);
    requested.push(u.hostname);
    assert.equal(init.redirect,'error');
    if(u.hostname==='enc-dec.app'){
      assert.equal(u.searchParams.get('text'),'27205');
      return Response.json({result:'inception_test_code'});
    }
    assert.equal(u.hostname,'vidlink.pro');
    return Response.json({qualities:{
      '1080':{url:'https://hcdn3.hakunaymatata.com/1080.mp4',type:'mp4'},
      '720':{url:'https://bcdn.hakunaymatata.com/720.mp4',type:'mp4'},
      '480':{url:'https://hcdn3.hakunaymatata.com/480.mp4',type:'mp4'},
      '2160':{url:'https://hcdn3.hakunaymatata.com/4k.mp4',type:'mp4'},
      '360':{url:'https://evil.example/360.mp4',type:'mp4'}
    }});
  };
  const result=await discoverVidlinkInventory({tmdbId:27205,fetchImpl:stub});
  assert.deepEqual(requested,['enc-dec.app','vidlink.pro']);
  assert.equal(result.tmdbId,27205);
  assert.equal(result.availableVariants,3);
  assert.deepEqual(result.advertisedQualities,[1080,720,480]);
  assert.deepEqual(result.formatCounts,{mp4:3,hls:0});
  assert.equal(result.playableVerified,false);
  assert.equal(result.rightsVerified,false);
  const publicBody=JSON.stringify(result);
  assert.doesNotMatch(publicBody,/https?:|\.mp4|api\/media|headers|cookie/i);
});

test('zero provider qualities and filtered hosts produce distinct actionable diagnostics',async()=>{
  async function run(qualities){
    return discoverVidlinkInventory({tmdbId:157336,fetchImpl:async url=>{
      if(new URL(url).hostname==='enc-dec.app')
        return Response.json({result:'interstellar_test'});
      return Response.json({qualities});
    }});
  }
  const noQualities=await run({});
  assert.equal(noQualities.status,'provider_no_quality_list');
  assert.equal(noQualities.filterCounts.providerEntries,0);
  const filtered=await run({
    '720':{url:'https://unapproved.example/film.mp4',type:'mp4'},
    '2160':{url:'https://hcdn3.hakunaymatata.com/film-4k.mp4',type:'mp4'},
    '480':{url:'https://hcdn3.hakunaymatata.com/fake.txt'}
  });
  assert.equal(filtered.status,'all_candidates_filtered');
  assert.equal(filtered.availableVariants,0);
  assert.equal(filtered.filterCounts.blockedHosts,1);
  assert.equal(filtered.filterCounts.excludedQualities,1);
  assert.equal(filtered.filterCounts.unsupportedFormats,1);
  assert.equal(JSON.stringify(filtered).includes('https://'),false);
});

test('invalid ids make zero network requests; upstream errors are explicit',async()=>{
  let called=0;
  const stub=async()=>{called++;throw Error('network unavailable');};
  for(const id of [0,-1,NaN,Infinity,1e12,'abc',null]){
    const result=await discoverVidlinkInventory({tmdbId:id,fetchImpl:stub});
    assert.equal(result.status,'invalid_movie_id');
  }
  assert.equal(called,0);
  assert.equal((await discoverVidlinkInventory({tmdbId:27205,fetchImpl:stub})).status,
    'provider_unavailable');
  assert.equal(called,1);
});

test('private lab exposes counts but never URLs and rejects unauthorized inventory requests',async()=>{
  const old={lab:process.env.MOOSA_TWO_FILM_PRIVATE_LAB,
    pass:process.env.MOOSA_LAB_PASSWORD};
  const original=globalThis.fetch;
  let app;
  try{
    process.env.MOOSA_TWO_FILM_PRIVATE_LAB='1';
    process.env.MOOSA_LAB_PASSWORD='my_test_password_123456';
    const {createServer}=await import('../server.mjs');
    globalThis.fetch=async(raw,init)=>{
      const u=new URL(typeof raw==='string'?raw:raw.href||raw.url);
      if(u.hostname==='enc-dec.app')return Response.json({result:'test_key'});
      if(u.hostname==='vidlink.pro')return Response.json({qualities:{
        '720':{url:'https://hcdn3.hakunaymatata.com/test-720.mp4',type:'mp4'}
      }});
      throw Error('Only approved provider hosts may be called');
    };
    app=createServer();
    await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));
    const base='http://127.0.0.1:'+app.address().port;
    const anonymous=await original(base+'/api/source-discovery?media=movie&id=27205');
    assert.equal(anonymous.status,401);
    const headers={Authorization:'Basic '+Buffer.from(
      'moosa:my_test_password_123456').toString('base64')};
    const good=await original(base+'/api/source-discovery?media=movie&id=27205',{headers});
    assert.equal(good.status,200);
    const body=await good.json();
    assert.equal(body.availableVariants,1);
    assert.equal(body.status,'candidates_found');
    assert.doesNotMatch(JSON.stringify(body),/https?:|\.mp4|test_key|headers/i);
    for(const path of [
      '/api/source-discovery?media=movie&id=-1',
      '/api/source-discovery?media=movie&id=abc',
      '/api/source-discovery?media=movie&id=27205&url=https://evil.example',
      '/api/source-discovery?media=tv&id=27205',
      '/api/source-discovery?id=27205',
      '/api/source-discovery?media=movie&id=27205&id=832'
    ])assert.equal((await original(base+path,{headers})).status,400,path);
    const page=await original(base+'/source-discovery.html',{headers});
    assert.equal(page.status,200);
    const html=await page.text();
    assert.equal(html.includes('href="/source-discovery.css"'),true);
    assert.equal(html.includes('<style'),false);
    const stylesheet=await original(base+'/source-discovery.css',{headers});
    assert.equal(stylesheet.status,200);
    assert.equal((stylesheet.headers.get('content-type')||'').includes('text/css'),true);
    assert.match(await stylesheet.text(),/background:#17181d/);
  }finally{
    if(app)await new Promise(resolve=>app.close(resolve));
    globalThis.fetch=original;
    for(const [key,val] of [
      ['MOOSA_TWO_FILM_PRIVATE_LAB',old.lab],
      ['MOOSA_LAB_PASSWORD',old.pass]
    ])if(val===undefined)delete process.env[key];else process.env[key]=val;
  }
});
