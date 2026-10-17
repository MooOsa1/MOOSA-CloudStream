/**
 * MOOSA Web source priority policy: native 4K first (2160), then FHD
 * (1080), then the minimum acceptable verified HD (720). Codec flags and
 * user-preferred provenance only break ties in resolution. Actual encoded
 * dimensions are checked separately by the server and browser.
 */
export function comparePlaybackSources(a,b){
  const quality=x=>{
    const raw=x?.quality;
    // Reject noncanonical strings (exponents, hex, whitespace, padded labels).
    // Quality remains only a ranking hint, never decoded-resolution proof.
    if(typeof raw!=='number' && !(typeof raw==='string' && /^(?:[1-9][0-9]*)$/.test(raw)))return 0;
    const n=Number(raw);
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
