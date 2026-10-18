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

test('HubDrive follows only its same-host button and discovers destination host labels',async()=>{
  const payload=JSON.stringify({o:b64('https://hubdrive.pics/film/secret')});
  const encoded=b64(b64(rot13(b64(payload))));
  const urls=[];
  const result=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/entry">Entry</a>',
    fetchImpl:async(raw,opts)=>{
      const u=new URL(raw);urls.push(u.hostname+u.pathname);
      assert.equal(opts.redirect,'manual');
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/entry'}});
      if(u.hostname==='greenmotors.cc')
        return new Response("<script>s('o','"+encoded+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubdrive.pics'&&u.pathname==='/film/secret')
        return new Response('<a class="btn" href="/next">Continue</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubdrive.pics'&&u.pathname==='/next')
        return new Response('<a class="btn" href="https://hubcloud.ist/player">HubCloud</a>',{
          headers:{'content-type':'text/html'}});
      throw Error('unexpected');
    }
  });
  assert.deepEqual(urls,['greenmotors.club/entry','greenmotors.cc/entry',
    'hubdrive.pics/film/secret','hubdrive.pics/next']);
  assert.equal(result.landings[0].nextPageStatus,'page_accessible');
  assert.deepEqual(result.landings[0].finalButtonHosts,['hubcloud.ist']);
  assert.equal(result.videoVerified,false);
  assert.doesNotMatch(JSON.stringify(result),/\/film\/secret|\/next|\/player/);
});

test('HubCloud Inception cross-host gamerxyt landing is checked once without following media',async()=>{
  const encoded=b64(b64(rot13(b64(JSON.stringify({
    o:b64('https://hubcloud.ist/drive/verified-title')
  })))));
  const seen=[];
  const actual=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/token">Start</a>',
    fetchImpl:async(raw,options)=>{
      const u=new URL(raw);seen.push(u.hostname);
      assert.equal(options.redirect,'manual');
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/token'}});
      if(u.hostname==='greenmotors.cc')
        return new Response("<script>s('o','"+encoded+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubcloud.ist')
        return new Response('<a id="download" href="https://gamerxyt.com/source/code">Open</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='gamerxyt.com')
        return new Response('<a class="btn" href="https://cdn.example/video.mp4">FSL</a>'+
          '<a class="btn" href="https://hubdrive.pics/entry">HubDrive</a>',{
          headers:{'content-type':'text/html'}});
      throw Error('unexpected host');
    }
  });
  assert.deepEqual(seen,[
    'greenmotors.club','greenmotors.cc','hubcloud.ist','gamerxyt.com'
  ]);
  assert.equal(actual.landings[0].nextPageStatus,'cross_host_reviewed');
  assert.equal(actual.landings[0].gatewayPage.status,'page_accessible');
  assert.equal(actual.landings[0].gatewayPage.serverButtons,2);
  assert.deepEqual(actual.landings[0].gatewayPage.buttonHosts,
    ['cdn.example','hubdrive.pics']);
  assert.equal(actual.videoVerified,false);
  assert.doesNotMatch(JSON.stringify(actual),/\/source\/|\/video\.mp4|\/entry|verified-title/);
});
test('gamerxyt off-host redirect is described, never followed',async()=>{
  const encoded=b64(b64(rot13(b64(JSON.stringify({o:b64('https://hubcloud.ist/start')})))));
  const visited=[];
  const result=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/a">Start</a>',
    fetchImpl:async raw=>{
      const u=new URL(raw);visited.push(u.hostname);
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/a'}});
      if(u.hostname==='greenmotors.cc')
        return new Response("<script>s('o','"+encoded+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubcloud.ist')
        return new Response('<a id="download" href="https://gamerxyt.com/continue">Go</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='gamerxyt.com')
        return new Response(null,{status:302,headers:{location:'https://external.example/token'}});
      throw Error('unexpected host');
    }
  });
  assert.deepEqual(visited,['greenmotors.club','greenmotors.cc',
    'hubcloud.ist','gamerxyt.com']);
  assert.equal(result.landings[0].gatewayPage.status,'redirect_not_followed');
  assert.equal(result.landings[0].gatewayPage.nextHost,'external.example');
  assert.equal(result.videoVerified,false);
});

