/**
 * Trust only normal HTTPS SRT/VTT files and the precise extensionless
 * OpenSubtitles v3 CDN URL format verified live on October 16 2026.
 *
 * No arbitrary /download route, URL rewriting, wildcard redirects,
 * private DNS name, IP, credentials or user-provided proxy.
 */
const DIRECT_SUBTITLE=/\.(?:srt|vtt)$/i;
const OFFICIAL_HOST=/^subs\d*\.strem\.io$/i;
const OFFICIAL_PATH=/^\/[a-z]{2}\/download\/subencoding-stremio-utf8\/src-api\/file\/\d{4,20}$/i;
const UNSAFE_HOST=/(?:^localhost$|\.localhost$|\.local$|\.internal$|\.test$|\.invalid$)$/i;
export function safeSubtitleFileUrl(raw){
  if(typeof raw!=='string'||raw.length<12||raw.length>2500)return null;
  let u;
  try{u=new URL(raw);}catch{return null;}
  if(u.protocol!=='https:'||u.username||u.password||u.port||
    !u.hostname||u.hostname.includes(':')||UNSAFE_HOST.test(u.hostname)||
    /^(?:\d+\.){3}\d+$/.test(u.hostname))return null;
  if(DIRECT_SUBTITLE.test(u.pathname))return u.href;
  if(OFFICIAL_HOST.test(u.hostname) && OFFICIAL_PATH.test(u.pathname) &&
    !u.search&&!u.hash)return u.href;
  return null;
}
export function isOfficialStremioSubtitleFile(url){
  const allowed=safeSubtitleFileUrl(url);
  if(!allowed)return false;
  const u=new URL(allowed);
  return OFFICIAL_HOST.test(u.hostname)&&OFFICIAL_PATH.test(u.pathname)&&
    !u.search&&!u.hash;
}
