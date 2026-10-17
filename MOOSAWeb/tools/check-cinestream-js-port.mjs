/**
 * Read-only, isolated assessment of a *third-party* JavaScript port of
 * CineStream. Runs exclusively in GitHub Actions, never in deployed MOOSA Web.
 * License uncertainty: upstream README/package say GPL-3.0-only but LICENSE
 * contains AGPL-3.0; no source code is copied into MOOSA.
 */
import assert from 'node:assert/strict';
import {inspectBrowserSource} from '../browser-source-readiness.mjs';
import {createRequire} from 'node:module';
import {realpathSync,mkdirSync,writeFileSync,chmodSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import path from 'node:path';

const SHA='27c2cb2c864a056003241c48a26ede5a7cff037a';
const root=realpathSync(process.env.STREAMLINE_DIR||'/tmp/streamline-ci');
const checked=execFileSync('git',['-C',root,'rev-parse','HEAD'],{encoding:'utf8'}).trim();
assert.equal(checked,SHA,'Refuse moving upstream code revision');
const origFetch=globalThis.fetch;
const requestHosts=new Set([
  'api.themoviedb.org','enc-dec.app','vidlink.pro',
  'streamdata.vaplayer.ru','vidfast.vc','vidcore.io',
  'h5-api.aoneroom.com','h5.aoneroom.com','fmoviesunblocked.net',
  'api.shegu.st','cinejoy.to','theemoviedb.hexa.su','hexa.su',
  'primesrc.me','fuckme.dragonballzfans.xyz','sub.wyzie.io','opensubtitles.stremio.homes',
  'subsense.nepiraw.com',
  'api.speedracelight.com','player.videasy.to',
  'vidrock.ru','player.vidzee.wtf'
]);
let fetches=0,deniedRequests=0;
globalThis.fetch=async(url,opts={})=>{
  const u=new URL(String(url));
  if(u.protocol!=='https:'||u.port||!requestHosts.has(u.hostname)){
    deniedRequests++;
    throw Error('unreviewed_provider_request');
  }
  fetches++;
  return origFetch(url,{...opts,redirect:'error',
    signal:AbortSignal.timeout(13000)});
};
const require=createRequire(import.meta.url);
const providers=['videasy','vidrock','vidzee'];
const targets=[
  {name:'Sintel',tmdbId:'45745',kind:'movie',licensed:true},
  {name:'Current feature film metadata',tmdbId:'693134',kind:'movie',licensed:false}
];
let total=0;
const candidateBuffer=[];
let hexaCandidate=null;
for(const target of targets){
  for(const provider of providers){
    const codePath=path.join(root,'providers',provider+'.js');
    const mod=require(codePath);
    let streams=[];
    try{
      const raw=await Promise.race([
        mod.getStreams(target.tmdbId,target.kind),
        new Promise((_,reject)=>setTimeout(()=>reject(Error('provider_timeout')),31000))
      ]);
      streams=Array.isArray(raw)?raw:[];
    }catch(e){console.log('MOOSA_JS_PORT_PROVIDER_ERROR '+JSON.stringify({
      provider,target:target.name,type:String(e?.name||'Error').slice(0,35)}));}
    const summary={target:target.name,tmdbId:target.tmdbId,provider,
      rawCandidates:streams.length,httpsPlayable:0,invalid:0,
      withCustomHeaders:0,hostnames:[],formats:[]};
    const hosts=new Set(),formats=new Set();
    for(const item of streams.slice(0,35)){
      try{
        const u=new URL(String(item?.url||''));
        if(u.protocol!=='https:'||u.username||u.password||u.port||
          u.hostname==='localhost'||u.hostname.endsWith('.local')||
          /^\d+\.\d+\.\d+\.\d+$/.test(u.hostname))throw Error('invalid');
        const ext=u.pathname.toLowerCase().match(/\.(m3u8|mp4|webm|mkv)(?:$)/)?.[1]||'other';
        if(!['m3u8','mp4','webm'].includes(ext))continue;
        summary.httpsPlayable++;
        if(provider==='hexa' && target.tmdbId==='693134' &&
          u.hostname==='fuckme.dragonballzfans.xyz' && ext==='m3u8'){
          hexaCandidate=u.href;
        }
        if(target.licensed && provider==='vidlink' &&
          u.hostname==='bcdnxw.hakunaymatata.com' && ext==='mp4' &&
          candidateBuffer.length<3){
          candidateBuffer.push(Buffer.from(u.href).toString('base64'));
        }
        hosts.add(u.hostname.toLowerCase());
        formats.add(ext);
        if(item?.headers && Object.keys(item.headers).some(k=>
          ['referer','origin','authorization','cookie'].includes(k.toLowerCase())))
          summary.withCustomHeaders++;
      }catch{summary.invalid++;}
    }
    summary.hostnames=[...hosts].slice(0,12);
    summary.formats=[...formats];
    total+=summary.httpsPlayable;
    console.log('MOOSA_JS_CINESTREAM_SOURCE_AUDIT '+JSON.stringify(summary));
  }
}
if(candidateBuffer.length){
  const dir='/tmp/moosa-js-port';
  mkdirSync(dir,{recursive:true,mode:0o700});
  const file=dir+'/sintel-vidlink-candidates.b64';
  writeFileSync(file,candidateBuffer.join('\n')+'\n',{mode:0o600});
  chmodSync(file,0o600);
}
console.log('MOOSA_ALTERNATIVE_CINESTREAM_PORT_AUDITED='+providers.join(','));
if(hexaCandidate){
  // HEAD only: never download copyrighted media, bypass Referer or proxy
  // video. The 720 value is solely a preflight eligibility placeholder,
  // not a claim about real encoded quality.
  const check=await inspectBrowserSource({url:hexaCandidate,quality:720},{
    fetchImpl:globalThis.fetch,timeoutMs:6500,
    allowedHosts:['fuckme.dragonballzfans.xyz']
  });
  console.log('MOOSA_HEXA_BROWSER_ORIGIN_PREFLIGHT '+JSON.stringify({
    ready:check.ready,status:check.status||null,reason:check.reason||null,
    reportedMime:check.mime||null,host:check.host||null,
    mediaPlaybackProven:false
  }));
}
console.log('MOOSA_JS_CINESTREAM_AUDIT_SUMMARY '+JSON.stringify({
  upstreamSha:SHA.slice(0,12),totalPlausibleDirectSources:total,
  allowlistedFetches:fetches,blockedUnreviewedFetches:deniedRequests,
  mediaPlaybackProven:false,
  exactFilmIdentityProven:false,
  sourceLicenseUnresolved:true,
  deployed:false
}));
// This is a DISCOVERY audit, not a CI success gate for external host availability.
assert.ok(fetches>0,'No real provider fetch calls were made');
