/**
 * Read-only index parser for real CloudStream repository manifests.
 *
 * Accepted formats:
 *  - repo.json {manifestVersion:1,pluginLists:["https://.../plugins.json"]}
 *  - plugins.json [{internalName,url,fileHash,version,...}]
 *
 * No outgoing requests, file execution or automatic installation here.
 * Validated candidate URLs remain untrusted until a separately configured
 * operator approval, download size check, verified digest and sandbox stage.
 */
import { createHash } from 'node:crypto';
import { isIP } from 'node:net';

const MAX_ENTRIES=300;
const MAX_PLUGIN_LISTS=25;
const MAX_JSON_BYTES=1_000_000;
const MAX_PACKAGE_BYTES=25_000_000;
const HASH=/^sha256-([a-f0-9]{64})$/i;
const NAME=/^[\p{L}\p{N}][\p{L}\p{N} ._+()-]{0,100}$/u;
const INTERNAL=/^[A-Za-z][A-Za-z0-9_-]{0,99}$/;

export function checkedPublicUrl(raw){
  if(typeof raw!=='string'||raw.length>1800)return null;
  let url;
  try{url=new URL(raw);}catch{return null;}
  if(url.protocol!=='https:'||url.username||url.password||
    url.port||url.hash||isIP(url.hostname)!==0||
    /(^|\.)(?:localhost|local|internal|test)$/.test(url.hostname)||
    !/^[a-z0-9.-]+$/i.test(url.hostname)||
    !url.hostname.includes('.')||url.hostname.includes('..'))return null;
  return url.href;
}
function documentObject(input){
  let obj=input;
  if(typeof input==='string'){
    if(Buffer.byteLength(input,'utf8')>MAX_JSON_BYTES)throw new Error('manifest_too_large');
    try{obj=JSON.parse(input)}catch{throw new Error('invalid_manifest_json');}
  }
  if(!obj||typeof obj!=='object')throw new Error('invalid_manifest_type');
  return obj;
}
function isManifestLocation(url,suffix){
  try{return new URL(url).pathname.toLowerCase().endsWith(suffix);}catch{return false;}
}

export function parseCloudStreamRepo(input){
  const doc=documentObject(input);
  if(Array.isArray(doc)||doc.manifestVersion!==1||
    typeof doc.name!=='string'||!NAME.test(doc.name.trim())||
    !Array.isArray(doc.pluginLists)||doc.pluginLists.length>MAX_PLUGIN_LISTS)
    throw new Error('invalid_repo_manifest');
  const lists=[...new Set(doc.pluginLists.map(checkedPublicUrl))];
  if(lists.includes(null)||lists.some(x=>!isManifestLocation(x,'/plugins.json')))
    throw new Error('unsafe_plugin_list');
  return {name:doc.name.trim(),pluginLists:lists,manifestVersion:1};
}
export function parseCloudStreamPlugins(input){
  const doc=documentObject(input);
  if(!Array.isArray(doc)||doc.length>MAX_ENTRIES)throw new Error('invalid_plugins_manifest');
  const seen=new Set();
  const plugins=[];
  const rejected=[];
  for(let i=0;i<doc.length;i++){
    const x=doc[i];
    if(!x||typeof x!=='object'||Array.isArray(x)){
      rejected.push({index:i,reason:'invalid_entry'});continue;
    }
    const name=typeof x.name==='string'?x.name.trim():'';
    const internalName=x.internalName;
    const url=checkedPublicUrl(x.url);
    const hash=typeof x.fileHash==='string'?HASH.exec(x.fileHash):null;
    const version=x.version;
    if(!INTERNAL.test(internalName||'')||!NAME.test(name)||!url||
      !isManifestLocation(url,'.cs3')||!hash||
      !Number.isSafeInteger(version)||version<0||version>2147483647){
      rejected.push({index:i,reason:'invalid_or_unverified_plugin'});continue;
    }
    if(seen.has(internalName)){
      rejected.push({index:i,reason:'duplicate_internal_name'});continue;
    }
    seen.add(internalName);
    plugins.push(Object.freeze({
      internalName,name,version,url,sha256:hash[1].toLowerCase(),
      status:Number.isSafeInteger(x.status)?x.status:null,
      // These are metadata records, NOT runnable installed plugins.
      executionStatus:'unverified'
    }));
  }
  return {plugins,rejected,verifiedMetadataOnly:true};
}
export function verifyCs3Package(bytes,sha256){
  if(!(bytes instanceof Uint8Array)||bytes.byteLength<10||
    bytes.byteLength>MAX_PACKAGE_BYTES||
    typeof sha256!=='string'||!/^[a-f0-9]{64}$/i.test(sha256))return false;
  if(bytes[0]!==0x50||bytes[1]!==0x4b||bytes[2]!==0x03||bytes[3]!==0x04)
    return false;
  const digest=createHash('sha256').update(bytes).digest('hex');
  // Digest validates integrity only, not safety, trust or copyright/license.
  return digest===sha256.toLowerCase();
}
