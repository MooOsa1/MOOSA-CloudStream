/**
 * One-time upstream VaPlayer diagnosis using the HTTP behavior implemented by
 * CineStreamExtractors.invokeVaPlayer. No real plugin executed, no production
 * switch, no playback claims. Probe at most one segment and one HD variant.
 * Never emit signed URLs or fetch third-party code.
 */
import {lookup} from 'node:dns/promises';
import {isIP} from 'node:net';
const API='https://streamdata.vaplayer.ru/api.php';
const REFERER='https://nextgencloudfabric.com/';
const ORIGIN='https://moosa-web.onrender.com';
const FILMS=[
 {id:'tt0063350',title:'Night of the Living Dead',year:1968},
 {id:'tt0032599',title:'His Girl Friday',year:1940}
];
const TIMEOUT=7000;
const MAX_HLS=120_000;
function safeIp(ip){
 if(!isIP(ip))return false;
 if(ip.includes(':')){
  const x=ip.toLowerCase();
  return !(/^(?:fc|fd|fe8|fe9|fea|feb|ff|::|0:)/.test(x)||x==='::1'||x.startsWith('2001:db8:'));
 }
 const p=ip.split('.').map(Number);
 return !([0,10,127].includes(p[0])||
 p[0]>=224||p[0]===169&&p[1]===254||
 p[0]===172&&p[1]>=16&&p[1]<=31||
 p[0]===192&&[0,168].includes(p[1])||
 p[0]===100&&p[1]>=64&&p[1]<=127||
 p[0]===198&&(p[1]===18||p[1]===19)||
 p[0]===203&&p[1]===0&&p[2]===113);
}
async function publicUrl(raw,base){
 try{
  const u=new URL(raw,base);
  if(u.protocol!=='https:'||u.port||u.username||u.password||
    u.hostname==='localhost'||u.hostname.endsWith('.local')||
    isIP(u.hostname)||!/^[a-z\d.-]{4,180}$/i.test(u.hostname))return null;
  const addresses=await lookup(u.hostname,{all:true,verbatim:true});
  if(!addresses.length||addresses.some(x=>!safeIp(x.address)))return null;
  return u.href;
 }catch{return null;}
}
async function load(url,headers={},binary=false){
 const at=Date.now();
 try{
  const r=await fetch(url,{
   method:'GET',redirect:'manual',
   headers:{Accept:'*/*',...headers},signal:AbortSignal.timeout(TIMEOUT)
  });
  const cors=r.headers.get('access-control-allow-origin')||'';
  if(binary){
   if(![200,206].includes(r.status))return {status:r.status,cors,cost:Date.now()-at};
   const reader=r.body?.getReader();
   const b=reader?await reader.read():{};
   await reader?.cancel().catch(()=>{});
   const buf=b.value||new Uint8Array();
   const isVideo=buf[0]===0x47||(buf.length>=8 &&
     new TextDecoder().decode(buf.slice(0,50)).match(/ftyp|styp|moof/));
   return {status:r.status,cors,isVideo:Boolean(isVideo),cost:Date.now()-at};
  }
  const size=Number(r.headers.get('content-length')||0);
  if(size>MAX_HLS)return {status:r.status,tooLarge:true,cors};
  const body=(await r.text()).slice(0,MAX_HLS+1);
  return {status:r.status,cors,body:body.length>MAX_HLS?'':body,cost:Date.now()-at};
 }catch(e){return {status:'error',reason:e?.name||'network'};}
}
function playlistUri(text,marker){
 const lines=text.split(/\r?\n/);
 for(let i=0;i<lines.length;i++){
  if(!lines[i].startsWith(marker))continue;
  const next=lines.slice(i+1).find(x=>x.trim()&&!x.startsWith('#'));
  if(next)return next.trim();
 }
 return null;
}
function selectedVariant(text){
 const lines=text.split(/\r?\n/);
 const choices=[];
 for(let i=0;i<lines.length;i++){
  if(!lines[i].startsWith('#EXT-X-STREAM-INF:'))continue;
  const m=lines[i].match(/RESOLUTION=(\d+)x(\d+)/);
  if(!m)continue;
  const height=+m[2];
  const url=lines.slice(i+1).find(x=>x.trim()&&!x.startsWith('#'));
  if(height>=720&&height<=1080&&url)choices.push({url:url.trim(),height});
 }
 choices.sort((a,b)=>b.height-a.height);
 return choices[0]||null;
}
function matched(data,film){
 const v=data?.data||{};
 const name=String(v.title||'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();
 return String(data?.status_code)==='200' && v.imdb_id===film.id &&
  name.startsWith(film.title.toLowerCase()) && name.includes(String(film.year));
}
for(const film of FILMS){
 const api=new URL(API);
 api.searchParams.set('imdb',film.id);
 api.searchParams.set('type','movie');
 const result={imdb:film.id,film:film.title};
 const from=await load(api.href,{Referer:REFERER});
 result.apiStatus=from.status;
 if(from.status!==200){console.log('SOURCE_DIAG '+JSON.stringify(result));continue;}
 let parsed;
 try{parsed=JSON.parse(from.body);}catch{result.result='api_not_json';console.log('SOURCE_DIAG '+JSON.stringify(result));continue;}
 result.identityMatched=matched(parsed,film);
 result.apiStreams=parsed?.data?.stream_urls?.length||0;
 if(!result.identityMatched||!result.apiStreams){
   result.result='no_exact_identity_or_stream';
   console.log('SOURCE_DIAG '+JSON.stringify(result));continue;
 }
 let source=await publicUrl(parsed.data.stream_urls[0]);
 if(!source){result.result='unsafe_source_url';console.log('SOURCE_DIAG '+JSON.stringify(result));continue;}
 const master=await load(source,{Origin:ORIGIN});
 result.masterStatus=master.status;
 result.masterCors=master.cors==='*'||master.cors===ORIGIN;
 if(!master.body?.startsWith('#EXTM3U')){
   result.result='master_unavailable';
   console.log('SOURCE_DIAG '+JSON.stringify(result));continue;
 }
 if(master.body.includes('#EXT-X-KEY:')||master.body.includes('#EXT-X-SESSION-KEY:')){
   result.result='encrypted_hls_not_tested';
   console.log('SOURCE_DIAG '+JSON.stringify(result));continue;
 }
 let media=source;
 const v=selectedVariant(master.body);
 if(v){
   media=await publicUrl(v.url,source);
   if(!media){result.result='unsafe_variant';console.log('SOURCE_DIAG '+JSON.stringify(result));continue;}
   const playlist=await load(media,{Origin:ORIGIN});
   result.variantStatus=playlist.status;
   result.variantCors=playlist.cors==='*'||playlist.cors===ORIGIN;
   if(!playlist.body?.startsWith('#EXTM3U')){
     result.result='variant_unavailable';
     console.log('SOURCE_DIAG '+JSON.stringify(result));continue;
   }
   result.measuredHeight=v.height;
   source=playlist.body;
 }else source=master.body;
 const seg=playlistUri(source,'#EXTINF:');
 const safe=seg?await publicUrl(seg,media):null;
 if(!safe){result.result='no_safe_media_segment';console.log('SOURCE_DIAG '+JSON.stringify(result));continue;}
 const noHeaders=await load(safe,{Range:'bytes=0-2047',Origin:ORIGIN},true);
 const providerHeaders=await load(safe,{Range:'bytes=0-2047',Origin:ORIGIN,Referer:REFERER},true);
 result.segmentWithoutReferer={status:noHeaders.status,video:!!noHeaders.isVideo,cors:noHeaders.cors==='*'||noHeaders.cors===ORIGIN};
 result.segmentWithOriginalReferer={status:providerHeaders.status,video:!!providerHeaders.isVideo,cors:providerHeaders.cors==='*'||providerHeaders.cors===ORIGIN};
 result.result=providerHeaders.isVideo&&providerHeaders.status<300?
  'segment_accessible_with_original_referer':'segment_not_usable_with_original_referer';
 console.log('SOURCE_DIAG '+JSON.stringify(result));
}
