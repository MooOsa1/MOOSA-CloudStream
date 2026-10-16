/**
 * Pure local source retry policy. No URL fetching or rights decisions here.
 * Called only for alternatives already attached to the SAME selected title.
 */
function format(url){
  try {
    const u=new URL(url);
    if(u.protocol!=='https:'||u.username||u.password)return '';
    const ext=u.pathname.toLowerCase().match(/\.(mp4|m4v|webm|m3u8)$/);
    return ext?.[1]||'';
  }catch{return '';}
}
export function nextPlaybackSource(sources,attempted,canPlayType=()=>'',maxTries=4){
  if(!Array.isArray(sources)||!attempted || attempted.size>=maxTries)return null;
  const candidates=sources.flatMap((source,index)=>{
    const type=format(source?.url);
    if(!type||attempted.has(source.url))return [];
    const mime=type==='webm'?'video/webm; codecs="vp9, opus"':
      type==='mp4'||type==='m4v'?'video/mp4':'application/vnd.apple.mpegurl';
    const supported=type==='m3u8'?0:(canPlayType(mime)?2:0);
    const preferred=source?.codecOptimized===true?1:0;
    return [{index,source,score:supported*10+preferred*2+(source?.preferred?1:0)}];
  });
  candidates.sort((a,b)=>b.score-a.score || a.index-b.index);
  return candidates[0]||null;
}
