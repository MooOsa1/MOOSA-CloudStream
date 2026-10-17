/**
 * CineStream's working first-party Vidlink protocol across FOUR exact movie
 * identities, with no Android, no Paperframe and no public site modification.
 *
 * Do not treat a returned title, quality LABEL or MP4 signature as movie
 * identity, exact resolution, playback authorization or viewer readiness.
 * Test only tiny byte ranges. Don't log tokens, source URLs or cookies.
 */
import assert from 'node:assert/strict';

const FILMS=[
 {title:'Night of the Living Dead',year:1968,tmdbId:10331,imdb:'tt0063350'},
 {title:'Nosferatu',year:1922,tmdbId:653,imdb:'tt0013442'},
 {title:'The General',year:1926,tmdbId:961,imdb:'tt0017925'},
 {title:'His Girl Friday',year:1940,tmdbId:3085,imdb:'tt0032599'},
 // Discovery ONLY. No third-party rights or movie identity asserted.
 {title:'Charade',year:1963,tmdbId:4808},
 {title:'House on Haunted Hill',year:1959,tmdbId:15856},
 {title:'The Little Shop of Horrors',year:1960,tmdbId:24452},
 {title:'Plan 9 from Outer Space',year:1959,tmdbId:10513},
 {title:'The Last Man on Earth',year:1964,tmdbId:21159},
 {title:'Sherlock Jr.',year:1924,tmdbId:992},
 {title:'The Gold Rush',year:1925,tmdbId:962},
 {title:'The Kid',year:1921,tmdbId:10098}
];
// CI-only bounded test allowlist: newly observed Vidlink CDN domain is
// included ONLY for media-prefix probing, not in public Render settings.
const APPROVED_HOSTS=new Set(['hcdn3.hakunaymatata.com',
  'bcdn.hakunaymatata.com']);
