/**
 * Non-deployed Vidlink multi-film prototype.
 * Do not publish candidates until EACH film's runtime and decoded frames
 * have been checked on Chrome and the specific edition's rights reviewed.
 */
// Strict catalog identity matching ONLY. This never authenticates the edition
// returned by the upstream CDN, and never bypasses the rightsReviewed flag.
const EXPECTED=new Map([
  [961,{tmdbId:961,imdb:'tt0017925',year:1926,title:'the general'}],
  [3085,{tmdbId:3085,imdb:'tt0032599',year:1940,title:'his girl friday'}],
  [19,{tmdbId:19,imdb:'tt0017136',year:1927,title:'metropolis'}],
  [832,{tmdbId:832,imdb:'tt0022100',year:1931,title:'m'}]
]);
function standardTitle(value){
  return String(value||'').normalize('NFKD').toLowerCase()
    .replace(/[^a-z0-9]+/g,' ').trim();
}
/**
 * Optional operator-reviewed movie registry. A single boolean flag is NOT
 * blanket film clearance. Each EXTRA title needs its own positive review,
 * exact identifiers and operator-held documentation for the source edition.
 *
 * No visitor may submit a registry or URLs through an HTTP query. The only
 * default registered titles are the four tightly scoped existing pilots.
 * Adding a record does not prove availability, rights or actual video frames.
 */
export function reviewedFilmRegistry(input=process.env.CINESTREAM_REVIEWED_FILMS||'[]'){
  let rows=input;
  if(typeof rows==='string'){
    if(rows.length>24000)return new Map();
    try{rows=JSON.parse(rows);}catch{return new Map();}
  }
  if(!Array.isArray(rows))return new Map();
  const allowed=new Map();
  for(const item of rows.slice(0,40)){
    if(!item||item.rightsReviewed!==true||
       item.sourceEditionReviewed!==true)continue;
    const tmdbId=Number(item.tmdbId),year=Number(item.year);
    const imdb=String(item.imdb||'');
    const title=standardTitle(item.title);
    if(!Number.isSafeInteger(tmdbId)||tmdbId<1||tmdbId>9999999999||
       !Number.isInteger(year)||year<1888||year>2100||
       !/^tt\d{5,12}$/.test(imdb)||title.length<1||title.length>140||
       allowed.has(tmdbId)||EXPECTED.has(tmdbId))continue;
    allowed.set(tmdbId,{tmdbId,imdb,year,title});
  }
  return allowed;
}
export function exactExperimentalFilm(meta,{
  reviewed=process.env.CINESTREAM_REVIEWED_FILMS||'[]'
}={}){
  const id=Number(meta?.id);
  if(!Number.isSafeInteger(id)||id<1||meta?.media!=='movie')return null;
  const item=EXPECTED.get(id)||reviewedFilmRegistry(reviewed).get(id);
  if(!item||meta?.imdbId!==item.imdb||
    Number(meta.year)!==item.year)return null;
  if(![meta.title,meta.originalTitle].some(t=>standardTitle(t)===item.title))
    return null;
  return {...item};
}
export function offeredVidlinkVariants(response,{
  // Both domains independently returned real MP4 bytes and Chrome frames
  // in opt-in tests; neither is globally exposed for anonymous proxying.
  approvedHosts=['hcdn3.hakunaymatata.com','bcdn.hakunaymatata.com'],
  maxVariants=4
}={}){
  const allowed=new Set(approvedHosts);
  const entries=response?.stream?.qualities||response?.qualities;
  if(!entries||typeof entries!=='object'||Array.isArray(entries))return [];
  const visited=new Set(),found=[];
  for(const [label,item] of Object.entries(entries).slice(0,18)){
    if(typeof item?.url!=='string')continue;
    let url;
    try{url=new URL(item.url);}
    catch{continue;}
    if(url.protocol!=='https:'||url.port||url.username||url.password||
      url.hash||!allowed.has(url.hostname.toLowerCase())||
      visited.has(url.href))continue;
    const format=/\.m3u8$/i.test(url.pathname)||item.type==='m3u8'?'hls':
      /\.mp4$/i.test(url.pathname)||item.type==='mp4'?'mp4':null;
    if(!format)continue;
    const quality=Number(String(label).match(/([0-9]{3,4})/)?.[1]||0);
    // 4K is intentionally out of scope: spend gateway checks on 1080p
    // and lower fallback variants until multi-film playback is reliable.
    if(!Number.isInteger(quality)||quality<144||quality>1080)continue;
    visited.add(url.href);
    found.push({url:url.href,format,advertisedQuality:quality,
      headers:item.headers&&typeof item.headers==='object'?item.headers:{
        Referer:'https://filmboom.top/',Origin:'https://filmboom.top'
      }});
  }
  return found.sort((a,b)=>b.advertisedQuality-a.advertisedQuality)
    .slice(0,Math.max(1,Math.min(4,Number(maxVariants)||1)));
}
export async function verifiedFilmVariants({
  meta,providerResponse,mediaGateway,enabled=false,
  rightsReviewed=false
}={}){
  if(!enabled||!rightsReviewed||!mediaGateway)return [];
  const exact=exactExperimentalFilm(meta);
  if(!exact)return [];
  const candidates=offeredVidlinkVariants(providerResponse);
  // HTTP Range preflights are independent. Run at most three at once to
  // reduce the Play-button delay, without opening unlimited CDN connections.
  // Keep advertised-quality order even if a lower quality finishes earlier.
  const accepted=[];
  for(let start=0;start<candidates.length;start+=3){
    const group=candidates.slice(start,start+3);
    const batch=await Promise.all(group.map(async candidate=>{
      try{
        const verification=await mediaGateway.issueIfBytesVerified({
          url:candidate.url,format:candidate.format,headers:candidate.headers,
          rightsApproved:true,sourceVerified:true
        });
        if(!verification?.source)return null;
        return {
          ...verification.source,
          name:'CineStream • Vidlink — verified bytes / '+candidate.advertisedQuality+'p offered',
          provider:'CineStream',
          // Decode and measure actual dimensions in Chrome before claiming quality.
          quality:0,
          subtitles:[],
          originalMovieTmdbId:exact.tmdbId,
          originalMovieImdbId:exact.imdb,
          originalMovieYear:exact.year
        };
      }catch{return null;}
    }));
    accepted.push(...batch.filter(Boolean));
  }
  return accepted;
}


