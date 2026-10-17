/**
 * Browser contract for a future trusted, SAME-ORIGIN, server-minted
 * media capability. This never accepts a third-party CDN redirect, arbitrary
 * proxy URL, MKV, or a raw plugin-supplied header. The server remains solely
 * responsible for authorization, expiring capabilities and origin security.
 *
 * This is a compatibility shim only: deployed MOOSA currently does NOT
 * implement /api/media/:token. No browser playback is claimed.
 */
import {isDirectVideoUrl} from './direct-media-policy.js';

const TOKEN=/^\/api\/media\/[A-Za-z0-9_-]{32,128}$/;
const FORMATS=new Map([
  ['mp4','mp4'],['video/mp4','mp4'],
  ['webm','webm'],['video/webm','webm'],
  ['m3u8','hls'],['hls','hls'],
  ['application/vnd.apple.mpegurl','hls']
]);

export function trustedMediaCapability(source,origin=''){
  if(!source||source.gatewayIssued!==true||
    typeof source.url!=='string'||typeof origin!=='string')return null;
  const mediaFormat=FORMATS.get(String(source.format||source.type||'').toLowerCase());
  if(!mediaFormat)return null;
  try{
    const base=new URL(origin);
    const url=new URL(source.url);
    if(base.protocol!=='https:'||base.username||base.password||base.port ||
       url.origin!==base.origin||url.search||url.hash||
       url.username||url.password||url.port||!TOKEN.test(url.pathname))return null;
    return {url:url.href,format:mediaFormat};
  }catch{return null;}
}

export function playableMediaSource(source,origin=''){
  if(isDirectVideoUrl(source?.url)){
    const url=String(source.url);
    return {url,format:/\.m3u8(?:\?|$)/i.test(url)?'hls':
      /\.webm(?:\?|$)/i.test(url)?'webm':'mp4'};
  }
  return trustedMediaCapability(source,origin);
}
