/**
 * MOOSA experimental CloudStream JVM loopback adapter.
 *
 * PROOF OF TRANSPORT ONLY. An exact, explicitly approved CineStream .cs3
 * provider is executed in its own short-lived Java process, and only a
 * hardcoded freely licensed film's METADATA are returned over localhost.
 * Does NOT download media, solve challenges, proxy streams, or execute
 * plugins supplied by web users.
 *
 * Untrusted extension execution is NOT made safe by a separate Java process.
 * This must never be exposed publicly or enabled in the production Render app
 * before an actual OS sandbox and resource isolation are introduced.
 */
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';

const TARGET=Object.freeze({imdb:'tt1727587',media:'movie',title:'Sintel'});
const MAX_BODY=1024;
const MAX_OUTPUT=8192;
const DEFAULT_TIMEOUT=25000;

function send(res,status,payload){
  const body=JSON.stringify(payload);
  res.writeHead(status,{'content-type':'application/json; charset=utf-8',
    'cache-control':'no-store','x-content-type-options':'nosniff',
    'content-length':Buffer.byteLength(body)});
  res.end(body);
}
function parseResult(stdout){
  // Only accept the two structured lines emitted by our approved JVM worker.
  // Substring matching incorrectly accepted CineStreamProviderEvil, Sintel2,
  // or another IMDb ID prefixed by a valid value.
  const lines=stdout.split(/\r?\n/).filter(s=>s.startsWith('MOOSA_GENERIC_PROVIDER '));
  if(lines.length!==2)return null;
  const inst=/^MOOSA_GENERIC_PROVIDER phase=instantiate result=success class=com\.megix\.CineStreamProvider(?:\s|$)/;
  const load=/^MOOSA_GENERIC_PROVIDER phase=load result=exact title=Sintel(?:\s|$)/;
  const imdb=/(?:^|\s)expectedImdb=tt1727587(?:\s|$)/;
  if(!inst.test(lines[0])||!load.test(lines[1])||!imdb.test(lines[1]))
    return null;
  return {provider:'CineStream',movie:{imdb:TARGET.imdb,title:TARGET.title},
    metadataVerified:true,playbackVerified:false,streams:[]};
}
async function runApprovedProvider(opts){
  const {java='java',jvmRuntime,libraryJar,pluginJar,timeoutMs=DEFAULT_TIMEOUT}=opts;
  if(![jvmRuntime,libraryJar,pluginJar].every(x=>typeof x==='string'&&x.length>0))
    throw new Error('engine_not_configured');
  return await new Promise((resolve,reject)=>{
    // No shell interpolation. No client-controlled binary paths or class names.
    const cp=['MOOSAJVM',jvmRuntime].join(process.platform==='win32'?';':':');
    const args=['-Xmx384m','-XX:ActiveProcessorCount=2','-cp',cp,
      'GenericProviderProbe',libraryJar,pluginJar,
      'com.megix.CineStreamProvider','load-licensed-sintel'];
    const child=spawn(java,args,{shell:false,
      env:{PATH:process.env.PATH||'',JAVA_HOME:process.env.JAVA_HOME||''},
      stdio:['ignore','pipe','pipe']});
    let stdout='',bytes=0,finished=false;
    const deadline=setTimeout(()=>child.kill('SIGKILL'),Math.min(timeoutMs,30000));
    function done(err,value){
      if(finished)return;
      finished=true;clearTimeout(deadline);
      if(err)reject(err);else resolve(value);
    }
    child.stdout.on('data',chunk=>{
      bytes+=chunk.byteLength;
      if(bytes>MAX_OUTPUT){child.kill('SIGKILL');return;}
      stdout+=String(chunk);
    });
    // Suppress stderr to avoid emitting plugin URLs, secrets and tokens.
    child.stderr.resume();
    child.on('error',()=>done(new Error('runtime_unavailable')));
    child.on('close',(code)=>{
      if(bytes>MAX_OUTPUT)return done(new Error('unbounded_plugin_output'));
      if(code!==0)return done(new Error('plugin_failed_or_timed_out'));
      const result=parseResult(stdout);
      if(!result)return done(new Error('unverified_metadata'));
      done(null,result);
    });
  });
}
export function createHeadlessCompatBridge(opts={}){
  let busy=false;
  const server=createServer(async(req,res)=>{
    // Requires loopback binding from caller; never allow wildcard host.
    if(req.method==='GET'&&req.url==='/health'){
      return send(res,200,{status:'ready',scope:'experimental-licensed-metadata-only'});
    }
    if(req.method!=='POST'||req.url!=='/api/metadata'){
      return send(res,404,{error:'not_found'});
    }
    const chunks=[];let size=0;
    for await (const chunk of req){
      size+=chunk.length;
      if(size>MAX_BODY)return send(res,413,{error:'request_too_large'});
      chunks.push(chunk);
    }
    let input;
    try{input=JSON.parse(Buffer.concat(chunks).toString('utf8'))}
    catch{return send(res,400,{error:'invalid_json'})}
    if(!input||typeof input!=='object'||Array.isArray(input)||
      Object.keys(input).sort().join(',')!=='imdb,media'||
      input.imdb!==TARGET.imdb||input.media!==TARGET.media){
      return send(res,422,{error:'unsupported_media'});
    }
    if(busy)return send(res,429,{error:'worker_busy'});
    busy=true;
    try{
      const result=await runApprovedProvider(opts);
      return send(res,200,result);
    }catch(e){
      return send(res,503,{error:e.message==='engine_not_configured'?
        'engine_not_configured':'metadata_probe_failed'});
    }finally{busy=false}
  });
  return {
    async listen(){
      return await new Promise((resolve,reject)=>{
        server.once('error',reject);
        server.listen(0,'127.0.0.1',()=>{
          const addr=server.address();
          resolve({port:addr.port,origin:'http://127.0.0.1:'+addr.port});
        });
      });
    },
    async close(){return await new Promise(resolve=>server.close(resolve))}
  };
}
