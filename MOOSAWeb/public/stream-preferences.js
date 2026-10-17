/**
 * MOOSA playback policy: highest verified resolution first (4K -> 1440p ->
 * 1080p -> 720p). A codec-optimized or preferred source breaks ties ONLY
 * within the same resolution. Browser playback still verifies actual
 * decoded dimensions; failed 4K can fall back to the next source.
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
