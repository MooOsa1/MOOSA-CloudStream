/**
 * MOOSA Web server-native playback source discovery.
 *
 * No user device, CloudStream install or pairing required.
 * The source registry runs on Node and supports:
 *  - Archive.org video files with an explicit permissive rights declaration
 *  - HTTP Stremio-compatible stream add-ons configured by the site operator
 *
 * CloudStream .cs3 extensions are Android/Kotlin plugins, not HTTP sources.
 * These adapters intentionally never load arbitrary Android plugin code.
 */
import { queryRemoteEngine } from './cloudstream-engine.mjs';
import { fromCommons } from './commons-provider.mjs';
import { augmentCatalogCommonsWithNativeJs, nativeJsFilmForCatalog, nativeJsEngine } from './native-js-catalog-runtime.mjs';

const MAX_SOURCES = 30;
const MIN_QUALITY = 720;
const EXCLUDED_NAME = /download|داونلود|تحميل|تنزيل/i;
const ALLOWED_LICENSE = /^https?:\/\/creativecommons\.org\/(?:publicdomain\/(?:zero\/1\.0|mark\/1\.0)|licenses\/(?:by|by-sa)\/[234]\.0)(?:\/|$)/i;
const EXT_VIDEO = /\.(mp4|m4v|webm|m3u8)(?:[?#]|$)/i;
const QUALITY = /(?:^|[\s._/\-[({])([0-9]{3,4})p(?:$|[\s._/\-)\]}])/i;
const state = new Map();

function secureHttpUrl(raw) {
  if(typeof raw!=='string' || raw.length>3000) return null;
  try {
    const u = new URL(raw);
    if(u.protocol!=='https:' || u.username || u.password ||
      !u.hostname || u.hostname==='localhost' || u.hostname.endsWith('.localhost') ||
      u.hostname.endsWith('.local') || u.hostname.endsWith('.internal') ||
      /^\d+(\.\d+){3}$/.test(u.hostname) || u.hostname.includes(':')) return null;
    return u.href;
  } catch {return null;}
}
function normalize(s) {
  return String(s||'').normalize('NFKD').toLowerCase().replace(/[\u0300-\u036f]/g,'')
    .replace(/[^a-z0-9\u0600-\u06ff]+/g,' ').trim().replace(/\s+/g,' ');
}
function declaredQuality(text) {
  const found=String(text||'').match(QUALITY);
  return found ? Number(found[1]) : 0;
}
function mediaQuality(file) {
  const numeric=Number(file?.height);
  if(Number.isInteger(numeric) && numeric>0 && numeric<=4320) return numeric;
  return declaredQuality(String(file?.name||'')+' '+String(file?.original||''));
}
function subtitleList(tracks=[]) {
  if(!Array.isArray(tracks))return [];
  return tracks.slice(0,24).flatMap(t=>{
    const url=secureHttpUrl(t?.url);
    if(!url)return [];
    const language=String(t.lang||t.language||'').toLowerCase();
    const short=language==='ar'||language.startsWith('ara')?'ar':
      language==='en'||language.startsWith('eng')?'en':null;
    return short?[{url,language:short,label:String(t.label||short).slice(0,90),source:String(t.source||'Web source').slice(0,60)}]:[];
  });
}
function cleanStream(value,provider){
  const url=secureHttpUrl(value.url),name=String(value.name||provider||'Web Source').slice(0,95);
  const quality=Number(value.quality);
  if(!url || !Number.isInteger(quality) || quality<MIN_QUALITY ||
      quality>4320 || EXCLUDED_NAME.test(name)) return null;
  const extra={};
  if(value.preferred===true)extra.preferred=true;
  // Preserve plain-text attribution for Creative Commons sources.
  if(typeof value.licenseUrl==='string' && /^https:\/\/creativecommons\.org\//.test(value.licenseUrl)) {
    extra.licenseUrl=value.licenseUrl;
    if(typeof value.sourcePage==='string'&&/^https:\/\/commons\.wikimedia\.org\/wiki\/File:/i.test(value.sourcePage))
      extra.sourcePage=value.sourcePage;
    if(typeof value.attribution==='string')extra.attribution=value.attribution.slice(0,135);
    // Carry the verified official Safari transcode flag from Commons through
    // the catalog resolver; without this flag the iPhone cannot distinguish
    // the working 4:2:0 variant from the older 4:4:4 master.
    if(value.codecOptimized===true &&
       value.sourcePage==='https://commons.wikimedia.org/wiki/File:Elephants_Dream_(2006).webm' &&
       new URL(url).hostname==='upload.wikimedia.org'){
      extra.codecOptimized=true;
    }
    // JS approval is attached only after exact URL intersection with the
    // separate file-level rights source returned by Commons.
    if(value.preflight?.verified===true &&
       /^video\/(?:webm|mp4)$/i.test(String(value.preflight.mime||''))){
      extra.preflight={verified:true,mime:value.preflight.mime};
      extra.runtime='web_native_js';
    }
  }
  return {url,name,quality,subtitles:subtitleList(value.subtitles),...extra};
}
function uniqueSort(values) {
  const seen=new Set();
  return values.map(x=>cleanStream(x,x.name)).filter(x=>{
    if(!x||seen.has(x.url))return false;
    seen.add(x.url);return true;
  }).sort((a,b)=>Number(b.preferred===true)-Number(a.preferred===true)||b.quality-a.quality).slice(0,MAX_SOURCES);
}
async function getJson(url,fetchImpl,timeout=4000) {
  const response=await fetchImpl(url,{headers:{Accept:'application/json'},redirect:'error',
    signal:AbortSignal.timeout(timeout)});
  if(!response.ok)throw new Error('HTTP '+response.status);
  const contentType=response.headers.get('content-type')||'';
  if(!contentType.includes('json'))throw new Error('Expected JSON response');
  const len=Number(response.headers.get('content-length')||0);
  if(len>1_200_000)throw new Error('Provider response too large');
  const body=await response.text();
  if(body.length>1_200_000)throw new Error('Provider response too large');
  return JSON.parse(body);
}
export function configuredAddons(value=process.env.WEB_SOURCE_ADDONS||'[]') {
  try {
    const parsed=JSON.parse(value);
    if(!Array.isArray(parsed))return [];
    return [...new Set(parsed.map(secureHttpUrl).filter(u=>u && u.endsWith('/manifest.json')))].slice(0,6);
  }catch{return [];}
}
export async function fromWebAddons(meta, {fetchImpl=fetch,addons=configuredAddons()}={}) {
  if(!/^tt\d{5,12}$/.test(meta.imdbId||''))return [];
  const type=meta.media==='movie'?'movie':'series';
  const queryId=type==='movie'?meta.imdbId:
    meta.imdbId+':'+meta.season+':'+meta.episode;
  const all=await Promise.allSettled(addons.map(async manifestUrl=>{
    if(!secureHttpUrl(manifestUrl))return [];
    const manifest=await getJson(manifestUrl,fetchImpl,3200);
    const compatible=Array.isArray(manifest.types)&&manifest.types.includes(type)&&
      Array.isArray(manifest.resources)&&manifest.resources.some(r=>{
        if(r==='stream')return true;
        if(r?.name!=='stream')return false;
        // CineStream custom HTTPS addons may expose only movie OR series
        // streams through an object resource. Never query a disallowed type.
        return (!Array.isArray(r.types)||r.types.includes(type)) &&
          (!Array.isArray(r.idPrefixes)||r.idPrefixes.some(p=>
            typeof p==='string'&&meta.imdbId.startsWith(p)));
      });
    if(!compatible || (Array.isArray(manifest.idPrefixes) &&
       manifest.idPrefixes.length && !manifest.idPrefixes.some(p=>meta.imdbId.startsWith(p))))return [];
    const path=manifestUrl.slice(0,-'manifest.json'.length);
    const url=path+'stream/'+type+'/'+encodeURIComponent(queryId)+'.json';
    const data=await getJson(url,fetchImpl,5200);
    const output=[];
    for(const stream of Array.isArray(data.streams)?data.streams.slice(0,50):[]){
      // HTTP video only. Never treat torrent hashes, external purchase links,
      // magnet URIs or YouTube embeds as browser-playable video files.
      const link=secureHttpUrl(stream?.url);
      if(!link || !EXT_VIDEO.test(link.split('?')[0]))continue;
      // Native CloudStream supports request headers in its extractor links,
      // but Safari's HTML5 video element cannot attach Referer, Cookie or
      // Authorization headers to arbitrary media requests. Fail closed.
      const protectedHeaders=[
        stream?.headers,
        stream?.behaviorHints?.headers,
        stream?.behaviorHints?.proxyHeaders?.request
      ];
      if(protectedHeaders.some(h=>h && typeof h==='object' &&
        Object.entries(h).some(([key,val])=>
          /^(?:referer|origin|cookie|authorization|proxy-authorization|x-api-key)$/i.test(key) &&
          typeof val==='string'&&val.trim())))continue;
      if(stream?.drm || stream?.referer || stream?.cookie ||
         stream?.behaviorHints?.notWebReady===true)continue;
      const label=String(stream.name||'')+' '+String(stream.title||'');
      const quality=declaredQuality(label) || Number(stream?.behaviorHints?.videoHeight) || 0;
      const source=cleanStream({
        url:link,name:(String(manifest.name||'Source')+' • '+String(stream.name||'').slice(0,70)),
        quality,subtitles:stream.subtitles
      },manifest.name);
      if(source)output.push(source);
    }
    return output;
  }));
  return uniqueSort(all.flatMap(x=>x.status==='fulfilled'?x.value:[]));
}
function listedRights(metadata){
  const raw=metadata?.metadata?.licenseurl||metadata?.metadata?.license_url;
  const values=Array.isArray(raw)?raw:[raw];
  return values.some(v=>typeof v==='string'&&ALLOWED_LICENSE.test(v.trim()));
}
export async function fromArchive(meta,{fetchImpl=fetch}={}) {
  if(meta.media!=='movie'||!meta.title || meta.title.length>100)return [];
  // Archive search is strictly a candidate finder. A separately fetched
  // item's rights metadata AND exact title must match before any result appears.
  const term=meta.originalTitle || meta.title;
  const q='mediatype:movies AND title:("'+term.replace(/["\\():]/g,' ').slice(0,90)+'")';
  const u=new URL('https://archive.org/advancedsearch.php');
  u.searchParams.set('q',q);
  u.searchParams.append('fl[]','identifier');
  u.searchParams.append('fl[]','title');
  u.searchParams.set('rows','5');
  u.searchParams.set('output','json');
  let response;
  try{response=await getJson(u.href,fetchImpl,4500);}catch{return [];}
  const candidates=(response?.response?.docs||[]).slice(0,5)
    .filter(item=>/^[a-zA-Z0-9_][\w.\-]{1,95}$/.test(item?.identifier||'') &&
      normalize(item.title)===normalize(term));
  const results=await Promise.allSettled(candidates.slice(0,3).map(async item=>{
    const metadata=await getJson('https://archive.org/metadata/'+encodeURIComponent(item.identifier),fetchImpl,3500);
    if(!listedRights(metadata) ||
      normalize(metadata?.metadata?.title)!==normalize(term))return [];
    const files=Array.isArray(metadata.files)?metadata.files:[];
    return files.filter(f=>{
      const name=String(f?.name||'');
      return /(?:\.mp4|\.webm)$/i.test(name) &&
        !/sample|preview|thumb|trailer|30sec/i.test(name) &&
        Number(f?.size||0)>100_000 &&
        mediaQuality(f)>=MIN_QUALITY;
    }).map(f=>{
      const full='https://archive.org/download/'+encodeURIComponent(item.identifier)+'/'+
        String(f.name).split('/').map(encodeURIComponent).join('/');
      return cleanStream({url:full,name:'Internet Archive • '+String(f.name).slice(0,60),
        quality:mediaQuality(f)},'Internet Archive');
    }).filter(Boolean).slice(0,4);
  }));
  return uniqueSort(results.flatMap(r=>r.status==='fulfilled'?r.value:[]));
}
export async function resolveWebSources(meta,opts={}) {
  if(!meta|| !['movie','tv'].includes(meta.media) || !(Number(meta.id)>0))return [];
  if(meta.media==='tv'&&(!Number.isInteger(meta.season) || !Number.isInteger(meta.episode)))return [];
  const key=[meta.media,meta.id,meta.season??'',meta.episode??'',String(meta.imdbId||''),String(meta.originalTitle||meta.title||''),meta.year??''].join(':');
  const now=Date.now(),cached=state.get(key);
  if(cached && cached.expiry>now)return cached.sources;
  // Keep one in-flight request per title; prevents multiple clients from
  // hammering metadata services when clicking Play simultaneously.
  if(cached?.pending)return cached.pending;
  // Start the reviewed Node worker while Commons/other sources are being
  // discovered. Waiting until after Commons returned previously added
  // another 1.4s to the normal Play path and often lost worker readiness.
  // Never spawn it for an unapproved original title, year or TV episode.
  const nativeFilm=nativeJsFilmForCatalog(meta);
  const nativePending=nativeFilm?Promise.resolve().then(()=>
    (opts.jsLoad||((slug)=>nativeJsEngine.load(slug)))(nativeFilm.slug)
  ).catch(()=>null):null;
  const pending=Promise.allSettled([
    fromWebAddons(meta,opts),fromArchive(meta,opts),fromCommons(meta,opts),queryRemoteEngine(meta,opts)
  ]).then(async res=>{
    const commons=res[2]?.status==='fulfilled'?res[2].value:[];
    const enhanced=await augmentCatalogCommonsWithNativeJs(meta,commons,{
      ...(nativePending?{load:()=>nativePending}:{})
    });
    const byProvider=res.flatMap((r,i)=>
      i===2?enhanced.sources:(r.status==='fulfilled'?r.value:[]));
    const sources=uniqueSort(byProvider);
    if(state.size>250)state.delete(state.keys().next().value);
    state.set(key,{sources,expiry:Date.now()+(sources.length?300_000:90_000)});
    return sources;
  }).catch(()=>{state.delete(key);return [];});
  state.set(key,{pending,expiry:now+45_000});
  return pending;
}
