/**
 * Read-only HDHub4u -> HubCloud handoff discovery.
 * Derived from CineStream's fixed source progression:
 * IMDb/title index -> movie post -> HubCloud entry page.
 *
 * NEVER resolves link-shorteners, follows third-party redirects, downloads
 * movies, exposes found URLs or issues MOOSA player capabilities.
 */
const INDEX='https://search.pingora.fyi/collections/post/documents/search';
const POST='https://new8.hdhub4u.cl';
const MAX_INDEX=160000, MAX_HTML=180000;
const result=(state,extra={})=>({
  provider:'HDHub4u → HubCloud',state,
  exactMoviePosts:0,hubcloudEntryPages:0,
  videoLinksVerified:0,playerReady:false,...extra
});
function normalize(text){
  return String(text||'').normalize('NFKD').toLowerCase()
    .replace(/[\u0300-\u036f]/g,'').replace(/[^a-z0-9]+/g,' ').trim();
}
async function boundedResponse(response,max,kind){
  if(!response?.ok)throw Error(kind+'_http_'+String(response?.status||0));
  const size=Number(response.headers.get('content-length')||0);
  if(size>max)throw Error(kind+'_too_large');
  const type=String(response.headers.get('content-type')||'').toLowerCase();
  if(kind==='index'&&!type.includes('json'))throw Error('unexpected_index_format');
  if(kind==='post'&&!type.includes('html'))throw Error('unexpected_post_format');
  if(!response.body)throw Error('missing_body');
  const reader=response.body.getReader(),chunks=[];
  let read=0;
  try {
    while(true){
      const part=await reader.read();
      if(part.done)break;
      read+=part.value.byteLength;
      if(read>max)throw Error(kind+'_too_large');
      chunks.push(Buffer.from(part.value));
    }
  }finally{await reader.cancel().catch(()=>{});}
  return Buffer.concat(chunks).toString('utf8');
}
function matchTitle(hit,title,year,imdb){
  const d=hit?.document;
  if(!d||typeof d!=='object')return false;
  const actual=normalize(d.post_title||d.title||'');
  const needle=normalize(title);
  if(!actual||!needle)return false;
  const hasName=actual.includes(needle);
  const yearPresent=new RegExp('(?:^| )'+year+'(?: |$)').test(actual);
  const id=String(d.imdb_id||d.imdbId||'');
  return hasName && (yearPresent || (!!imdb && id===imdb));
}
function permittedPost(path){
  if(typeof path!=='string'||path.length>800)return null;
  let input;
  try{input=new URL(path,POST);}catch{return null;}
  if(input.protocol!=='https:'||input.username||input.password||input.port||
    input.hash||!input.pathname.startsWith('/')||input.pathname.startsWith('//'))return null;
  // Rebase a domain's permalink to a fixed reviewed front-end domain;
  // never send an arbitrary remote URL supplied by the index.
  if(input.hostname!==new URL(POST).hostname &&
    !/(^|\.)hdhub4u\.[a-z]{2,12}$/i.test(input.hostname))return null;
  return new URL(input.pathname+input.search,POST).href;
}
export async function inspectHubCloudHandoff({
  title,year,imdbId='',fetchImpl=fetch,timeoutMs=8500
}={}){
  const requested=normalize(title),yr=Number(year);
  if(!requested||requested.length>100||!Number.isInteger(yr)||
    yr<1888||yr>2100|| (imdbId!==''&&!/^tt[0-9]{5,12}$/.test(imdbId)))
      return result('invalid_movie');
  try{
    const params=new URLSearchParams({
      q:imdbId||title+' '+year,
      query_by:'post_title,category,stars,director,imdb_id',
      query_by_weights:'4,2,2,2,4',
      sort_by:'sort_by_date:desc',limit:'15',
      highlight_fields:'none',use_cache:'true',page:'1'
    });
    const index=await fetchImpl(INDEX+'?'+params,{
      redirect:'error',headers:{Accept:'application/json'},
      signal:AbortSignal.timeout(timeoutMs)
    });
    const data=JSON.parse(await boundedResponse(index,MAX_INDEX,'index'));
    const hits=Array.isArray(data.hits)?data.hits.slice(0,15):[];
    const good=hits.filter(h=>matchTitle(h,title,yr,imdbId));
    const urls=[...new Set(good.map(h=>permittedPost(h.document?.permalink)).filter(Boolean))]
      .slice(0,3);
    if(!urls.length)return result('no_exact_movie_post',{
      indexHits:hits.length,exactMoviePosts:good.length});
    let pages=0,entries=0;
    const errors=[];
    for(const url of urls){
      try{
        const page=await fetchImpl(url,{
          redirect:'error',headers:{Accept:'text/html'},
          signal:AbortSignal.timeout(timeoutMs)
        });
        const html=await boundedResponse(page,MAX_HTML,'post');
        pages++;
        for(const link of html.matchAll(/<a\b[^>]*href\s*=\s*["']([^"']{1,900})["'][^>]*>/gi)){
          try{
            const target=new URL(link[1],url);
            if(target.protocol!=='https:'||target.username||target.password||
              target.port||target.hash)continue;
            if(/(^|\.)hubcloud\.[a-z]{2,12}$/i.test(target.hostname)||
              /(^|\.)vcloud\.[a-z]{2,12}$/i.test(target.hostname))entries++;
          }catch{}
        }
        if(entries)break;
      }catch(error){
        errors.push(String(error?.message||'post_failed').replace(/[^a-z0-9_]/gi,'').slice(0,50));
      }
    }
    return result(entries?'hubcloud_entry_found':pages?'no_hubcloud_entry':'post_unavailable',{
      indexHits:hits.length,exactMoviePosts:good.length,
      moviePagesAccessible:pages,hubcloudEntryPages:entries,
      ...(errors.length?{errorClass:errors[0]}:{})
    });
  }catch(error){
    const message=String(error?.message||'');
    return result(/^index_http_[0-9]{3}$/.test(message)?message:'index_unavailable');
  }
}
