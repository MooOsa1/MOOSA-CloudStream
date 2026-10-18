import http from 'node:http';
import {createHmac,timingSafeEqual} from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createBridge } from './bridge.mjs';
import { resolveWebSources, configuredAddons } from './providers.mjs';
import { getCuratedFilm, listCuratedFilms } from './free-film-library.mjs';
import {filmmakerSoundSources,FILMMAKER_LICENSE} from './sita-filmmaker-sound.mjs';
import { inspectRepositories, configuredEngine, singleFilmPilotStatus } from './cloudstream-engine.mjs';
import { matchVerifiedJsMedia, supportedJsFilmIds } from './js-engine-demo.mjs';
import { nativeJsEngine } from './native-js-catalog-runtime.mjs';
import {stremioProtocolManifest,resolveStremioProtocolMovie} from './cloudstream-stremio-interop.mjs';
import {collectStremioAddonSubtitles,officialSubtitleAddons} from './web-stremio-subtitles.mjs';
import {createMediaGateway} from './verified-media-gateway.mjs';
import {findNightFromCineStream,vidlinkPilotHosts} from './vidlink-original-night-pilot.mjs';
import {findVerifiedMultiFilmVidlink,discoverVidlinkInventory} from './vidlink-multifilm-candidates.mjs';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = path.join(ROOT, 'public');
const API_BASE = 'https://api.themoviedb.org/3';
// Render-only, password-gated preview. No user-facing production effect.
const PRIVATE_LAB=process.env.MOOSA_TWO_FILM_PRIVATE_LAB==='1';
const PRIVATE_FILMS=[
  {id:19,title:'Metropolis',original_title:'Metropolis',
    release_date:'1927-01-10',imdb_id:'tt0017136',
    media_type:'movie',vote_average:8.0,vote_count:1,
    overview:'Private test of the original film player. Edition and source rights unverified.'},
  {id:832,title:'M',original_title:'M',
    release_date:'1931-05-11',imdb_id:'tt0022100',
    media_type:'movie',vote_average:8.0,vote_count:1,
    overview:'Private test of the original film player. Edition and source rights unverified.'}
];
function privateCatalog(route){
  if(!PRIVATE_LAB)return null;
  if(route.endpoint==='/search/multi'){
    const search=String(route.params?.query||'').toLowerCase().trim();
    const match=PRIVATE_FILMS.filter(x=>
      search===x.title.toLowerCase() ||
      search===x.title.toLowerCase()+' '+x.release_date.slice(0,4));
    return {page:1,total_pages:1,total_results:match.length,results:match};
  }
  const detail=route.endpoint.match(/^\/movie\/(19|832)$/);
  if(detail){
    const film=PRIVATE_FILMS.find(x=>String(x.id)===detail[1]);
    return {...film,external_ids:{imdb_id:film.imdb_id},runtime:null};
  }
  if(route.endpoint==='/trending/movie/week'||
     route.endpoint==='/movie/top_rated' ||
     route.endpoint==='/discover/movie'){
    return {page:1,total_pages:1,total_results:2,results:PRIVATE_FILMS};
  }
  return {page:1,total_pages:1,total_results:0,results:[]};
}
function sameSecret(a,b){
  const left=Buffer.from(String(a||'')),right=Buffer.from(String(b||''));
  return left.length===right.length && timingSafeEqual(left,right);
}
function authenticatePrivateLab(req,res,pathname){
  if(!PRIVATE_LAB||pathname==='/healthz')return true;
  const secret=process.env.MOOSA_LAB_PASSWORD||'';
  const session=secret.length>=16?createHmac('sha256',secret)
    .update('moosa-private-video-session-v1').digest('base64url'):'';
  // Safari AVFoundation media Range requests can rely on same-site cookies
  // instead of separately caching the browser's HTTP Basic credentials.
  const cookies=String(req.headers.cookie||'').split(';').map(x=>x.trim());
  const cookie=cookies.find(x=>x.startsWith('__Host-moosa_lab='))||'';
  if(session && sameSecret(cookie.slice('__Host-moosa_lab='.length),session))
    return true;
  const basic=secret&&'Basic '+Buffer.from('moosa:'+secret).toString('base64');
  if(secret.length>=16&&sameSecret(req.headers.authorization,basic)){
    res.setHeader('Set-Cookie','__Host-moosa_lab='+session+
      '; Path=/; Max-Age=43200; Secure; HttpOnly; SameSite=Lax');
    return true;
  }
  res.writeHead(401,{
    'WWW-Authenticate':'Basic realm="MOOSA two-film private lab", charset="UTF-8"',
    'Cache-Control':'no-store',
    'Content-Type':'text/plain; charset=utf-8',
    'X-Content-Type-Options':'nosniff'
  });
  res.end('Private two-film test. Authentication required.');
  return false;
}
const CONFIG_PATH = process.env.STREAMS_FILE || 'data/streams.json';
const sourceInventoryCache=new Map();
async function memoizedSourceInventory(id){
  const key=String(id),now=Date.now();
  const existing=sourceInventoryCache.get(key);
  if(existing&&existing.until>now)return existing.value;
  const value=discoverVidlinkInventory({tmdbId:id});
  if(sourceInventoryCache.size>=80)
    sourceInventoryCache.delete(sourceInventoryCache.keys().next().value);
  sourceInventoryCache.set(key,{until:now+120000,value});
  return value;
}
const CACHE = new Map();
const bridge = createBridge();
const jsDemo = nativeJsEngine;
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
// Strict first-party playback boundary. External media files and TMDB
// artwork may load, but the site cannot embed hosted ad-player pages,
// execute marketing scripts, open plugin objects, or submit third-party forms.
// A source could still contain ads inside the audio/video itself; CSP cannot
// inspect or remove those bytes.
export const AD_FREE_WEB_CSP = [
  "default-src 'self'",
  "base-uri 'self'",
  "object-src 'none'",
  "frame-src 'none'",
  "frame-ancestors 'none'",
  "form-action 'self'",
  "script-src 'self' https://cdn.jsdelivr.net",
  "style-src 'self'",
  "img-src 'self' data: https:",
  "font-src 'self' data:",
  "connect-src 'self' https:",
  "media-src 'self' blob: https:",
  "worker-src 'self' blob:",
  "child-src 'self' blob:"
].join('; ');