const PROVIDER='https://vidlink.pro/api/b/movie/';
const LOOKUP='https://enc-dec.app/api/enc-vidlink';
const AGENT='Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36';
const HEADER_ALLOW=new Set(['accept','origin','referer','user-agent']);
async function jsonSmall(response,max=180000){
  if(!response.ok)throw Error('upstream_http_'+response.status);
  if(Number(response.headers.get('content-length')||0)>max)throw Error('oversized_response');
  const rdr=response.body?.getReader();
  if(!rdr)throw Error('missing_body');
  let n=0;const parts=[];
  try{
    for(;;){
      const {value,done}=await rdr.read();
      if(done)break;
      n+=value.byteLength;
      if(n>max)throw Error('oversized_response');
      parts.push(Buffer.from(value));
    }
  }finally{await rdr.cancel().catch(()=>{});}
  return JSON.parse(Buffer.concat(parts).toString('utf8'));
}
function approvedUrl(raw){
  try{
    const u=new URL(String(raw));
    if(u.protocol!=='https:'||u.username||u.password||u.port||u.hash)return null;
    return u;
  }catch{return null;}
}
function formatFor(item,u){
  if(item?.type==='m3u8'||u.pathname.toLowerCase().endsWith('.m3u8'))return 'hls';
  if(item?.type==='mp4'||u.pathname.toLowerCase().endsWith('.mp4'))return 'mp4';
  return null;
}
function requestHeaders(item){
  const source=item?.headers&&typeof item.headers==='object'?item.headers:{
    Referer:'https://filmboom.top/',Origin:'https://filmboom.top'
  };
  const clean={};
  for(const [key,value] of Object.entries(source)){
    if(HEADER_ALLOW.has(key.toLowerCase())&&typeof value==='string'&&
      value.length<500&&!/[\r\n]/.test(value))clean[key]=value;
  }
  return clean;
}
async function probe(u,format,headers){
  if(!APPROVED_HOSTS.has(u.hostname.toLowerCase()))
    return {status:'host_not_operator_approved',realVideoPrefix:false};
  let res;
  try{
    res=await fetch(u.href,{redirect:'error',
      headers:{...headers,...(format==='mp4'?{Range:'bytes=0-8191'}:{})},
      signal:AbortSignal.timeout(14000)});
    const mime=(res.headers.get('content-type')||'').split(';')[0].toLowerCase();
    const status=res.status;
    if(![200,206].includes(status))
      return {status:'http_'+status,realVideoPrefix:false,mime};
    const rdr=res.body?.getReader();
    const block=rdr?await rdr.read():{done:true};
    const first=Buffer.from(block.value||[]);
    await rdr?.cancel().catch(()=>{});
    const mp4=first.length>12&&['ftyp','styp','moof'].includes(first.subarray(4,8).toString('latin1'));
    const hls=first.toString('utf8',0,10).startsWith('#EXTM3U');
    const valid=format==='mp4'
      ?mp4&&(/video\/mp4|octet-stream/.test(mime))
      :hls&&(/mpegurl|octet-stream/.test(mime));
    return {status:'http_'+status,mime,
      realVideoPrefix:format==='mp4'&&valid,
      validHlsPlaylist:format==='hls'&&valid,
      // Declared quality is NOT an actual decoded pixel height.
      decodedResolutionVerified:false};
  }catch(error){
    return {status:String(error.name||'network_error').slice(0,32),
      realVideoPrefix:false};
  }finally{if(res?.body&&!res.body.locked)await res.body.cancel().catch(()=>{});}
}
const start=Date.now();
const results=[];
for(const film of FILMS){
  let result={title:film.title,year:film.year,tmdbId:film.tmdbId,
    imdbId:film.imdb,provider:'CineStream Vidlink',status:'not_attempted',
    declaredVariants:0,approvedHostVariants:0,sourceHostCount:0,
    unapprovedCandidateHosts:[],probedVariants:[],
    byteVerifiedVariants:0,filmIdentityVerified:false,
    actualHeightVerified:false,playbackVerified:false};
  try{
    const encoded=await jsonSmall(await fetch(
      LOOKUP+'?text='+film.tmdbId,{
        headers:{Accept:'application/json'},redirect:'error',
        signal:AbortSignal.timeout(11500)
      }),24000);
    const key=encoded?.result;
    if(typeof key!=='string'||!key.match(/^[A-Za-z0-9+/=_-]{3,320}$/))
      throw Error('encoded_id_invalid');
    const provider=await jsonSmall(await fetch(
      PROVIDER+encodeURIComponent(key),{
        headers:{'User-Agent':AGENT,Accept:'application/json',
          Referer:'https://vidlink.pro/',Origin:'https://vidlink.pro'},
        redirect:'error',signal:AbortSignal.timeout(15500)
      }),180000);
    const qualities=provider?.stream?.qualities||provider?.qualities||{};
    const alternatives=Object.entries(qualities).slice(0,18).flatMap(([label,item])=>{
      const url=approvedUrl(item?.url);
      if(!url)return [];
      const format=formatFor(item,url);
      if(!format)return [];
      const claimedQuality=Number(String(label).match(/([0-9]{3,4})/)?.[1]||0);
      return [{label:String(label).slice(0,18),claimedQuality,url,format,
        approved:APPROVED_HOSTS.has(url.hostname.toLowerCase()),
        headers:requestHeaders(item)}];
    }).sort((a,b)=>b.claimedQuality-a.claimedQuality);
    result.declaredVariants=alternatives.length;
    result.approvedHostVariants=alternatives.filter(x=>x.approved).length;
    result.sourceHostCount=new Set(alternatives.map(x=>x.url.hostname)).size;
    // Discover NEW potential CDNs, but do not issue playback tokens or
    // send media requests to unreviewed hosts.
    result.unapprovedCandidateHosts=[...new Set(alternatives
      .filter(x=>!x.approved).map(x=>x.url.hostname))].slice(0,8);
    const probed=[];
    // Try distinct quality variants, not just the first successful server.
    for(const entry of alternatives.slice(0,5)){
      const check=await probe(entry.url,entry.format,entry.headers);
      probed.push({declaredQuality:entry.label,claimedHeight:entry.claimedQuality,
        approvedHost:entry.approved,format:entry.format,...check});
    }
    result.probedVariants=probed;
    result.byteVerifiedVariants=probed.filter(x=>x.realVideoPrefix).length;
    result.status='completed';
  }catch(error){
    result.status=String(error.name||'upstream_error').slice(0,38);
  }
  results.push(result);
  console.log('MOOSA_CINESTREAM_MULTI_FILM_QUALITY_AUDIT '+JSON.stringify(result));
}
console.log('MOOSA_CINESTREAM_MULTI_FILM_SUMMARY '+JSON.stringify({
  filmCount:results.length,
  sourceCDNHostsIdentified:[...new Set(results.flatMap(
    x=>x.unapprovedCandidateHosts))].slice(0,12),
  filmsWithRealMp4Bytes:results.filter(r=>r.byteVerifiedVariants>0).length,
  totalMp4ByteVerifiedVariants:results.reduce((n,r)=>n+r.byteVerifiedVariants,0),
  userSiteUnchanged:true,actualDecodedQualityUnproven:true,
  exactMovieIdentityUnprovenForNewFilms:true,
  nextStep:'Browser duration + frame verify each promising exact film'
}));
assert.equal(results.length,FILMS.length);
assert.ok(results.some(x=>x.byteVerifiedVariants>0),
  'No source returned real media bytes; do not assume any multi-film support');
