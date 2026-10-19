#!/usr/bin/env node
/**
 * Strictly read-only NO-TORRENT Stremio source discovery. Test CURRENT 2025
 * titles, NEVER infer playability from a JSON listing or a working manifest.
 * No external movie/video URL is fetched. No private upstream URLs are logged.
 */
import {readFile} from 'node:fs/promises';
const titles=[['tt31193180','Sinners (2025)'],['tt16311594','F1: The Movie (2025)']];
const sample=JSON.parse(await readFile(new URL('./moosa-direct-only.template.json',import.meta.url),'utf8'));
export function isHttpsMediaCandidate(stream){
  if(!stream||typeof stream!=='object'||stream.infoHash||stream.fileIdx!==undefined)return false;
  const signals=[stream.url,...(Array.isArray(stream.sources)?stream.sources:[])]
    .filter(x=>typeof x==='string');
  if(signals.some(x=>/^(magnet:|btih:|urn:btih:|stremio:)/i.test(x)||
         /(?:^|[/:])(?:tracker|announce|bittorrent)(?:[/:?]|$)/i.test(x)))return false;
  if(typeof stream.url!=='string')return false;
  let u;try{u=new URL(stream.url);}catch{return false;}
  if(u.protocol!=='https:'||u.username||u.password)return false;
  if(/^(localhost|.*\\.local|127\\.|10\\.|192\\.168\\.|169\\.254\\.|0\\.|172\\.(?:1[6-9]|2\\d|3[01])\\.)/i.test(u.hostname))return false;
  // Direct HTTPS URL is ONLY a candidate. HLS manifests, expiring cookies,
  // codec and file identity need a separate device/media verification.
  return true;
}
export function checkPreset(p){
  if(!p||!Array.isArray(p.config?.presets))throw Error('missing preset structure');
  if(!p.config.requiredStreamTypes?.includes('http'))throw Error('not HTTP required');
  if(!p.config.excludedStreamTypes?.includes('p2p'))throw Error('torrent not excluded');
  if(p.config.preferredStreamTypes?.includes('p2p'))throw Error('torrent still preferred');
  if((p.metadata?.services||[]).length)throw Error('paid services unexpectedly configured');
  const active=p.config.presets.filter(x=>x.enabled&&x.options?.resources?.includes('stream'));
  if(!active.length)throw Error('no direct HTTP provider');
  for(const p of active){
    if(p.type!=='custom')throw Error('non-custom stream provider enabled');
    if(!/^https:\/\//.test(p.options.manifestUrl))throw Error('non-HTTPS provider');
    if(/(?:torrentio|comet|jackett|torznab)/i.test(p.options.manifestUrl))throw Error('P2P provider');
  }
  return active;
}
async function json(url,timeout){
  const r=await fetch(url,{headers:{Accept:'application/json'},signal:AbortSignal.timeout(timeout),redirect:'error'});
  if(!r.ok)throw Error('HTTP '+r.status);
  const len=Number(r.headers.get('content-length')||0);
  if(len>3_000_000)throw Error('response too large');
  const raw=await r.text();
  if(raw.length>3_000_000)throw Error('response too large');
  return JSON.parse(raw);
}
export async function main(){
  const providers=checkPreset(sample);
  let total=0,failures=0;
  for(const p of providers){
    let base;try {
      const m=await json(p.options.manifestUrl,18000);
      if(!m.id||!m.resources?.some(x=>x==='stream'||x?.name==='stream'))throw Error('missing stream API');
      base=p.options.manifestUrl.slice(0,-'/manifest.json'.length);
      console.log('MOOSA_HTTP_MANIFEST provider='+p.instanceId+' version='+String(m.version||'-').slice(0,18)+' valid=true');
    }catch(e){failures++;console.log('MOOSA_HTTP_MANIFEST provider='+p.instanceId+' valid=false reason='+String(e.message).slice(0,70));continue;}
    for(const [id,title] of titles){
      try{
        const info=await json(base+'/stream/movie/'+id+'.json',30000);
        if(!Array.isArray(info.streams))throw Error('missing streams array');
        const candidates=info.streams.filter(isHttpsMediaCandidate);
        total+=candidates.length;
        console.log('MOOSA_HTTP_FILM '+title+' provider='+p.instanceId+
          ' listed='+info.streams.length+' HTTPS_candidate='+candidates.length+
          ' rejected='+String(info.streams.length-candidates.length)+
          ' videoDecoded=NOT_TESTED audioDecoded=NOT_TESTED');
      }catch(e){failures++;console.log('MOOSA_HTTP_FILM '+title+' provider='+p.instanceId+
        ' failed='+String(e.message).slice(0,70));}
    }
  }
  console.log('MOOSA_HTTP_ONLY_FINAL candidates='+total+' discoveryErrors='+failures+
    ' verifiedPlayableFilms=0');
  // This discovery does not establish playable movies; a user device or safe
  // end-to-end media probe must verify audio, frames, seeking and identity.
  if(total===0)process.exitCode=1;
}
if(process.argv[1]&&import.meta.url===new URL('file://'+process.argv[1]).href)
 main().catch(e=>{console.error('MOOSA_HTTP_ONLY_FATAL '+e.message);process.exitCode=1;});
