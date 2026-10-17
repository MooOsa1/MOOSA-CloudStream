import test from 'node:test';
import assert from 'node:assert/strict';
import {isDirectVideoUrl} from '../public/direct-media-policy.js';
import {createServer,AD_FREE_WEB_CSP} from '../server.mjs';

test('direct native video URL policy rejects ad player pages, script schemes and local web targets',()=>{
  for(const good of [
    'https://cdn.example.org/movie.mp4',
    'https://cdn.example.org/movie.m3u8?token=abc123',
    'https://upload.wikimedia.org/wikipedia/commons/a/ab/test.webm',
    'https://cdn.example.org/show.mov?season=1'
  ])assert.equal(isDirectVideoUrl(good),true,good);
  for(const bad of [
    'https://vidsrc.sh/embed/movie/tt1375666',
    'https://sample.example.org/embed.html',
    'https://sample.example.org/player?url=https://cdn.example.org/a.mp4',
    'https://example.org/adframe.js',
    'https://example.org/iframe.mp4#advertiser',
    'https://user:password@example.org/movie.mp4',
    'http://cdn.example.org/movie.mp4',
    'javascript:alert(1)',
    'data:video/mp4;base64,xxxx',
    'https://127.0.0.1/movie.mp4',
    'https://192.168.1.1/movie.mp4',
    'https://169.254.1.1/movie.mp4',
    'https://localhost/movie.mp4',
    'https://media.local/movie.mp4',
    'https://[::1]/movie.mp4',
    'https://video.example.org:8443/movie.mp4',
    null,''
  ])assert.equal(isDirectVideoUrl(bad),false,String(bad));
});

test('live HTML response enforces zero third-party ad iframe and object execution',async()=>{
  const app=createServer();
  await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));
  try {
    const base='http://127.0.0.1:'+app.address().port;
    const page=await fetch(base+'/');
    assert.equal(page.status,200);
    const policy=page.headers.get('content-security-policy');
    assert.equal(policy,AD_FREE_WEB_CSP);
    for(const clause of [
      "frame-src 'none'",
      "object-src 'none'",
      "frame-ancestors 'none'",
      "default-src 'self'",
      "form-action 'self'",
      "script-src 'self' https://cdn.jsdelivr.net",
      "media-src 'self' blob: https:",
      "worker-src 'self' blob:"
    ])assert.ok(policy.includes(clause),'missing '+clause);
    const html=await page.text();
    assert.match(html,/<video\s+id="video"/);
    assert.doesNotMatch(html,/<iframe\b/i);
    const asset=await fetch(base+'/player.js');
    assert.equal(asset.status,200);
    assert.match(await asset.text(),/isDirectVideoUrl/);
    assert.match(asset.headers.get('content-type'),/javascript/);
    // Explicitly keep assets and API usable for our existing HLS/metadata
    // path. Frame blocking must not stop the HTML5 player or TMDB images.
    assert.ok(policy.includes('connect-src \'self\' https:'));
    assert.ok(policy.includes('img-src \'self\' data: https:'));
  }finally{
    await new Promise(resolve=>app.close(resolve));
  }
});
