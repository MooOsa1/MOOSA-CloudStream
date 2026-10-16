/**
 * Onetime free CI diagnostic for ACTUAL original CineStream Kotlin callback
 * URLs on a proven CC licensed film. This script never prints signed URLs.
 *
 * Not enabled as public server endpoint. Returned URLs are untrusted.
 * Reject private DNS, non-HTTPS, redirects, credentials, no-CORS and DRM.
 * A successful first segment is NOT proof of full video decoding in Safari.
 */
import {readFile,writeFile} from 'node:fs/promises';
import {lookup} from 'node:dns/promises';
import {isIP} from 'node:net';
import {probeHlsFirstSegment} from '../real-hls-segment-probe.mjs';

const origin='https://moosa-web.onrender.com';
const timeoutMs=7000;
const filename='/tmp/moosa-jvm/cinestream-candidates.b64';
function publicIP(address){
  if(isIP(address)===4){
    const p=address.split('.').map(Number);
    if(!p.every(Number.isInteger))return false;
    if(p[0]===0||p[0]===10||p[0]===127||p[0]>=224)return false;
    if(p[0]===100&&p[1]>=64&&p[1]<=127)return false;
    if(p[0]===169&&p[1]===254)return false;
    if(p[0]===172&&p[1]>=16&&p[1]<=31)return false;
    if(p[0]===192&&(p[1]===168||p[1]===0))return false;
    if(p[0]===198&&[18,19].includes(p[1]))return false;
    return true;
  }
  if(isIP(address)===6){
    const s=address.toLowerCase();
    return !(/^::|^fc|^fd|^fe[89ab]|^ff|^2001:db8/.test(s)||s.includes(':ffff:'));
  }
  return false;
}
async function approved(url){
  try{
    const u=new URL(url);
    if(u.protocol!=='https:'||u.port||u.username||u.password||u.hash||
      u.hostname.endsWith('.local')||!u.hostname.includes('.')||isIP(u.hostname)||
      url.length>3000)return false;
    const ips=await lookup(u.hostname,{all:true});
    return ips.length>0&&ips.length<=8&&ips.every(x=>publicIP(x.address));
  }catch{return false;}
}
async function directSegment(url){
  try{
    const res=await fetch(url,{
      method:'GET',redirect:'error',signal:AbortSignal.timeout(timeoutMs),
      headers:{Origin:origin,Range:'bytes=0-2047'}
    });
    const cors=res.headers.get('access-control-allow-origin');
    if(![200,206].includes(res.status)||!['*',origin].includes(cors))return {ok:false,status:res.status,reason:'http_or_cors'};
    if(!res.body)return {ok:false,reason:'no_body'};
    const reader=res.body.getReader();
    let bytes=new Uint8Array();
    try{
      const {value}=await reader.read();
      bytes=(value||new Uint8Array()).slice(0,2048);
    }finally{await reader.cancel().catch(()=>{});}
    const head=new TextDecoder().decode(bytes.slice(0,48));
    const magic=bytes[0]===0x47||head.includes('ftyp')||
      (bytes[0]===0x1a&&bytes[1]===0x45&&bytes[2]===0xdf&&bytes[3]===0xa3);
    return {ok:magic,status:res.status,reason:magic?'first_video_bytes':'not_video'};
  }catch(e){return {ok:false,reason:e.name||'network_error'};}
}

