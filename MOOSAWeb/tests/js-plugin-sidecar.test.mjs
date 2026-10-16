import test from 'node:test';
import assert from 'node:assert/strict';
import {runLicensedJsProbe} from '../js-plugin-sidecar.mjs';

const meta={media:'movie',id:1,title:'Big Buck Bunny',originalTitle:'Big Buck Bunny'};
const licensed={licensedMedia:true,permittedHosts:['upload.wikimedia.org']};

test('isolated JS worker returns ONLY the synthetic licensed 720p candidate',async()=>{
  const r=await runLicensedJsProbe(meta,licensed);
  assert.equal(r.ok,true,JSON.stringify(r));
  assert.equal(r.reason,'mock_only');
  assert.equal(r.sources.length,1);
  assert.equal(r.sources[0].quality,720);
  assert.match(r.sources[0].url,/mock-open-film-720\.webm$/);
  assert.equal(r.blockedHeaders,1,'Protected Referer stream must be rejected');
  assert.ok(r.rejected>=2,'Protected and unknown domain sources denied');
});

test('no rights permission or no explicit CDN allowlist means no plugin is launched',async()=>{
  const denied=await runLicensedJsProbe(meta,{});
  assert.equal(denied.ok,false);
  assert.equal(denied.reason,'rights_not_approved');
  assert.deepEqual(denied.sources,[]);
  const empty=await runLicensedJsProbe(meta,{licensedMedia:true,permittedHosts:[]});
  assert.equal(empty.ok,false);
  assert.deepEqual(empty.sources,[]);
});

test('only pinned fixture ID is accepted and arbitrary code paths are rejected',async()=>{
  const invalid=await runLicensedJsProbe(meta,{...licensed,provider:'../../evil.cjs'});
  assert.equal(invalid.reason,'provider_not_approved');
  assert.deepEqual(invalid.sources,[]);
});

test('unrelated movie and TV season are denied before running a plugin',async()=>{
  for(const data of [
    {...meta,title:'Hollywood movie'},
    {...meta,originalTitle:'Different movie'},
    {...meta,media:'tv',season:1,episode:1}
  ]){
    const result=await runLicensedJsProbe(data,licensed);
    assert.equal(result.reason,'sample_not_approved');
  }
});

test('a slow worker is killed on the request deadline',async()=>{
  const started=Date.now();
  const result=await runLicensedJsProbe({...meta,id:999},{...licensed,timeoutMs:300});
  assert.equal(result.ok,false);
  assert.equal(result.reason,'timeout',JSON.stringify(result));
  assert.ok(Date.now()-started<1150,'Timeout must prevent waiting for stalled providers');
});

test('wrong CDN host does not leak the even-licensed synthetic source',async()=>{
  const result=await runLicensedJsProbe(meta,{
    licensedMedia:true,permittedHosts:['example.org']});
  assert.equal(result.ok,true);
  assert.deepEqual(result.sources,[]);
});
