/* Verified URL construction only: never proxy or expose a user torrent service remotely. */
const HASH=/^[a-f0-9]{40}$/i;
export function localStremioHls(stream){
  if(!stream||!HASH.test(String(stream.infoHash||'')))return null;
  const idx=Number(stream.fileIdx);
  if(!Number.isInteger(idx)||idx< -1||idx>9999)return null;
  return 'http://127.0.0.1:11470/'+stream.infoHash.toLowerCase()+'/'+idx+'/hls.m3u8';
}
export function validMovieId(id){
  return typeof id==='string'&&/^tt[0-9]{7,10}$/.test(id);
}