async function trustedPlaylist(url){
  if(!await approved(url))return null;
  try{
    const response=await fetch(url,{
      method:'GET',redirect:'error',signal:AbortSignal.timeout(timeoutMs),
      headers:{Origin:origin,Accept:'application/vnd.apple.mpegurl'}
    });
    if(!response.ok||!['*',origin].includes(
      response.headers.get('access-control-allow-origin')))return null;
    if(Number(response.headers.get('content-length')||0)>512_000)return null;
    if(!response.body)return null;
    const reader=response.body.getReader();
    let size=0,text='';
    const decoder=new TextDecoder();
    try{
      while(true){
        const {done,value}=await reader.read();
        if(done)break;
        size+=value.byteLength;
        if(size>512_000)return null;
        text+=decoder.decode(value,{stream:true});
      }
      text+=decoder.decode();
    }finally{await reader.cancel().catch(()=>{});}
    return text.startsWith('#EXTM3U')?text:null;
  }catch{return null;}
}
function firstHlsUri(playlist,type){
  const rows=playlist.split(/\r?\n/);
  for(let i=0;i<rows.length;i++){
    const marker=rows[i].trim();
    if(type==='variant' && !marker.startsWith('#EXT-X-STREAM-INF:'))continue;
    if(type==='segment' && !marker.startsWith('#EXTINF:'))continue;
    if(type==='variant'){
      const m=marker.match(/RESOLUTION=\d+x(\d+)/i);
      if(m && Number(m[1])<720)continue;
    }
    for(let j=i+1;j<rows.length;j++){
      const line=rows[j].trim();
      if(line&&!line.startsWith('#'))return line;
    }
  }
  return null;
}
async function diagnoseCrossHost(root){
  // One targeted check for the source that was blocked before media request.
  // Never print signed URLs, paths, userinfo or original playlist bytes.
  let media=root,playlist=await trustedPlaylist(root);
  if(!playlist)return {status:'root_playlist_unavailable'};
  if(playlist.includes('#EXT-X-STREAM-INF:')){
    const variant=firstHlsUri(playlist,'variant');
    if(!variant)return {status:'no_hd_variant'};
    try{media=new URL(variant,root).href;}catch{return {status:'invalid_variant'};}
    playlist=await trustedPlaylist(media);
    if(!playlist)return {status:'variant_unavailable_or_unapproved'};
  }
  if(/#EXT-X-(?:SESSION-)?KEY:/i.test(playlist))
    return {status:'encrypted_segment_not_tested'};
  const first=firstHlsUri(playlist,'segment');
  if(!first)return {status:'no_segment'};
  let actual;
  try{actual=new URL(first,media);}catch{return {status:'invalid_segment'};}
  if(!await approved(actual.href))return {status:'segment_host_not_public_https'};
  const host=actual.hostname;
  if(host===new URL(root).hostname)return {status:'same_host_but_invalid_path'};
  const safeHosts=[...new Set([new URL(root).hostname,new URL(media).hostname,host])];
  const verified=await probeHlsFirstSegment(root,{
    allowedHosts:safeHosts,timeoutMs
  });
  // Byte-level minimum-quality gate on the SAME real external segment.
  // Preserve the browser diagnostic for lower quality, but never promote it
  // as an HD source. No signed URL is printed.
  const qualityCheck=verified.ok?await probeHlsFirstSegment(root,{
    allowedHosts:safeHosts,timeoutMs,verifyEncodedResolution:true
  }):null;
  if(verified.ok && verified.playableUrl){
    // Signed URL never enters workflow logs or artifacts. Only the next test
    // in this same isolated runner receives the exact segment-verified HLS.
    await writeFile('/tmp/moosa-jvm/verified-cinestream-hls.b64',
      Buffer.from(verified.playableUrl,'utf8').toString('base64'),
      {mode:0o600});
  }
  return {
    status:verified.ok?'first_real_video_segment':'cross_host_still_unplayable',
    segmentHost:host,
    quality:verified.ok?verified.quality:null,
    encodedQuality:qualityCheck?.ok?qualityCheck.quality:null,
    hdProof:qualityCheck?.ok===true,
    hdGateReason:qualityCheck?.ok?'verified_hd':qualityCheck?.reason||null,
    reason:verified.ok?'verified_video_bytes':verified.reason
  };
}

const content=await readFile(filename,'utf8');
const candidates=[...new Set(content.trim().split(/\r?\n/).filter(Boolean)
  .slice(0,24).map(s=>Buffer.from(s,'base64').toString('utf8')))];
const rows=[];
for(const url of candidates){
  const row={scheme:'https',kind:'unknown',ok:false};
  rows.push(row);
  if(!await approved(url)){row.reason='private_or_unsafe_url';continue;}
  const parsed=new URL(url);
  row.host=parsed.hostname; // no signed URI, path or token
  if(/\.m3u8(?:$|[?#])/i.test(url)){
    row.kind='hls';
    const probe=await probeHlsFirstSegment(url,{
      allowedHosts:[parsed.hostname],timeoutMs
    });
    row.ok=probe.ok;
    row.reason=probe.ok?'first_hls_segment_proven':probe.reason;
    if(probe.ok)row.quality=probe.quality;
    if(probe.reason==='unapproved_segment_host_or_url'){
      row.crossHostCheck=await diagnoseCrossHost(url);
      if(row.crossHostCheck.status==='first_real_video_segment'){
        row.ok=true;row.quality=row.crossHostCheck.quality;
        row.reason='first_hls_segment_proven_on_public_cross_host';
      }
    }
  }else if(/\.(?:mp4|webm|m4v|ts)(?:$|[?#])/i.test(url)){
    row.kind='direct';
    const result=await directSegment(url);
    row.ok=result.ok;row.reason=result.reason;row.status=result.status;
  }else{
    row.reason='not_direct_browser_media';
  }
}
console.log('ORIGINAL_CINESTREAM_SEGMENT_PROBE '+JSON.stringify({
  inputCount:candidates.length,examined:rows.length,
  browserSegmentReady:rows.filter(x=>x.ok).length,
  httpsHosts:[...new Set(rows.map(x=>x.host).filter(Boolean))].sort(),
  results:rows
}));
