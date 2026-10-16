import test from 'node:test';
import assert from 'node:assert/strict';
import {resolveBrowserReadyJsSources} from '../js-provider-validation.mjs';
const meta={media:'movie',id:42,title:'Big Buck Bunny',originalTitle:'Big Buck Bunny'};
const allowed={provider:'commons-authorized',licensedMedia:true,permittedHosts:['upload.wikimedia.org']};
const good=(url,options)=>{
 const type=url.endsWith('.webm')?'video/webm':'text/html';
 return new Response(null,{status:200,
  headers:{'Content-Type':type,
    'Access-Control-Allow-Origin':'*','Content-Length':'93192761'}});
};
test('rights gate prevents even starting local approved plugin if not authorized',async()=>{
  const v=await resolveBrowserReadyJsSources(meta,{provider:'commons-authorized'});
  assert.equal(v.state,'rights_not_approved');
  assert.deepEqual(v.sources,[]);
});
test('fake media 429 from allowed host is never passed to browser',async()=>{
  const res=await resolveBrowserReadyJsSources(meta,{
    provider:'licensed-fixture',licensedMedia:true,
    permittedHosts:['upload.wikimedia.org'],
    fetchImpl:async()=>new Response(null,{status:429,
      headers:{'Content-Type':'text/html'}})
  });
  assert.equal(res.state,'no_browser_approved_sources');
  assert.equal(res.sources.length,0);
  assert.equal(res.issues.rate_limited,1);
  assert.equal(res.counts.blockedHeaders,1);
});
test('even reviewed fixture is not accepted without a valid video MIME and CORS',async()=>{
  for(const headers of [
    {'Content-Type':'text/html','Access-Control-Allow-Origin':'*'},
    {'Content-Type':'video/webm'}
  ]){
    const res=await resolveBrowserReadyJsSources(meta,{
      provider:'licensed-fixture',licensedMedia:true,
      permittedHosts:['upload.wikimedia.org'],
      fetchImpl:async()=>new Response(null,{status:200,headers})
    });
    assert.deepEqual(res.sources,[]);
  }
});
test('one verified approved movie candidate emerges from fixture after browser HEAD',async()=>{
  const res=await resolveBrowserReadyJsSources(meta,{
    provider:'licensed-fixture',licensedMedia:true,
    permittedHosts:['upload.wikimedia.org'],
    fetchImpl:async()=>new Response(null,{status:200,headers:{
      'Content-Type':'video/webm',
      'Access-Control-Allow-Origin':'*',
      'Content-Length':'1000000'
    }})
  });
  assert.equal(res.state,'browser_preflight_passed',JSON.stringify(res));
  assert.equal(res.counts.verified,1);
  assert.equal(res.sources[0].preflight.verified,true);
  assert.equal(res.sources[0].quality,720);
});
