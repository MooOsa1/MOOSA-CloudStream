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
