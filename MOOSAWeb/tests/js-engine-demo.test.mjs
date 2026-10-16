import test from 'node:test';
import assert from 'node:assert/strict';
import {createJsEngineDemo,DEMO_META,matchVerifiedJsMedia} from '../js-engine-demo.mjs';
import {createServer} from '../server.mjs';

const allowed={
  url:'https://upload.wikimedia.org/wikipedia/commons/a/ab/Big_Buck_Bunny_alt.webm',
  quality:720,preflight:{verified:true,mime:'video/webm'}
};

test('Only fixed Blender open-licensed movie and trusted JS plugin are requested',async()=>{
  let calls=0,options;
  const engine=createJsEngineDemo({
    run:async(meta,opts)=>{
      calls++;options={meta,opts};
      return {sources:[allowed],state:'browser_preflight_passed'};
    }
  });
  const r=await engine.load();
  assert.equal(calls,1);
  assert.deepEqual(options.meta,DEMO_META);
  assert.equal(options.opts.provider,'commons-authorized');
  assert.equal(options.opts.licensedMedia,true);
  assert.deepEqual(options.opts.permittedHosts,['upload.wikimedia.org']);
  assert.equal(r.count,1);
  assert.equal(r.originalCloudStreamPlugins,false);
  assert.equal(r.sources[0].quality,720);
  assert.equal(r.sources[0].licenseUrl,'https://creativecommons.org/licenses/by/3.0/');
});

test('Unverified, unapproved host and low quality are all rejected from public JSON',async()=>{
  const engine=createJsEngineDemo({run:async()=>({sources:[
    {...allowed,preflight:{verified:false}},
    {...allowed,url:'https://attacker.example/movie.webm'},
    {...allowed,quality:480},
    {...allowed,url:'javascript:alert(1)'}
  ]})});
  const r=await engine.load();
  assert.equal(r.count,0);
  assert.equal(r.state,'no_browser_approved_sources');
  assert.deepEqual(r.sources,[]);
});

test('Cached verified sources share one request and expire after five minutes',async()=>{
  let clock=10000,calls=0;
  const engine=createJsEngineDemo({
    now:()=>clock,
    run:async()=>{calls++;await new Promise(resolve=>setTimeout(resolve,10));return {sources:[allowed]};}
  });
  const [a,b]=await Promise.all([engine.load(),engine.load()]);
  assert.equal(calls,1);
  assert.equal(a.count,1);
  assert.equal(b.count,1);
  clock+=10000;await engine.load();assert.equal(calls,1);
  clock+=300_001;await engine.load();assert.equal(calls,2);
});

test('Failures are non-leaky, cache briefly and recover after expiry',async()=>{
  let clock=0,calls=0;
  const engine=createJsEngineDemo({
    now:()=>clock,
    run:async()=>{
      calls++;
      if(calls===1)throw new Error('PRIVATE_TOKEN_DO_NOT_SHOW');
      return {sources:[allowed]};
    }
  });
  const bad=await engine.load();
  assert.equal(bad.count,0);
  assert.ok(!JSON.stringify(bad).includes('PRIVATE_TOKEN_DO_NOT_SHOW'));
  await engine.load();assert.equal(calls,1);
  clock+=45_001;
  const good=await engine.load();
  assert.equal(good.count,1);
});

test('Fixed sample Node engine endpoint is GET-only and excludes user parameters',async()=>{
  const server=createServer();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const base='http://127.0.0.1:'+server.address().port;
    const invalid=await fetch(base+'/api/js-engine/demo?provider=../../evil.js&url=http://127.0.0.1/');
    assert.equal(invalid.status,400);
    const method=await fetch(base+'/api/js-engine/demo',{method:'POST'});
    assert.equal(method.status,405);
  }finally{await new Promise(resolve=>server.close(resolve));}
});


test('Only identical licensed Commons URLs can be promoted to JS-verified playback',()=>{
  const existing={id:'big-buck-bunny',sources:[
    {url:allowed.url,quality:720,sourcePage:'https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_alt.webm'},
    {url:'https://upload.wikimedia.org/some-other-4k.webm',quality:2250}
  ]};
  const proposed={
    filmId:'big-buck-bunny',state:'browser_preflight_passed',sources:[
      {...allowed,preflight:{verified:true,mime:'video/webm'}},
      {url:'https://evil.example/unknown.mp4',quality:2160,preflight:{verified:true,mime:'video/mp4'}}
    ]
  };
  const result=matchVerifiedJsMedia(existing,proposed);
  assert.equal(result.length,2,'Keep licensed unverified alternatives as playback fallback');
  assert.equal(result[0].url,allowed.url);
  assert.equal(result[0].preflight.verified,true);
  assert.equal(result[0].sourcePage,existing.sources[0].sourcePage);
  assert.equal(result[1].preflight,undefined);
  assert.equal(matchVerifiedJsMedia({...existing,id:'sintel'},proposed),null);
  assert.equal(matchVerifiedJsMedia(existing,{...proposed,state:'no_browser_approved_sources'}),null);
  assert.equal(matchVerifiedJsMedia(existing,{
    state:'browser_preflight_passed',sources:[{...allowed,
      preflight:{verified:false}}]
  }),null);
});

test('A separate reviewed JS worker request supports every fixed curated film without cross-title cache',async()=>{
  const metaCalls=[];
  const engine=createJsEngineDemo({run:async meta=>{
    metaCalls.push(meta);
    return {sources:[allowed],state:'browser_preflight_passed'};
  }});
  const ids=['big-buck-bunny','sintel','tears-of-steel','elephants-dream'];
  for(const id of ids){
    const sample=await engine.load(id);
    assert.equal(sample.filmId,id);
    assert.equal(sample.originalCloudStreamPlugins,false);
  }
  assert.deepEqual(metaCalls.map(x=>x.title),
    ['Big Buck Bunny','Sintel','Tears of Steel','Elephants Dream']);
  await engine.load('sintel');
  assert.equal(metaCalls.length,4,'Each film is cached independently');
  assert.equal(await engine.load('../../evil'),null);
});

test('No JavaScript verification or attribution can cross between approved film identities',()=>{
  const existing={id:'sintel',sources:[
    {url:allowed.url,licenseUrl:'https://creativecommons.org/licenses/by-sa/3.0/',
      attribution:'Original license metadata',quality:720}
  ]};
  const bad={filmId:'big-buck-bunny',state:'browser_preflight_passed',sources:[allowed]};
  assert.equal(matchVerifiedJsMedia(existing,bad),null);
  const verified={...bad,filmId:'sintel'};
  const sources=matchVerifiedJsMedia(existing,verified);
  assert.equal(sources.length,1);
  assert.equal(sources[0].licenseUrl,existing.sources[0].licenseUrl);
  assert.equal(sources[0].attribution,'Original license metadata');
});

test('HTTP route only accepts an explicitly approved title ID, never code, URLs or extra arguments',async()=>{
  const server=createServer();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    const base='http://127.0.0.1:'+server.address().port;
    for(const query of [
      '?id=evil-film','?id=../../evil','?url=https://example.com/a.mp4',
      '?id=sintel&provider=remote','?id=sintel&id=big-buck-bunny'
    ]){
      const res=await fetch(base+'/api/js-engine/demo'+query);
      assert.equal(res.status,400,query);
    }
  }finally{await new Promise(resolve=>server.close(resolve));}
});
