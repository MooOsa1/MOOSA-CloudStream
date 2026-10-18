import test from 'node:test';
import assert from 'node:assert/strict';
import {probeStreamPlayRedirects,classifyStreamPlayRedirectHtml} from '../streamplay-redirect-probe.mjs';
const rot13=s=>s.replace(/[a-zA-Z]/g,c=>String.fromCharCode(
  (c.charCodeAt(0)&32?97:65)+(c.toLowerCase().charCodeAt(0)-97+13)%26));
const b64=s=>Buffer.from(s).toString('base64');
test('StreamPlay encoded link stage reports destination hostname without disclosing URL',async()=>{
  const target='https://hubcloud.example/drive/signedpath';
  const envelope=JSON.stringify({o:b64(target),data:b64('unused'),blog_url:'https://blog.example'});
  const packed=b64(b64(rot13(b64(envelope))));
  const page="<script>s('o','"+packed+"')</script>";
  const classified=classifyStreamPlayRedirectHtml(page);
  assert.equal(classified.state,'decoded_intermediate');
  assert.equal(classified.destinationHost,'hubcloud.example');
  assert.equal(classified.hubcloudHints,1);
  assert.doesNotMatch(JSON.stringify(classified),/signedpath|drive\/|blog\.example/);
  const calls=[];
  const result=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/?id=1">source</a>'+
      '<a href="https://evil.example/path">unexpected</a>',
    fetchImpl:async(url,opts)=>{
      calls.push(url);assert.equal(opts.redirect,'error');
      return new Response(page,{headers:{'content-type':'text/html'}});
    }
  });
  assert.deepEqual(calls,['https://greenmotors.club/?id=1']);
  assert.equal(result.decodedIntermediateLinks,1);
  assert.equal(result.videoVerified,false);
  assert.doesNotMatch(JSON.stringify(result),/https:|signedpath|id=1/);
});
test('Unapproved hosts, 403 and page without encoded links do not imply video',async()=>{
  let calls=0;
  const empty=await probeStreamPlayRedirects({
    postHtml:'<a href="https://evil.example/link">Fake</a>',
    fetchImpl:async()=>{calls++;return new Response('');}
  });
  assert.equal(empty.state,'no_greenmotors_links');assert.equal(calls,0);
  const blocked=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/token">Link</a>',
    fetchImpl:async()=>new Response('Forbidden',{status:403})
  });
  assert.equal(blocked.state,'http_403');
  assert.equal(blocked.decodedIntermediateLinks,0);
});
