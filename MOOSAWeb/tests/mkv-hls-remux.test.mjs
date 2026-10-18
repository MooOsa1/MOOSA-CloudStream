import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtemp,readFile,readdir,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {createRequire} from 'node:module';
import {remuxCli,createMkvHlsRemux,parseHlsByteRange} from '../mkv-hls-remux.mjs';

const require=createRequire(import.meta.url);
const ffmpeg=require('ffmpeg-static');

test('HLS input uses realtime pacing, blocks redirects and forwards only safe headers',()=>{
  const {args}=remuxCli('https://approved.example/film.mkv','/tmp/moosa-mux',{
    binary:ffmpeg,
    headers:{referer:'https://approved.example/',origin:'https://approved.example',
      'user-agent':'MOOSA'}
  });
  assert.equal(args.includes('-re'),true);
  assert.equal(args[args.indexOf('-max_redirects')+1],'0');
  const input=args.indexOf('-i');
  assert.equal(args[input+1],'https://approved.example/film.mkv');
  assert.match(args[args.indexOf('-headers')+1],/referer: https:\/\/approved.example\/\r\n/);
  assert.equal(args[args.indexOf('-c:v')+1],'copy');
  assert.equal(args[args.indexOf('-c:a')+1],'copy');
  assert.equal(args[args.indexOf('-hls_segment_type')+1],'fmp4');
  assert.throws(()=>remuxCli('http://approved.example/film','/tmp'),/HTTPS/);
  assert.throws(()=>remuxCli('https://user@approved.example/film','/tmp'),/HTTPS/);
});
test('Real FFmpeg remuxes generated HEVC+EAC3 MKV into native fMP4 HLS with sound',async t=>{
  const encoders=spawnSync(ffmpeg,['-hide_banner','-encoders'],{
    encoding:'utf8',timeout:6000
  });
  if(encoders.status!==0 || !/\blibx265\b/.test(encoders.stdout||'')){
    t.skip('bundled FFmpeg does not provide HEVC test encoder');return;
  }
  const dir=await mkdtemp(path.join(os.tmpdir(),'moosa-hls-smoke-'));
  try{
    const sample=path.join(dir,'test.mkv');
    const make=spawnSync(ffmpeg,[
      '-y','-nostdin','-hide_banner','-loglevel','error',
      '-f','lavfi','-i','color=c=blue:s=96x64:r=12:d=2',
      '-f','lavfi','-i','sine=frequency=440:duration=2:sample_rate=48000',
      '-threads','1','-c:v','libx265','-preset','ultrafast',
      '-x265-params','pools=1:frame-threads=1:log-level=error:keyint=12',
      '-pix_fmt','yuv420p','-c:a','eac3','-b:a','128k',
      '-shortest',sample
    ],{stdio:'ignore',timeout:14000});
    assert.equal(make.status,0,'create synthetic HEVC/EAC3 fixture');
    const command=remuxCli('https://licensed.example/fixture.mkv',dir,{binary:ffmpeg});
    const args=[...command.args],input=args.indexOf('-i');
    // This is a LOCAL trusted test fixture, never an operator-controlled URL.
    args[input+1]=sample;
    const noHttp=args.indexOf('-max_redirects');
    args.splice(noHttp,2); // FFmpeg HTTP option does not apply to local files.
    const converted=spawnSync(ffmpeg,args,{stdio:'ignore',timeout:14000});
    assert.equal(converted.status,0,'copy HEVC/EAC3 into fMP4 HLS');
    const manifest=await readFile(path.join(dir,'index.m3u8'),'utf8');
    assert.match(manifest,/#EXTM3U/);
    assert.match(manifest,/#EXT-X-MAP:URI="init\.mp4"/);
    assert.match(manifest,/seg_00000\.m4s/);
    const files=await readdir(dir);
    assert.ok(files.includes('init.mp4'));
    assert.ok(files.some(file=>/^seg_\d{5}\.m4s$/.test(file)));
    const decoded=spawnSync(ffmpeg,['-hide_banner','-i',path.join(dir,'init.mp4')],
      {encoding:'utf8',timeout:3500});
    assert.match(decoded.stderr||'',/Video:\s*hevc/i);
    assert.match(decoded.stderr||'',/Audio:\s*eac3/i);
  }finally{await rm(dir,{recursive:true,force:true});}
});

test('Safari partial HLS fMP4 init and segment requests are computed safely',()=>{
  assert.deepEqual(parseHlsByteRange('bytes=0-3',100),
    {start:0,end:3,length:4});
  assert.deepEqual(parseHlsByteRange('bytes=32-',100),
    {start:32,end:99,length:68});
  assert.deepEqual(parseHlsByteRange('bytes=0-200',100),
    {start:0,end:99,length:100});
  for(const bad of ['bytes=-2','bytes=100-','bytes=7-3',
    'bytes=0-1,5-8','bytes=99999999999999999-','bytes=01-2',
    'bytes=0-3\r\nX-Evil:1','not-a-range']){
    assert.equal(parseHlsByteRange(bad,100),null,bad);
  }
});
