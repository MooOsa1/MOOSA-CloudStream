/**
 * Small opt-in MOOSA-origin video capability gateway.
 *
 * The issuer is INTERNAL ONLY. There is deliberately no HTTP endpoint that
 * accepts a URL or authorizes media on behalf of an anonymous visitor.
 * A vetted, rights-approved operator adapter must issue each capability.
 * It does not defeat 403/426, anti-bot pages, signed-link expiry or DRM.
 *
 * No third-party GPL/AGPL code included.
 */
import {randomBytes} from 'node:crypto';
import {Readable} from 'node:stream';
import {pipeline} from 'node:stream/promises';

const ALLOWED_FORMATS=new Map([
  ['mp4','video/mp4'],['video/mp4','video/mp4'],
  ['webm','video/webm'],['video/webm','video/webm'],
  ['m3u8','application/vnd.apple.mpegurl'],['hls','application/vnd.apple.mpegurl'],
  ['application/vnd.apple.mpegurl','application/vnd.apple.mpegurl'],
  ['ts','video/mp2t'],['video/mp2t','video/mp2t']
]);
const HEADER_NAMES=new Set(['referer','origin','user-agent','accept']);
const PATH=/^\/api\/media\/([a-zA-Z0-9_-]{32,128})$/;
const PLAYLIST_LIMIT=2*1024*1024;

function mediaUrl(raw,allowedHosts){
  if(typeof raw!=='string'||raw.length>4000)throw Error('Invalid media URL');
  const u=new URL(raw);
  if(u.protocol!=='https:'||u.username||u.password||u.port||u.hash||
    !allowedHosts.has(u.hostname.toLowerCase()) ||
    /^\d+(?:\.\d+){3}$/.test(u.hostname)||u.hostname.includes(':')||
    /(?:^|\.)(?:localhost|local|internal)$/.test(u.hostname))
      throw Error('Upstream not on operator allowlist');
  return u;
}
function safeHeaders(value){
  const clean={};
  if(value && typeof value==='object'&&!Array.isArray(value)){
    for(const [name,raw] of Object.entries(value)){
      const key=name.toLowerCase();
      if(HEADER_NAMES.has(key)&&typeof raw==='string'&&raw.length<=512&&
        !/[\r\n]/.test(raw))clean[key]=raw;
    }
  }
  return clean;
}
function respond(res,status,message='Unavailable'){
  res.writeHead(status,{'Content-Type':'text/plain; charset=utf-8',
    'X-Content-Type-Options':'nosniff','Cache-Control':'no-store'});
  res.end(message);
}
async function boundedText(response,max=PLAYLIST_LIMIT){
  const reader=response.body?.getReader();
  if(!reader)throw Error('Empty HLS playlist');
  const parts=[];let size=0;
  try{
    while(true){
      const {value,done}=await reader.read();
      if(done)break;
      size+=value.length;
      if(size>max)throw Error('HLS playlist oversized');
      parts.push(Buffer.from(value));
    }
  }finally{await reader.cancel().catch(()=>{});}
  return Buffer.concat(parts).toString('utf8');
}
function sniffContentMime(response,format){
  const type=(response.headers.get('content-type')||'').split(';')[0]
    .trim().toLowerCase();
  if(/(?:html|json|xml|text\/plain)/i.test(type))throw Error('Upstream returned non-video content');
  if(format==='application/vnd.apple.mpegurl'){
    if(type && !/mpegurl|octet-stream/i.test(type))throw Error('Not an HLS MIME');
  }else{
    // Neither 200 nor a video-looking name is proof of media bytes. The
    // browser/ffmpeg final codec+frame check remains mandatory.
    if(type && !/^video\/(?:mp4|webm|mp2t)$/.test(type) &&
      type!=='application/octet-stream')throw Error('Not a video MIME');
  }
}

