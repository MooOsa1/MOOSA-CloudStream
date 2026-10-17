import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {
  checkedPublicUrl,parseCloudStreamRepo,parseCloudStreamPlugins,verifyCs3Package
} from './repository-index.mjs';

const cine='https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CineStream.cs3';
const phisher='https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/StreamPlay.cs3';
const hash='sha256-'+ 'a'.repeat(64);
const entry=(internalName,url)=>({
  internalName,name:internalName,url,version:33,status:1,fileHash:hash
});
test('CloudStream repo.json authentic manifestVersion 1 and multi plugin list',()=>{
  const actualShape={
    name:'Phisher Repo',manifestVersion:1,
    pluginLists:['https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/plugins.json']
  };
  const x=parseCloudStreamRepo(JSON.stringify(actualShape));
  assert.deepEqual(x.pluginLists,actualShape.pluginLists);
  assert.equal(x.manifestVersion,1);
  assert.equal(x.name,'Phisher Repo');
});
test('direct plugins.json authentic CineStream and StreamPlay entry schema',()=>{
  const r=parseCloudStreamPlugins([
    entry('CineStream',cine),
    entry('StreamPlay',phisher)
  ]);
  assert.equal(r.plugins.length,2);
  assert.equal(r.rejected.length,0);
  assert.equal(r.plugins[0].executionStatus,'unverified');
  assert.equal(r.plugins[1].sha256,'a'.repeat(64));
  assert.equal(r.verifiedMetadataOnly,true);
});
test('reject malformed records and confusing or duplicate plugin identities',()=>{
  const b=entry('CineStream',cine);
  const r=parseCloudStreamPlugins([
    b,b,{...b,internalName:'Other',fileHash:'not-hashed'},
    {...b,internalName:'Other',url:'https://example.com/other.zip'},
    {...b,internalName:'Other',version:-1},
    {...b,internalName:'Other',url:'http://example.com/Other.cs3'}
  ]);
  assert.equal(r.plugins.length,1);
  assert.equal(r.rejected.length,5);
  assert.deepEqual(r.rejected.map(x=>x.reason),[
    'duplicate_internal_name','invalid_or_unverified_plugin',
    'invalid_or_unverified_plugin','invalid_or_unverified_plugin',
    'invalid_or_unverified_plugin'
  ]);
});
test('never allow network-private, insecure or credentialed manifest URLs',()=>{
  for(const u of [
    'http://example.com/plugins.json','https://127.0.0.1/plugins.json',
    'https://[::1]/plugins.json','https://localhost/plugins.json',
    'https://example.local/plugins.json','https://admin:pass@example.com/plugins.json',
    'https://example.com:8080/plugins.json','https://example.com/plugins.json#fragment',
    'https://sub.internal/plugins.json','file:///tmp/plugins.json',
    'https://192.168.1.10/plugins.json'
  ])assert.equal(checkedPublicUrl(u),null,'blocked '+u);
  assert.ok(checkedPublicUrl('https://raw.githubusercontent.com/owner/repo/builds/plugins.json'));
});
test('malformed repo.json, unsafe plugin lists and oversized JSON are rejected',()=>{
  for(const bad of [
    {name:'Good',manifestVersion:2,pluginLists:[]},
    {name:'Good',manifestVersion:1,pluginLists:['https://localhost/plugins.json']},
    {name:'Good',manifestVersion:1,pluginLists:['https://example.com/search?q=x']},
    {name:'Good',manifestVersion:1,pluginLists:Array(26).fill('https://example.com/plugins.json')}
  ])assert.throws(()=>parseCloudStreamRepo(bad));
  assert.throws(()=>parseCloudStreamRepo('x'.repeat(1_000_100)),/manifest_too_large/);
  assert.throws(()=>parseCloudStreamPlugins(Array(301).fill(entry('A',cine))));
});
test('cs3 digest verifies archive header and bytes; not a trust endorsement',()=>{
  const file=Buffer.concat([Buffer.from([0x50,0x4b,0x03,0x04]),Buffer.alloc(250,3)]);
  const digest=createHash('sha256').update(file).digest('hex');
  assert.equal(verifyCs3Package(file,digest),true);
  assert.equal(verifyCs3Package(file,'b'.repeat(64)),false);
  const changed=Buffer.from(file);changed[17]=4;
  assert.equal(verifyCs3Package(changed,digest),false);
  assert.equal(verifyCs3Package(Buffer.from('hello world'),digest),false);
  assert.equal(verifyCs3Package(Buffer.concat([file,Buffer.alloc(25_000_000)]),digest),false);
});
test('parse routines never install or run plugin code',()=>{
  const r=parseCloudStreamPlugins([entry('CineStream',cine)]);
  assert.equal(Object.hasOwn(r.plugins[0],'install'),false);
  assert.ok(Object.isFrozen(r.plugins[0]));
});
