/**
 * CloudStream repository discovery and OPTIONAL remote runtime adapter.
 *
 * Repo manifests list Android .cs3 packages. Listing an extension DOES NOT
 * install it or make its streams playable in Node. The optional runtime must
 * be a separately deployed, permissioned Android-compatible service.
 *
 * The web process never downloads/executes untrusted .cs3 artifacts.
 */
const REPOS = Object.freeze([
  Object.freeze({
    id: 'cinestream', label: 'CineStream', plugin: 'CineStream',
    repository: 'https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json',
    expectedPlugins: 'https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/plugins.json'
  }),
  Object.freeze({
    id: 'streamplay', label: 'StreamPlay', plugin: 'StreamPlay',
    repository: 'https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/repo.json',
    expectedPlugins: 'https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/plugins.json'
  })
]);
const cache = new Map();
const TTL = 10 * 60 * 1000;
const MAX_JSON = 512 * 1024;

function allowedUrl(value) {
  if(typeof value!=='string'||value.length>2500)return false;
  try{
    const u=new URL(value);
    return u.protocol==='https:'&&!u.username&&!u.password
      &&u.hostname==='raw.githubusercontent.com';
  }catch{return false;}
}
async function readJson(url, fetchImpl, timeout=5500) {
  if(!allowedUrl(url)) throw Error('Unsafe repository URL');
  const r=await fetchImpl(url, {
    headers:{Accept:'application/json'},
    redirect:'error',
    signal:AbortSignal.timeout(timeout)
  });
  if(!r.ok)throw Error('Repository HTTP '+r.status);
  if(Number(r.headers.get('content-length')||0)>MAX_JSON)throw Error('Manifest too large');
  const data=await r.text();
  if(data.length>MAX_JSON)throw Error('Manifest too large');
  return JSON.parse(data);
}
function isPluginURL(url,id) {
  if(!allowedUrl(url))return false;
  const u=new URL(url);
  if(!u.pathname.endsWith('.cs3'))return false;
  if(id==='cinestream')return u.pathname.startsWith('/SaurabhKaperwan/CSX/builds/');
  if(id==='streamplay')return u.pathname.startsWith('/phisher98/cloudstream-extensions-phisher/builds/');
  return false;
}
export function configuredEngine(env=process.env) {
  const url=String(env.CLOUDSTREAM_RUNTIME_URL||'').trim().replace(/\/+$/,'');
  const token=String(env.CLOUDSTREAM_RUNTIME_TOKEN||'');
  if(!url||!token)return null;
  try {
    const x=new URL(url);
    // The host is trusted operator configuration, never a URL supplied by a viewer.
    if(x.protocol!=='https:'||x.username||x.password||
      /^(?:localhost|127\.|10\.|192\.168\.|169\.254\.|0\.)/i.test(x.hostname) ||
      x.hostname.endsWith('.local') || x.hostname.includes(':'))return null;
    return {url:x.origin+x.pathname.replace(/\/+$/,''),token};
  }catch{return null;}
}
export function registry() {
  return REPOS.map(x=>({id:x.id,name:x.label,repositoryUrl:x.repository}));
}
function approvedPluginList(repo, declaredLists) {
  if(!Array.isArray(declaredLists))return null;
  // GitHub Raw supports both /builds/ and /refs/heads/builds/ paths.
  // Accept ONLY these two explicit aliases of our trusted index; don't
  // follow arbitrary manifest-supplied hosts or plugin indexes.
  const canonical=repo.expectedPlugins;
  const refStyle=canonical.replace('/builds/', '/refs/heads/builds/');
  return declaredLists.find(url=>url===canonical||url===refStyle)||null;
}
async function inspectOne(repo, fetchImpl){
  const manifest=await readJson(repo.repository,fetchImpl);
  const pluginListUrl=approvedPluginList(repo,manifest?.pluginLists);
  if(!pluginListUrl)throw Error('Unexpected repository manifest');
  const listing=await readJson(pluginListUrl,fetchImpl);
  if(!Array.isArray(listing))throw Error('Unexpected plugin index');
  const candidates=listing.filter(x=>x && (x.internalName===repo.plugin||x.name===repo.plugin));
  const usable=candidates.filter(x=>isPluginURL(x.url,repo.id)
    && Number.isInteger(x.version)&&x.version>=0
    && (!x.fileHash||/^sha256-[a-f0-9]{64}$/i.test(x.fileHash)));
  const newest=usable.sort((a,b)=>b.version-a.version)[0];
  if(!newest)throw Error('Expected .cs3 plugin not found');
  return {
    id:repo.id, name:repo.label, extensionName:String(newest.name||repo.plugin).slice(0,90),
    version:newest.version, fileSize:Number(newest.fileSize)||null,
    pluginUrl:newest.url, fileHash:newest.fileHash||null,
    state:'indexed', execution:'requires_android_runtime',
    lastChecked:new Date().toISOString()
  };
}
export async function inspectRepositories({fetchImpl=fetch,now=Date.now,force=false}={}) {
  const key='default', time=now(),entry=cache.get(key);
  if(!force&&entry&&entry.until>time)return entry.promise;
  const promise=Promise.all(REPOS.map(async repo=>{
    try{return await inspectOne(repo,fetchImpl);}
    catch(e){return {
      id:repo.id,name:repo.label,state:'unavailable',
      execution:'requires_android_runtime',
      error:typeof e?.message==='string'?e.message.slice(0,90):'Repository unavailable',
      lastChecked:new Date().toISOString()
    };}
  }));
  cache.set(key,{until:time+TTL,promise});
  return promise;
}
function normalizeMediaRequest(meta) {
  if(!meta||!['movie','tv'].includes(meta.media) ||
      !Number.isInteger(Number(meta.id)) || Number(meta.id)<1 ||
      !/^tt\d{5,12}$/.test(String(meta.imdbId||'')))return null;
  if(meta.media==='tv'&&(!Number.isInteger(meta.season)||
      !Number.isInteger(meta.episode)||meta.season<0||meta.episode<1))return null;
  return {media:meta.media,tmdbId:Number(meta.id),imdbId:meta.imdbId,
    title:String(meta.title||'').slice(0,150),
    year:meta.year||null,
    season:meta.media==='tv'?meta.season:null,
    episode:meta.media==='tv'?meta.episode:null,
    plugins:['CineStream','StreamPlay']};
}
function cleanRuntimeResult(source) {
  if(!source || typeof source.url!=='string')return null;
  let u;
  try{u=new URL(source.url)}catch{return null;}
  if(u.protocol!=='https:'||u.username||u.password||!u.hostname||
      /^(?:localhost|127\.|10\.|192\.168\.|169\.254\.)/i.test(u.hostname) ||
      u.hostname.endsWith('.local'))return null;
  // Do not claim a working stream merely because it is an unplayable embed,
  // a magnet/torrent or an arbitrary web page.
  if(!/\.(?:m3u8|mp4|webm)(?:$|[?#])/i.test(u.pathname))return null;
  const quality=Number(source.quality);
  if(!Number.isInteger(quality)||quality<720||quality>4320)return null;
  const provider=String(source.provider||'CloudStream').slice(0,70);
  if(!['CineStream','StreamPlay'].includes(provider))return null;
  const name=String(source.name||provider).slice(0,95);
  if(/download|تحميل|تنزيل|داونلود/i.test(name))return null;
  const subtitles=Array.isArray(source.subtitles)?source.subtitles.slice(0,24).flatMap(s=>{
    const lang=String(s.language||s.lang||'').toLowerCase();
    if(!['ar','en'].includes(lang)||typeof s.url!=='string')return [];
    try{
      const u=new URL(s.url);
      if(u.protocol!=='https:')return [];
      return [{url:u.href,language:lang,label:String(s.label||lang).slice(0,80),source:provider}];
    }catch{return [];}
  }):[];
  return {url:u.href,name:provider+' • '+name,quality,subtitles};
}
export async function queryRemoteEngine(meta,{
  fetchImpl=fetch,engine=configuredEngine()
}={}) {
  if(!engine)return [];
  const task=normalizeMediaRequest(meta);
  if(!task)return [];
  try {
    const response=await fetchImpl(engine.url+'/v1/resolve',{
      method:'POST',redirect:'error',
      headers:{Authorization:'Bearer '+engine.token,'Content-Type':'application/json',Accept:'application/json'},
      body:JSON.stringify(task),signal:AbortSignal.timeout(12_000)
    });
    if(!response.ok||Number(response.headers.get('content-length')||0)>MAX_JSON)return [];
    const text=await response.text();
    if(text.length>MAX_JSON)return [];
    const data=JSON.parse(text);
    const seen=new Set();
    return (Array.isArray(data.sources)?data.sources:[]).slice(0,70)
      .map(cleanRuntimeResult).filter(x=>{
        if(!x||seen.has(x.url))return false;
        seen.add(x.url);return true;
      }).sort((a,b)=>b.quality-a.quality).slice(0,30);
  }catch{return [];}
}
