/**
 * CloudStream .cs3 repository import - metadata and integrity only.
 * This module NEVER executes extensions, extracts their code or accesses media.
 *
 * First phase supports trusted GitHub raw-hosted repo.json / plugins.json.
 * Repository allowlist is explicit; all remote plugin binaries are untrusted.
 */
import {createHash} from 'node:crypto';

const INDEX_LIMIT=1024*1024;
const ARCHIVE_LIMIT=20*1024*1024;
const MAX_PLUGINS=500;

function reject(reason){throw new Error(reason)}
export function trustedRawUrl(raw,allowedRepos=[],kind='index'){
  if(typeof raw!=='string'||raw.length>2500)reject('invalid_url');
  let u;
  try{u=new URL(raw)}catch{reject('invalid_url')}
  if(u.protocol!=='https:'||u.hostname!=='raw.githubusercontent.com' ||
     u.port||u.username||u.password||u.search||u.hash)reject('untrusted_host');
  let parts=u.pathname.split('/').filter(Boolean);
  if(parts.length<4||parts.some(p=>/%|\\|[\u0000-\u001f]/.test(p)||
     p==='.'||p==='..'))reject('invalid_repository_path');
  const owner=parts[0],name=parts[1];
  if(!/^[A-Za-z0-9-]{1,39}$/.test(owner)||
     !/^[A-Za-z0-9_.-]{1,100}$/.test(name))reject('invalid_repository_path');
  const slug=(owner+'/'+name).toLowerCase();
  if(!Array.isArray(allowedRepos)||!allowedRepos.some(x=>
    typeof x==='string'&&x.toLowerCase()===slug))reject('repository_not_allowed');
  const file=parts[parts.length-1];
  if(kind==='repo'&&file!=='repo.json')reject('not_repository_manifest');
  if(kind==='catalog'&&file!=='plugins.json')reject('not_plugin_catalog');
  if(kind==='archive'&&!/^[a-zA-Z0-9_.-]+\.cs3$/.test(file))reject('not_cs3');
  if(kind==='index'&&!['repo.json','plugins.json'].includes(file))reject('not_cloudstream_index');
  // Avoid Git ref/path confusion or traversal. raw.githubusercontent.com
  // encodes refs as path segments; GitHub resolves the actual ref.
  if(parts.length>12)reject('repository_path_too_deep');
  return {href:u.href,slug,file};
}
async function boundedGet(url,{fetchImpl=fetch,maxBytes=INDEX_LIMIT,timeoutMs=10000}={}){
  const response=await fetchImpl(url,{
    method:'GET',redirect:'error',signal:AbortSignal.timeout(timeoutMs),
    headers:{Accept:'application/json, application/octet-stream'}
  });
  if(!response.ok)reject('upstream_http_'+response.status);
  const claimed=Number(response.headers.get('content-length')||0);
  if(claimed>maxBytes)reject('response_too_large');
  const chunks=[];let count=0;
  const reader=response.body?.getReader();
  if(!reader)reject('missing_response_body');
  try{
    while(true){
      const {done,value}=await reader.read();
      if(done)break;
      count+=value.byteLength;
      if(count>maxBytes)reject('response_too_large');
      chunks.push(value);
    }
  }finally{await reader.cancel().catch(()=>{})}
  const result=new Uint8Array(count);
  let offset=0;
  for(const chunk of chunks){result.set(chunk,offset);offset+=chunk.byteLength}
  return result;
}
async function boundedJson(url,opts){
  const raw=await boundedGet(url,{...opts,maxBytes:INDEX_LIMIT});
  let parsed;
  try{parsed=JSON.parse(new TextDecoder().decode(raw))}
  catch{reject('invalid_json')}
  return parsed;
}
function validEntry(item,allowedRepos){
  if(!item||typeof item!=='object')return null;
  if(!/^[A-Za-z0-9_.-]{1,120}$/.test(item.internalName||''))return null;
  if(typeof item.name!=='string'||item.name.length>150)return null;
  const version=Number(item.version);
  if(!Number.isSafeInteger(version)||version<0)return null;
  if(item.status!==undefined&&item.status!==1)return null;
  if(typeof item.fileHash!=='string'||!/^sha256-[a-f0-9]{64}$/.test(item.fileHash))return null;
  if(!Number.isInteger(item.fileSize)||item.fileSize<100||item.fileSize>ARCHIVE_LIMIT)return null;
  let locator;
  try{locator=trustedRawUrl(item.url,allowedRepos,'archive')}
  catch{return null}
  return {
    name:item.name,internalName:item.internalName,version,
    url:locator.href,repo:locator.slug,sha256:item.fileHash.slice(7),
    size:item.fileSize,apiVersion:Number.isSafeInteger(item.apiVersion)?
      item.apiVersion:null
  };
}
export async function inspectCloudStreamRepo(indexUrl,{
  allowedRepos=[],fetchImpl=fetch,timeoutMs=10000
}={}){
  const locator=trustedRawUrl(indexUrl,allowedRepos,'index');
  let catalogs=[];
  if(locator.file==='plugins.json')catalogs=[locator.href];
  else{
    const root=await boundedJson(locator.href,{fetchImpl,timeoutMs});
    if(!root||root.manifestVersion!==1||!Array.isArray(root.pluginLists)||
       root.pluginLists.length===0||root.pluginLists.length>10)
      reject('invalid_repository_manifest');
    catalogs=[...new Set(root.pluginLists.map(x=>
      trustedRawUrl(x,allowedRepos,'catalog').href))];
  }
  const plugins=[];
  let skipped=0;
  for(const url of catalogs){
    const doc=await boundedJson(url,{fetchImpl,timeoutMs});
    if(!Array.isArray(doc)||doc.length>MAX_PLUGINS)reject('invalid_plugin_catalog');
    for(const x of doc){
      const validated=validEntry(x,allowedRepos);
      if(validated)plugins.push(validated);
      else skipped++;
    }
  }
  // Prefer newest version for an internalName and repository, but don't
  // silently override different repos sharing the same plugin name.
  const dedup=new Map();
  for(const item of plugins){
    const key=item.repo+'/'+item.internalName;
    const prior=dedup.get(key);
    if(!prior||prior.version<item.version)dedup.set(key,item);
  }
  return {repository:locator.slug,catalogCount:catalogs.length,
    accepted:[...dedup.values()],rejected:skipped};
}
export async function downloadCheckedCs3(plugin,{
  allowedRepos=[],fetchImpl=fetch,timeoutMs=15000
}={}){
  if(!plugin||typeof plugin!=='object')reject('invalid_plugin');
  const location=trustedRawUrl(plugin.url,allowedRepos,'archive');
  if(plugin.repo!==location.slug)reject('repository_mismatch');
  if(!Number.isInteger(plugin.size)||plugin.size<100||
     plugin.size>ARCHIVE_LIMIT||! /^[a-f0-9]{64}$/.test(plugin.sha256||''))
    reject('invalid_archive_metadata');
  const bytes=await boundedGet(location.href,{fetchImpl,
    maxBytes:Math.min(plugin.size,ARCHIVE_LIMIT),timeoutMs});
  if(bytes.length!==plugin.size)reject('archive_size_mismatch');
  const digest=createHash('sha256').update(bytes).digest('hex');
  if(digest!==plugin.sha256)reject('sha256_mismatch');
  if(bytes[0]!==0x50||bytes[1]!==0x4b||bytes[2]!==0x03||bytes[3]!==0x04)
    reject('not_a_zip_archive');
  return {bytes,sha256:digest,name:plugin.internalName};
}
// No default imports/no web server endpoint: this is only an offline trusted
// operator import helper. Loading any returned .cs3 requires a separate sandbox.
