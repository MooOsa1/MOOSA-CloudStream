/**
 * Actual public Render deployment verification, not a localhost/mock check.
 * No secrets, no video requests, no signed URLs, no unsafe retries.
 */
const SITE='https://moosa-web.onrender.com';
const POLL=12,WAIT=15000,ATTEMPTS=20;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function request(path){
  const response=await fetch(SITE+path,{redirect:'error',
    headers:{Accept:'application/json'},
    signal:AbortSignal.timeout(WAIT)});
  const ct=response.headers.get('content-type')||'';
  if(!ct.includes('json'))return {http:response.status,mime:ct.slice(0,60),json:null};
  const length=Number(response.headers.get('content-length')||0);
  if(length>50000)throw Error('unexpected_json_size');
  const text=await response.text();
  if(text.length>50000)throw Error('unexpected_json_size');
  return {http:response.status,json:JSON.parse(text)};
}
let latest=null;
for(let i=1;i<=ATTEMPTS;i++){
  try{
    const health=await request('/healthz');
    const state=await request('/api/cloudstream/pilot-status');
    latest={attempt:i,health:health.http===200&&health.json?.status==='ok',
      statusHttp:state.http,filmId:state.json?.filmImdbId||null,
      enabled:state.json?.enabled??null,
      runtimeConfigured:state.json?.runtimeConfigured??null,
      readyForQuery:state.json?.readyForQuery??null};
    console.log('MOOSA_RENDER_PILOT_STATUS '+JSON.stringify(latest));
    if(latest.health && latest.statusHttp===200 &&
       latest.filmId==='tt5719076' && typeof latest.readyForQuery==='boolean'){
      console.log('MOOSA_RENDER_CODE_DEPLOYED=true');
      console.log('MOOSA_ORIGINAL_JVM_PLAYBACK_PROVEN=false');
      console.log('MOOSA_EXTERNAL_RUNTIME_CONNECTED='+String(latest.readyForQuery));
      process.exit(0);
    }
  }catch(e){latest={attempt:i,error:String(e?.name||'fetch_failure').slice(0,75)};
    console.log('MOOSA_RENDER_PILOT_WAIT '+JSON.stringify(latest));}
  if(i<ATTEMPTS)await sleep(POLL*1000);
}
console.log('MOOSA_RENDER_CODE_DEPLOYED=not_confirmed');
console.log('MOOSA_ORIGINAL_JVM_PLAYBACK_PROVEN=false');
process.exitCode=1;
