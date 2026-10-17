/**
 * Managed external HTTP add-on feasibility audit. Isolated CI only.
 * Fetches metadata/manifest JSON, never any media bytes, secrets or tokens.
 * Distinguishes independently maintained addon APIs from browser-playable FHD.
 * No torrent engine, no proxying, no Android, and no production changes.
 */
const CANDIDATES=[
  {name:'Pipe',manifest:'https://pipe.boringways.workers.dev/manifest.json'},
  {name:'Showbox (account-dependent control)',manifest:'https://showbox.codiv.dpdns.org/manifest.json'},
  {name:'WatchNow',manifest:'https://sword-watch.vercel.app/manifest.json'},
  {name:'Tenies',manifest:'https://tenies-site-addon.vercel.app/manifest.json'},
  {name:'HdHub (directory-configured FHD)',manifest:'https://hdhub.thevolecitor.qzz.io/eyJ0b3Jib3giOiJ1bnNldCIsInF1YWxpdGllcyI6IjIxNjBwLDEwODBwLDcyMHAiLCJzb3J0IjoiZGVzYyJ9/manifest.json'},
  {name:'FrostStream',manifest:'https://froststream.cloutteam.com/manifest.json'},
  {name:'Stremify Hayduk',manifest:'https://stremify.hayd.uk/manifest.json'},
  {name:'Nuvio Streams public',manifest:'https://nuviostreams.hayd.uk/manifest.json'},
  {name:'NoDebrid',manifest:'https://nodebrid.fly.dev/manifest.json'},
  {name:'PlavioHD (October active)',manifest:'https://plaviohd.vercel.app/manifest.json'},
  {name:'Hyper',manifest:'https://hyper.stravo.site/manifest.json'}
];
const MOVIES=[
  {name:'Sintel (CC licensed)',type:'movie',id:'tt1727587'},
  {name:'Inception (metadata only)',type:'movie',id:'tt1375666'},
  {name:'Game of Thrones S1E1 (metadata only)',type:'series',id:'tt0944947:1:1'}
];
const TIMEOUT=6500;
async function json(url){
  const controller=new AbortController();
  const timeout=setTimeout(()=>controller.abort(),TIMEOUT);
  try{
    const r=await fetch(url,{headers:{Accept:'application/json'},redirect:'error',signal:controller.signal});
    if(!r.ok)return {status:r.status,error:'HTTP_'+r.status};
    if(Number(r.headers.get('content-length')||0)>250_000)return {error:'response_too_large'};
    const data=await r.text();
    if(data.length>250_000)return {error:'response_too_large'};
    return {status:r.status,value:JSON.parse(data)};
  }catch(e){return {error:e?.name==='AbortError'?'timeout':'unavailable_or_invalid_json'}}
  finally{clearTimeout(timeout);}
}
function getCompatibility(item){
  if(item?.infoHash||item?.torrent||item?.fileIdx!=null||item?.ytId||item?.externalUrl)return 'not_direct_http';
  if(item?.behaviorHints?.notWebReady||item?.behaviorHints?.proxyHeaders||
     item?.referer||item?.cookie||item?.headers)return 'requires_native_or_proxy_headers';
  if(typeof item?.url!=='string')return 'missing_url';
  try{
    const u=new URL(item.url);
    if(u.protocol!=='https:'||u.username||u.password)return 'not_usable_https';
    if(!/\.(?:m3u8|mp4|webm)$/i.test(u.pathname))return 'not_direct_media_path';
  }catch{return 'not_usable_https';}
  return 'browser_candidate_unverified';
}
function qualityHint(item){
  const str=String(item?.name||'')+' '+String(item?.title||'')+' '+
    String(item?.description||'');
  if(/(?:^|[^\d])1080p?([^\d]|$)/i.test(str))return '1080p_claimed';
  if(/(?:^|[^\d])2160p?([^\d]|$)/i.test(str))return '2160p_claimed';
  if(/(?:^|[^\d])720p?([^\d]|$)/i.test(str))return '720p_claimed';
  return 'unknown_quality';
}
let usable=0;
for(const candidate of CANDIDATES){
  const manifest=await json(candidate.manifest);
  if(!manifest.value){
    console.log('MANAGED_FHD_ADDON name='+JSON.stringify(candidate.name)+
      ' manifest='+(manifest.error||'invalid')+' playable1080Verified=false');
    continue;
  }
  const data=manifest.value;
  const hasStream=Array.isArray(data.resources)&&data.resources.some(r=>r==='stream'||r?.name==='stream');
  const types=Array.isArray(data.types)?data.types:[];
  const prefix=Array.isArray(data.idPrefixes)?data.idPrefixes:['tt'];
  console.log('MANAGED_FHD_ADDON name='+JSON.stringify(candidate.name)+
    ' manifest=ok version='+JSON.stringify(String(data.version||'unknown'))+
    ' streamResource='+hasStream+' movies='+types.includes('movie')+
    ' series='+types.includes('series')+
    ' requiresConfiguration='+(data.behaviorHints?.configurationRequired===true)+
    ' playable1080Verified=false');
  if(!hasStream)continue;
  for(const movie of MOVIES){
    if(!types.includes(movie.type)||!prefix.some(s=>movie.id.startsWith(s)))continue;
    const result=await json(new URL('./stream/'+movie.type+'/'+encodeURIComponent(movie.id)+'.json',
      candidate.manifest).href);
    if(!result.value){
      console.log('MANAGED_FHD_QUERY name='+JSON.stringify(candidate.name)+
        ' case='+JSON.stringify(movie.name)+' status='+(result.error||'invalid')+
        ' browserCandidates=0 fhdHints=0');
      continue;
    }
    const streams=Array.isArray(result.value.streams)?result.value.streams.slice(0,40):[];
    const formats=streams.map(getCompatibility);
    const browserCandidates=formats.filter(x=>x==='browser_candidate_unverified').length;
    const fhdHints=streams.filter(x=>qualityHint(x)==='1080p_claimed').length;
    const browserFhd=streams.filter((x,i)=>formats[i]==='browser_candidate_unverified'&&
      qualityHint(x)==='1080p_claimed').length;
    usable+=browserFhd;
    console.log('MANAGED_FHD_QUERY name='+JSON.stringify(candidate.name)+
      ' case='+JSON.stringify(movie.name)+
      ' streamCount='+streams.length+' browserCandidates='+browserCandidates+
      ' fhdHints='+fhdHints+' browserFhdHints='+browserFhd+
      ' requiringNativeHeaders='+formats.filter(x=>x==='requires_native_or_proxy_headers').length+
      ' torrentOrOther='+formats.filter(x=>x==='not_direct_http').length+
      ' playable1080Verified=false');
  }
}
console.log('MANAGED_FHD_AUDIT browserFhdMetadataCandidates='+usable+
  ' actualDecoded1080Verified=0 reason=metadata_only_no_media_fetch');
// This is an exploratory health report, not a source acceptance gate.
