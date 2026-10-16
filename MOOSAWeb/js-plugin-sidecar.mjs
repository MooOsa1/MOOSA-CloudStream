/**
 * Execute a developer-reviewed, locally pinned Nuvio-like JS provider in
 * its own bounded Node process. This prototype is OFF in MOOSA Web.
 *
 * A child process is NOT an untrusted-code security sandbox. Never point
 * this at a remote or user-selected JS file. No production runtime plugin
 * is installed, and this test's media URLs are intentionally synthetic.
 */
import {spawn} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {adaptNuvioStreams} from './nuvio-js-adapter.mjs';
import approved from './js-approved-films.cjs';
const {approvedMedia}=approved;

const ROOT=path.dirname(fileURLToPath(import.meta.url));
const WORKER=path.join(ROOT,'js-plugin-worker.cjs');
const INPUT_MAX=4096,OUTPUT_MAX=125000,MIN_TIMEOUT=200,MAX_TIMEOUT=10000;
const fail=(reason,elapsedMs)=>({
  ok:false,reason,elapsedMs,sources:[],rejected:0,blockedHeaders:0
});

/**
 * CI fixture only. permittedHosts/license gate must be set by a trusted
 * operator, not copied from plugin output or a browser query.
 */
export async function runLicensedJsProbe(meta,{
  provider='licensed-fixture',
  licensedMedia=false,
  permittedHosts=[],
  timeoutMs=3000
}={}){
  const start=Date.now();
  if(!['licensed-fixture','commons-authorized'].includes(provider))
    return fail('provider_not_approved',0);
  if(!licensedMedia || !Array.isArray(permittedHosts)||!permittedHosts.length)
    return fail('rights_not_approved',0);
  const fixtureAllowed=meta?.media==='movie' && meta.title==='Big Buck Bunny' &&
    meta.originalTitle==='Big Buck Bunny' && Number.isSafeInteger(meta.id) && meta.id>0;
  if(provider==='licensed-fixture'?!fixtureAllowed:!approvedMedia(meta))
    return fail('sample_not_approved',0);
  const body=JSON.stringify({provider,meta});
  if(body.length>INPUT_MAX)return fail('request_too_large',0);
  const deadline=Math.max(MIN_TIMEOUT,Math.min(MAX_TIMEOUT,timeoutMs));
  return new Promise(resolve=>{
    let done=false;
    let stdout='',stderr='',timedOut=false,overflow=false;
    const worker=spawn(process.execPath,[
      '--max-old-space-size=96','--disable-proto=throw',WORKER
    ],{
      cwd:ROOT,
      stdio:['pipe','pipe','pipe'],
      windowsHide:true,
      // Do NOT inherit TMDB tokens, Render credentials, or secrets.
      env:{NODE_ENV:'production',PATH:process.env.PATH||''}
    });
    const finish=(value)=>{
      if(done)return;
      done=true;
      clearTimeout(timer);
      resolve({...value,elapsedMs:Date.now()-start});
    };
    const timer=setTimeout(()=>{
      timedOut=true;
      worker.kill('SIGKILL');
    },deadline);
    worker.on('error',()=>{
      worker.kill('SIGKILL');
      finish(fail('spawn_failed',0));
    });
    worker.stdout.on('data',buf=>{
      stdout+=buf.toString('utf8');
      if(stdout.length>OUTPUT_MAX){
        overflow=true;
        worker.kill('SIGKILL');
      }
    });
    worker.stderr.on('data',buf=>{
      stderr+=buf.toString('utf8').slice(0,1024);
      if(stderr.length>2048)stderr=stderr.slice(0,2048);
    });
    worker.on('close',()=>{
      if(timedOut)return finish(fail('timeout',0));
      if(overflow)return finish(fail('response_too_large',0));
      try {
        const report=JSON.parse(stdout.trim());
        if(report.error || !Array.isArray(report.streams))
          return finish(fail(report.error||'bad_worker_data',0));
        const clean=adaptNuvioStreams(report.streams,{
          provider:provider==='commons-authorized'?'JS Commons (verified)':'JS fixture',
          permittedHosts,licensedMedia
        });
        // No output is a playback guarantee. The URL is test-only!
        return finish({ok:true,
          reason:provider==='commons-authorized'?'reviewed_commons_provider':'mock_only',
          ...clean});
      }catch{
        return finish(fail('invalid_worker_response',0));
      }
    });
    worker.stdin.on('error',()=>{}); // EPIPE if worker rejects request early
    worker.stdin.end(body);
  });
}
