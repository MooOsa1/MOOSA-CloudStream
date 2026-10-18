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
      calls.push(url);assert.equal(opts.redirect,'manual');
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

test('HTTP 302 is classified by host with no redirect follow or signed-path disclosure',async()=>{
  let calls=0;
  const outcome=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/?id=private">Link</a>',
    fetchImpl:async(url,init)=>{
      calls++;
      assert.equal(init.redirect,'manual');
      return new Response(null,{status:302,headers:{
        location:'https://hubcloud.example/drive/do-not-disclose?token=secret'
      }});
    }
  });
  assert.equal(calls,1);
  assert.equal(outcome.state,'http_redirect');
  assert.equal(outcome.redirectsSeen,1);
  assert.deepEqual(outcome.destinationHosts,['hubcloud.example']);
  assert.equal(outcome.videoVerified,false);
  assert.doesNotMatch(JSON.stringify(outcome),/secret|private|do-not-disclose|https:|drive\//);
});
test('Network failure is distinct from rejected video lookup and no source',async()=>{
  const failure=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/link">Link</a>',
    fetchImpl:async()=>{throw TypeError('fetch failed');}
  });
  assert.equal(failure.state,'connection_failed');
  assert.equal(failure.inspected,1);
  assert.deepEqual(failure.statuses,['connection_failed']);
  assert.equal(failure.videoVerified,false);
});
