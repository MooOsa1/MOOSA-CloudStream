/**
 * Read-only BingeBang movie page probe. Checks whether the *fixed provider*
 * serves a TMDB movie page and whether it advertises media file references.
 * Does not fetch or publish any external media URLs, bypass access tokens,
 * or treat an HTML player as a playable source.
 */
// The provider explicitly identifies bingebang.cc as a fake domain.
const ORIGIN='https://bingebang.st';
const MAX_BYTES=98304;
const empty=(id,state)=>({provider:'BingeBang',tmdbId:id,state,
  pageAccessible:false,embeddedMediaCandidates:0,
  observedVidyServers:[],playbackVerified:false});
export async function probeBingeBangMovie({tmdbId,fetchImpl=fetch,timeoutMs=7000}={}){
  const id=Number(tmdbId);
  if(!Number.isSafeInteger(id)||id<1||id>9999999999)
    return empty(null,'invalid_id');
  const page=ORIGIN+'/movie/'+id;
  try{
    const response=await fetchImpl(page,{
      headers:{Accept:'text/html','User-Agent':'Mozilla/5.0 (compatible; MOOSA-Lab/1.0)'},
      redirect:'error',signal:AbortSignal.timeout(timeoutMs)
    });
    if(!response.ok){
      await response.body?.cancel?.().catch(()=>{});
      return empty(id,response.status===403||response.status===429?
        'provider_access_restricted':'http_'+response.status);
    }
    const contentType=response.headers.get('content-type')||'';
    if(!contentType.toLowerCase().includes('text/html')){
      await response.body?.cancel?.().catch(()=>{});
      return empty(id,'unexpected_content_type');
    }
    if(Number(response.headers.get('content-length')||0)>MAX_BYTES){
      await response.body?.cancel?.().catch(()=>{});
      return empty(id,'page_too_large');
    }
    const reader=response.body?.getReader();
    if(!reader)return empty(id,'empty_page');
    const chunks=[];
    let size=0;
    while(true){
      const next=await reader.read();
      if(next.done)break;
      size+=next.value.length;
      if(size>MAX_BYTES){
        await reader.cancel().catch(()=>{});
        return empty(id,'page_too_large');
      }
      chunks.push(next.value);
    }
    const html=new TextDecoder().decode(Buffer.concat(chunks));
    const matches=[...html.matchAll(/(?:file|src|url)\s*[:=]\s*["'](https:\/\/[^"'\s<>]{1,600}\.(?:mp4|m3u8)(?:\?[^"'\s<>]{0,300})?)["']/ig)];
    const vidy=['Miami','Boise'].filter(label=>
      new RegExp('vidy[^\\n]{0,60}'+label+'|'+label+'[^\\n]{0,60}vidy','i').test(html));
    return {provider:'BingeBang',tmdbId:id,
      state:matches.length?'embedded_candidates_found':'page_no_direct_media',
      pageAccessible:true,embeddedMediaCandidates:Math.min(matches.length,20),
      observedVidyServers:vidy,playbackVerified:false
    };
  }catch{return empty(id,'network_or_timeout');}
}
