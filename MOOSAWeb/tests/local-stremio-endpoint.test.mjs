import test from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {createServer} from '../server.mjs';

async function siteFixture(run){
 const server=createServer();
 server.listen(0,'127.0.0.1');
 await once(server,'listening');
 const origin='http://127.0.0.1:'+server.address().port;
 try {await run(origin);}finally {await new Promise(resolve=>server.close(resolve));}
}
test('MOOSA Web serves the local Stremio lab with strict localhost-only CSP',async()=>{
 await siteFixture(async origin=>{
  const r=await fetch(origin+'/stremio-lab.html');
  assert.equal(r.status,200);
  assert.match(r.headers.get('content-security-policy')||'',/http:\/\/127\.0\.0\.1:11470/);
  assert.match(await r.text(),/Stremio Browser Lab/);
  const js=await fetch(origin+'/stremio-lab.js');
  assert.equal(js.status,200);
  assert.match(await js.text(),/localStremioHls/);
 });
});
test('private token bridge refuses cross-origin, GET, malformed request and SSRF',async()=>{
 await siteFixture(async origin=>{
  const endpoint=origin+'/api/stremio-local/lookup';
  const okHeaders={'Origin':origin,'Content-Type':'application/json'};
  let r=await fetch(endpoint);assert.equal(r.status,405);
  r=await fetch(endpoint,{method:'POST',headers:{...okHeaders,Origin:'https://evil.example'},
   body:JSON.stringify({})});assert.equal(r.status,403);
  r=await fetch(endpoint,{method:'POST',headers:{Origin:origin,'Content-Type':'text/plain'},
   body:'{}'});assert.equal(r.status,415);
  r=await fetch(endpoint,{method:'POST',headers:okHeaders,body:'not json'});
  assert.equal(r.status,400);
  r=await fetch(endpoint,{method:'POST',headers:okHeaders,
   body:JSON.stringify({manifestUrl:'https://127.0.0.1/stremio/x/manifest.json',imdbId:'tt31193180'})});
  assert.equal(r.status,400);
  r=await fetch(endpoint,{method:'POST',headers:okHeaders,
   body:JSON.stringify({manifestUrl:'https://example.com',imdbId:'tt31193180'})});
  assert.equal(r.status,400);
 });
});
