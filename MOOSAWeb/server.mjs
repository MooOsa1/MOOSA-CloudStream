import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createBridge } from './bridge.mjs';
import { resolveWebSources } from './providers.mjs';
import { getCuratedFilm, listCuratedFilms } from './free-film-library.mjs';
import { inspectRepositories, configuredEngine } from './cloudstream-engine.mjs';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = path.join(ROOT, 'public');
const API_BASE = 'https://api.themoviedb.org/3';
const CONFIG_PATH = process.env.STREAMS_FILE || 'data/streams.json';
const CACHE = new Map();
const bridge = createBridge();
const CACHE_TTL = 5 * 60_000;
const CACHE_MAX = 160;
const mediaTypes = new Set(['movie', 'tv']);
const sortValues = new Set(['popularity.desc','vote_average.desc','vote_count.desc','date.desc']);
const countries = new Set(['', 'IN', 'US', 'GB', 'KR', 'JP', 'TR', 'ES', 'FR', 'EG', 'SA']);
const genres = {
  movie: new Set([28,12,16,35,80,99,18,10751,14,36,27,10402,9648,10749,878,10770,53,10752,37]),
  tv: new Set([10759,16,35,80,99,18,10751,10762,9648,10764,10765,10766,10767,10768,37])
};
const mime = {'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.svg':'image/svg+xml','.png':'image/png','.ico':'image/x-icon','.json':'application/json; charset=utf-8'};