async function smallProviderJson(response,max=180000){
  if(!response.ok)throw Error('provider_unavailable');
  if(Number(response.headers.get('content-length')||0)>max)throw Error('oversized');
  const reader=response.body?.getReader();
  if(!reader)throw Error('missing_body');
  let size=0;const chunks=[];
  try{
    for(;;){
      const {done,value}=await reader.read();
      if(done)break;
      size+=value.length;
      if(size>max)throw Error('oversized');
      chunks.push(Buffer.from(value));
    }
  }finally{await reader.cancel().catch(()=>{});}
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

/**
 * Experimental catalog-allowlisted film operator adapter. All external calls are pinned to
 * the existing known protocol hosts; unapproved titles can NEVER contact it.
 * No HTML to scrape, no downloading feature movies, and no source URLs in logs.
 */
// Account for candidates filtered out by the existing source rules, without
// exposing private CDN hosts, media URLs or any upstream response body.
function inventoryFilterCounts(response,allowedHosts){
  const values=response?.stream?.qualities||response?.qualities;
  const results={providerEntries:0,blockedHosts:0,invalidUrls:0,
    unsupportedFormats:0,excludedQualities:0,duplicates:0};
  if(!values||typeof values!=='object'||Array.isArray(values))return results;
  const seen=new Set(),allowed=new Set(allowedHosts);
  const rows=Object.entries(values).slice(0,18);
  results.providerEntries=rows.length;
  for(const [label,item] of rows){
    if(typeof item?.url!=='string'){results.invalidUrls++;continue;}
    let u;
    try{u=new URL(item.url);}catch{results.invalidUrls++;continue;}
    if(u.protocol!=='https:'||u.port||u.username||u.password||u.hash){
      results.invalidUrls++;continue;
    }
    if(!allowed.has(u.hostname.toLowerCase())){
      results.blockedHosts++;continue;
    }
    const validFormat=/\.m3u8$/i.test(u.pathname)||item.type==='m3u8'||
      /\.mp4$/i.test(u.pathname)||item.type==='mp4';
    if(!validFormat){results.unsupportedFormats++;continue;}
    const q=Number(String(label).match(/([0-9]{3,4})/)?.[1]||0);
    if(!Number.isInteger(q)||q<144||q>1080){
      results.excludedQualities++;continue;
    }
    if(seen.has(u.href)){results.duplicates++;continue;}
    seen.add(u.href);
  }
  return results;
}

// One reusable Vidlink lookup for arbitrary positive TMDB movie IDs.
// This stage discovers provider availability ONLY. It never returns a
// media URL, auth header, signed capability or playable stream to a viewer.
// The existing exact-title / rights-reviewed path is a separate promotion.
export async function discoverVidlinkInventory({
  tmdbId,fetchImpl=fetch,allowedHosts=[
    'hcdn3.hakunaymatata.com','bcdn.hakunaymatata.com']
}={}){
  const id=Number(tmdbId);
  const empty=(status)=>({
    tmdbId:Number.isSafeInteger(id)&&id>0?id:null,
    provider:'CineStream • Vidlink',status,availableVariants:0,
    advertisedQualities:[],formatCounts:{mp4:0,hls:0},
    filterCounts:{providerEntries:0,blockedHosts:0,invalidUrls:0,
      unsupportedFormats:0,excludedQualities:0,duplicates:0},
    playableVerified:false,rightsVerified:false
  });
  if(!Number.isSafeInteger(id)||id<1||id>9999999999)
    return empty('invalid_movie_id');
  try{
    const query=new URL('https://enc-dec.app/api/enc-vidlink');
    query.searchParams.set('text',String(id));
    const keyResult=await smallProviderJson(await fetchImpl(query.href,{
      headers:{Accept:'application/json'},redirect:'error',
      signal:AbortSignal.timeout(11000)
    }),24000);
    const code=keyResult?.result;
    if(typeof code!=='string'||!(/^[a-zA-Z0-9+/_=-]{3,320}$/).test(code))
      return empty('no_provider_id');
    const providerResponse=await smallProviderJson(await fetchImpl(
      'https://vidlink.pro/api/b/movie/'+encodeURIComponent(code),{
      headers:{Accept:'application/json',Referer:'https://vidlink.pro/',
        Origin:'https://vidlink.pro/',
        'User-Agent':'Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36'},
      redirect:'error',signal:AbortSignal.timeout(14000)
    }));
    const variants=offeredVidlinkVariants(providerResponse,{
      approvedHosts:allowedHosts,maxVariants:4});
    const filterCounts=inventoryFilterCounts(providerResponse,allowedHosts);
    return {
      ...empty(variants.length?'candidates_found':
        filterCounts.providerEntries===0?'provider_no_quality_list':
        'all_candidates_filtered'),
      filterCounts,
      availableVariants:variants.length,
      advertisedQualities:variants.map(x=>x.advertisedQuality),
      formatCounts:{
        mp4:variants.filter(x=>x.format==='mp4').length,
        hls:variants.filter(x=>x.format==='hls').length
      }
    };
  }catch{return empty('provider_unavailable');}
}

// The safe, existing promotion path remains restricted to independently
// reviewed exact film identities. Shared discovery does NOT grant rights.
export async function findVerifiedMultiFilmVidlink({
  meta,mediaGateway,
  enabled=process.env.CINESTREAM_MULTIFILM_PILOT==='1',
  rightsReviewed=process.env.CINESTREAM_MULTIFILM_RIGHTS_APPROVED==='1',
  fetchImpl=fetch
}={}){
  const film=exactExperimentalFilm(meta);
  if(!enabled||!rightsReviewed||!mediaGateway||!film)return [];
  try{
    const query=new URL('https://enc-dec.app/api/enc-vidlink');
    query.searchParams.set('text',String(film.tmdbId));
    const keyResult=await smallProviderJson(await fetchImpl(query.href,{
      headers:{Accept:'application/json'},redirect:'error',
      signal:AbortSignal.timeout(11000)
    }),24000);
    const code=keyResult?.result;
    if(typeof code!=='string'||!(/^[a-zA-Z0-9+/_=-]{3,320}$/).test(code))
      return [];
    const providerResponse=await smallProviderJson(await fetchImpl(
      'https://vidlink.pro/api/b/movie/'+encodeURIComponent(code),{
      headers:{Accept:'application/json',Referer:'https://vidlink.pro/',
        Origin:'https://vidlink.pro/',
        'User-Agent':'Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36'},
      redirect:'error',signal:AbortSignal.timeout(14000)
    }));
    return verifiedFilmVariants({meta,providerResponse,mediaGateway,
      enabled:true,rightsReviewed:true});
  }catch{return [];}
}

/**
 * Read-only real MP4 byte audit for arbitrary exact TMDB IDs.
 * This does NOT authorize films, publish source URLs, issue media tokens,
 * prove editions, or claim decoded browser playback.
 */
export async function probeVidlinkContainerBytes({
  tmdbId,fetchImpl=fetch,maxVariants=2
}={}){
  const id=Number(tmdbId);
  const state=(status,extra={})=>({
    tmdbId:Number.isSafeInteger(id)&&id>0?id:null,
    status,checked:0,mp4ContainersVerified:0,
    verifiedQualityLabels:[],rightsVerified:false,
    playbackVerified:false,...extra
  });
  if(!Number.isSafeInteger(id)||id<1||id>9999999999)
    return state('invalid_movie_id');
  try{
    const keyUrl=new URL('https://enc-dec.app/api/enc-vidlink');
    keyUrl.searchParams.set('text',String(id));
    const key=await smallProviderJson(await fetchImpl(keyUrl.href,{
      redirect:'error',headers:{Accept:'application/json'},
      signal:AbortSignal.timeout(10000)
    }),24000);
    const code=key?.result;
    if(typeof code!=='string'||!/^[a-zA-Z0-9+/_=-]{3,320}$/.test(code))
      return state('no_provider_id');
    const data=await smallProviderJson(await fetchImpl(
      'https://vidlink.pro/api/b/movie/'+encodeURIComponent(code),{
      redirect:'error',signal:AbortSignal.timeout(12000),
      headers:{Accept:'application/json',Referer:'https://vidlink.pro/',
        Origin:'https://vidlink.pro/'}
    }));
    const found=offeredVidlinkVariants(data,{maxVariants:3})
      .filter(x=>x.format==='mp4')
      .slice(0,Math.max(1,Math.min(3,Number(maxVariants)||2)));
    if(!found.length)return state('no_mp4_candidate');
    let confirmed=0;
    const labels=[];
    const statuses=[];
    for(const candidate of found){
      let response;
      try{
        response=await fetchImpl(candidate.url,{
          method:'GET',redirect:'error',
          signal:AbortSignal.timeout(8000),
          headers:{
            Range:'bytes=0-4095',
            Referer:'https://filmboom.top/',
            Origin:'https://filmboom.top/'
          }
        });
        if(response.status!==206){
          statuses.push('http_'+response.status);continue;
        }
        const type=(response.headers.get('content-type')||'').toLowerCase();
        if(!type.startsWith('video/mp4') &&
          !type.startsWith('application/octet-stream')){
          statuses.push('wrong_mime');continue;
        }
        const reader=response.body?.getReader();
        if(!reader){statuses.push('no_body');continue;}
        let bytes=Buffer.alloc(0);
        try{
          while(bytes.length<16){
            const part=await reader.read();
            if(part.done)break;
            bytes=Buffer.concat([bytes,Buffer.from(part.value).subarray(0,16-bytes.length)]);
          }
        }finally{await reader.cancel().catch(()=>{});}
        if(bytes.length>=12 && ['ftyp','styp'].includes(
          bytes.subarray(4,8).toString('ascii'))){
          confirmed++;labels.push(candidate.advertisedQuality);
          statuses.push('mp4_header_verified');
        }else statuses.push('wrong_mp4_signature');
      }catch{statuses.push('network_or_timeout');}
      finally{
        if(response?.body&&!response.body.locked)
          await response.body.cancel().catch(()=>{});
      }
    }
    return state(confirmed?'mp4_bytes_verified':'no_valid_media_bytes',{
      checked:found.length,mp4ContainersVerified:confirmed,
      verifiedQualityLabels:labels,statuses
    });
  }catch{return state('provider_unavailable');}
}
