/**
 * Read-only, isolated assessment of a *third-party* JavaScript port of
 * CineStream. Runs exclusively in GitHub Actions, never in deployed MOOSA Web.
 * License uncertainty: upstream README/package say GPL-3.0-only but LICENSE
 * contains AGPL-3.0; no source code is copied into MOOSA.
 */
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {realpathSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import path from 'node:path';

const SHA='27c2cb2c864a056003241c48a26ede5a7cff037a';
const root=realpathSync(process.env.STREAMLINE_DIR||'/tmp/streamline-ci');
const checked=execFileSync('git',['-C',root,'rev-parse','HEAD'],{encoding:'utf8'}).trim();
assert.equal(checked,SHA,'Refuse moving upstream code revision');
const origFetch=globalThis.fetch;
const requestHosts=new Set([
  'api.themoviedb.org','enc-dec.app','vidlink.pro',
  'streamdata.vaplayer.ru','vidfast.vc','vidcore.io'
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
const providers=['vidlink','vidfast','vidcore','vaplayer'];
const targets=[
  {name:'Sintel',tmdbId:'45745',kind:'movie',licensed:true},
  {name:'Current feature film metadata',tmdbId:'693134',kind:'movie',licensed:false}
];
let total=0;
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
          /^\\d+\\.\\d+\\.\\d+\\.\\d+$/.test(u.hostname))throw Error('invalid');
        const ext=u.pathname.toLowerCase().match(/\\.(m3u8|mp4|webm|mkv)(?:$)/)?.[1]||'other';
        if(!['m3u8','mp4','webm'].includes(ext))continue;
        summary.httpsPlayable++;
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
