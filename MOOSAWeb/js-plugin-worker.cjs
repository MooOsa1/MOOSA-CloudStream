/**
 * Disposable and PRIVILEGED Node process for a vetted local JS provider.
 * IPC over one bounded JSON request on stdin, one bounded JSON reply on stdout.
 *
 * Node child_process is an availability boundary, NOT a security sandbox.
 * This subprocess must ONLY run developer-reviewed code. It must never
 * execute uploaded JS, remote URLs, user-provided paths or arbitrary .cs3.
 */
'use strict';
const path=require('node:path');
const {approvedMedia}=require('./js-approved-films.cjs');

const modules=Object.freeze({
  'licensed-fixture':path.resolve(__dirname,'tests/fixtures/js-licensed-provider.cjs'),
  'commons-authorized':path.resolve(__dirname,'js-plugins/commons-authorized.cjs')
});
const maxInput=4096;
let request='';
process.stdin.setEncoding('utf8');
process.stdin.on('data',chunk=>{
  request+=chunk;
  if(request.length>maxInput){
    process.stdout.write('{"error":"request_too_large"}\n');
    process.exit(2);
  }
});
process.stdin.on('end',async()=>{
  try {
    const data=JSON.parse(request);
    if(!['licensed-fixture','commons-authorized'].includes(data?.provider) || !modules[data.provider]){
      throw new Error('unknown_provider');
    }
    const meta=data.meta;
    const fixtureAllowed=meta?.media==='movie' && meta.title==='Big Buck Bunny' &&
      meta.originalTitle==='Big Buck Bunny' && Number.isSafeInteger(meta.id) && meta.id>0;
    if(data.provider==='licensed-fixture'?!fixtureAllowed:!approvedMedia(meta)){
      throw new Error('unapproved_sample');
    }
    const loaded=require(modules[data.provider]);
    if(typeof loaded.getStreams!=='function')throw new Error('missing_getStreams');
    const result=await loaded.getStreams(meta.id,meta.media,null,null);
    if(!Array.isArray(result)||result.length>80)throw new Error('invalid_provider_result');
    const response=JSON.stringify({streams:result});
    if(response.length>120000)throw new Error('provider_result_too_large');
    process.stdout.write(response+'\n');
  }catch(e){
    process.stdout.write(JSON.stringify({
      error:['unknown_provider','unapproved_sample'].includes(e.message)?e.message:'worker_failed'
    })+'\n');
    process.exitCode=1;
  }
});
