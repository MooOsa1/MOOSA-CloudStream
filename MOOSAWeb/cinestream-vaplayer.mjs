/**
 * Direct Node.js port of:
 * CineStreamExtractors.invokeVaPlayer (CineStream upstream Kotlin).
 *
 * Only enabled if the operator explicitly supplies approved media hosts.
 * No Android device, WebView cookies, token bypass or media proxy.
 * An HLS master URL does not become a browser source until a real first
 * segment has passed the strict HLS probe.
 */
import {probeHlsFirstSegment} from './real-hls-segment-probe.mjs';

const API='https://streamdata.vaplayer.ru/api.php';
const REFERER='https://nextgencloudfabric.com/';
function words(s){
  return String(s||'').normalize('NFKD').toLowerCase()
    .replace(/[\u0300-\u036f]/g,'').replace(/[^a-z0-9]+/g,' ').trim()
    .replace(/\s+/g,' ');
}
export function configuredCineStreamHosts(
  value=process.env.CINESTREAM_ALLOWED_MEDIA_HOSTS||''
){
  if(typeof value!=='string')return [];
  return [...new Set(value.split(',').map(x=>x.trim().toLowerCase()))]
    .filter(x=>/^(?!-)[a-z0-9.-]{5,130}$/.test(x)&&
      !x.includes('localhost')&&!x.endsWith('.local')&&!/^\d/.test(x))
    .slice(0,15);
}
export function cineStreamVaPlayerEnabled(
  value=process.env.CINESTREAM_VAPLAYER_ENABLED||''
){
  return value==='1';
}
export function exactVaPlayerMovie(meta,response){
  const id=String(meta?.imdbId||'');
  if(meta?.media!=='movie'||!/^tt\d{5,12}$/.test(id) ||
    !meta.originalTitle||!Number.isInteger(Number(meta.year)) ||
    Number(meta.year)<1888)return false;
  if(String(response?.status_code)!=='200'||response.data?.imdb_id!==id)return false;
  const real=words(response.data?.title),wanted=words(meta.originalTitle);
  const year=String(meta.year);
  if(!real.startsWith(wanted+' ') && real!==wanted)return false;
  return new RegExp('(?:^|\\s)'+year+'(?:\\s|$)').test(real);
}
export async function fromCineStreamVaPlayer(meta,{
  fetchImpl=fetch,enabled=cineStreamVaPlayerEnabled(),
  mediaHosts=configuredCineStreamHosts(),timeoutMs=3500
}={}){
  if(!enabled||!mediaHosts.length||meta?.media!=='movie' ||
    !/^tt\d{5,12}$/.test(meta.imdbId||''))return [];
  try {
    const req=new URL(API);
    req.searchParams.set('imdb',meta.imdbId);
    req.searchParams.set('type','movie');
    const response=await fetchImpl(req.href,{
      method:'GET',headers:{Accept:'application/json',Referer:REFERER},
      redirect:'error',signal:AbortSignal.timeout(timeoutMs)
    });
    if(!response.ok || Number(response.headers.get('content-length')||0)>100000)
      return [];
    const txt=await response.text();
    if(txt.length>100000)return [];
    const data=JSON.parse(txt);
    if(!exactVaPlayerMovie(meta,data))return [];
    const candidates=Array.isArray(data.data?.stream_urls)?
      [...new Set(data.data.stream_urls)].slice(0,3):[];
    const results=await Promise.allSettled(candidates.map(async(url,i)=>{
      const probe=await probeHlsFirstSegment(url,{
        fetchImpl,allowedHosts:mediaHosts,timeoutMs
      });
      if(!probe.ok)return null;
      return {
        url,name:'CineStream • VaPlayer '+(i+1),
        quality:probe.quality,verifiedSegment:true,subtitles:[]
      };
    }));
    return results.flatMap(x=>x.status==='fulfilled'&&x.value?[x.value]:[]);
  }catch{return [];}
}
