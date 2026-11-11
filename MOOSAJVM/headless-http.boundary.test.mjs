import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,chmod,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHeadlessCompatBridge} from './headless-http.mjs';

/**
 * Node HTTP transport tests only; fake Java launchers are test doubles,
 * never proof of original CloudStream plugin execution or playable video.
 */
const GOOD=[
  'MOOSA_GENERIC_PROVIDER phase=instantiate result=success class=com.megix.CineStreamProvider displayName=CineStream',
  'MOOSA_GENERIC_PROVIDER phase=load result=exact title=Sintel kind=MovieLoadResponse expectedImdb=tt1727587'
];
async function launcher(lines,{wait=0}={}){
  const dir=await mkdtemp(join(tmpdir(),'moosa-jvm-boundary-'));
  const filename=join(dir,'mock-java');
  const script='#!/bin/sh\n'+(wait>0?'sleep '+wait+'\n':'')+
    lines.map(x=>"printf '%s\\n' '"+x.replaceAll("'","'\\''")+"'").join('\n')+'\n';
  await writeFile(filename,script);
  await chmod(filename,0o700);
  return {filename,cleanup:()=>rm(dir,{recursive:true,force:true})};
}
async function withBridge(java,fn,timeoutMs=1800){
  const bridge=createHeadlessCompatBridge({
    java,jvmRuntime:'fixture-runtime',libraryJar:'fixture-library',
    pluginJar:'fixture-plugin',timeoutMs
  });
  const address=await bridge.listen();
  try{await fn(address.origin,address)}
  finally{await bridge.close()}
}
async function post(origin,value){
  return fetch(origin+'/api/metadata',{method:'POST',
    headers:{'content-type':'application/json'},body:JSON.stringify(value)});
}
const LICENSED={imdb:'tt1727587',media:'movie'};

test('binds only loopback and rejects arbitrary plugins, wrong ID, wrong type and malformed input',async()=>{
  const stub=await launcher(GOOD);
  try{
    await withBridge(stub.filename,async(origin,{origin:address})=>{
      assert.ok(address.startsWith('http://127.0.0.1:'));
      const health=await fetch(origin+'/health');
      assert.equal(health.status,200);
      assert.equal((await health.json()).scope,'experimental-licensed-metadata-only');
      assert.equal((await fetch(origin+'/api/metadata')).status,404);
      for(const input of [
        {imdb:'tt1375666',media:'movie'},
        {imdb:'tt1727587',media:'tv'},
        {...LICENSED,plugin:'https://example.org/arbitrary.cs3'},
        {imdb:['tt1727587'],media:'movie'}
      ])assert.equal((await post(origin,input)).status,422);
      const malformed=await fetch(origin+'/api/metadata',{
        method:'POST',body:'{invalid'
      });
      assert.equal(malformed.status,400);
    });
  }finally{await stub.cleanup()}
});

test('passes only fixed, exact-name, exact-ID metadata; never claims video playback',async()=>{
  const good=await launcher(GOOD);
  const mismatched=await launcher([GOOD[0],
    'MOOSA_GENERIC_PROVIDER phase=load result=mismatch title=AnotherMovie expectedImdb=tt1727587']);
  try{
    await withBridge(good.filename,async origin=>{
      const r=await post(origin,LICENSED);
      assert.equal(r.status,200);
      assert.deepEqual(await r.json(),{
        provider:'CineStream',movie:{imdb:'tt1727587',title:'Sintel'},
        metadataVerified:true,playbackVerified:false,streams:[]
      });
    });
    await withBridge(mismatched.filename,async origin=>{
      const r=await post(origin,LICENSED);
      assert.equal(r.status,503);
      assert.deepEqual(await r.json(),{error:'metadata_probe_failed'});
    });
  }finally{await Promise.all([good.cleanup(),mismatched.cleanup()])}
});

test('never accepts prefix-spoofed JVM worker class, movie title, or IMDb',async()=>{
  const cases=[
    [GOOD[0].replace('CineStreamProvider','CineStreamProviderEvil'),GOOD[1]],
    [GOOD[0],GOOD[1].replace('title=Sintel ','title=Sintel2 ')],
    [GOOD[0],GOOD[1].replace('tt1727587','tt1727587123')],
    [GOOD[0],GOOD[1],GOOD[1]]
  ];
  for(const lines of cases){
    const mock=await launcher(lines);
    try{
      await withBridge(mock.filename,async origin=>{
        const response=await post(origin,LICENSED);
        assert.equal(response.status,503);
        assert.deepEqual(await response.json(),{error:'metadata_probe_failed'});
      });
    }finally{await mock.cleanup()}
  }
});

test('rejects missing Java engine, extra output and timeout without leaking details',async()=>{
  const noisy=await launcher([...GOOD,...Array(200).fill('MOOSA_EXTRA_OUTPUT '.repeat(15))]);
  const stalled=await launcher(GOOD,{wait:2});
  try{
    await withBridge(join(tmpdir(),'missing-moosa-java-runtime-234234'),async origin=>{
      const r=await post(origin,LICENSED);
      assert.equal(r.status,503);
      assert.deepEqual(await r.json(),{error:'metadata_probe_failed'});
    },350);
    await withBridge(noisy.filename,async origin=>{
      const r=await post(origin,LICENSED);
      assert.equal(r.status,503);
    },900);
    await withBridge(stalled.filename,async origin=>{
      const r=await post(origin,LICENSED);
      assert.equal(r.status,503);
    },250);
  }finally{await Promise.all([noisy.cleanup(),stalled.cleanup()])}
});

test('rejects concurrent JVM worker requests while one approved task is active',async()=>{
  const slow=await launcher(GOOD,{wait:1});
  try{
    await withBridge(slow.filename,async origin=>{
      const first=post(origin,LICENSED);
      await new Promise(resolve=>setTimeout(resolve,80));
      const second=await post(origin,LICENSED);
      assert.equal(second.status,429);
      const response=await first;
      assert.equal(response.status,200);
    },2400);
  }finally{await slow.cleanup()}
});
