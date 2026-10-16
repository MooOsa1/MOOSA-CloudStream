/**
 * HLS source acceptance gate for direct HTTP CineStream providers.
 * Unlike a 200 playlist response, the first MEDIA SEGMENT must be fetchable.
 *
 * Only operator-allowlisted public media hosts can be contacted. This prevents
 * returned third-party URLs from becoming an SSRF primitive on Render.
 * Never retries with forged Referer, cookies, credentials or DRM keys.
 */
const MAX_PLAYLIST_BYTES=160_000;
const SAFE_SEGMENT_EXT=/\.(?:ts|m4s|mp4|m4v|aac)$/i;

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
function selectMasterVariant(raw,origin,hosts,minWidth){
  const lines=raw.split(/\r?\n/);
  const choices=[];
  for(let i=0;i<lines.length;i++){
    if(!lines[i].startsWith('#EXT-X-STREAM-INF:'))continue;
    const m=lines[i].match(/RESOLUTION=(\d+)x(\d+)/i);
    const width=Number(m?.[1]),height=Number(m?.[2]);
    if(!Number.isInteger(width)||!Number.isInteger(height) ||
       width<minWidth||height<360||height>4320)continue;
    const path=nextUri(lines,i);
    if(!path)continue;
    let candidate;
    try{candidate=new URL(path,origin).href}catch{continue}
    const allowed=safeMediaUrl(candidate,hosts);
    if(!allowed)continue;
    choices.push({url:allowed.href,width,height});
  }
  choices.sort((a,b)=>b.width-a.width||b.height-a.height);
  return choices[0]||null;
}
async function fetchText(url,fetchImpl,timeoutMs){
  const response=await fetchImpl(url,{
    method:'GET',headers:{Accept:'application/vnd.apple.mpegurl, application/x-mpegURL'},
    redirect:'error',signal:AbortSignal.timeout(timeoutMs)
  });
  if(!response.ok || Number(response.headers.get('content-length')||0)>MAX_PLAYLIST_BYTES)
    return null;
  const txt=await response.text();
  if(txt.length>MAX_PLAYLIST_BYTES||!txt.startsWith('#EXTM3U'))return null;
  if(/#EXT-X-KEY:(?![^\n]*METHOD=NONE)/i.test(txt) ||
     /#EXT-X-SESSION-KEY:/i.test(txt))return null;
  return txt;
}
function validCors(response,origin){
  const c=String(response.headers.get('access-control-allow-origin')||'').trim();
  return c==='*'||c===origin;
}
async function checkSegment(segmentUrl,fetchImpl,timeoutMs,origin){
  const response=await fetchImpl(segmentUrl,{
    method:'GET',redirect:'error',signal:AbortSignal.timeout(timeoutMs),
    headers:{Range:'bytes=0-2047',Origin:origin}
  });
  if(![200,206].includes(response.status)||!validCors(response,origin))
    return {ok:false,reason:'segment_http_or_cors'};
  const reader=response.body?.getReader();
  if(!reader)return {ok:false,reason:'segment_no_body'};
  let part;
  try{part=(await reader.read()).value||new Uint8Array();}
  finally{await reader.cancel().catch(()=>{});}
  if(part.length<12)return {ok:false,reason:'segment_too_small'};
  const preview=new TextDecoder().decode(part.slice(0,80));
  const magicMp4=preview.includes('ftyp')||preview.includes('styp')||preview.includes('moof');
  const magicTs=part[0]===0x47 || (part.length>188&&part[188]===0x47);
  return magicMp4||magicTs
    ?{ok:true}
    :{ok:false,reason:'segment_not_video'};
}
/**
 * @returns {{ok:boolean, reason?:string, quality?:number, width?:number}}
 */
export async function probeHlsFirstSegment(rawUrl,{
  fetchImpl=fetch,allowedHosts=[],origin='https://moosa-web.onrender.com',
  timeoutMs=3500,minWidth=1280
}={}){
  const hosts=[...new Set(allowedHosts.map(x=>String(x).trim().toLowerCase()))]
    .filter(h=>/^[a-z0-9.-]{4,130}$/.test(h)&&!h.startsWith('.') &&
      !h.endsWith('.local')&&!h.includes('localhost')).slice(0,15);
  const root=safeMediaUrl(rawUrl,hosts);
  if(!root||!root.pathname.endsWith('.m3u8'))
    return {ok:false,reason:'not_approved_https_hls'};
  try{
    const playlist=await fetchText(root.href,fetchImpl,timeoutMs);
    if(!playlist)return {ok:false,reason:'invalid_master'};
    let variant,media=playlist,mediaUrl=root.href;
    if(playlist.includes('#EXT-X-STREAM-INF:')){
      variant=selectMasterVariant(playlist,root.href,hosts,minWidth);
      if(!variant)return {ok:false,reason:'no_hd_variant'};
      mediaUrl=variant.url;
      media=await fetchText(mediaUrl,fetchImpl,timeoutMs);
      if(!media)return {ok:false,reason:'invalid_variant'};
    }
    if(!media.includes('#EXTINF:'))
      return {ok:false,reason:'no_media_segments'};
    const lines=media.split(/\r?\n/);
    const marker=lines.findIndex(x=>x.startsWith('#EXTINF:'));
    const segment=marker>=0?nextUri(lines,marker):null;
    if(!segment)return {ok:false,reason:'no_first_segment'};
    let full;
    try{full=new URL(segment,mediaUrl).href}catch{
      return {ok:false,reason:'invalid_segment_url'};
    }
    const allowed=safeMediaUrl(full,hosts);
    if(!allowed||!SAFE_SEGMENT_EXT.test(allowed.pathname))
      return {ok:false,reason:'untrusted_segment'};
    const verified=await checkSegment(allowed.href,fetchImpl,timeoutMs,origin);
    if(!verified.ok)return verified;
    return {ok:true,quality:variant?.height||720,width:variant?.width||1280};
  }catch{return {ok:false,reason:'timeout_or_network'};}
}
