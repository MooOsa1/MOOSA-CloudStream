/**
 * Candidate external HTTP Stremio provider: Flix-Streams Free.
 * Pure read-only metadata discovery, NOT a streaming rights/quality claim.
 * Intentionally never fetch/play/download third-party movie streams.
 * Unofficial sources can be unlicensed, geoblocked, unstable or ad-supported.
 * No secrets, no accounts, no redirects, no cookie/Referer bypass.
 */
import {selectStremioStreamId} from '../providers.mjs';
const ORIGIN='https://free.flixnest.app';
const items=[
  {name:'Sintel CC BY 2010',media:'movie',id:45745,imdbId:'tt1727587',
    rightsAtSource:'independently licensed open short'},
  {name:'Big Buck Bunny CC BY 2008',media:'movie',id:10378,imdbId:'tt1254207',
    rightsAtSource:'independently licensed open short'},
  {name:'Dune Part Two 2024 (metadata only)',media:'movie',id:693134,
    imdbId:'tt15239678',rightsAtSource:'unverified third-party - no playback'},
  {name:'The Dark Knight 2008 (metadata only)',media:'movie',id:155,
    imdbId:'tt0468569',rightsAtSource:'unverified third-party - no playback'},
];
const clip=(v,max=80)=>String(v??'').slice(0,max);
async function getJson(url){
  const u=new URL(url);
  if(u.origin!==ORIGIN||!/^(?:\/manifest\.json|\/stream\/movie\/[^/]+\.json)$/.test(u.pathname))
    throw Error('Unexpected Stremio path');
  const res=await fetch(u.href,{headers:{Accept:'application/json'},
    signal:AbortSignal.timeout(8500),redirect:'error'});
  const ct=res.headers.get('content-type')||'';
  if(Number(res.headers.get('content-length')||0)>200000)return {
    ok:false,status:res.status,tooLarge:true
  };
  if(!res.ok)return {ok:false,status:res.status,ct};
  if(!ct.toLowerCase().includes('json'))return {
    ok:false,status:res.status,ct:clip(ct),notJson:true
  };
  const raw=await res.text();
  if(raw.length>200000)return {ok:false,status:res.status,tooLarge:true};
  try{return {ok:true,status:res.status,data:JSON.parse(raw)}}
  catch{return {ok:false,status:res.status,parseFailed:true}}
}
function classify(stream){
  if(!stream||typeof stream!=='object')return {kind:'invalid'};
  const headerSets=[stream.headers,stream.behaviorHints?.headers,
    stream.behaviorHints?.proxyHeaders?.request,stream.behaviorHints?.proxyHeaders];
  const needsHeaders=headerSets.some(h=>h&&typeof h==='object'&&Object.values(h).length>0)||
    !!stream.referer||!!stream.cookie;
  if(stream.drm||stream.behaviorHints?.drm)return {kind:'drm'};
  if(typeof stream.infoHash==='string' || typeof stream.magnet==='string')
    return {kind:'p2p_not_browser'};
  if(typeof stream.externalUrl==='string')return {kind:'external_page_not_video'};
  if(typeof stream.url!=='string')return {kind:'no_url'};
  try{
    const u=new URL(stream.url);
    const direct=u.protocol==='https:'&&!u.username&&!u.password&&!u.port&&
      /\.(?:m3u8|mp4|webm|mov|m4v)$/i.test(u.pathname);
    return {
      kind:!direct?'not_direct_https_file':needsHeaders?'requires_custom_headers':'direct_candidate',
      host:clip(u.hostname,100),
      suffix:(u.pathname.match(/\.([a-z0-9]{2,6})$/i)||[])[1]||'none',
      claim:clip((stream.name||'')+' '+(stream.title||''),90),
      hasProtectedHeaders:needsHeaders,
      noRightsOrIdentityVerified:true,
      decodedFrame:false
    };
  }catch{return {kind:'invalid_url'}}
}
const status={};
let manifest;
try{manifest=await getJson(ORIGIN+'/manifest.json')}
catch(e){
  console.log('FLIX_FREE_MANIFEST '+JSON.stringify({error:clip(e.name)}));
  process.exit(0);
}
if(!manifest.ok){
  console.log('FLIX_FREE_MANIFEST '+JSON.stringify({
    status:manifest.status,ok:false,
    contentType:manifest.ct||null,
    parseFailed:!!manifest.parseFailed
  }));
  process.exit(0);
}
const m=manifest.data;
console.log('FLIX_FREE_MANIFEST '+JSON.stringify({
  status:manifest.status,id:clip(m.id),name:clip(m.name),version:clip(m.version),
  types:Array.isArray(m.types)?m.types:[],
  resources:Array.isArray(m.resources)?m.resources.map(r=>
    typeof r==='string'?r:clip(r?.name,30)).slice(0,10):[],
  prefixes:Array.isArray(m.idPrefixes)?m.idPrefixes.slice(0,6):[],
  official:false,licenseAndAdStatusUnknown:true
}));
for(const item of items){
  const id=selectStremioStreamId(m,item);
  if(!id){console.log('FLIX_FREE_MOVIE '+JSON.stringify({
    movie:item.name,reason:'manifest_does_not_allow_identifier',
    count:0,streamDecoded:false
  }));continue;}
  const path='/stream/'+id.type+'/'+encodeURIComponent(id.id)+'.json';
  let data;
  try{data=await getJson(ORIGIN+path)}
  catch(e){
    console.log('FLIX_FREE_MOVIE '+JSON.stringify({
      movie:item.name,error:clip(e.name),streamDecoded:false
    }));continue;
  }
  const streams=Array.isArray(data.data?.streams)?data.data.streams.slice(0,30):[];
  const statuses=streams.map(classify);
  const counts={};
  for(const s of statuses)counts[s.kind]=(counts[s.kind]||0)+1;
  console.log('FLIX_FREE_MOVIE '+JSON.stringify({
    movie:item.name,manifestIdScheme:id.scheme,
    jsonStatus:data.status,bodyOk:data.ok,
    count:streams.length,counts,samples:statuses.slice(0,8),
    acceptedCommercialRights:false,decodedVideoFrames:0
  }));
}
console.log('FLIX_FREE_SUMMARY readOnly:true addedToProduction:false'+
  ' addonConfiguredOnRender:false noLicensedCommercialStreamingVerified:true');