export function json(res, status, data) {
  res.writeHead(status, {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
  res.end(JSON.stringify(data));
}
const err = (res, status, message) => json(res, status, {error: message});
// ChatGPT and other link previews may add harmless UTM tracking fields to
// links users tap. Read-only endpoints ignore these exact conventional keys,
// but do NOT accept custom providers, stream URLs, repeated identity fields,
// or unknown options. No tracking field is ever forwarded to a provider.
const LINK_TRACKING_KEYS = new Set([
  'utm_source','utm_medium','utm_campaign','utm_content','utm_term'
]);
function functionalParams(params){
  return [...params.keys()].filter(key=>!LINK_TRACKING_KEYS.has(key));
}
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
  if(PRIVATE_LAB)return privateCatalog(route);
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
async function handle(req,res,mediaGateway=null) {
  try {
    const earlyUrl = new URL(req.url||'/', 'http://localhost');
    if(!authenticatePrivateLab(req,res,earlyUrl.pathname))return;
    if(earlyUrl.pathname.startsWith('/api/bridge/'))return bridge.handle(req,res,earlyUrl);
    if(earlyUrl.pathname.startsWith('/api/media/')){
      if(!mediaGateway)return err(res,404,'No operator-approved media gateway configured');
      return mediaGateway.handle(req,res,earlyUrl);
    }
    if(req.method !== 'GET' && req.method !== 'HEAD')return err(res,405,'Method not allowed');
    const url=new URL(req.url||'/', 'http://localhost');
    if(url.pathname==='/healthz')return json(res,200,{status:'ok'});
    // CineStream supports Stremio-compatible HTTPS add-ons through its
    // configurable Settings.AddonType.HTTPS provider interface.
    // This *exports* licensed MOOSA sources through the compatible HTTP
    // transport. It does NOT run CineStream / StreamPlay Android plugins.
    if(url.pathname==='/api/stremio/manifest.json'){
      if(url.search)return err(res,400,'Stremio manifest takes no parameters');
      return json(res,200,stremioProtocolManifest());
    }
    if(url.pathname.startsWith('/api/stremio/stream/')){
      if(url.search)return err(res,400,'Stremio stream takes no parameters');
      const match=url.pathname.match(/^\/api\/stremio\/stream\/movie\/(tt\d{5,12})\.json$/);
      if(!match)return err(res,404,'Unsupported Stremio stream route');
      return json(res,200,await resolveStremioProtocolMovie(match[1]));
    }
    if(PRIVATE_LAB&&url.pathname==='/api/source-discovery'){
      // Authenticated private inventory for any numeric TMDB movie ID.
      // Enumerate provider availability, NOT video URLs or approved rights.
      const keys=functionalParams(url.searchParams);
      if(keys.length!==2||keys[0]!=='media'||keys[1]!=='id'||
         url.searchParams.get('media')!=='movie')
        return err(res,400,'Require only media=movie and id=TMDB number');
      const raw=url.searchParams.get('id');
      if(!/^[1-9]\\d{0,9}$/.test(raw||''))
        return err(res,400,'Invalid movie ID');
      const result=await memoizedSourceInventory(Number(raw));
      return json(res,200,result);
    }
    if(PRIVATE_LAB&&url.pathname==='/api/lab-status')
      return json(res,200,{films:twoFilmLabStatus,privateLab:true,
        note:'Backend token checks only; use the player to validate video decoding.'});
    if(url.pathname==='/api/config')return json(res,200,{
      catalogReady:PRIVATE_LAB||!!apiKey(),playerDemo:true,sourcePolicy:'authorized_only',
      automaticSources:true,cloudstreamEngineConfigured:!!configuredEngine(),
      // Inform the browser about a strictly operator-reviewed source pilot
      // so only those catalog titles receive a longer Play lookup window.
      verifiedCineStreamPilotReady:!!mediaGateway &&
        process.env.CINESTREAM_MULTIFILM_PILOT==='1' &&
        process.env.CINESTREAM_MULTIFILM_RIGHTS_APPROVED==='1'
    });
    if(url.pathname==='/api/web-native/status'){
      if(functionalParams(url.searchParams).length)
        return err(res,400,'No arbitrary provider parameters');
      return json(res,200,{
        engine:'node_js',runtime:'server_native',pairingRequired:false,
        catalogIntegrated:true,
        httpStremioSubtitles:{
          supported:true,protocol:'stremio_subtitles_http',
          media:['movie','series'],languages:['ar','en'],
          configuredAddonCount:configuredAddons().length,
          includedOfficialAddon:'OpenSubtitles v3',
          officialManifest:'https://opensubtitles-v3.strem.io/manifest.json',
          active:true,
          note:'Official Stremio subtitle addon is enabled without a key. Individual subtitles may be unavailable, mistimed or inaccessible; active is not proof of correct subtitles for each title.'
        },
        activeReviewedProviders:[{
          id:'commons-authorized',protocol:'nuvio_getStreams',
          media:'licensed_movies_only',approvedFilmIds:supportedJsFilmIds()
        }],
        cloudstreamCs3Supported:false,
        cineStreamAndStreamPlayOriginals:false,
        note:'Native JS integration in /api/resolve supports only independently licensed, verified Commons sources. Original CloudStream Kotlin extensions are not running on this host.'
      });
    }
    if(url.pathname==='/api/js-engine/demo'){
      // A single ID from the server-owned open-film allowlist. Never accept
      // provider code, custom titles, CDN URLs or rights assertions.
      const keys=[...url.searchParams.keys()];
      if(keys.some(k=>k!=='id')||keys.length>1)
        return err(res,400,'Only one approved film ID is allowed');
      const filmId=url.searchParams.get('id')||'big-buck-bunny';
      const result=await jsDemo.load(filmId);
      if(!result)return err(res,400,'Film not approved for JS engine');
      return json(res,200,result);
    }
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
      let sources=film.sources;
      let sourceEngine='commons_metadata';
      const authenticAudio=filmmakerSoundSources(film);
      if(authenticAudio.length){
        sources=authenticAudio;
        sourceEngine='filmmaker_official_archive_h264_aac';
      }else if(film.available){
        // Existing open-licensed sources remain the fallback. A slow or
        // unavailable external JS worker must not stall the phone's player.
        let timer;
        try{
          const verified=await Promise.race([
            jsDemo.load(requested),
            new Promise(resolve=>{timer=setTimeout(()=>resolve(null),1800);})
          ]);
          const approved=matchVerifiedJsMedia(film,verified);
          if(approved?.length){
            sources=approved;
            sourceEngine='reviewed_js_worker_preflight';
          }
        }finally{clearTimeout(timer);}
      }
      return json(res,200,{
        ...film,sources,sourceEngine,
        copyright:authenticAudio.length?FILMMAKER_LICENSE:
          film.license||'No verified source available',
        soundtrackNote:authenticAudio.length?
          'The official MP4 includes licensed music; the Commons WebM fallback is SILENT.':null,
        note:'Independent open-license sample, not a source for the selected commercial movie.',
        count:sources.length
      });
    }
    if(url.pathname==='/api/cloudstream/pilot-status') {
      if(url.search)return err(res,400,'No query arguments accepted');
      const pilot=singleFilmPilotStatus();
      const engine=configuredEngine();
      // The original JVM process lives on a separately deployed Render
      // service. Expose only its live safe health and extraction count;
      // no signed video URLs, request tokens or logs leave this route.
      let remote={connected:false,extraction:'not_connected',candidateCount:0,
        distributionEnabled:false};
      if(engine && pilot.enabled){
        try{
          const response=await fetch(engine.url+'/healthz',{
            redirect:'error',headers:{Accept:'application/json'},
            signal:AbortSignal.timeout(4500)
          });
          if(response.ok && Number(response.headers.get('content-length')||0)<12000){
            const data=await response.json();
            if(data?.status==='ok' && data.engine==='cinestream_original_jvm'){
              const candidateCount=Number(data.extractionProbe?.candidateCount);
              remote={
                connected:true,
                extraction:String(data.extractionProbe?.result||
                  data.extractionProbe?.state||'not_started').slice(0,50),
                candidateCount:Number.isInteger(candidateCount)&&candidateCount>=0?
                  Math.min(candidateCount,100):0,
                distributionEnabled:data.distributionEnabled===true
              };
            }
          }
        }catch{/* A sleeping/free JVM service must not break the main site. */}
      }
      return json(res,200,{...pilot,remote});
    }
    if(url.pathname==='/api/cloudstream/repositories') {
      const results=await inspectRepositories();
      return json(res,200,{
        engine:configuredEngine()?'configured':'not_configured',
        supportsOriginalPlugins:!!configuredEngine(),
        caveat:configuredEngine()?
          'Configured remote runtime has not been verified. Provider availability depends on external compatible execution and browser playback.':
          'Repository indexes only. Android .cs3 plugins are not executed by the Node server.',
        repositories:results,
        httpStremioCompatibility:{
          manifest:'/api/stremio/manifest.json',
          streaming:'/api/stremio/stream/movie/{imdbId}.json',
          status:'reviewed_licensed_movie_sources_only',
          cineStreamAddonTransportCompatible:true,
          originalAndroidPluginsExecuted:false
        }
      });
    }
    if(url.pathname==='/api/stremio-subtitles'){
      const allowed=new Set(['media','id','season','episode']);
      const keys=functionalParams(url.searchParams);
      if(keys.some(k=>!allowed.has(k)) ||
         keys.length!==new Set(keys).size)
        return err(res,400,'Unsupported subtitle parameters');
      const media=url.searchParams.get('media');
      const id=cleanId(url.searchParams.get('id'));
      const episodeRequested=url.searchParams.has('season')||url.searchParams.has('episode');
      const season=Number(url.searchParams.get('season'));
      const episode=Number(url.searchParams.get('episode'));
      if(!id || !mediaTypes.has(media) ||
         (media==='tv' && (!url.searchParams.has('season') || !url.searchParams.has('episode') ||
           !Number.isInteger(season) || season<0 || season>100 ||
           !Number.isInteger(episode) || episode<1 || episode>1000)) ||
         (media==='movie' && episodeRequested))
        return err(res,400,'Invalid movie or episode');
      const addons=officialSubtitleAddons(configuredAddons());
      try {
        const details=await tmdbGet({endpoint:'/'+media+'/'+id,
          params:{language:'en-US',append_to_response:'external_ids'}});
        const meta={
          media,id:Number(id),
          title:details.title||details.name||'',
          originalTitle:details.original_title||details.original_name||'',
          year:Number(String(details.release_date||details.first_air_date||'').slice(0,4))||null,
          imdbId:details.imdb_id||details.external_ids?.imdb_id||null,
          ...(media==='tv'?{season,episode}:{})
        };
        const subtitles=await collectStremioAddonSubtitles(meta,{addons});
        return json(res,200,{subtitles,active:true,count:subtitles.length});
      }catch(e){
        return err(res,e.status||502,'Subtitle lookup temporarily unavailable');
      }
    }
    if(url.pathname==='/api/resolve'){
      // The default response can return verified pilot sources immediately.
      // A second same-title request asks for ordinary providers in the
      // background without delaying first video playback.
      const phase=url.searchParams.get('phase');
      if(phase!==null&&phase!=='extras')
        return err(res,400,'Invalid source discovery phase');
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
        // Start the ordinary source search in parallel. In an explicitly
        // approved CineStream pilot, do NOT wait for unrelated slow catalog
        // providers once multiple byte-verified same-title sources are available.
        // If the pilot cannot deliver, continue to await the usual adapters.
        // Keep the public/disabled-pilot behavior unchanged.
        // Source expansion reuses the normal resolver's per-title in-flight
        // cache; it never runs unreviewed CineStream protocol requests.
        if(phase==='extras'){
          const sources=await resolveWebSources(meta);
          return json(res,200,{
            sources,providerMode:'server',count:sources.length,
            cloudstreamEngineConfigured:!!configuredEngine()
          });
        }
        const usualPending=resolveWebSources(meta).catch(()=>[]);
        const [cinestream,multifilm]=await Promise.all([
          findNightFromCineStream({meta,mediaGateway}),
          findVerifiedMultiFilmVidlink({meta,mediaGateway})
        ]);
        const vetted=[...cinestream,...multifilm];
        const extrasAvailable=vetted.length>=2;
        const usual=extrasAvailable?[]:await usualPending;
        const sources=[...vetted,...usual.filter(x=>
          !vetted.some(y=>y.url===x.url))];
        return json(res,200,{
          sources,providerMode:'server',count:sources.length,extrasAvailable,
          cloudstreamEngineConfigured:!!configuredEngine()
        });
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
    res.writeHead(200,{
      'Content-Type':mime[ext],
      'Cache-Control':ext==='.html'?'no-store':'public, max-age=120',
      'X-Content-Type-Options':'nosniff',
      ...(ext==='.html'?{
        'Content-Security-Policy':AD_FREE_WEB_CSP,
        'Permissions-Policy':'camera=(), microphone=(), geolocation=(), payment=()'
      }:{})
    });
    res.end(req.method==='HEAD'?undefined:data);
  }catch(e){err(res,500,'Server error');}
}
export function createServer({mediaGateway=null}={}){
  const hosts=vidlinkPilotHosts();
  // One-film only, operator controlled, and disabled by default.
  const gateway=mediaGateway || (
    (process.env.CINESTREAM_NIGHT_PILOT==='1' ||
      process.env.CINESTREAM_MULTIFILM_PILOT==='1') && hosts.length
      ?createMediaGateway({
          origin:PRIVATE_LAB?'https://moosa-two-film-lab.onrender.com':
            'https://moosa-web.onrender.com',
          allowedHosts:hosts,ttlMs:3*60*60_000
        }):null
  );
  return http.createServer((req,res)=>handle(req,res,gateway));
}
// Check actual live Render backend once per boot, without recording any
// third-party video URL, signed source token, cookie or request credential.
// A verified source response is NOT proof of browser video frame decoding.
const twoFilmLabStatus={};
async function probePrivateFilmSources(port){
  const auth='Basic '+Buffer.from('moosa:'+process.env.MOOSA_LAB_PASSWORD)
    .toString('base64');
  for(const id of [19,832]){
    const result={tmdbId:id,checkedAt:new Date().toISOString(),
      backendResponsive:false,verifiedSources:0,playbackNotYetVerified:true};
    try{
      const response=await fetch('http://127.0.0.1:'+port+
        '/api/resolve?media=movie&id='+id,{
        headers:{Authorization:auth,Accept:'application/json'},
        signal:AbortSignal.timeout(55000)
      });
      result.backendResponsive=response.ok;
      const body=await response.json();
      const verified=Array.isArray(body.sources)?
        body.sources.filter(x=>x.gatewayIssued===true):[];
      result.verifiedSources=verified.length;
      result.httpStatus=response.status;
      result.gatewayRanges=[];
      for(const source of verified.slice(0,3)){
        // HTTP Range must work on the same server-issued capability the
        // browser will request. Never include URL, token or query in logs.
        const requestPath=new URL(source.url).pathname;
        try{
          const range=await fetch('http://127.0.0.1:'+port+requestPath,{
            headers:{Authorization:auth,Range:'bytes=0-8191'},
            signal:AbortSignal.timeout(17000)
          });
          const mime=range.headers.get('content-type')||'';
          const length=Number(range.headers.get('content-length')||0);
          const prefix=Buffer.from(await range.arrayBuffer());
          const looksMp4=prefix.length>=12&&
            ['ftyp','styp','moof'].includes(prefix.toString('latin1',4,8));
          result.gatewayRanges.push({
            httpStatus:range.status,partialContent:range.status===206,
            contentTypeVideo:mime.includes('video/mp4')||mime.includes('octet-stream'),
            contentRange:!!range.headers.get('content-range'),
            size:prefix.length,looksMp4
          });
          if(length>2_000_000)break;
        }catch{
          result.gatewayRanges.push({error:'range_unavailable'});
        }
      }
      result.result=result.verifiedSources>0?'verified_capabilities_ready':
        'no_verified_capabilities';
    }catch(e){result.result='network_or_timeout';}
    twoFilmLabStatus[id]=result;
    console.log('MOOSA_PRIVATE_TWO_FILM_BACKEND_PROBE '+JSON.stringify(result));
  }
}
if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const PORT = Number(process.env.PORT||3000);
  createServer().listen(PORT,'0.0.0.0',()=>{
    console.log(`MOOSA Web on http://localhost:${PORT}`);
    if(PRIVATE_LAB)void probePrivateFilmSources(PORT).catch(()=>
      console.log('MOOSA_PRIVATE_TWO_FILM_PROBE_UNAVAILABLE'));
  });
}
