/**
 * A narrowly scoped ORIGINAL first-party HTTPS client for a single
 * independently reviewed *full-length film* source used by CineStream's
 * Vidlink extractor. No upstream GPL/AGPL plugin source is embedded or
 * evaluated. This is a protocol adapter, not a general scraper service.
 *
 * Disabled by default; the operator must opt in, supply allowed media hosts,
 * and review the rights to the actual video edition. No DRM/cookie bypass.
 */
const LOOKUP='https://enc-dec.app/api/enc-vidlink';
const VIDLINK='https://vidlink.pro/api/b/movie/';
const FILM={tmdb:10331,imdb:'tt0063350',year:1968,title:'night of the living dead'};
const MOBILE_AGENT='Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36';
const VIDLINK_HEADERS={'User-Agent':MOBILE_AGENT,Accept:'application/json',
  Referer:'https://vidlink.pro/',Origin:'https://vidlink.pro'};
function title(value){
  return String(value||'').normalize('NFKD').toLowerCase()
    .replace(/[^a-z0-9]+/g,' ').trim();
}
export function isExactNight(meta){
  return meta?.media==='movie'&&Number(meta.id)===FILM.tmdb&&
    meta.imdbId===FILM.imdb&&Number(meta.year)===FILM.year&&
    [meta.title,meta.originalTitle].some(x=>title(x)===FILM.title);
}
export function vidlinkPilotHosts(raw=process.env.CINESTREAM_MEDIA_HOSTS||''){
  return [...new Set(String(raw).split(',').map(x=>x.trim().toLowerCase())
    .filter(x=>/^[a-z0-9-]+(?:\.[a-z0-9-]+)+$/.test(x)&&
      !/^\d+(?:\.\d+){3}$/.test(x)&&
      !x.endsWith('.local')&&!x.endsWith('.internal')&&x!=='localhost'))]
    .slice(0,4);
}
async function limitedJson(response,max=90000){
  if(!response.ok)throw Error('Provider returned HTTP '+response.status);
  if(Number(response.headers.get('content-length')||0)>max)
    throw Error('Provider response too large');
  const reader=response.body?.getReader();
  if(!reader)throw Error('Provider body missing');
  let size=0;const chunks=[];
  try{
    for(;;){
      const {done,value}=await reader.read();
      if(done)break;
      size+=value.length;
      if(size>max)throw Error('Provider JSON too large');
      chunks.push(Buffer.from(value));
    }
  }finally{await reader.cancel().catch(()=>{});}
  const raw=Buffer.concat(chunks).toString('utf8');
  return JSON.parse(raw);
}
function qualityStreams(stream){
  const items=stream?.stream?.qualities||stream?.qualities;
  if(!items||typeof items!=='object'||Array.isArray(items))return [];
  return Object.entries(items).slice(0,10).flatMap(([label,obj])=>{
    try{
      if(!obj||typeof obj.url!=='string')return [];
      const u=new URL(obj.url);
      if(u.protocol!=='https:'||u.username||u.password||u.port||u.hash)
        return [];
      const format=/\.m3u8$/i.test(u.pathname)||obj.type==='m3u8'
        ?'hls':/\.mp4$/i.test(u.pathname)||obj.type==='mp4'
          ?'mp4':null;
      if(!format)return [];
      const h=obj.headers && typeof obj.headers==='object'?obj.headers:{
        Referer:'https://filmboom.top/',Origin:'https://filmboom.top'
      };
      return [{url:u.href,format,headers:h,
        rank:parseInt(label,10)||0}];
    }catch{return [];}
  }).sort((a,b)=>b.rank-a.rank).slice(0,4);
}
export async function findNightFromCineStream({
  meta,mediaGateway,enabled=process.env.CINESTREAM_NIGHT_PILOT==='1',
  fetchImpl=fetch
}={}){
  if(!enabled||!mediaGateway||!isExactNight(meta))return [];
  try{
    const keyUrl=new URL(LOOKUP);
    keyUrl.searchParams.set('text',String(FILM.tmdb));
    const keyResponse=await fetchImpl(keyUrl.href,{
      headers:{Accept:'application/json'},redirect:'error',
      signal:AbortSignal.timeout(11000)
    });
    const {result:lookupKey}=await limitedJson(keyResponse,20000);
    if(typeof lookupKey!=='string'||lookupKey.length<3||
      lookupKey.length>320 || !/^[A-Za-z0-9+/=_-]+$/.test(lookupKey) ||
      lookupKey.includes('..'))return [];
    const response=await fetchImpl(VIDLINK+lookupKey,{
      headers:VIDLINK_HEADERS,redirect:'error',
      signal:AbortSignal.timeout(14000)
    });
    const json=await limitedJson(response);
    const candidates=qualityStreams(json);
    for(const item of candidates){
      const result=await mediaGateway.issueIfBytesVerified({
        url:item.url,format:item.format,headers:item.headers,
        rightsApproved:true,sourceVerified:true
      });
      if(result.source){
        return [{
          ...result.source,
          name:'CineStream • Vidlink — single-film pilot',
          provider:'CineStream',quality:0,subtitles:[],
          originalMovieTmdbId:FILM.tmdb,
          originalMovieImdbId:FILM.imdb,
          originalMovieYear:FILM.year
        }];
      }
    }
  }catch{/* Upstream unavailable or wrong content — never guess a link. */}
  return [];
}
