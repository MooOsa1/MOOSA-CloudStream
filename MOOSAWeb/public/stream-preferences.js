/**
 * User-selected FHD playback priority: stable 1080p first, larger sources
 * as optional fallbacks, then >=720p HD when FHD is unavailable.
 * This avoids automatically starting giant 4K files when an authorized
 * 1080p derivative exists. Browser decoder still checks real dimensions.
 */
export function comparePlaybackSources(a,b){
  const quality=x=>{
    const n=Number(x?.quality);
    return Number.isInteger(n)&&n>=720&&n<=4320?n:0;
  };
  const rank=q=>{
    if(q===1080)return 5000;
    if(q>1080)return 4000-(q-1080)/1000;
    if(q>=720)return 2000+q;
    return 0;
  };
  return rank(quality(b))-rank(quality(a)) ||
    Number(b?.codecOptimized===true)-Number(a?.codecOptimized===true) ||
    Number(b?.preferred===true)-Number(a?.preferred===true);
}
/** Arabic subtitle choices first; then English. Stable ordering within
 * languages avoids disturbing source/edition descriptions. This does not
 * claim any subtitle is synchronized until playback validates its cues. */
export function compareSubtitleTracks(a,b){
  return Number(b?.language==='ar')-Number(a?.language==='ar');
}
