/**
 * Browser-only, no-iframe media URL gate.
 *
 * Accepts direct HTTPS HLS/video files for MOOSA's own <video> element,
 * not ad-hosted web player pages, data: URLs, magnets, javascript: or
 * arbitrary remote HTML. URLs are still *candidates*; this cannot detect
 * advertising baked into media or prove media rights/actual resolution.
 */
const MEDIA_EXTENSION=/\.(?:m3u8|mp4|webm|mov)$/i;
const BLOCKED_HOST=/^(?:localhost|[^.]+\.localhost|[^.]+\.local)$/i;
const PRIVATE_V4=/^(?:(?:10|127|0)\.|192\.168\.|169\.254\.|172\.(?:1[6-9]|2\d|3[01])\.)/;

export function isDirectVideoUrl(raw){
  if(typeof raw!=='string'||raw.length>3000||/\s/.test(raw))return false;
  try{
    const u=new URL(raw);
    if(u.protocol!=='https:'||u.username||u.password||u.port||u.hash)return false;
    if(!u.hostname||BLOCKED_HOST.test(u.hostname)||PRIVATE_V4.test(u.hostname)||
       u.hostname.includes(':'))return false;
    return MEDIA_EXTENSION.test(u.pathname);
  }catch{return false;}
}
