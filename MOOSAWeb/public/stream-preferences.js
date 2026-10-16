/**
 * MOOSA Web source priority policy: native 4K first (2160), then FHD
 * (1080), then the minimum acceptable verified HD (720). Codec flags and
 * user-preferred provenance only break ties in resolution. Actual encoded
 * dimensions are checked separately by the server and browser.
 */
export function comparePlaybackSources(a,b){
  const quality=x=>{
    const n=Number(x?.quality);
    return Number.isInteger(n)&&n>=720&&n<=4320?n:0;
  };
  return quality(b)-quality(a) ||
    Number(b?.codecOptimized===true)-Number(a?.codecOptimized===true) ||
    Number(b?.preferred===true)-Number(a?.preferred===true);
}
/** Arabic subtitle choices first; then English. Stable ordering within
 * languages avoids disturbing source/edition descriptions. This does not
 * claim any subtitle is synchronized until playback validates its cues. */
export function compareSubtitleTracks(a,b){
  return Number(b?.language==='ar')-Number(a?.language==='ar');
}
