/**
 * Browser presentation only: prefer the independently licensed official
 * Elephants Dream 4:2:0 transcode on iOS WebKit when the older original
 * is also present. The original remains on non-iOS browsers and remains
 * available on iOS if the compatible variant is not present.
 *
 * Does not grant a stream license, fetch code, or modify source URLs.
 */
const ELEPHANTS_FILE='https://commons.wikimedia.org/wiki/File:Elephants_Dream_(2006).webm';

export function isIosWebKit(userAgent=''){
  const ua=String(userAgent);
  return /\b(?:iPhone|iPad|iPod)\b/i.test(ua) ||
    /\bMacintosh\b/i.test(ua) && /\bMobile\//i.test(ua);
}
function originalMedia(source){
  if(source?.sourcePage!==ELEPHANTS_FILE ||
     typeof source.url!=='string')return false;
  try {
    const u=new URL(source.url);
    return u.protocol==='https:' && u.hostname==='upload.wikimedia.org'
      && u.pathname.toLowerCase().endsWith('.webm');
  }catch{return false;}
}
export function compatibleElephantsSource(source){
  return originalMedia(source) && source.codecOptimized===true &&
    typeof source.licenseUrl==='string' &&
    /^https:\/\/creativecommons\.org\//i.test(source.licenseUrl);
}
export function chooseBrowserSources(sources,userAgent=''){
  if(!Array.isArray(sources))return [];
  if(!isIosWebKit(userAgent))return [...sources];
  const replacements=sources.filter(compatibleElephantsSource);
  if(!replacements.length)return [...sources];
  return sources.filter(source=>{
    if(!originalMedia(source)||source.codecOptimized===true)return true;
    // Never hide an unrelated source or an independent original if its
    // exact same licensed official transcode has not been provided.
    return !replacements.some(replacement=>
      replacement.licenseUrl===source.licenseUrl &&
      replacement.sourcePage===source.sourcePage);
  });
}