test('Inception pixel.hubcloud option proves MP4 bytes only with Range 206 and correct magic',async()=>{
  const envelope=JSON.stringify({o:b64('https://hubcloud.ist/film/inception')});
  const packed=b64(b64(rot13(b64(envelope))));
  const requested=[];
  const r=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/t">Source</a>',
    fetchImpl:async(raw,opts)=>{
      const u=new URL(raw);requested.push(u.hostname);
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/t'}});
      if(u.hostname==='greenmotors.cc')
        return new Response("<script>s('o','"+packed+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubcloud.ist')
        return new Response('<a id="download" href="https://gamerxyt.com/s/go">Go</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='gamerxyt.com')
        return new Response('<a class="btn" href="https://pixel.hubcloud.ist/v?token=private">File</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='pixel.hubcloud.ist'){
        assert.equal(opts.headers.Range,'bytes=0-4095');
        assert.equal(opts.redirect,'manual');
        const bytes=Buffer.concat([
          Buffer.from([0,0,0,24]),Buffer.from('ftypisom'),Buffer.alloc(16)
        ]);
        return new Response(bytes,{status:206,headers:{
          'content-type':'video/mp4',
          'content-range':'bytes 0-4095/12345678'
        }});
      }
      throw Error('not allowlisted');
    }
  });
  assert.deepEqual(requested,['greenmotors.club','greenmotors.cc',
    'hubcloud.ist','gamerxyt.com','pixel.hubcloud.ist']);
  assert.equal(r.landings[0].gatewayPage.pixelAudit.status,'mp4_bytes_verified');
  assert.equal(r.videoVerified,false,'byte test is not a full movie or rights proof');
  assert.doesNotMatch(JSON.stringify(r),/token=|\/v\?|\/film\/inception/);
});
test('pixel.hubcloud HTTP 429 never passes as video',async()=>{
  const envelope=JSON.stringify({o:b64('https://hubcloud.ist/f')});
  const packed=b64(b64(rot13(b64(envelope))));
  const r=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/t">Source</a>',
    fetchImpl:async(raw)=>{
      const u=new URL(raw);
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/t'}});
      if(u.hostname==='greenmotors.cc')
        return new Response("<script>s('o','"+packed+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubcloud.ist')
        return new Response('<a id="download" href="https://gamerxyt.com/s">Go</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='gamerxyt.com')
        return new Response('<a class="btn" href="https://pixel.hubcloud.ist/film">File</a>',{
          headers:{'content-type':'text/html'}});
      return new Response('Too many requests',{status:429});
    }
  });
  assert.equal(r.landings[0].gatewayPage.pixelAudit.status,'rate_limited');
  assert.equal(r.videoVerified,false);
});

