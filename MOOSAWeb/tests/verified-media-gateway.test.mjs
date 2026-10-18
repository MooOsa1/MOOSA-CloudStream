import test from 'node:test';
import assert from 'node:assert/strict';
import {createMediaGateway} from '../verified-media-gateway.mjs';
import {createServer} from '../server.mjs';

const site='https://moosa-web.onrender.com';
const host='licensed-media.example';
function gateway(opts={}){
  return createMediaGateway({
    origin:site,allowedHosts:[host],...opts
  });
}
async function withServer(g,body){
  const srv=createServer({mediaGateway:g});
  await new Promise((resolve,reject)=>{
    srv.once('error',reject);
    srv.listen(0,'127.0.0.1',resolve);
  });
  try{return await body('http://127.0.0.1:'+srv.address().port);}
  finally{await new Promise(resolve=>srv.close(resolve));}
}
function issue(g,format='video/mp4',url='https://'+host+'/film.mp4'){
  return g.issue({url,format,rightsApproved:true,sourceVerified:true});
}
test('server only issues capabilities for operator-verified HTTPS allowlisted hosts',()=>{
  const g=gateway();
  assert.throws(()=>g.issue({url:'https://'+host+'/film.mp4',
    format:'mp4',rightsApproved:true}),/verification/);
  assert.throws(()=>g.issue({url:'https://evil.test/file.mp4',format:'mp4',
    rightsApproved:true,sourceVerified:true}),/allowlist/);
  assert.throws(()=>g.issue({url:'http://'+host+'/film.mp4',format:'mp4',
    rightsApproved:true,sourceVerified:true}),/allowlist/);
  assert.throws(()=>g.issue({url:'https://user:pass@'+host+'/film.mp4',
    format:'mp4',rightsApproved:true,sourceVerified:true}),/allowlist/);
  assert.throws(()=>g.issue({url:'https://'+host+'/film.mkv',format:'mkv',
    rightsApproved:false,sourceVerified:true}),/verification/);
  const mkv=g.issue({url:'https://'+host+'/film.mkv',format:'mkv',
    rightsApproved:true,sourceVerified:true});
  assert.match(mkv.url,/^https:\/\/moosa-web\.onrender\.com\/api\/media\/[A-Za-z0-9_-]{40,}\/index\.m3u8$/);
  assert.equal(mkv.format,'m3u8');
  const stream=issue(g);
  assert.match(stream.url,/^https:\/\/moosa-web\.onrender\.com\/api\/media\/[a-zA-Z0-9_-]{40,}$/);
  assert.equal(stream.format,'video/mp4');
  assert.equal(stream.gatewayIssued,true);
});
test('expired capability and missing/incorrect route never fetch upstream',async()=>{
  let now=100;
  let upstreamCalls=0;
  const g=gateway({clock:()=>now,ttlMs:500,fetchImpl:()=>{
    upstreamCalls++;throw Error('should not fetch');
  }});
  const src=issue(g);
  await withServer(g,async base=>{
    const token=new URL(src.url).pathname;
    assert.equal((await fetch(base+token+'?url=https://evil.example/video')).status,400);
    assert.equal((await fetch(base+'/api/media/'+('x'.repeat(43)))).status,404);
    const put=await fetch(base+token,{method:'POST'});
    assert.equal(put.status,405);
    now=700;
    assert.equal((await fetch(base+token)).status,404);
    assert.equal(upstreamCalls,0);
  });
});
test('gateway rejects HTTP 426 and HTML 200 instead of exposing bad media',async()=>{
  const cases=[
    ()=>new Response('upgrade required',{status:426,headers:{'content-type':'text/plain'}}),
    ()=>new Response('<html>blocked</html>',{status:200,headers:{'content-type':'text/html'}})
  ];
  for(const make of cases){
    const g=gateway({fetchImpl:async()=>make()});
    await withServer(g,async base=>{
      const src=issue(g);
      const res=await fetch(base+new URL(src.url).pathname);
      assert.equal(res.status,502);
      assert.doesNotMatch(await res.text(),/<html|upgrade required/i);
    });
  }
});
test('real Node route forwards video byte ranges and sanitizes source headers',async()=>{
  const bytes=Buffer.from('00000020ftypisomvideo-filmbinary-data');
  const requests=[];
  const g=gateway({fetchImpl:async(url,opts)=>{
    requests.push({url,opts});
    const range=opts.headers.range;
    if(range){
      assert.equal(range,'bytes=0-7');
      return new Response(bytes.subarray(0,8),{status:206,headers:{
        'content-type':'video/mp4','content-range':'bytes 0-7/'+bytes.length,
        'content-length':'8','accept-ranges':'bytes'
      }});
    }
    return new Response(bytes,{status:200,headers:{
      'content-type':'video/mp4','content-length':String(bytes.length)
    }});
  }});
  const cap=g.issue({url:'https://'+host+'/film.mp4',format:'mp4',
    rightsApproved:true,sourceVerified:true,headers:{
      Referer:'https://licensed-media.example/',Cookie:'sensitive',
      Authorization:'Bearer secret','User-Agent':'MOOSA player'
    }});
  await withServer(g,async base=>{
    const url=base+new URL(cap.url).pathname;
    const res=await fetch(url,{headers:{Range:'bytes=0-7'}});
    assert.equal(res.status,206);
    assert.equal(res.headers.get('content-range'),'bytes 0-7/'+bytes.length);
    assert.equal((await res.arrayBuffer()).byteLength,8);
    assert.equal(res.headers.get('content-type'),'video/mp4');
    const head=await fetch(url,{method:'HEAD'});
    assert.equal(head.status,200);
    assert.equal((await head.arrayBuffer()).byteLength,0);
  });
  assert.equal(requests.length,2);
  assert.equal(requests[0].opts.headers.referer,'https://licensed-media.example/');
  assert.equal(requests[0].opts.headers.cookie,undefined);
  assert.equal(requests[0].opts.headers.authorization,undefined);
});
test('actual Node gateway rewrites HLS child playlist, segment and MAP URLs to opaque tokens',async()=>{
  const seen=[];
  const master='#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-STREAM-INF:BANDWIDTH=1500000\nlevel.m3u8\n';
  const inner='#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MAP:URI="init.mp4"\n#EXTINF:4,\npart.ts\n#EXT-X-ENDLIST\n';
  const g=gateway({fetchImpl:async(url,opts)=>{
    seen.push({url,opts});
    const pathname=new URL(url).pathname;
    if(pathname.endsWith('.m3u8')){
      return new Response(pathname.endsWith('/master.m3u8')?master:inner,{
        status:200,headers:{'content-type':'application/vnd.apple.mpegurl'}
      });
    }
    return new Response(Buffer.from('00000016ftypxxxx'),{
      status:200,headers:{'content-type':pathname.endsWith('.mp4')?'video/mp4':'video/mp2t'}
    });
  }});
  const token=g.issue({url:'https://'+host+'/master.m3u8',
    format:'m3u8',rightsApproved:true,sourceVerified:true});
  await withServer(g,async base=>{
    const first=await fetch(base+new URL(token.url).pathname);
    assert.equal(first.status,200);
    const raw=await first.text();
    assert.match(raw,/^#EXTM3U/);
    assert.doesNotMatch(raw,/licensed-media\.example|level\.m3u8/);
    const child=new URL(raw.trim().split('\n').at(-1)).pathname;
    assert.match(child,/^\/api\/media\/[A-Za-z0-9_-]+$/);
    const second=await fetch(base+child);
    assert.equal(second.status,200);
    const playlist=await second.text();
    assert.match(playlist,/#EXT-X-MAP:URI="https:\/\/moosa-web\.onrender\.com\/api\/media\//);
    assert.match(playlist,/#EXTINF:4,\nhttps:\/\/moosa-web\.onrender\.com\/api\/media\//);
    assert.doesNotMatch(playlist,/part\.ts|init\.mp4/);
    const mapToken=playlist.match(/#EXT-X-MAP:URI="([^"]+)"/)[1];
    const mapped=await fetch(base+new URL(mapToken).pathname);
    assert.equal(mapped.status,200);
    assert.equal(mapped.headers.get('content-type'),'video/mp4');
  });
  assert.ok(seen.every(x=>new URL(x.url).hostname===host));
});
test('encrypted HLS playlists cannot be rewritten through this gateway',async()=>{
  const manifest='#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\nsegment.ts\n';
  const g=gateway({fetchImpl:async()=>new Response(manifest,{status:200,
    headers:{'content-type':'application/vnd.apple.mpegurl'}})});
  const c=issue(g,'hls','https://'+host+'/master.m3u8');
  await withServer(g,async base=>{
    const response=await fetch(base+new URL(c.url).pathname);
    assert.equal(response.status,502);
  });
});

test('preflight does not issue Play token for 426 or HTTP 200 HTML instead of video',async()=>{
  const approval={url:'https://'+host+'/film.mp4',format:'mp4',
    rightsApproved:true,sourceVerified:true};
  for(const status of [426,403,400]){
    const g=gateway({fetchImpl:async()=>new Response('blocked',{
      status,headers:{'content-type':'text/plain'}
    })});
    const result=await g.issueIfBytesVerified(approval);
    assert.equal(result.source,null);
    assert.equal(result.reason,'upstream_http_'+status);
    assert.equal(g.activeTokenCount(),0);
  }
  const g=gateway({fetchImpl:async()=>new Response('<html>ftyp</html>',{
    status:200,headers:{'content-type':'text/html'}
  })});
  const result=await g.issueIfBytesVerified(approval);
  assert.deepEqual(result,{source:null,reason:'upstream_returned_nonvideo_mime'});
  assert.equal(g.activeTokenCount(),0);
});

test('verified MP4 magic bytes issue a short-lived capability, fake MIME alone does not',async()=>{
  const approval={url:'https://'+host+'/film.mp4',format:'mp4',
    rightsApproved:true,sourceVerified:true};
  const good=Buffer.from([0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,0]);
  const g=gateway({fetchImpl:async(url,opts)=>{
    assert.equal(opts.headers.range,'bytes=0-4095');
    assert.equal(opts.redirect,'error');
    return new Response(good,{status:206,
      headers:{'content-type':'video/mp4'}});
  }});
  const result=await g.issueIfBytesVerified(approval);
  assert.equal(result.reason,'media_bytes_verified');
  assert.equal(result.source.gatewayIssued,true);
  assert.equal(g.activeTokenCount(),1);
  const fake=gateway({fetchImpl:async()=>new Response('not actually a video',{
    status:200,headers:{'content-type':'video/mp4'}
  })});
  assert.deepEqual(await fake.issueIfBytesVerified(approval),
    {source:null,reason:'wrong_media_bytes'});
});

test('HLS preflight follows allowlisted playlist to real MPEG TS byte signatures',async()=>{
  const ts=Buffer.alloc(188*3);
  ts[0]=0x47;ts[188]=0x47;ts[376]=0x47;
  const visited=[];
  const g=gateway({fetchImpl:async(raw,opts)=>{
    visited.push(new URL(raw).pathname);
    if(raw.endsWith('master.m3u8'))return new Response(
      '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=600000\nlow.m3u8\n',{
      headers:{'content-type':'application/vnd.apple.mpegurl'}
    });
    if(raw.endsWith('low.m3u8'))return new Response(
      '#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:3,\nchunk.ts\n',{
      headers:{'content-type':'application/vnd.apple.mpegurl'}
    });
    if(raw.endsWith('chunk.ts')){
      assert.equal(opts.headers.range,'bytes=0-4095');
      return new Response(ts,{status:206,
        headers:{'content-type':'video/mp2t'}});
    }
    return new Response('missing',{status:404});
  }});
  const proof=await g.issueIfBytesVerified({
    url:'https://'+host+'/master.m3u8',format:'hls',
    rightsApproved:true,sourceVerified:true
  });
  assert.equal(proof.reason,'media_bytes_verified');
  assert.equal(proof.source.format,'m3u8');
  assert.deepEqual(visited,['/master.m3u8','/low.m3u8','/chunk.ts']);
});

test('preflight rejects links to other hosts, fake TS bytes and encrypted HLS',async()=>{
  const license={rightsApproved:true,sourceVerified:true};
  let calls=0;
  const g=gateway({fetchImpl:async()=>{
    calls++;return new Response('#EXTM3U\n#EXTINF:3,\nhttps://evil.test/video.ts\n',{
      headers:{'content-type':'application/vnd.apple.mpegurl'}
    });
  }});
  assert.deepEqual(await g.issueIfBytesVerified({
    ...license,url:'https://evil.test/master.m3u8',format:'hls'
  }),{source:null,reason:'not_on_operator_allowlist'});
  assert.equal(calls,0);
  assert.deepEqual(await g.issueIfBytesVerified({
    ...license,url:'https://'+host+'/master.m3u8',format:'hls'
  }),{source:null,reason:'hls_subresource_not_allowlisted'});
  const encrypted=gateway({fetchImpl:async()=>new Response(
    '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key"\n#EXTINF:3,\nchunk.ts\n',
    {headers:{'content-type':'application/vnd.apple.mpegurl'}}
  )});
  assert.deepEqual(await encrypted.issueIfBytesVerified({
    ...license,url:'https://'+host+'/master.m3u8',format:'hls'
  }),{source:null,reason:'encrypted_hls_unsupported'});
  assert.equal(encrypted.activeTokenCount(),0);
});
