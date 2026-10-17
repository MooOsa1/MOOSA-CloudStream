/**
 * MOOSA Web read-only audit, version 2:
 * - Sintel (TMDB movie 45745) is independently released CC-BY.
 * - NO credentials, privileged Referer spoofing, CDN proxying, media GET
 *   or player iframe. Do not claim content rights from a TMDB match.
 * - A missing TMDB key makes lookup-based providers INCONCLUSIVE.
 * - skipProxy metadata is NOT proof of browser playability.
 * - Third-party SDK executes only in an isolated GitHub Actions runner.
 *
 * No raw URLs, signed query tokens, cookies or credentials in logs.
 */
import VylaSDK from '@vyla-entertainment/sdk';
import {setTimeout as sleep} from 'node:timers/promises';

const LICENSED_MOVIE_TMDB_ID='45745';
const sdk=new VylaSDK({tmdbApiKey:''});
const FAVOR=[
  '111477','3eyedraven','flaxmovies','fsonline','movy','movienight',
  'peestream','purstream','streamaggregator','vidnest-sub','vixsrc',
  'cinemaarmy','dulo','vidgod','vidfast','lookmovie','1embed'
];
const sources=sdk.getSources(true);
const arr=Array.isArray(sources)?sources:[];
const candidate=arr.filter(x=>x&&FAVOR.includes(x.key)&&x.disabled!==true)
  .sort((a,b)=>FAVOR.indexOf(a.key)-FAVOR.indexOf(b.key)).slice(0,15);
console.log('VYLA_AUDIT_SETUP sdkFound='+Boolean(VylaSDK)+
  ' active='+arr.length+' candidateMovieSources='+candidate.length+
  ' tmdbKeySupplied=false sourceStatus=untested_not_verified');

function mediaUrl(raw){
  if(typeof raw!=='string'||raw.length>3500||/\s/.test(raw))return null;
  try{
    const u=new URL(raw);
    if(u.protocol!=='https:'||u.username||u.password||u.port||u.hash||
       !u.hostname.includes('.')||u.hostname.includes(':')||
       /^(?:localhost|[^.]+\.localhost|[^.]+\.local|127\.|10\.|192\.168\.|169\.254\.|0\.|172\.(?:1[6-9]|2\d|3[01])\.)/i.test(u.hostname))
      return null;
    const isDirect=/\.(?:m3u8|mp4|m4v|webm|mov)$/i.test(u.pathname);
    return {host:u.hostname,mediaSuffix:isDirect,
      extension:(u.pathname.match(/\.([a-z0-9]{2,6})$/i)||[])[1]||'none'};
  }catch{return null;}
}
function recordLinks(result){
  const rows=[];
  const put=v=>{
    if(typeof v==='string')rows.push({url:v});
    else if(v&&typeof v==='object'&&(v.url||v.link||v.file||v.stream_url))
      rows.push({...v,url:v.url||v.link||v.file||v.stream_url});
  };
  if(Array.isArray(result))result.forEach(put);
  for(const field of [result?.allUrls,result?.streams,result?.sources,
      result?.urls,result?.data?.sources,result?.data?.streams]){
    if(Array.isArray(field))field.forEach(put);
  }
  put(result);
  return rows.slice(0,30);
}
function providerRules(source,urlHost){
  const rules=Array.isArray(source.cdnHeaders)?source.cdnHeaders:[];
  let matches=false,ambiguous=false;
  for(const rule of rules){
    try{
      if(rule.pattern instanceof RegExp && rule.pattern.test(urlHost))matches=true;
      else if(!(rule.pattern instanceof RegExp))ambiguous=true;
    }catch{ambiguous=true}
  }
  return {matches,ambiguous};
}
async function check(source){
  let outcome,kind='response';
  try{
    // SDK getStream may use TMDB metadata, which is deliberately omitted from
    // this unprivileged research test. No credentials supplied to vendor code.
    outcome=await Promise.race([
      sdk.getStream(source.key,LICENSED_MOVIE_TMDB_ID),
      sleep(8500).then(()=>({__auditTimeout:true}))
    ]);
    if(outcome?.__auditTimeout)kind='timeout';
  }catch(e){
    kind='sdk_error_'+String(e?.name||'unknown').replace(/[^a-zA-Z]/g,'').slice(0,24);
  }
  const links=recordLinks(outcome);
  const counts={raw:links.length,nonHttps:0,extensionless:0,
    specialHeaders:0,cdnRules:0,playbackCandidates:0};
  const samples=[];
  for(const link of links){
    const raw=mediaUrl(link.url);
    if(!raw){counts.nonHttps++;continue;}
    if(!raw.mediaSuffix)counts.extensionless++;
    const needsHeaders=!!(link.headers||link.requestHeaders||
      link.behaviorHints?.proxyHeaders||outcome?.headers||outcome?.proxyHeaders);
    const rules=providerRules(source,raw.host);
    if(needsHeaders)counts.specialHeaders++;
    if(rules.matches||rules.ambiguous)counts.cdnRules++;
    const candidate=raw.mediaSuffix&&!needsHeaders&&
      !rules.matches&&!rules.ambiguous;
    if(candidate)counts.playbackCandidates++;
    if(samples.length<3)samples.push({
      // Hostnames are not URL tokens; never fetch host until separately approved.
      host:raw.host,ext:raw.extension,
      hasMediaSuffix:raw.mediaSuffix,
      headersRequired:needsHeaders,
      metadataCDNRulesApply:rules.matches||rules.ambiguous,
      candidate
    });
  }
  console.log('VYLA_SINTEL_VERIFICATION '+JSON.stringify({
    provider:source.key,kind,metadataSkipProxy:source.skipProxy===true,
    hasMetadataHeaderRules:!!source.cdnHeaders?.length,
    counts,samples,tmdbMetadataVerified:false,licenseIndependentFromSDK:true,
    contentMatchVerified:false,httpMediaTested:false,browser1080Decoded:false
  }));
  return counts.playbackCandidates;
}
let available=0;
for(let i=0;i<candidate.length;i+=3){
  const each=await Promise.all(candidate.slice(i,i+3).map(check));
  available+=each.reduce((a,b)=>a+b,0);
}
console.log('VYLA_SINTEL_SUMMARY activeMovieProviderCandidates='+candidate.length+
  ' candidateDirectUrls='+available+' commercialCatalogSupported=false'+
  ' successfullyPlayed1080=0'+' noAdStreamGuaranteed=false'+
  ' verdict='+(available?'requires_allowlist_and_browser_decode':'unverified_unavailable_or_key_dependent'));