test('pixel.hubcloud follows only one exact reviewed Cloudflare-host redirect for HTTP 206 MP4',async()=>{
  const envelope=JSON.stringify({o:b64('https://hubcloud.ist/film')});
  const encoded=b64(b64(rot13(b64(envelope))));
  const visited=[];
  const scan=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/film">Source</a>',
    fetchImpl:async(raw,opts)=>{
      const u=new URL(raw);visited.push(u.hostname);
      assert.equal(opts.redirect,'manual');
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/film'}});
      if(u.hostname==='greenmotors.cc')
        return new Response("<script>s('o','"+encoded+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubcloud.ist')
        return new Response('<a id="download" href="https://gamerxyt.com/p">Continue</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='gamerxyt.com')
        return new Response('<a class="btn" href="https://pixel.hubcloud.ist/p?token=hidden">File</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='pixel.hubcloud.ist')
        return new Response(null,{status:302,headers:{
          location:'https://pixel.rohitkiskk.workers.dev/range?p=secret'
        }});
      if(u.hostname==='pixel.rohitkiskk.workers.dev'){
        assert.equal(opts.headers.Range,'bytes=0-4095');
        return new Response(Buffer.concat([
          Buffer.from([0,0,0,24]),Buffer.from('ftypisom'),Buffer.alloc(20)
        ]),{status:206,headers:{
          'content-type':'video/mp4',
          'content-range':'bytes 0-4095/999999999'
        }});
      }
      throw Error('unsupported host');
    }
  });
  assert.deepEqual(visited,[
    'greenmotors.club','greenmotors.cc','hubcloud.ist',
    'gamerxyt.com','pixel.hubcloud.ist','pixel.rohitkiskk.workers.dev'
  ]);
  assert.equal(scan.landings[0].gatewayPage.pixelAudit.status,'mp4_bytes_verified');
  assert.equal(scan.videoVerified,false);
  assert.doesNotMatch(JSON.stringify(scan),/secret|hidden|\/range|\/film|token=/);
});
test('unreviewed Cloudflare redirect remains blocked and no third-party requests are made',async()=>{
  const envelope=JSON.stringify({o:b64('https://hubcloud.ist/film')});
  const encoded=b64(b64(rot13(b64(envelope))));
  const visited=[];
  const result=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/film">Source</a>',
    fetchImpl:async raw=>{
      const u=new URL(raw);visited.push(u.hostname);
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/film'}});
      if(u.hostname==='greenmotors.cc')
        return new Response("<script>s('o','"+encoded+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubcloud.ist')
        return new Response('<a id="download" href="https://gamerxyt.com/p">Continue</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='gamerxyt.com')
        return new Response('<a class="btn" href="https://pixel.hubcloud.ist/p">File</a>',{
          headers:{'content-type':'text/html'}});
      return new Response(null,{status:302,headers:{location:'https://other.workers.dev/p'}});
    }
  });
  assert.deepEqual(visited,['greenmotors.club','greenmotors.cc',
    'hubcloud.ist','gamerxyt.com','pixel.hubcloud.ist']);
  assert.equal(result.landings[0].gatewayPage.pixelAudit.status,'redirect_not_followed');
  assert.equal(result.videoVerified,false);
});

test('second reviewed worker option accepts MP4 bytes but never publishes the source URL',async()=>{
  const encoded=b64(b64(rot13(b64(JSON.stringify({o:b64('https://hubcloud.ist/film')})))));
  const visited=[];
  const r=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/b">Movie</a>',
    fetchImpl:async (raw,init)=>{
      const u=new URL(raw);visited.push(u.hostname);
      if(u.hostname==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/b'}});
      if(u.hostname==='greenmotors.cc')
        return new Response("<script>s('o','"+encoded+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='hubcloud.ist')
        return new Response('<a id="download" href="https://gamerxyt.com/entry">Download</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='gamerxyt.com')
        return new Response('<a class="btn" href="https://little-hat-b47b.legalo74863004.workers.dev/path?secret=1">FSL</a>',{
          headers:{'content-type':'text/html'}});
      if(u.hostname==='little-hat-b47b.legalo74863004.workers.dev'){
        assert.equal(init.headers.Range,'bytes=0-4095');
        return new Response(Buffer.concat([
          Buffer.from([0,0,0,24]),Buffer.from('ftypisom'),Buffer.alloc(12)
        ]),{status:206,headers:{
          'content-type':'application/octet-stream',
          'content-range':'bytes 0-4095/123456'
        }});
      }
      throw Error('unexpected host');
    }
  });
  assert.deepEqual(visited,['greenmotors.club','greenmotors.cc',
    'hubcloud.ist','gamerxyt.com',
    'little-hat-b47b.legalo74863004.workers.dev']);
  assert.equal(r.landings[0].gatewayPage.workerAudit.status,'mp4_bytes_verified');
  assert.equal(r.videoVerified,false);
  assert.doesNotMatch(JSON.stringify(r),/secret=1|\/entry|\/path|\/film/);
});

test('2025 Sinners alternative R2 and GPDL options are probed once each with Range',async()=>{
  const packed=b64(b64(rot13(b64(JSON.stringify({
    o:b64('https://hubcloud.ist/sinners')
  })))));
  const seen=[];
  const res=await probeStreamPlayRedirects({
    postHtml:'<a href="https://greenmotors.club/sinners">Sinners</a>',
    fetchImpl:async (url,opts)=>{
      const host=new URL(url).hostname;
      seen.push(host);
      if(host==='greenmotors.club')
        return new Response(null,{status:302,headers:{location:'https://greenmotors.cc/sinners'}});
      if(host==='greenmotors.cc')
        return new Response("<script>s('o','"+packed+"')</script>",{
          headers:{'content-type':'text/html'}});
      if(host==='hubcloud.ist')
        return new Response('<a id="download" href="https://gamerxyt.com/sinners">Options</a>',{
          headers:{'content-type':'text/html'}});
      if(host==='gamerxyt.com')
        return new Response('<a class="btn" href="https://gpdl.hubcloud.ist/video?token=secret">GPDL</a>'+
          '<a class="btn" href="https://297de52426dc9b4d861466cc51253577.r2.cloudflarestorage.com/film?token=hidden">R2</a>',{
          headers:{'content-type':'text/html'}});
      assert.equal(opts.headers.Range,host.endsWith('.r2.cloudflarestorage.com')?
        'bytes=0-65535':'bytes=0-4095');
      if(host==='gpdl.hubcloud.ist')
        return new Response(Buffer.concat([
          Buffer.from([0x1a,0x45,0xdf,0xa3]),Buffer.from('matroska'),Buffer.alloc(20)
        ]),{status:206,headers:{
          'content-type':'video/x-matroska',
          'content-range':'bytes 0-4095/100000'
        }});
      if(host.endsWith('.r2.cloudflarestorage.com'))
        return new Response(Buffer.concat([
          Buffer.from([0,0,0,24]),Buffer.from('ftypisom'),Buffer.alloc(20)
        ]),{status:206,headers:{
          'content-type':'video/mp4','content-range':'bytes 0-4095/200000'
        }});
      throw Error('unexpected source host');
    }
  });
  assert.equal(res.landings[0].gatewayPage.gpdlAudit.status,'ebml_bytes_verified');
  assert.equal(res.landings[0].gatewayPage.gpdlAudit.container,'matroska');
  assert.equal(res.landings[0].gatewayPage.r2Audit.status,'mp4_bytes_verified');
  assert.equal(res.landings[0].gatewayPage.r2Audit.container,'mp4');
  assert.equal(res.videoVerified,false);
  assert.deepEqual(seen.slice(-2).sort(),[
    '297de52426dc9b4d861466cc51253577.r2.cloudflarestorage.com',
    'gpdl.hubcloud.ist'
  ]);
  assert.doesNotMatch(JSON.stringify(res),/token=|secret|hidden|\/film|\/sinners/);
});

test('Modern movie alternate route selection examines only two later unique links',async()=>{
  const page=Array.from({length:8},(_,i)=>
    '<a href="https://greenmotors.club/source/'+i+'">S'+i+'</a>').join('');
  const paths=[];
  const r=await probeStreamPlayRedirects({
    postHtml:page,startAt:4,
    fetchImpl:async(url,opts)=>{
      paths.push(new URL(url).pathname);
      assert.equal(opts.redirect,'manual');
      return new Response('not here',{status:403});
    }
  });
  assert.deepEqual(paths,['/source/4','/source/5']);
  assert.equal(r.inspected,2);
  assert.equal(r.decodedIntermediateLinks,0);
  assert.equal(r.videoVerified,false);
  const bad=await probeStreamPlayRedirects({postHtml:page,startAt:-1});
  assert.equal(bad.state,'invalid_start_index');
});
