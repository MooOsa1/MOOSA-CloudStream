import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {inspectCloudStreamRepo,downloadCheckedCs3,trustedRawUrl} from '../cloudstream-repository.mjs';

const root='https://raw.githubusercontent.com/example/project/builds/';
const archive=Buffer.alloc(120,0);
archive.set([0x50,0x4b,0x03,0x04],0);
const sha256=createHash('sha256').update(archive).digest('hex');
const valid={
  name:'Demo Plugin',internalName:'DemoPlugin',version:3,status:1,
  fileHash:'sha256-'+sha256,fileSize:archive.length,apiVersion:1,
  url:root+'DemoPlugin.cs3'
};
function mock(records){
  const called=[];
  return {called,fetchImpl:async (url,opts)=>{
    called.push({url,opts});
    const v=records[url];
    if(v===undefined)return new Response('Not found',{status:404});
    const body=typeof v==='string'?v:Buffer.isBuffer(v)?v:JSON.stringify(v);
    return new Response(body,{status:200,headers:{'Content-Length':String(Buffer.byteLength(body))}});
  }};
}
test('real CloudStream repository manifest -> plugins.json -> verified original .cs3 workflow',async()=>{
  const m=mock({
    [root+'repo.json']:{name:'Demo',manifestVersion:1,pluginLists:[root+'plugins.json']},
    [root+'plugins.json']:[valid,{...valid,version:2}, {bad:'bad'}],
    [root+'DemoPlugin.cs3']:archive
  });
  const index=await inspectCloudStreamRepo(root+'repo.json',{
    allowedRepos:['EXAMPLE/PROJECT'],fetchImpl:m.fetchImpl
  });
  assert.equal(index.repository,'example/project');
  assert.equal(index.catalogCount,1);
  assert.equal(index.accepted.length,1);
  assert.equal(index.accepted[0].version,3);
  assert.equal(index.rejected,1);
  const loaded=await downloadCheckedCs3(index.accepted[0],{
    allowedRepos:['example/project'],fetchImpl:m.fetchImpl
  });
  assert.equal(loaded.sha256,sha256);
  assert.equal(loaded.bytes.byteLength,120);
  assert.equal(m.called.length,3);
  assert.ok(m.called.every(x=>x.opts.redirect==='error'));
});
test('direct plugins.json supported even when repo.json is absent',async()=>{
  const m=mock({[root+'plugins.json']:[valid]});
  const value=await inspectCloudStreamRepo(root+'plugins.json',{
    allowedRepos:['example/project'],fetchImpl:m.fetchImpl
  });
  assert.equal(value.accepted[0].internalName,'DemoPlugin');
  assert.equal(m.called.length,1);
});
test('reject arbitrary GitHub owners, redirects, credentials, path traversal and HTTP',()=>{
  const urls=[
    'https://raw.githubusercontent.com/evil/project/builds/plugins.json',
    'http://raw.githubusercontent.com/example/project/builds/plugins.json',
    'https://user:pass@raw.githubusercontent.com/example/project/builds/plugins.json',
    'https://raw.githubusercontent.com/example/project/builds/../plugins.json',
    'https://raw.githubusercontent.com/example/project/builds/%2e%2e/plugins.json',
    'https://raw.githubusercontent.com/example/project/builds/plugins.json?x=1',
    'https://127.0.0.1/example/project/builds/plugins.json',
    'https://raw.githubusercontent.com.evil.test/example/project/builds/plugins.json'
  ];
  for(const url of urls)
    assert.throws(()=>trustedRawUrl(url,['example/project'],'index'),Error,url);
});
test('reject untrusted cross-repo plugin list before contacting extra repository',async()=>{
  const bad='https://raw.githubusercontent.com/untrusted/repo/builds/plugins.json';
  const m=mock({[root+'repo.json']:{manifestVersion:1,pluginLists:[bad]}});
  await assert.rejects(inspectCloudStreamRepo(root+'repo.json',{
    allowedRepos:['example/project'],fetchImpl:m.fetchImpl
  }),/repository_not_allowed/);
  assert.equal(m.called.length,1);
});
test('reject tampering, invalid zip magic and content-length mismatch',async()=>{
  const m=mock({[root+'DemoPlugin.cs3']:archive});
  const item={name:'DemoPlugin',internalName:'DemoPlugin',repo:'example/project',
    url:root+'DemoPlugin.cs3',size:120,sha256:'f'.repeat(64)};
  await assert.rejects(downloadCheckedCs3(item,{
    allowedRepos:['example/project'],fetchImpl:m.fetchImpl
  }),/sha256_mismatch/);
  const wrong={...item,size:121,sha256};
  await assert.rejects(downloadCheckedCs3(wrong,{
    allowedRepos:['example/project'],fetchImpl:m.fetchImpl
  }),/archive_size_mismatch/);
  const fake=Buffer.alloc(120,0);
  const changed=mock({[root+'DemoPlugin.cs3']:fake});
  await assert.rejects(downloadCheckedCs3({...item,sha256:createHash('sha256')
    .update(fake).digest('hex')},{
    allowedRepos:['example/project'],fetchImpl:changed.fetchImpl
  }),/not_a_zip_archive/);
});
test('invalid or excessive catalogs fail closed',async()=>{
  const m=mock({[root+'plugins.json']:[
    {...valid,url:'https://evil.example/video.mp4'},
    {...valid,fileHash:'MD5-foo'},
    {...valid,fileSize:31*1024*1024},
    {...valid,status:0}
  ]});
  const result=await inspectCloudStreamRepo(root+'plugins.json',{
    allowedRepos:['example/project'],fetchImpl:m.fetchImpl
  });
  assert.equal(result.accepted.length,0);
  assert.equal(result.rejected,4);
});
