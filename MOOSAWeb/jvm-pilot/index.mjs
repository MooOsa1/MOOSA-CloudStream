import http from 'node:http';
import {spawn} from 'node:child_process';
import {readFile,rm,access,mkdir} from 'node:fs/promises';
import {timingSafeEqual} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {probeHlsFirstSegment} from '../real-hls-segment-probe.mjs';

/** Isolated original CloudStream/CineStream JVM bridge; no user-selected code. */
const ROOT='/opt/moosa/jvm';
const OUTPUT_DIR='/tmp/moosa-jvm';
const IMDB='tt5719076';
// Read-only status of one controlled startup extraction in the FREE container.
// No media URLs, signatures, request headers or raw JVM logs reach healthz.
let bootProbe={state:'not_requested'};
function reply(res,status,obj){
  res.writeHead(status,{'Content-Type':'application/json; charset=utf-8',
    'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
  res.end(JSON.stringify(obj));
}
export function exactFilm(x){
  return !!x && x.media==='movie' && x.imdbId===IMDB &&
    String(x.title||'').trim().toLowerCase()==='dead night' &&
    [2017,2018].includes(Number(x.year)) && Number(x.tmdbId)>0;
}
export function approvedUrls(input,allowedHosts){
  const seen=new Set();
  return (Array.isArray(input)?input:[]).flatMap(raw=>{
    try{
      if(typeof raw!=='string'||raw.length>3000)return [];
      const u=new URL(raw);
      if(u.protocol!=='https:'||u.username||u.password||u.port||u.hash||
         !allowedHosts.includes(u.hostname.toLowerCase())||
         !/\.(?:m3u8|mp4|webm)$/i.test(u.pathname)||seen.has(u.href))return [];
      seen.add(u.href);
      return [u.href];
    }catch{return []}
  }).slice(0,4);
}
/** Parse only private v2 candidate records. Neither names nor headers nor
 * signed URLs are exposed by the public health endpoint. */
export function parsePrivateCandidates(text){
  if(typeof text!=='string')return [];
  const seen=new Map();
  for(const row of text.trim().split(/\r?\n/).slice(0,24)){
    const fields=row.split('\t');
    if(fields.length!==3 || !['0','1'].includes(fields[2]) ||
      !fields.slice(0,2).every(s=>/^[A-Za-z0-9+/=]{4,6000}$/.test(s)))continue;
    try{
      const url=Buffer.from(fields[0],'base64').toString('utf8');
      const source=Buffer.from(fields[1],'base64').toString('utf8');
      if(url.length>3000||source.length>70||!source || !url.startsWith('https://'))continue;
      const next={url,provider:source,requiresHeaders:fields[2]==='1'};
      const old=seen.get(url);
      if(!old || (!old.requiresHeaders && next.requiresHeaders))
        seen.set(url,next);
    }catch{}
  }
  return [...seen.values()];
}
export async function extractOriginal({timeoutMs=54000}={}){
  await mkdir(OUTPUT_DIR,{recursive:true});
  const outputFile=OUTPUT_DIR+'/cinestream-candidates.b64';
  await rm(outputFile,{force:true});
  const args=['-Xmx250m','-cp',ROOT,'JvmOriginalProviderLinks',
    ROOT+'/official-cloudstream-jvm.jar',ROOT+'/original-cinestream.jar',
    ROOT+'/deps',IMDB,'Dead Night'];
  const process=spawn('/usr/bin/java',args,{cwd:ROOT,stdio:['ignore','pipe','pipe']});
  let logs='',total=0;
  const collect=bytes=>{
    total+=bytes.length;
    if(total<=2_000_000)logs+=bytes.toString('utf8');
  };
  process.stdout.on('data',collect);process.stderr.on('data',collect);
  let timedOut=false,spawnErrorCode=null;
  const status=await new Promise(resolve=>{
    const timeout=setTimeout(()=>{timedOut=true;process.kill('SIGKILL');},Math.min(180000,Math.max(5000,timeoutMs)));
    process.once('error',error=>{
      spawnErrorCode=['ENOENT','EACCES','EMFILE','ENOMEM'].includes(error?.code)
        ?error.code:'OTHER';
      clearTimeout(timeout);resolve(-1);
    });
    process.once('close',code=>{clearTimeout(timeout);resolve(code)});
  });
  const returned=status===0 && !timedOut &&
    /ORIGINAL_LINKS_CALLBACK_COUNTS:\s*links=[1-9]\d*/.test(logs);
  // The film identity must come from the underlying Castle detail payload,
  // not just the requested title passed to CineStream.load().
  const upstreamMatched=/(?:DEBUG|ERROR) Castle: details:/.test(logs) &&
    /"title":"Dead Night"/.test(logs) &&
    /"duration":(?:4[6-9]\d{2}|5[0-3]\d{2})/.test(logs);
  // Only a normalized error class and simple booleans may leave this
  // process. Never return third-party URLs, raw logs or signed query tokens.
  const exceptionClasses=[...logs.matchAll(/(?:java\.lang\.|java\.net\.|kotlin\.|org\.json\.)[A-Za-z0-9.$]*(?:Error|Exception)/g)]
    .map(x=>x[0]).slice(0,5);
  const diagnostic={
    javaExitCode:status,spawnErrorCode,wasTimeout:timedOut,outputBytes:total,
    callbacksPresent:returned,
    javaStartupIssues:{
      heapReservationFailed:/Could not reserve enough space|Could not allocate|Native memory allocation|There is insufficient memory/i.test(logs),
      invalidJvmArgument:/Unrecognized (?:VM )?option|Unrecognized VM option/i.test(logs),
      missingRuntimeClass:/ClassNotFoundException|NoClassDefFoundError|Could not find or load main class/i.test(logs),
      rejectedMethodArguments:/IllegalArgumentException|Invalid title identification/i.test(logs)
    },
    originalLoadReturned:/ORIGINAL_LINKS_EXACT_IDENTITY_VERIFIED: true/.test(logs),
    castleDetailsSeen:/(?:DEBUG|ERROR) Castle: details:/.test(logs),
    castleIdentityMatched:upstreamMatched,
    javaFailureType:exceptionClasses.at(-1)||(
      /Could not find or load main class/.test(logs)?'main_class_missing':
      /No such file or directory/.test(logs)?'missing_file':
      status===0?'no_matching_link':'unknown')
  };
  logs=''; // Never expose raw signed CDN URLs / extractor logs.
  if(!returned||!upstreamMatched)
    return {state:timedOut?'timeout':'no_verified_upstream_identity',
      urls:[],diagnostic};
  try{
    const raw=await readFile(outputFile,'utf8');
    const candidates=parsePrivateCandidates(raw);
    return {state:'original_callbacks_returned',
      urls:candidates.map(candidate=>candidate.url),candidates};
  }catch{return {state:'no_callbacks_file',urls:[]}}
  finally{await rm(outputFile,{force:true});}
}
function bearerAuth(value,secret){
  if(!secret || typeof value!=='string'||!value.startsWith('Bearer '))return false;
  const a=Buffer.from(value.slice(7)),b=Buffer.from(secret);
  return a.length===b.length&&timingSafeEqual(a,b);
}
/** Reuse short-lived original JVM callbacks in memory; no signed URLs in logs.
 * Simultaneous boot and Play requests await one extraction, not two JVMs. */
export function makeSharedExtraction(extract,{ttlMs=120000,clock=Date.now}={}){
  let active=null,cached=null;
  return function sharedExtraction(){
    if(cached && cached.until>clock())return Promise.resolve(cached.result);
    if(active)return active;
    active=Promise.resolve().then(extract).then(result=>{
      if(result?.state==='original_callbacks_returned' &&
          Array.isArray(result.urls) && result.urls.length>0)
        cached={result,until:clock()+ttlMs};
      return result;
    }).finally(()=>{active=null});
    return active;
  };
}
const sharedOriginalExtraction=makeSharedExtraction(
  ()=>extractOriginal({timeoutMs:170000}),{ttlMs:120000});

export async function executeBootProbe(extract=sharedOriginalExtraction,{
  segmentProbe=probeHlsFirstSegment,
  verifySegment=process.env.MOOSA_JVM_SEGMENT_PROBE==='1',
  allowedHosts=String(process.env.MOOSA_MEDIA_HOSTS||'')
    .split(',').map(s=>s.trim().toLowerCase()).filter(Boolean)
}={}){

  if(bootProbe.state==='running')return;
  bootProbe={state:'running'};
  const started=Date.now();
  try{
    const output=await extract();
    let mediaProbe={checked:0,firstSegmentHttpVerified:0,
      note:'Segment validation is HTTP-only, not actual Chrome playback.'};
    const rawCandidates=Array.isArray(output?.candidates)?output.candidates:[];
    const browserDirect=rawCandidates.filter(c=>c.requiresHeaders===false);
    if(verifySegment && output?.state==='original_callbacks_returned'){
      // A .cs3 plugin may declare Referer while a CDN ALSO serves the same
      // video directly without it. Probe ALL safe allowlisted candidates
      // using ordinary browser-like requests with no custom Referer, Cookie,
      // Authorization, or proxy. No URLs are exposed in diagnostic results.
      const candidates=approvedUrls(rawCandidates.map(c=>c.url),allowedHosts)
        .filter(url=>new URL(url).pathname.endsWith('.m3u8')).slice(0,4);
      const declarations=new Map(rawCandidates.map(c=>[c.url,c]));
      const checks=await Promise.allSettled(candidates.map(url=>
        segmentProbe(url,{allowedHosts,timeoutMs:5500,
          origin:'https://moosa-web.onrender.com',
          minWidth:1,verifyEncodedResolution:false})));
      let successful=0,successWithoutDeclaredHeaders=0;
      let successDespiteDeclaredHeaders=0,exactCastleWithoutHeaders=0;
      for(let i=0;i<checks.length;i++){
        if(checks[i].status!=='fulfilled'||checks[i].value?.ok!==true)continue;
        successful++;
        if(declarations.get(candidates[i])?.requiresHeaders)
          successDespiteDeclaredHeaders++;
        else successWithoutDeclaredHeaders++;
        // Castle upstream title and duration are independently checked by the
        // JVM extractor. Do not equate a playable OTHER-provider URL with it.
        if(declarations.get(candidates[i])?.provider==='Castle')
          exactCastleWithoutHeaders++;
      }
      mediaProbe={checked:checks.length,firstSegmentHttpVerified:successful,
        noCustomHeaderHttpVerified:successWithoutDeclaredHeaders,
        declaredHeaderButHeaderlessHttpVerified:successDespiteDeclaredHeaders,
        castleHeaderlessHttpVerified:exactCastleWithoutHeaders,
        reasons:checks.map(x=>x.status==='fulfilled'?
          String(x.value?.reason|| (x.value?.ok?'verified':'unavailable')).slice(0,42):
          'probe_error'),
        note:'HTTP+CORS byte proof without plugin headers; not Chrome-decoded frames or film-rights verification.'};
    }
    bootProbe={
      state:'complete',
      result:String(output?.state||'unavailable').slice(0,50),
      candidateCount:Array.isArray(output?.urls)?output.urls.length:0,
      browserDirectCount:browserDirect.length,
      headerRequiredCount:rawCandidates.filter(c=>c.requiresHeaders===true).length,
      castleCount:rawCandidates.filter(c=>c.provider==='Castle').length,
      castleBrowserDirectCount:rawCandidates.filter(c=>
        c.provider==='Castle'&&!c.requiresHeaders).length,
      mediaProbe,
      elapsedMs:Date.now()-started,
      failureType:output?.diagnostic?.javaFailureType||null,
      javaExitCode:output?.diagnostic?.javaExitCode??null,
      spawnErrorCode:output?.diagnostic?.spawnErrorCode??null,
      outputBytes:output?.diagnostic?.outputBytes??null,
      startupIssues:output?.diagnostic?.javaStartupIssues||null,
      providerMetadataVerified:output?.diagnostic?.originalLoadReturned??null,
      castleDetailsSeen:output?.diagnostic?.castleDetailsSeen??null
    };
  }catch{
    bootProbe={state:'failed',result:'runtime_exception',
      candidateCount:0,elapsedMs:Date.now()-started};
  }
}
export function bootProbeStatus(){return {...bootProbe};}
export function gateway({
  token=process.env.MOOSA_JVM_TOKEN||'',
  hosts=String(process.env.MOOSA_MEDIA_HOSTS||'').split(',')
    .map(s=>s.trim().toLowerCase()).filter(s=>/^[a-z0-9.-]{5,130}$/.test(s)),
  rightsApproved=process.env.MOOSA_MEDIA_RIGHTS_APPROVED==='1',
  extract=sharedOriginalExtraction
}={}){
  if(token.length<32)throw Error('Missing secure MOOSA_JVM_TOKEN');
  let busy=false;
  return http.createServer(async(req,res)=>{
    if(req.url==='/healthz'&&req.method==='GET')
      return reply(res,200,{status:'ok',engine:'cinestream_original_jvm',
        playbackProven:false,distributionEnabled:rightsApproved,
        extractionProbe:bootProbeStatus()});
    if(req.url!=='/v1/resolve'||req.method!=='POST')
      return reply(res,404,{error:'not_found'});
    if(!bearerAuth(req.headers.authorization,token))
      return reply(res,401,{error:'unauthorized'});
    // Authorized Play joins the boot-time extraction through the shared
    // promise instead of receiving 429 or launching a second JVM process.
    if(busy)return reply(res,429,{error:'busy'});
    let raw='';
    try{
      for await(const part of req){
        raw+=part.toString('utf8');
        if(raw.length>6000)return reply(res,413,{error:'body_too_large'});
      }
      const request=JSON.parse(raw);
      if(!exactFilm(request))return reply(res,400,{error:'wrong_film'});
      busy=true;
      const result=await extract();
      busy=false;
      // A correct movie in Castle's detail response cannot establish
      // correctness for every OTHER extractor callback. Do not promote
      // sources from unrelated providers, or links requiring custom Referer.
      const exactDirect=Array.isArray(result?.candidates)?
        result.candidates.filter(candidate=>
          candidate.provider==='Castle' && candidate.requiresHeaders===false)
          .map(candidate=>candidate.url):[];
      const urls=approvedUrls(exactDirect,hosts);
      // Rights approval is deliberately opt-in; matching an upstream title
      // and decoding frames is not permission to distribute a commercial film.
      const sources=rightsApproved && result.state==='original_callbacks_returned'
        ?urls.map((url,i)=>({url,name:'CineStream • JVM '+(i+1),
          provider:'CineStream',quality:480,
          resolvedImdbId:IMDB,resolvedTitle:'Dead Night',
          resolvedYear:2018,operatorAuthorized:true})):[];
      return reply(res,200,{sources,count:sources.length,
        state:result?.state||'unavailable',diagnostic:result?.diagnostic||null,
        sourceIdentityVerified:false,
        distributionEnabled:rightsApproved});
    }catch{
      busy=false;return reply(res,502,{error:'runtime_execution_failed'});
    }
  });
}
if(process.argv[1]===fileURLToPath(import.meta.url)){
  const port=Number(process.env.PORT||10000);
  gateway().listen(port,'0.0.0.0',()=>{
    console.log('MOOSA original JVM pilot service listening');
    if(process.env.MOOSA_JVM_BOOT_PROBE==='1')
      void executeBootProbe();
  });
}
