import http from 'node:http';
import {spawn} from 'node:child_process';
import {readFile,rm,access} from 'node:fs/promises';
import {timingSafeEqual} from 'node:crypto';
import {fileURLToPath} from 'node:url';

/** Isolated original CloudStream/CineStream JVM bridge; no user-selected code. */
const ROOT='/tmp/moosa-jvm';
const IMDB='tt5719076';
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
export async function extractOriginal(){
  const outputFile=ROOT+'/cinestream-candidates.b64';
  await rm(outputFile,{force:true});
  const args=['-Xmx250m','-cp',ROOT,'JvmOriginalProviderLinks',
    ROOT+'/official-cloudstream-jvm.jar',ROOT+'/original-cinestream.jar',
    ROOT+'/deps',IMDB,'Dead Night'];
  const process=spawn('java',args,{cwd:ROOT,stdio:['ignore','pipe','pipe']});
  let logs='',total=0;
  const collect=bytes=>{
    total+=bytes.length;
    if(total<=2_000_000)logs+=bytes.toString('utf8');
  };
  process.stdout.on('data',collect);process.stderr.on('data',collect);
  let timedOut=false;
  const status=await new Promise(resolve=>{
    const timeout=setTimeout(()=>{timedOut=true;process.kill('SIGKILL');},54000);
    process.once('error',()=>{clearTimeout(timeout);resolve(-1)});
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
    javaExitCode:status,wasTimeout:timedOut,outputBytes:total,
    callbacksPresent:returned,
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
    const urls=raw.trim().split(/\r?\n/).filter(s=>/^[A-Za-z0-9+/=]{12,6000}$/.test(s))
      .slice(0,24).map(s=>Buffer.from(s,'base64').toString('utf8'));
    return {state:'original_callbacks_returned',urls};
  }catch{return {state:'no_callbacks_file',urls:[]}}
  finally{await rm(outputFile,{force:true});}
}
function bearerAuth(value,secret){
  if(!secret || typeof value!=='string'||!value.startsWith('Bearer '))return false;
  const a=Buffer.from(value.slice(7)),b=Buffer.from(secret);
  return a.length===b.length&&timingSafeEqual(a,b);
}
export function gateway({
  token=process.env.MOOSA_JVM_TOKEN||'',
  hosts=String(process.env.MOOSA_MEDIA_HOSTS||'').split(',')
    .map(s=>s.trim().toLowerCase()).filter(s=>/^[a-z0-9.-]{5,130}$/.test(s)),
  rightsApproved=process.env.MOOSA_MEDIA_RIGHTS_APPROVED==='1',
  extract=extractOriginal
}={}){
  if(token.length<32)throw Error('Missing secure MOOSA_JVM_TOKEN');
  let busy=false;
  return http.createServer(async(req,res)=>{
    if(req.url==='/healthz'&&req.method==='GET')
      return reply(res,200,{status:'ok',engine:'cinestream_original_jvm',
        playbackProven:false,distributionEnabled:rightsApproved});
    if(req.url!=='/v1/resolve'||req.method!=='POST')
      return reply(res,404,{error:'not_found'});
    if(!bearerAuth(req.headers.authorization,token))
      return reply(res,401,{error:'unauthorized'});
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
      const urls=approvedUrls(result?.urls,hosts);
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
  });
}
