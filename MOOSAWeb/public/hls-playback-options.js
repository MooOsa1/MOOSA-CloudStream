/**
 * 4K-first playback must not be silently capped to the rendered video size.
 * The old capLevelToPlayerSize:true forced low-resolution renditions on
 * narrow mobile player windows even when a 2160p track was available.
 * Keep adaptive bandwidth selection; the existing decoded-dimension guard
 * rejects actual resolutions below 1280x720.
 */
export function hlsPlayerOptions(){
  return {
    enableWorker:true,
    capLevelToPlayerSize:false,
    backBufferLength:40
  };
}

/**
 * hls.js auto-ABR must not choose a sub-HD rendition when this SAME HLS
 * master advertises a verified >=1280x720 variant and bitrate metadata
 * clearly separates the HD and SD levels. This does not force 4K and
 * does not mistake a master-advertised label for decoded frames.
 *
 * Fail open to the existing decoded-dimensions gate when metadata is
 * absent or contradictory. Never lower an already stricter floor.
 */
export function keepHlsAutoAtLeastHd(engine){
  if(!engine || !Array.isArray(engine.levels) || !engine.config)return false;
  const levels=engine.levels;
  if(levels.length<2)return false;
  let maxSd=0, minHd=Infinity, sdCount=0, hdCount=0;
  for(const level of levels){
    const height=Number(level?.height),width=Number(level?.width);
    const raw=Number(level?.maxBitrate ?? level?.bitrate);
    if(!Number.isFinite(height)||!Number.isFinite(raw)||raw<=0||
      !Number.isInteger(height)||height<1)return false;
    // A height of 720 without a declared width is accepted as a plausible
    // HD rendition; actual videoWidth >= 1280 is checked after decoding.
    const isHd=height>=720 && (!Number.isFinite(width)||width===0||width>=1280);
    if(isHd){hdCount++;minHd=Math.min(minHd,raw);}
    else{sdCount++;maxSd=Math.max(maxSd,raw);}
  }
  if(!hdCount || !sdCount || maxSd>=minHd)return false;
  const previous=Number(engine.config.minAutoBitrate)||0;
  engine.config.minAutoBitrate=Math.max(previous,maxSd+1);
  return true;
}
