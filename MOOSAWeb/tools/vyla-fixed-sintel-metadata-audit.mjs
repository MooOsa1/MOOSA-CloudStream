/**
 * Vyla SDK media-source research on a FIXED licensed film (Sintel).
 *
 * Vyla's metadata helpers often require a private TMDB key. Instead of
 * entrusting a user's credential to a third-party extractor, provide one
 * independently known Sintel (2010) TMDB *fixture* exclusively in the
 * isolated runner. Every movie/CDN provider request remains genuine.
 * This tests SDK extractor compatibility, not TMDB correctness or rights
 * to reproduce commercial content. Never proxy media or fetch its bytes.
 */
const ID='45745', API_HOST='api.themoviedb.org';
const originalFetch=globalThis.fetch;
let fixedMetadataQueries=0,foreignMetadataRequests=0;
globalThis.fetch=(input,opts)=>{
  let url;
  try{url=new URL(typeof input==='string'||input instanceof URL?String(input):input?.url)}
  catch{return originalFetch(input,opts)}
  if(url.hostname===API_HOST){
    if(url.pathname==='/3/movie/'+ID){
      fixedMetadataQueries++;
      const data={
        id:45745,title:'Sintel',original_title:'Sintel',
        release_date:'2010-09-27',imdb_id:'tt1727587',
        external_ids:{imdb_id:'tt1727587'},
        genres:[{id:16,name:'Animation'},{id:12,name:'Adventure'}],
        original_language:'en',origin_country:['NL'],popularity:1
      };
      return Promise.resolve(new Response(JSON.stringify(data),{
        status:200,headers:{'content-type':'application/json'}
      }));
    }
    foreignMetadataRequests++;
    // Fail closed rather than looking up unrelated TMDB identities or leaking
    // a synthetic API key to real TMDB. Only one CC film ID is in scope.
    return Promise.resolve(new Response(JSON.stringify({status_code:34}),{
      status:404,headers:{'content-type':'application/json'}
    }));
  }
  return originalFetch(input,opts);
};
const {default:VylaSDK}=await import('@vyla-entertainment/sdk');
const {setTimeout:sleep}=await import('node:timers/promises');
const sdk=new VylaSDK({tmdbApiKey:'MOOSA_SINTEL_FIXED_METADATA_FIXTURE'});
const WANT=[
  '111477','flaxmovies','fsonline','movy','movienight','peestream',
  'purstream','streamaggregator','vidnest-sub','vixsrc','cinemaarmy',
  'dulo','3eyedraven','vidgod','vidfast','lookmovie','1embed'
];
const configured=sdk.getSources(true);
const catalog=Array.isArray(configured)?configured:[];
const candidate=catalog.filter(x=>WANT.includes(x.key)&&!x.disabled)
  .sort((a,b)=>WANT.indexOf(a.key)-WANT.indexOf(b.key)).slice(0,14);
function decodeCandidates(v){
  if(!v||typeof v!=='object')return [];
  const all=[];
  const add=(p)=>{
    if(typeof p==='string')all.push({url:p});
    else if(p&&typeof p==='object'&&
      (p.url||p.link||p.file||p.stream_url))all.push({
        url:p.url||p.link||p.file||p.stream_url,
        headers:p.headers||p.requestHeaders||p.behaviorHints?.proxyHeaders||null,
        quality:String(p.quality||p.resolution||'').slice(0,25)
      });
  };
  if(Array.isArray(v))v.forEach(add);
  for(const bucket of [v?.allUrls,v?.urls,v?.streams,v?.sources,
    v?.data?.sources,v?.data?.streams])if(Array.isArray(bucket))bucket.forEach(add);
  add(v);
  return all.slice(0,30);
}
function safeHost(url){
  if(typeof url!=='string'||url.length>3500)return null;
  try{
    const u=new URL(url);
    if(u.protocol!=='https:'||u.username||u.password||u.port||u.hash||
       !u.hostname.includes('.')||u.hostname.includes(':')||
       /^(?:localhost|[^.]+\.localhost|[^.]+\.local|0\.|10\.|127\.|169\.254\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.)/i.test(u.hostname))return null;
    return {host:u.hostname,suffix:/\.(?:m3u8|mp4|m4v|webm|mov)$/i.test(u.pathname)};
  }catch{return null;}
}
async function runSource(cfg){
  let v=null,state='ok';
  try{
    v=await Promise.race([
      sdk.getStream(cfg.key,ID),
      sleep(8500).then(()=>({__timedOut:true}))
    ]);
    if(v?.__timedOut)state='timeout';
    else if(v===null||v===undefined)state='no_links';
  }catch(e){state='sdk_error_'+String(e?.name||'Error').replace(/[^a-zA-Z]/g,'').slice(0,25)}
  const links=decodeCandidates(v);
  let direct=0,protectedMedia=0,nonDirect=0,invalid=0;
  const samples=[];
  for(const item of links){
    const url=safeHost(item.url);
    if(!url){invalid++;continue}
    let cdnRule=false;
    for(const rule of Array.isArray(cfg.cdnHeaders)?cfg.cdnHeaders:[]){
      if(!(rule.pattern instanceof RegExp)){cdnRule=true;break}
      try{if(rule.pattern.test(url.host)){cdnRule=true;break}}catch{cdnRule=true;break}
    }
    const headers=!!(item.headers||v?.headers||v?.proxyHeaders||cdnRule);
    if(headers)protectedMedia++;
    else if(!url.suffix)nonDirect++;
    else direct++;
    if(samples.length<3)samples.push({
      host:url.host,advertisedDirect:cfg.skipProxy===true,
      mediaSuffix:url.suffix,headersRequired:headers
    });
  }
  const record={key:cfg.key,state,raw:links.length,direct,
    needsNonbrowserHeaders:protectedMedia,extensionless:nonDirect,
    invalid,candidates:samples,frameDecoded:false,rightsVerifiedBySDK:false};
  console.log('VYLA_FIXED_SINTEL '+JSON.stringify(record));
  return record;
}
console.log('VYLA_FIXED_SETUP '+JSON.stringify({
  scope:'independent licensed Sintel only',activeSDKProviders:catalog.length,
  candidateProviders:candidate.length,tmdbSecretsShared:false,
  metadataFixtureUsed:true,realProviderRequests:true,
  maxConcurrent:3,actualVideoDownloaded:false
}));
const records=[];
for(let i=0;i<candidate.length;i+=3){
  records.push(...await Promise.all(candidate.slice(i,i+3).map(runSource)));
}
console.log('VYLA_FIXED_SUMMARY '+JSON.stringify({
  sdkProvidersTested:records.length,
  fixedMetadataQueries,foreignMetadataRequests,
  browserDirectCandidates:records.reduce((s,x)=>s+x.direct,0),
  needsNonbrowserHeaders:records.reduce((s,x)=>s+x.needsNonbrowserHeaders,0),
  decoded1080p:0,verifiedFilmPlayback:false,
  verdict:'source_candidates_only_not_playback'
}));
