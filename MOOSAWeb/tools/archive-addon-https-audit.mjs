/**
 * External Stremio / Archive.org add-on independent source audit.
 * Manifest: dev.nebulawp.org/stremio/archive.org-addon/manifest.json
 *
 * Only known independently licensed Blender shorts (CC-BY; identity known).
 * A returned URL is NOT playable, rights-cleared or correctly named unless
 * separately matched against Archive.org source item metadata.
 * No video bytes, P2P, proxy, bypass, iframe, accounts or arbitrary URLs.
 */
import {validateExternalArchiveStream,parseArchiveFileUrl}
 from '../archive-stream-verifier.mjs';
import {fromWebAddons} from '../providers.mjs';

const manifestUrl='https://dev.nebulawp.org/stremio/archive.org-addon/manifest.json';
const base=new URL(manifestUrl);
const trustedOrigin=base.origin;
const FIXTURES=[
  {media:'movie',id:4000,title:'Big Buck Bunny',originalTitle:'Big Buck Bunny',
    year:2008,imdbId:'tt1254207'},
  {media:'movie',id:45745,title:'Sintel',originalTitle:'Sintel',
    year:2010,imdbId:'tt1727587'},
  {media:'movie',id:150004,title:'Tears of Steel',originalTitle:'Tears of Steel',
    year:2012,imdbId:'tt2285752'},
  {media:'movie',id:9761,title:'Elephants Dream',originalTitle:'Elephants Dream',
    year:2006,imdbId:'tt0807840'}
];
const json=async (url,timeout=12000)=>{
  const u=new URL(url);
  if(u.origin!==trustedOrigin || !u.pathname.startsWith('/stremio/archive.org-addon/'))
    throw Error('Unapproved add-on host/path');
  const r=await fetch(u,{headers:{Accept:'application/json'},
    redirect:'error',signal:AbortSignal.timeout(timeout)});
  const ct=r.headers.get('content-type')||'';
  if(!r.ok)return {status:r.status,body:null,contentType:ct};
  if(!ct.includes('json'))return {status:r.status,body:null,contentType:ct};
  if(Number(r.headers.get('content-length')||0)>350000)
    throw Error('Response too large');
  const body=await r.text();
  if(body.length>350000)throw Error('Response too large');
  return {status:r.status,body:JSON.parse(body),contentType:ct};
};
let manifest;
try{manifest=await json(manifestUrl,12500)}
catch(e){console.log('ARCHIVE_ADDON manifestFetchError='+String(e.name||'failed'));
 process.exit(0);}
const m=manifest.body;
console.log('ARCHIVE_ADDON_MANIFEST '+JSON.stringify({
  status:manifest.status,name:String(m?.name||'').slice(0,65),
  expectedAddonId:m?.id==='org.archive.clean',
  types:m?.types||[],supportsStream:m?.resources?.includes('stream')||false,
  imdbPrefix:m?.idPrefixes?.includes('tt')||false,
  contentType:manifest.contentType.slice(0,45)
}));
if(!m||m.id!=='org.archive.clean'||!m.resources?.includes('stream')||
   !m.types?.includes('movie')||!m.idPrefixes?.includes('tt')){
  console.log('ARCHIVE_ADDON_SUMMARY verdict=manifest_unexpected integrated=false');
  process.exit(0);
}
let verified=0,unverified=0,errors=0;
for(const fixture of FIXTURES){
  const api=new URL('stream/movie/'+fixture.imdbId+'.json',manifestUrl);
  let response;
  try{response=await json(api.href,13000)}
  catch(e){
    errors++;
    console.log('ARCHIVE_ADDON_FILM '+JSON.stringify({
      title:fixture.title,outcome:'network_'+String(e.name||'error'),
      confirmedVideoSources:0,decoderTested:false}));
    continue;
  }
  const streams=Array.isArray(response.body?.streams)?response.body.streams.slice(0,16):[];
  const results=[];
  for(const stream of streams){
    let host='invalid',extension='none',urlType='not_url';
    try{
      const u=new URL(stream?.url);
      host=u.hostname;extension=(u.pathname.match(/\.([a-z0-9]{2,6})$/i)||[])[1]||'none';
      urlType=parseArchiveFileUrl(stream?.url)?'archive_download':'other';
    }catch{}
    const rights=urlType==='archive_download' ?
      await validateExternalArchiveStream(stream.url,fixture) : null;
    if(rights)verified++;else unverified++;
    results.push({
      host,extension,urlType,declaredHeight:String(stream?.name||stream?.title||'').match(/\b(720|1080|2160)p\b/i)?.[1]||'unknown',
      archiveRightsAndExactFilm:!!rights,
      independentlyCheckedHeight:rights?.quality||null,
      // Important: actual video transport NOT requested.
      browserDecoded:false
    });
  }
  console.log('ARCHIVE_ADDON_FILM '+JSON.stringify({
    title:fixture.title,http:response.status,
    returned:streams.length,verifiedArchiveFiles:results.filter(x=>x.archiveRightsAndExactFilm).length,
    samples:results.slice(0,7),actualPlayback:false
  }));
  try {
    const viaMoosa=await fromWebAddons(fixture,{addons:[manifestUrl]});
    console.log('ARCHIVE_MOOSA_RESOLVER '+JSON.stringify({
      title:fixture.title,filteredLinks:viaMoosa.length,
      qualities:viaMoosa.map(s=>s.quality),
      filteredAndVerifiedArchiveOnly:viaMoosa.every(s=>parseArchiveFileUrl(s.url)),
      browserDecoded:false
    }));
  }catch(e){errors++;console.log('ARCHIVE_MOOSA_RESOLVER failed='+String(e.name||'error'))}
}
console.log('ARCHIVE_ADDON_SUMMARY '+JSON.stringify({
  filmsQueried:FIXTURES.length,independentlyVerifiedArchiveFiles:verified,
  rejectedOrUnverified:unverified,requestErrors:errors,
  actualDecodedVideoFrames:0,
  verdict:verified?'licensed_archive_candidates_require_browser_decode':
    'no_verified_archive_files_DO_NOT_CONNECT',
  commercialLibraryVerified:false,cdnStreamingProxyNeeded:false
}));
