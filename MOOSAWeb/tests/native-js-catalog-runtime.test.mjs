import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeJsFilmForCatalog,augmentCatalogCommonsWithNativeJs} from '../native-js-catalog-runtime.mjs';
const eligible={id:100,title:'Sintel',originalTitle:'Sintel',year:2010,media:'movie'};
const source={
  name:'Wikimedia Commons • CC BY 3.0',url:'https://upload.wikimedia.org/wikipedia/commons/a/ab/Sintel.webm',
  quality:1080,licenseUrl:'https://creativecommons.org/licenses/by/3.0/',
  sourcePage:'https://commons.wikimedia.org/wiki/File:Sintel.webm',
  attribution:'Blender Foundation',subtitles:[]
};
const response=(url=source.url,slug='sintel')=>({
  filmId:slug,state:'browser_preflight_passed',
  sources:[{url,quality:1080,preflight:{verified:true,mime:'video/webm'}}]
});
test('native engine maps independent TMDB movie identity by exact original title and year',()=>{
  assert.equal(nativeJsFilmForCatalog(eligible).slug,'sintel');
  assert.equal(nativeJsFilmForCatalog({...eligible,title:'سينتل'}).slug,'sintel');
  assert.equal(nativeJsFilmForCatalog({...eligible,originalTitle:'Sintel 2'}),null);
  assert.equal(nativeJsFilmForCatalog({...eligible,year:2011}),null);
  assert.equal(nativeJsFilmForCatalog({...eligible,media:'tv',season:1,episode:1}),null);
  assert.equal(nativeJsFilmForCatalog({...eligible,id:0}),null);
});
test('catalog native JS worker verifies only exact independently licensed metadata URLs',async()=>{
  const seen=[];
  const result=await augmentCatalogCommonsWithNativeJs(eligible,[source],{
    load:async slug=>{seen.push(slug);return response();}
  });
  assert.deepEqual(seen,['sintel']);
  assert.equal(result.engine,'reviewed_js_worker_preflight');
  assert.equal(result.verified,1);
  assert.equal(result.sources[0].sourcePage,source.sourcePage);
  assert.equal(result.sources[0].licenseUrl,source.licenseUrl);
  assert.deepEqual(result.sources[0].preflight,{verified:true,mime:'video/webm'});
});
test('unapproved movie or TV cannot trigger worker execution',async()=>{
  let calls=0;
  for(const meta of [{...eligible,year:2020},{...eligible,media:'tv',season:1,episode:1}]){
    const result=await augmentCatalogCommonsWithNativeJs(meta,[source],{
      load:async()=>{calls++;return response();}
    });
    assert.equal(result.attempted,false);
    assert.equal(result.engine,'commons_metadata');
    assert.deepEqual(result.sources,[source]);
  }
  assert.equal(calls,0);
});
test('cross-title URLs and bogus provider success cannot overwrite exact-license sources',async()=>{
  for(const wrong of [
    response('https://upload.wikimedia.org/wikipedia/commons/evil/Other.webm'),
    response(source.url,'big-buck-bunny'),
    {...response(),sources:[{url:source.url,preflight:{verified:false}}]},
    {...response(),state:'no_browser_approved_sources'}
  ]){
    const result=await augmentCatalogCommonsWithNativeJs(eligible,[source],{
      load:async()=>wrong
    });
    assert.equal(result.verified,0);
    assert.equal(result.engine,'commons_metadata');
    assert.deepEqual(result.sources,[source]);
  }
});
test('no independent licensed source means no worker request',async()=>{
  let called=false;
  const res=await augmentCatalogCommonsWithNativeJs(eligible,[],{
    load:async()=>{called=true;return response();}
  });
  assert.equal(res.attempted,false);
  assert.equal(called,false);
});
test('worker errors and timeouts cannot remove previously verified licensed sources',async()=>{
  const thrown=await augmentCatalogCommonsWithNativeJs(eligible,[source],{
    load:async()=>{throw Error('private token');}
  });
  assert.equal(thrown.engine,'commons_metadata');
  assert.deepEqual(thrown.sources,[source]);
  const timed=await augmentCatalogCommonsWithNativeJs(eligible,[source],{
    load:()=>new Promise(()=>{}),timeoutMs:5
  });
  assert.equal(timed.engine,'commons_metadata');
  assert.deepEqual(timed.sources,[source]);
});
test('multiple sources retain Safari-compatible fallbacks after JS preflight',async()=>{
  const optimized={...source,url:source.url.replace('.webm','-safari.webm'),codecOptimized:true};
  const result=await augmentCatalogCommonsWithNativeJs(eligible,[source,optimized],{
    load:async()=>response()
  });
  assert.equal(result.sources.length,2);
  assert.equal(result.sources[0].preflight.verified,true);
  assert.equal(result.sources[1].url,optimized.url);
});
