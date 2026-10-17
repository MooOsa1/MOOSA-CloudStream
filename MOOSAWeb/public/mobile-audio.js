/**
 * iOS Safari/WebKit requires sound-enabled media play() to be initiated
 * directly from a user gesture. Keep this independent of the source provider
 * and never claim that an unmuted video proves audible PCM.
 */
export function needsIosSoundGesture(ua='', platform='', touchPoints=0){
  return /iPhone|iPad|iPod/i.test(String(ua)) ||
    (String(platform)==='MacIntel' && Number(touchPoints)>1);
}

export function audioPlaybackState(media){
  const trackCount=typeof media?.audioTracks?.length==='number'
    ?media.audioTracks.length:null;
  return {
    sourceLoaded:Boolean(media && (media.currentSrc||media.src) && media.readyState>=1),
    playing:Boolean(media && !media.paused && media.readyState>=2),
    unmuted:Boolean(media && !media.muted && Number(media.volume)>0),
    trackCount
  };
}

export function resumeWithSound(media){
  // No await, setTimeout, or stream lookup before play(). On iOS the click's
  // transient user activation must still be active when play() is called.
  if(!media || !(media.currentSrc||media.src)){
    return Promise.reject(new Error('missing_media_source'));
  }
  media.muted=false;
  if(Number(media.volume)===0){
    try{media.volume=1;}catch{ /* iOS system volume may be read-only */ }
  }
  return media.play();
}
