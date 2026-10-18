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

test('only reviewed .club to .cc host redirect is followed once, without leaking the link',async()=>{
  const envelope=JSON.stringify({o:b64('https://hubcloud.example/signed-private-media')});
  const encoded=b64(b64(rot13(b64(envelope))));
  const calls=[];
  const probe=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/go/film">movie source</a>',
    fetchImpl:async(raw,init)=>{
      const u=new URL(raw);
      calls.push(u.hostname);
      assert.equal(init.redirect,'manual');
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{
          location:'https://greenmotors.cc/go/film?token=hidden'
        }});
      assert.equal(u.hostname,'greenmotors.cc');
      return new Response("<script>s('o','"+encoded+"')</script>",{
        headers:{'content-type':'text/html'}
      });
    }
  });
  assert.deepEqual(calls,['greenmotors.club','greenmotors.cc']);
  assert.equal(probe.state,'resolved');
  assert.equal(probe.decodedIntermediateLinks,1);
  assert.equal(probe.hubcloudHints,1);
  assert.equal(probe.videoVerified,false);
  assert.doesNotMatch(JSON.stringify(probe),/private-media|token=|\/go\/film/);
});
test('second HTTP redirect is deliberately blocked, never contacts a third-party destination',async()=>{
  let calls=0;
  const output=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/id">redirect</a>',
    fetchImpl:async()=>{
      calls++;
      return new Response(null,{status:302,headers:{
        location:calls===1?'https://greenmotors.cc/id':'https://other.example/remote'
      }});
    }
  });
  assert.equal(calls,2);
  assert.equal(output.state,'second_redirect_blocked');
  assert.equal(output.decodedIntermediateLinks,0);
});

test('inspect approved HubCloud landing HTML selector without following buttons',async()=>{
  const payload=JSON.stringify({o:b64('https://hubcloud.ist/drive/secret')});
  const encoded=b64(b64(rot13(b64(payload))));
  const urls=[];
  const r=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/go">Source</a>',
    fetchImpl:async(url,options)=>{
      const u=new URL(url);urls.push(u.hostname);
      assert.equal(options.redirect,'manual');
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/go'}});
      if(u.hostname==='greenmotors.cc')
        return new Response("<script>s('o','"+encoded+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubcloud.ist'&&u.pathname==='/file/go')
        return new Response('<a class="btn" href="https://cdn.example/secret.mp4">FSLv2</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubcloud.ist')
        return new Response('<html><a id="download" href="/file/go">Download</a>'+
          '<a class="btn" href="https://cdn.example/secret.mp4">FSL</a></html>',{
          headers:{'content-type':'text/html'}});
      throw Error('unexpected host');
    }
  });
  assert.deepEqual(urls,['greenmotors.club','greenmotors.cc',
    'hubcloud.ist','hubcloud.ist']);
  assert.equal(r.landings[0].status,'page_accessible');
  assert.equal(r.landings[0].downloadButtons,1);
  assert.equal(r.landings[0].serverButtons,1);
  assert.equal(r.landings[0].nextPageStatus,'page_accessible');
  assert.equal(r.landings[0].finalServerButtons,1);
  assert.equal(r.decodedIntermediateLinks,1);
  assert.equal(r.videoVerified,false);
  assert.equal(r.landings[0].buttonHosts[0],'cdn.example');
  assert.doesNotMatch(JSON.stringify(r),/\/drive\/|\/file\/|secret\.mp4/);
});
test('approved HubDrive buttons are recognized, but no URLs are opened',async()=>{
  const payload=JSON.stringify({o:b64('https://hubdrive.pics/s/hidden')});
  const encoded=b64(b64(rot13(b64(payload))));
  let requests=0;
  const r=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/start">Source</a>',
    fetchImpl:async(url)=>{
      requests++;
      const host=new URL(url).hostname;
      if(host==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/start'}});
      if(host==='greenmotors.cc')
        return new Response("<script>s('o','"+encoded+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(host==='hubdrive.pics')
        return new Response('<a class="btn btn-primary btn-user btn-success1 m-1" href="https://evil.example/media">View</a>',{
          headers:{'content-type':'text/html'}});
      throw Error('unexpected');
    }
  });
  assert.equal(requests,3);
  assert.equal(r.landings[0].hubdriveButtons,1);
  assert.equal(r.videoVerified,false);
});

test('HubCloud download link to another host is not requested',async()=>{
  const payload=JSON.stringify({o:b64('https://hubcloud.ist/p/hidden')});
  const encoded=b64(b64(rot13(b64(payload))));
  const hosts=[];
  const r=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/a">source</a>',
    fetchImpl:async url=>{
      const u=new URL(url);hosts.push(u.hostname);
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/a'}});
      if(u.hostname==='greenmotors.cc')
        return new Response("<script>s('o','"+encoded+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubcloud.ist')
        return new Response('<a id="download" href="https://evil.example/film">Download</a>',{
          headers:{'content-type':'text/html'}});
      throw Error('unexpected host');
    }
  });
  assert.deepEqual(hosts,['greenmotors.club','greenmotors.cc','hubcloud.ist']);
  assert.equal(r.landings[0].nextPageStatus,undefined);
  assert.equal(r.videoVerified,false);
});
