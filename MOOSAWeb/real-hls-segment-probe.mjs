/**
 * HLS source acceptance gate for direct HTTP CineStream providers.
 * Unlike a 200 playlist response, the first MEDIA SEGMENT must be fetchable.
 *
 * Only operator-allowlisted public media hosts can be contacted. This prevents
 * returned third-party URLs from becoming an SSRF primitive on Render.
 * Never retries with forged Referer, cookies, credentials or DRM keys.
 */
import {getH264ResolutionFromMpegTs} from './h264-mpegts-resolution.mjs';
const MAX_PLAYLIST_BYTES=512_000;

function safeMediaUrl(raw,hosts){
  if(typeof raw!=='string'||raw.length>3000)return null;
  let u;
  try{u=new URL(raw);}catch{return null;}
  if(u.protocol!=='https:'||u.username||u.password||u.port||u.hash||
    !hosts.includes(u.hostname.toLowerCase()))return null;
  return u;
}
function nextUri(lines,at){
  for(let i=at+1;i<lines.length;i++){
    const row=lines[i].trim();
    if(row&&!row.startsWith('#'))return row;
  }
  return null;
}
function rankedMasterVariants(raw,origin,hosts,minWidth,minHeight){
  const lines=raw.split(/\r?\n/);
  const choices=[];
  for(let i=0;i<lines.length;i++){
    if(!lines[i].startsWith('#EXT-X-STREAM-INF:'))continue;
    const m=lines[i].match(/RESOLUTION=(\d+)x(\d+)/i);
    const width=Number(m?.[1]),height=Number(m?.[2]);
    if(!Number.isInteger(width)||!Number.isInteger(height) ||
       width<minWidth||height<minHeight||height>4320)continue;
    const path=nextUri(lines,i);
    if(!path)continue;
    let candidate;
    try{candidate=new URL(path,origin).href;}catch{continue;}
    const allowed=safeMediaUrl(candidate,hosts);
    if(!allowed||!allowed.pathname.endsWith('.m3u8'))continue;
    if(choices.some(x=>x.url===allowed.href))continue;
    choices.push({url:allowed.href,width,height});
  }
  // Check a representative spread of HD variants, not just the top three.
  // A master with 8K / 4K / 1440p / 1080p / 720p previously never tested
  // its best browser-friendly 1080p or 720p fallback.
  choices.sort((a,b)=>b.height-a.height||b.width-a.width);
  const selected=[];
  const add=choice=>{
    if(choice&&!selected.some(item=>item.url===choice.url))selected.push(choice);
  };
  choices.slice(0,2).forEach(add);
  add(choices.find(x=>x.height===1080) ||
      choices.find(x=>x.height<1080&&x.height>=720));
  add(choices.at(-1));
  for(const choice of choices){
    if(selected.length>=4)break;
    add(choice);
  }
  return selected.sort((a,b)=>b.height-a.height||b.width-a.width).slice(0,4);
}
async function fetchText(url,fetchImpl,timeoutMs,origin){
  const response=await fetchImpl(url,{
    method:'GET',headers:{Accept:'application/vnd.apple.mpegurl, application/x-mpegURL',Origin:origin},
    redirect:'error',signal:AbortSignal.timeout(timeoutMs)
  });
  if(!response.ok || !validCors(response,origin) ||
     Number(response.headers.get('content-length')||0)>MAX_PLAYLIST_BYTES)
    return null;
  // Some real 720p full-film HLS VOD playlists are ~165-172 KB.
  // Read at most MAX_PLAYLIST_BYTES, then cancel the response body. This
  // prevents a remote HTTP 200 stream with no Content-Length from exhausting
  // Render memory while still accepting those legitimate media playlists.
  const reader=response.body?.getReader();
  if(!reader)return null;
  const decoder=new TextDecoder();
  let bytes=0,txt='';
  try{
    while(true){
      const {done,value}=await reader.read();
      if(done)break;
      bytes+=value.byteLength;
      if(bytes>MAX_PLAYLIST_BYTES)return null;
      txt+=decoder.decode(value,{stream:true});
    }
    txt+=decoder.decode();
  }finally{
    await reader.cancel().catch(()=>{});
  }
  if(!txt.startsWith('#EXTM3U'))return null;
  if(/#EXT-X-KEY:(?![^\n]*METHOD=NONE)/i.test(txt) ||
     /#EXT-X-SESSION-KEY:/i.test(txt))return null;
  return txt;
}
function validCors(response,origin){
  const c=String(response.headers.get('access-control-allow-origin')||'').trim();
  return c==='*'||c===origin;
}
async function checkSegment(segmentUrl,fetchImpl,timeoutMs,origin,{verifyEncodedResolution=false,minWidth=1280,minHeight=720}={}){
  const fetchChunk=range=>fetchImpl(segmentUrl,{
    method:'GET',redirect:'error',signal:AbortSignal.timeout(timeoutMs),
    headers:range
      ?{Range:verifyEncodedResolution?'bytes=0-98303':'bytes=0-2047',Origin:origin}
      :{Origin:origin}
  });
  let response=await fetchChunk(true);
  // Some compliant servers reject the optional Range header. Retry one
  // ordinary GET (not a forged Referer, credential or Cloudflare bypass).
  // Never buffer an entire segment: read one chunk then cancel immediately.
  if([400,403,405,416].includes(response.status)){
    await response.body?.cancel().catch(()=>{});
    response=await fetchChunk(false);
  }
  if(![200,206].includes(response.status))
    return {ok:false,reason:'segment_http_'+response.status};
  if(!validCors(response,origin))
    return {ok:false,reason:'segment_cors_missing'};
  const reader=response.body?.getReader();
  if(!reader)return {ok:false,reason:'segment_no_body'};
  let part;
  let dimensions=null;
  try{
    const blocks=[];
    let total=0;
    while(total<98304){
      const {value,done}=await reader.read();
      if(done)break;
      if(!value?.length)continue;
      const limited=value.subarray(0,98304-total);
      blocks.push(limited);
      total+=limited.length;
      if(!verifyEncodedResolution)break;
      // SPS commonly appears in the first video PES, but may straddle TS
      // packets or HTTP chunks. Decode progressively with a hard 96KiB cap.
      const current=new Uint8Array(total);
      let pos=0;
      for(const block of blocks){current.set(block,pos);pos+=block.length;}
      dimensions=getH264ResolutionFromMpegTs(current.subarray(0,
        Math.floor(current.length/188)*188));
      if(dimensions)break;
    }
    part=new Uint8Array(total);
    let pos=0;
    for(const block of blocks){part.set(block,pos);pos+=block.length;}
  }finally{await reader.cancel().catch(()=>{});}
  if(part.length<12)return {ok:false,reason:'segment_too_small'};
  const preview=new TextDecoder().decode(part.slice(0,80));
  const magicMp4=preview.includes('ftyp')||preview.includes('styp')||preview.includes('moof');
  const magicTs=part[0]===0x47 || (part.length>188&&part[188]===0x47);
  if(!magicMp4&&!magicTs)return {ok:false,reason:'segment_not_video'};
  if(!verifyEncodedResolution)return {ok:true};
  if(!dimensions)return {ok:false,reason:'segment_resolution_unverified'};
  if(dimensions.height<minHeight||dimensions.width<minWidth)
    return {ok:false,reason:'segment_below_min_quality',
      measuredHeight:dimensions.height,measuredWidth:dimensions.width};
  return {ok:true,measuredHeight:dimensions.height,
    measuredWidth:dimensions.width,frameVerified:true};
}
/**
 * @returns {{ok:boolean, reason?:string, quality?:number, width?:number}}
 */
export async function probeHlsFirstSegment(rawUrl,{
  fetchImpl=fetch,allowedHosts=[],origin='https://moosa-web.onrender.com',
  timeoutMs=3500,minWidth=1280,minHeight=720,verifyEncodedResolution=false
}={}){
  const hosts=[...new Set(allowedHosts.map(x=>String(x).trim().toLowerCase()))]
    .filter(h=>/^[a-z0-9.-]{4,130}$/.test(h)&&!h.startsWith('.') &&
      !h.endsWith('.local')&&!h.includes('localhost')).slice(0,15);
  const root=safeMediaUrl(rawUrl,hosts);
  if(!root||!root.pathname.endsWith('.m3u8'))
    return {ok:false,reason:'not_approved_https_hls'};
  try{
    const playlist=await fetchText(root.href,fetchImpl,timeoutMs,origin);
    if(!playlist)return {ok:false,reason:'invalid_master'};
    const isMaster=playlist.includes('#EXT-X-STREAM-INF:');
    const variants=isMaster
      ?rankedMasterVariants(playlist,root.href,hosts,minWidth,minHeight)
      // A standalone media playlist carries no reliable dimensions. Do NOT
      // invent 720p merely because its first TS packet is playable. A real
      // CineStream candidate advertised as 720 decoded to 852x480 in Chrome.
      :[{url:root.href,width:0,height:0}];
    if(!variants.length)return {ok:false,reason:'no_hd_variant'};
    // Return the exact media playlist that was verified, never the master.
    // A master may cause Safari to select a broken high-resolution rendition.
    const attempted=await Promise.all(variants.map(async variant=>{
      try{
        const media=variant.url===root.href
          ?playlist:await fetchText(variant.url,fetchImpl,timeoutMs,origin);
        if(!media)return {ok:false,reason:'invalid_variant'};
        if(!media.includes('#EXTINF:'))return {ok:false,reason:'no_media_segments'};
        const lines=media.split(/\r?\n/);
        const marker=lines.findIndex(x=>x.startsWith('#EXTINF:'));
        const segment=marker>=0?nextUri(lines,marker):null;
        if(!segment)return {ok:false,reason:'no_first_segment'};
        let full;
        try{full=new URL(segment,variant.url).href;}catch{
          return {ok:false,reason:'invalid_segment_url'};
        }
        const allowed=safeMediaUrl(full,hosts);
        if(!allowed)return {ok:false,reason:'unapproved_segment_host_or_url'};
        // Real HLS segments may use extensionless signed URLs. Require the
        // exact approved HTTPS hostname, valid browser CORS, and actual video
        // magic bytes instead of trusting the filename extension.
        const verified=await checkSegment(allowed.href,fetchImpl,timeoutMs,origin,{verifyEncodedResolution,minWidth,minHeight});
        if(!verified.ok)return verified;
        return verifyEncodedResolution
          ?{ok:true,quality:verified.measuredHeight,width:verified.measuredWidth,
              playableUrl:variant.url,frameVerified:true}
          :{ok:true,quality:variant.height,width:variant.width,playableUrl:variant.url};
      }catch{return {ok:false,reason:'timeout_or_network'};}
    }));
    return attempted.find(x=>x.ok)||attempted[0]||
      {ok:false,reason:'no_media_segments'};
  }catch{return {ok:false,reason:'timeout_or_network'};}
}