export function json(res, status, data) {
  res.writeHead(status, {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
  res.end(JSON.stringify(data));
}
const err = (res, status, message) => json(res, status, {error: message});
function clampPage(value) {const n = Number(value); return Number.isInteger(n) ? Math.min(500,Math.max(1,n)) : 1;}
function mediaType(value) { return mediaTypes.has(value) ? value : 'movie'; }
function cleanId(value) {return /^\d{1,10}$/.test(value || '') && Number(value) > 0 ? value : null;}
function apiKey() {return process.env.TMDB_READ_ACCESS_TOKEN || process.env.TMDB_API_KEY || '';}

export function tmdbRoute(url) {
  const {pathname,searchParams:p} = url;
  const page = String(clampPage(p.get('page')));
  if(pathname === '/api/catalog') {
    const today=new Date();
    const threeYearsAgo=new Date(Date.UTC(today.getUTCFullYear(),today.getUTCMonth()-36,1));
    const name = p.get('section') || 'trending_movies';
    const routes = {
      trending_movies:['/trending/movie/week',{}],
      trending_series:['/trending/tv/week',{}],
      top_movies:['/movie/top_rated',{}],
      top_series:['/tv/top_rated',{}],
      indian_trending:['/discover/movie',{with_origin_country:'IN',sort_by:'popularity.desc','vote_count.gte':'20','vote_average.gte':'6','without_genres':'99','primary_release_date.gte':threeYearsAgo.toISOString().slice(0,10),'primary_release_date.lte':today.toISOString().slice(0,10)}],
      indian_top:['/discover/movie',{with_origin_country:'IN',sort_by:'vote_average.desc','vote_count.gte':'200','vote_average.gte':'7','without_genres':'99'}]
    };
    const entry = routes[name]; if(!entry) return null;
    return {endpoint:entry[0],params:{...entry[1],page,language:'en-US',include_adult:'false'}};
  }
  if(pathname === '/api/discover') {
    const type = mediaType(p.get('media'));
    const sort = sortValues.has(p.get('sort')) ? p.get('sort') : 'popularity.desc';
    const params = {page, language:'en-US', include_adult:'false', sort_by:sort === 'date.desc' ? (type === 'tv' ? 'first_air_date.desc':'primary_release_date.desc') : sort};
    const genre = Number(p.get('genre'));
    if(genres[type].has(genre)) params.with_genres = String(genre);
    const country = p.get('country') || '';
    if(countries.has(country) && country) params.with_origin_country = country;
    if(sort === 'vote_average.desc') params['vote_count.gte'] = type === 'tv' ? '50':'100';
    if(sort === 'date.desc') params[type === 'tv' ? 'first_air_date.lte':'primary_release_date.lte'] = new Date().toISOString().slice(0,10);
    return {endpoint:`/discover/${type}`,params};
  }
  if(pathname === '/api/search') {
    const query = (p.get('q') || '').trim().slice(0,100);
    if(query.length < 2) return {invalid:'اكتب حرفين على الأقل للبحث'};
    return {endpoint:'/search/multi',params:{query,page,language:'en-US',include_adult:'false'}};
  }
  if(pathname === '/api/episodes') {
    const id=cleanId(p.get('id'));
    const raw=p.get('season');
    const season=Number(raw);
    if(!id || raw===null || !Number.isInteger(season) || season<0 || season>100)
      return {invalid:'الموسم أو معرّف المسلسل غير صالح'};
    return {endpoint:`/tv/${id}/season/${season}`,params:{language:'ar-SA'}};
  }
  if(pathname === '/api/details') {
    const type = mediaType(p.get('media'));
    const id=cleanId(p.get('id'));
    if(!id) return {invalid:'معرّف الفيلم غير صالح'};
    return {endpoint:`/${type}/${id}`,params:{language:'en-US',append_to_response:'videos,credits'}};
  }
  return null;
}
async function tmdbGet(route) {
  const key = apiKey();
  if(!key) throw Object.assign(new Error('TMDB_NOT_CONFIGURED'),{status:503});
  const u = new URL(API_BASE+route.endpoint);
  for(const [k,v] of Object.entries(route.params)) u.searchParams.set(k,String(v));
  const headers = {'Accept':'application/json'};
  if(process.env.TMDB_READ_ACCESS_TOKEN) headers.Authorization=`Bearer ${process.env.TMDB_READ_ACCESS_TOKEN}`;
  else u.searchParams.set('api_key',process.env.TMDB_API_KEY);
  const cacheKey = u.pathname+'?'+u.searchParams.toString();
  const hit=CACHE.get(cacheKey);
  if(hit && hit.until>Date.now())return hit.data;
  const response=await fetch(u,{headers,signal:AbortSignal.timeout(10000)});
  if(!response.ok)throw Object.assign(new Error('TMDB_HTTP_'+response.status),{status:response.status===429?429:502});
  const body=await response.json();
  if(CACHE.size>=CACHE_MAX){const first=CACHE.keys().next().value;CACHE.delete(first);}
  CACHE.set(cacheKey,{until:Date.now()+CACHE_TTL,data:body});
  return body;
}
async function readSources(media,id,season=null,episode=null) {
  const streamFile=path.resolve(ROOT,CONFIG_PATH);
  const data=JSON.parse(await readFile(streamFile,'utf8'));
  // An episode must match its exact season/episode key; never reuse a
  // generic show-wide link and accidentally play another episode.
  const key=media==='tv' && season!==null && episode!==null
    ? `tv:${id}:${season}:${episode}` : media+':'+id;
  const arr=data[key];
  if(!Array.isArray(arr))return [];
  return arr.filter(x=>x && typeof x.url==='string' && /^https:\/\//i.test(x.url))
    .map(x=>({name:String(x.name||'Authorized source').slice(0,80),url:x.url,subtitles:Array.isArray(x.subtitles)?x.subtitles.filter(t=>t && /^https:\/\//i.test(t.url||'')).map(t=>({label:String(t.label||'Subtitle'),language:t.language==='ar'?'ar':'en',source:String(t.source||'Custom'),url:t.url})):[]})).slice(0,12);
}
async function handle(req,res) {
  try {
    const earlyUrl = new URL(req.url||'/', 'http://localhost');
    if(earlyUrl.pathname.startsWith('/api/bridge/'))return bridge.handle(req,res,earlyUrl);
    if(req.method !== 'GET' && req.method !== 'HEAD')return err(res,405,'Method not allowed');
    const url=new URL(req.url||'/', 'http://localhost');
    if(url.pathname==='/healthz')return json(res,200,{status:'ok'});
    if(url.pathname==='/api/config')return json(res,200,{catalogReady:!!apiKey(),playerDemo:true,sourcePolicy:'authorized_only',automaticSources:true,cloudstreamEngineConfigured:!!configuredEngine()});
    if(url.pathname==='/api/free-library'){
      const films=await listCuratedFilms();
      return json(res,200,{
        films,count:films.length,
        note:'Only movies with discoverable exact-name CC/public-domain full-video sources are listed.'
      });
    }
    if(url.pathname==='/api/free-film'){
      const requested=url.searchParams.get('id')||'big-buck-bunny';
      if(!/^[a-z0-9-]{1,50}$/.test(requested))return err(res,400,'Invalid film');
      const film=await getCuratedFilm(requested);
      if(!film)return err(res,404,'Unknown open-licensed film');
      return json(res,200,{
        ...film,
        copyright:film.license||'No verified source available',
        note:'Independent open-license sample, not a source for the selected commercial movie.',
        count:film.sources.length
      });
    }
    if(url.pathname==='/api/cloudstream/repositories') {
      const results=await inspectRepositories();
      return json(res,200,{
        engine:configuredEngine()?'configured':'not_configured',
        supportsOriginalPlugins:!!configuredEngine(),
        caveat:configuredEngine()?
          'Configured remote runtime has not been verified. Provider availability depends on external compatible execution and browser playback.':
          'Repository indexes only. Android .cs3 plugins are not executed by the Node server.',
        repositories:results
      });
    }
    if(url.pathname==='/api/resolve'){
      const media=url.searchParams.get('media');
      const id=cleanId(url.searchParams.get('id'));
      const episodeRequested=url.searchParams.has('season')||url.searchParams.has('episode');
      const season=Number(url.searchParams.get('season'));
      const episode=Number(url.searchParams.get('episode'));
      if(!id || !mediaTypes.has(media) ||
        (media==='tv' && (!url.searchParams.has('season') || !url.searchParams.has('episode') ||
           !Number.isInteger(season) || season<0 || season>100 ||
           !Number.isInteger(episode) || episode<1 || episode>1000)) ||
        (media==='movie' && episodeRequested)) return err(res,400,'Invalid movie or episode');
      try {
        const details=await tmdbGet({endpoint:'/'+media+'/'+id,
          params:{language:'en-US',append_to_response:'external_ids'}});
        const meta={
          media,id:Number(id),title:details.title||details.name||'',
          originalTitle:details.original_title||details.original_name||'',
          year:Number(String(details.release_date||details.first_air_date||'').slice(0,4))||null,
          imdbId:details.imdb_id||details.external_ids?.imdb_id||null,
          ...(media==='tv'?{season,episode}:{})
        };
        const sources=await resolveWebSources(meta);
        return json(res,200,{sources,providerMode:'server',count:sources.length,cloudstreamEngineConfigured:!!configuredEngine()});
      } catch(e) {
        return err(res,e.status||502,e.message==='TMDB_NOT_CONFIGURED'?
          'Configure TMDB for automatic source matching':'Source lookup temporarily unavailable');
      }
    }
    if(url.pathname==='/api/streams'){
      const media=mediaType(url.searchParams.get('media'));
      const id=cleanId(url.searchParams.get('id'));
      if(!id)return err(res,400,'Invalid ID');
      const hasEpisode=url.searchParams.has('episode') || url.searchParams.has('season');
      const season=Number(url.searchParams.get('season'));
      const episode=Number(url.searchParams.get('episode'));
      if(hasEpisode && (media!=='tv' ||
        !url.searchParams.has('season') || !url.searchParams.has('episode') ||
        !Number.isInteger(season) || season<0 || season>100 ||
        !Number.isInteger(episode) || episode<1 || episode>1000))
        return err(res,400,'Invalid episode');
      return json(res,200,{sources:await readSources(media,id,
        hasEpisode?season:null,hasEpisode?episode:null)});
    }
    if(url.pathname.startsWith('/api/')){
      const route=tmdbRoute(url);
      if(!route)return err(res,404,'Unknown API endpoint');
      if(route.invalid)return err(res,400,route.invalid);
      try{return json(res,200,await tmdbGet(route));}
      catch(e){return err(res,e.status||502,e.message==='TMDB_NOT_CONFIGURED'?'Catalog requires TMDB credentials':'Could not reach TMDB');}
    }
    let target=url.pathname==='/'?'index.html':decodeURIComponent(url.pathname).replace(/^\/+/, '');
    if(target.includes('..') || target.split('/').some(p=>p.startsWith('.')))return err(res,403,'Forbidden');
    let filePath=path.resolve(WEB_ROOT,target);
    if(!filePath.startsWith(WEB_ROOT+path.sep) && filePath!==WEB_ROOT)return err(res,403,'Forbidden');
    const ext=path.extname(filePath);
    if(!mime[ext])return err(res,404,'Not found');
    let data;
    try{data=await readFile(filePath);}catch{return err(res,404,'Not found');}
    res.writeHead(200,{'Content-Type':mime[ext],'Cache-Control':ext==='.html'?'no-store':'public, max-age=120','X-Content-Type-Options':'nosniff'});
    res.end(req.method==='HEAD'?undefined:data);
  }catch(e){err(res,500,'Server error');}
}
export function createServer(){return http.createServer(handle);}
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const PORT = Number(process.env.PORT||3000);
  createServer().listen(PORT,'0.0.0.0',()=>console.log(`MOOSA Web on http://localhost:${PORT}`));
}
