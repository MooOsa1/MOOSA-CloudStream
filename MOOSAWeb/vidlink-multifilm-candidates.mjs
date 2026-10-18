/**
 * Non-deployed Vidlink multi-film prototype.
 * Do not publish candidates until EACH film's runtime and decoded frames
 * have been checked on Chrome and the specific edition's rights reviewed.
 */
const EXPECTED=new Map([
  [961,{tmdbId:961,imdb:'tt0017925',year:1926,title:'the general'}],
  [3085,{tmdbId:3085,imdb:'tt0032599',year:1940,title:'his girl friday'}]
]);
function standardTitle(value){
  return String(value||'').normalize('NFKD').toLowerCase()
    .replace(/[^a-z0-9]+/g,' ').trim();
}
export function exactExperimentalFilm(meta){
  const item=EXPECTED.get(Number(meta?.id));
  if(!item||meta?.media!=='movie'||meta?.imdbId!==item.imdb||
    Number(meta.year)!==item.year)return null;
  if(![meta.title,meta.originalTitle].some(t=>standardTitle(t)===item.title))
    return null;
  return {...item};
}
export function offeredVidlinkVariants(response,{
  approvedHosts=['hcdn3.hakunaymatata.com'],
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
  const accepted=[];
  for(const candidate of candidates){
    let verification;
    try{verification=await mediaGateway.issueIfBytesVerified({
      url:candidate.url,format:candidate.format,headers:candidate.headers,
      rightsApproved:true,sourceVerified:true
    });}catch{continue;}
    if(!verification?.source)continue;
    accepted.push({
      ...verification.source,
      name:'CineStream • Vidlink — verified bytes / '+candidate.advertisedQuality+'p offered',
      provider:'CineStream',
      // Leave quality automatic until Chrome measures actual vertical pixels.
      quality:0,
      subtitles:[],
      originalMovieTmdbId:exact.tmdbId,
      originalMovieImdbId:exact.imdb,
      originalMovieYear:exact.year
    });
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
 * Experimental two-film operator adapter. All external calls are pinned to
 * the existing known protocol hosts; unapproved titles can NEVER contact it.
 * No HTML to scrape, no downloading feature movies, and no source URLs in logs.
 */
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
  }catch{
    return [];
  }
}