export function createMediaGateway({
  allowedHosts=[],origin,fetchImpl=fetch,clock=()=>Date.now(),
  ttlMs=5*60_000,maxEntries=2000
}={}){
  const base=new URL(origin);
  if(base.protocol!=='https:'||base.pathname!=='/'||base.search||
    base.username||base.password||base.port)throw Error('Public HTTPS origin required');
  const hosts=new Set(allowedHosts.map(x=>String(x).toLowerCase()));
  if(!hosts.size)throw Error('At least one operator-approved media host required');
  const entries=new Map();
  function add({url,format,headers={},rightsApproved=false,sourceVerified=false},internal=false){
    if(!internal&&(!rightsApproved||!sourceVerified))
      throw Error('Trusted film source verification required');
    const target=mediaUrl(url,hosts);
    const mime=ALLOWED_FORMATS.get(String(format||'').toLowerCase());
    if(!mime)throw Error('Unsupported media format');
    const token=randomBytes(32).toString('base64url');
    if(entries.size>=maxEntries){
      for(const [key,entry] of entries){
        if(entry.expiry<=clock())entries.delete(key);
      }
      if(entries.size>=maxEntries)entries.delete(entries.keys().next().value);
    }
    entries.set(token,{url:target.href,mime,headers:safeHeaders(headers),
      expiry:clock()+ttlMs});
    return {
      url:base.origin+'/api/media/'+token,gatewayIssued:true,
      format:mime==='application/vnd.apple.mpegurl'?'m3u8':
        mime==='video/mp2t'?'ts':mime
    };
  }
  function issue(input){return add(input);}
  // Perform a LIMITED read-only probe BEFORE putting a server under Play.
  // Upstream source sites may return 200 HTML (VaPlayer), 400 or 426.
  // Only actual media byte signatures plus compatible HLS child resources
  // qualify. This cannot prove movie identity, full duration or playback.
  async function issueIfBytesVerified(input){
    if(input?.rightsApproved!==true||input?.sourceVerified!==true)
      return {source:null,reason:'operator_approval_required'};
    const kind=ALLOWED_FORMATS.get(String(input?.format||'').toLowerCase());
    if(!kind)return {source:null,reason:'unsupported_media_type'};
    let target;
    try{target=mediaUrl(input.url,hosts);}
    catch{return {source:null,reason:'not_on_operator_allowlist'};}
    const reqHeaders=safeHeaders(input.headers);
    let result='unknown';
    async function prefix(response,maxBytes=4096){
      const reader=response.body?.getReader();
      if(!reader)return Buffer.alloc(0);
      let size=0;
      const chunks=[];
      try{
        while(size<maxBytes){
          const part=await reader.read();
          if(part.done)break;
          const bytes=Buffer.from(part.value);
          chunks.push(bytes.subarray(0,maxBytes-size));
          size+=Math.min(bytes.length,maxBytes-size);
        }
      }finally{await reader.cancel().catch(()=>{});}
      return Buffer.concat(chunks,size);
    }
    async function verify(url,format,depth=0){
      if(depth>3)return 'too_many_hls_levels';
      let remote;
      try{remote=mediaUrl(url,hosts);}
      catch{return 'hls_subresource_not_allowlisted';}
      const playlist=format==='application/vnd.apple.mpegurl';
      let response;
      try{
        response=await fetchImpl(remote.href,{
          method:'GET',redirect:'error',
          headers:{...reqHeaders,...(playlist?{}:{range:'bytes=0-4095'})},
          signal:AbortSignal.timeout(10000)
        });
        if(![200,206].includes(response.status))
          return 'upstream_http_'+String(response.status);
        try{sniffContentMime(response,format);}
        catch{return 'upstream_returned_nonvideo_mime';}
        if(playlist){
          let text;
          try{text=await boundedText(response,256*1024);}
          catch{return 'invalid_or_oversized_hls';}
          if(!text.startsWith('#EXTM3U'))return 'invalid_hls_header';
          if(/#EXT-X-(?:SESSION-)?KEY:(?![^\r\n]*METHOD=NONE)/i.test(text))
            return 'encrypted_hls_unsupported';
          const first=text.split(/\r?\n/).map(x=>x.trim()).find(x=>x&&!x.startsWith('#'));
          if(!first)return 'empty_hls_media';
          let child;
          try{child=mediaUrl(new URL(first,remote).href,hosts);}
          catch{return 'hls_subresource_not_allowlisted';}
          const extension=child.pathname.toLowerCase().match(/\.(m3u8|mp4|webm|ts)$/)?.[1];
          if(!extension)return 'unrecognized_hls_segment';
          return verify(child.href,ALLOWED_FORMATS.get(extension),depth+1);
        }
        const bytes=await prefix(response);
        if(format==='video/mp4'){
          const box=bytes.subarray(4,8).toString('latin1');
          return bytes.length>=12&&['ftyp','styp','moof'].includes(box)
            ?'media_bytes_verified':'wrong_media_bytes';
        }
        if(format==='video/webm'){
          return bytes.length>=4&&bytes.subarray(0,4).equals(
            Buffer.from([0x1a,0x45,0xdf,0xa3]))
            ?'media_bytes_verified':'wrong_media_bytes';
        }
        if(format==='video/mp2t'){
          return bytes.length>=377 &&
            bytes[0]===0x47&&bytes[188]===0x47&&bytes[376]===0x47
            ?'media_bytes_verified':'wrong_media_bytes';
        }
        return 'unsupported_media_bytes';
      }catch{return 'upstream_network_error';}
      finally{
        if(response?.body&&!response.body.locked)
          await response.body.cancel().catch(()=>{});
      }
    }
    result=await verify(target.href,kind);
    if(result!=='media_bytes_verified')
      return {source:null,reason:result};
    // Only an operator-approved exact film with observed real media bytes
    // receives a browser-facing capability. No public issue endpoint.
    return {source:issue(input),reason:'media_bytes_verified'};
  }

  function find(token){
    const value=entries.get(token);
    if(!value)return null;
    if(value.expiry<=clock()){entries.delete(token);return null;}
    return value;
  }
  function rewrittenHls(text,parent){
    if(!text.startsWith('#EXTM3U'))throw Error('Invalid HLS manifest');
    if(/#EXT-X-(?:SESSION-)?KEY:(?![^\r\n]*METHOD=NONE)/i.test(text))
      throw Error('Encrypted/DRM HLS unsupported by this restricted gateway');
    const child=(raw)=>{
      const target=mediaUrl(new URL(raw,parent.url).href,hosts);
      const extension=target.pathname.toLowerCase().match(/\.(m3u8|mp4|webm|ts)$/)?.[1];
      if(!extension)throw Error('Unsupported HLS subresource');
      return add({url:target.href,format:extension,headers:parent.headers},true).url;
    };
    return text.split(/\r?\n/).map(line=>{
      const trimmed=line.trim();
      if(!trimmed)return line;
      if(trimmed.startsWith('#')){
        if(!/^#EXT-X-(?:MAP|MEDIA|I-FRAME-STREAM-INF|SESSION-DATA|KEY):/i.test(trimmed))
          return line;
        return line.replace(/URI="([^"]+)"/g,(_match,url)=>'URI="'+child(url)+'"');
      }
      return child(trimmed);
    }).join('\n');
  }
  async function handle(req,res,requestUrl){
    const match=String(requestUrl?.pathname||'').match(PATH);
    if(!match)return respond(res,404);
    if(!['GET','HEAD'].includes(req.method))return respond(res,405);
    if(requestUrl.search||requestUrl.hash)return respond(res,400);
    const item=find(match[1]);
    if(!item)return respond(res,404,'Expired or invalid media token');
    const headers={...item.headers};
    const range=req.headers.range;
    if(range){
      if(typeof range!=='string'||!/^bytes=\d+-\d*$/.test(range))
        return respond(res,416,'Invalid byte range');
      headers.range=range;
    }
    let upstream;
    try{
      upstream=await fetchImpl(item.url,{method:req.method,headers,
        redirect:'error',signal:AbortSignal.timeout(20000)});
      if(![200,206].includes(upstream.status))return respond(res,502,'Media source unavailable');
      sniffContentMime(upstream,item.mime);
      if(item.mime==='application/vnd.apple.mpegurl'&&req.method!=='HEAD'){
        if(range)return respond(res,416,'HLS manifests cannot be ranged');
        const raw=await boundedText(upstream);
        const output=rewrittenHls(raw,item);
        res.writeHead(200,{'Content-Type':item.mime,
          'Content-Length':Buffer.byteLength(output),
          'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
        res.end(output);
        return;
      }
      const safe={'Content-Type':item.mime,
        'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
        'Accept-Ranges':upstream.headers.get('accept-ranges')||'bytes'};
      for(const name of ['content-length','content-range']){
        const value=upstream.headers.get(name);
        if(value)safe[name]=value;
      }
      res.writeHead(upstream.status,safe);
      if(req.method==='HEAD'||!upstream.body){res.end();return;}
      await pipeline(Readable.fromWeb(upstream.body),res);
    }catch{
      if(!res.headersSent)return respond(res,502,'Media upstream failed safety or media checks');
      res.destroy();
    }finally{
      if(upstream?.body&&!upstream.body.locked){
        await upstream.body.cancel().catch(()=>{});
      }
    }
  }
  return {issue,issueIfBytesVerified,handle,activeTokenCount:()=>entries.size};
}
