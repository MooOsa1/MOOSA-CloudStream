/**
 * Playback continuity guard for two byte-verified sources already attached
 * to the SAME selected movie or episode. Different cuts/editions can have
 * a different timeline, so never seek blindly to the old timestamp.
 *
 * Does not authenticate a film's identity or grant streaming rights.
 */
export function compatibleResumeTime({position,oldDuration,newDuration}={}){
  const seekTo=Number(position),before=Number(oldDuration),after=Number(newDuration);
  if(!Number.isFinite(seekTo)||!Number.isFinite(before)||
     !Number.isFinite(after)||before<1200||after<1200||
     seekTo<30||seekTo>=before-20||seekTo>=after-20)return null;
  // Tolerate small release-encoding duration differences, but refuse
  // noticeably different editions that may have shifted scenes.
  if(Math.abs(before-after)>Math.max(90,before*0.025))return null;
  return Math.floor(seekTo*10)/10;
}
