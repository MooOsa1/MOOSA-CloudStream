/**
 * Read-only 4KHDHub -> HubCloud handoff probe for private MOOSA Lab.
 * High-risk directory: strict fixed-origin, no redirects, no scripts,
 * no file downloads, and no outgoing requests to extracted destinations.
 * All outputs are counts and failure states, never links or credentials.
 */
const SITE='https://4khdhub.one',CAP=150000;
const status=(state,extra={})=>({
  provider:'4KHDHub → HubCloud',state,
  exactMoviePosts:0,hubcloudEntryPages:0,videoLinksVerified:0,
  playerReady:false,...extra
});
function norm(text){
  return String(text||'').replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,' ')
    .replace(/<[^>]*>/g,' ').replace(/&(?:nbsp|amp|quot|#39);/gi,' ')
    .replace(/[^a-zA-Z0-9]+/g,' ').toLowerCase().trim();
}
async function html(response){
  if(!response?.ok)throw Error('http_'+(response?.status||0));
  if(!String(response.headers.get('content-type')||'').includes('text/html'))
    throw Error('non_html_response');
  if(Number(response.headers.get('content-length')||0)>CAP)
    throw Error('page_oversized');
  if(!response.body)throw Error('empty_body');
  const reader=response.body.getReader(),pieces=[];let total=0;
  try{
    while(true){
      const next=await reader.read();if(next.done)break;
      total+=next.value.byteLength;
      if(total>CAP)throw Error('page_oversized');
      pieces.push(Buffer.from(next.value));
    }
  }finally{await reader.cancel().catch(()=>{});}
  return Buffer.concat(pieces).toString('utf8');
}
function safePost(raw){
  if(typeof raw!=='string'||raw.length>500)return null;
  let u;
  try{u=new URL(raw,SITE);}catch{return null;}
  return u.origin===SITE&&!u.username&&!u.password&&!u.hash&&!u.port&&
    u.pathname!=='/'&&!u.pathname.startsWith('//')?u.href:null;
}
export function extract4khdhubCards(input,title,year){
  const needle=' '+norm(title)+' ',ymarker=' '+year+' ';
  if(needle.trim().length<2)return [];
  const cards=String(input||'').matchAll(
    /<a\b[^>]*href=["']([^"']{1,500})["'][^>]*>([\s\S]{0,6000}?)<\/a\s*>/gi);
  const list=[];
  for(const match of cards){
    if(!match[2].includes('movie-card-content'))continue;
    const text=' '+norm(match[2])+' ';
    if(!text.includes(needle)||!text.includes(ymarker))continue;
    const safe=safePost(match[1]);
    if(safe&&!list.includes(safe))list.push(safe);
    if(list.length>=3)break;
  }
  return list;
}
/**
 * CineStream invokeHdhub4u() selects:
 *   div.mod a[data-wpel-link=external]
 * Those intermediate links are NOT required to mention HubCloud in the URL.
 * Inspect anchor attributes in either order, without ever following URLs.
 */
export function classifyCineStreamAnchors(markup,base=SITE){
  const source=String(markup||'');
  const hasModule=/<div\b[^>]*class\s*=\s*["'][^"']*\bmod\b[^"']*["']/i.test(source);
  let count=0,hubHints=0,redirectHints=0;
  const seen=new Set();
  if(!hasModule)return {moduleFound:false,externalSourceAnchors:0,
    directHubHints:0,redirectorHints:0};
  for(const m of source.matchAll(/<a\b[^>]{0,2000}>/gi)){
    const tag=m[0];
    if(!/\bdata-wpel-link\s*=\s*["']?external\b/i.test(tag))continue;
    const attr=tag.match(/\bhref\s*=\s*(["'])([^"']{1,900})\1/i);
    if(!attr)continue;
    let target;
    try{target=new URL(attr[2],base);}catch{continue;}
    if(target.protocol!=='https:'||target.username||target.password||
       target.port||target.hash||seen.has(target.href))continue;
    seen.add(target.href);count++;
    if(/(?:^|\.)(?:hubcloud|vcloud|hubdrive)\./i.test(target.hostname))
      hubHints++;
    if(/(?:^|\.)(?:greenmountmotors|hubcdn)\./i.test(target.hostname))
      redirectHints++;
    if(count>=30)break;
  }
  return {moduleFound:true,externalSourceAnchors:count,
    directHubHints:hubHints,redirectorHints:redirectHints};
}
export async function probe4khdhubHandoff({
  title,year,fetchImpl=fetch,timeoutMs=8500
}={}){
  const name=norm(title),yr=Number(year);
  if(name.length<2||name.length>100||!Number.isInteger(yr)||
     yr<1888||yr>2100)return status('invalid_movie');
  try{
    const url=new URL('/',SITE);url.searchParams.set('s',String(title));
    const search=await fetchImpl(url.href,{
      redirect:'error',headers:{Accept:'text/html'},
      signal:AbortSignal.timeout(timeoutMs)
    });
    const index=await html(search),cards=extract4khdhubCards(index,title,yr);
    if(!cards.length)return status('no_exact_movie_card');
    let posts=0,entries=0,externalLinks=0,downloadRowLinks=0;
    const cine={moduleFound:false,externalSourceAnchors:0,directHubHints:0,redirectorHints:0};
    const hostGroups={hubLike:0,fileHost:0,shortener:0,other:0};
    for(const href of cards){
      try{
        const response=await fetchImpl(href,{
          redirect:'error',headers:{Accept:'text/html'},
          signal:AbortSignal.timeout(timeoutMs)
        });
        const body=await html(response);posts++;
        const inspected=classifyCineStreamAnchors(body,href);
        cine.moduleFound||=inspected.moduleFound;
        cine.externalSourceAnchors+=inspected.externalSourceAnchors;
        cine.directHubHints+=inspected.directHubHints;
        cine.redirectorHints+=inspected.redirectorHints;
        for(const m of body.matchAll(/<a\b[^>]*href=["']([^"']{1,800})["'][^>]*>/gi)){
          let target;
          try{target=new URL(m[1],href);}catch{continue;}
          if(target.protocol!=='https:'||target.username||target.password||
             target.port||target.hash)continue;
          if(target.origin!==SITE){
            externalLinks++;
            const host=target.hostname.toLowerCase();
            if(/(?:^|\.)(?:hubcloud|vcloud|hubdrive|hubcdn|hblinks|hubstream)\./.test(host))
              hostGroups.hubLike++;
            else if(/(?:^|\.)(?:pixeldrain|gofile|filepress|filemoon|gdflix)\./.test(host))
              hostGroups.fileHost++;
            else if(/(?:^|\.)(?:hrefli|linkvertise|shrinkme|shorturl|ouo)\./.test(host))
              hostGroups.shortener++;
            else hostGroups.other++;
          }
          const nearby=body.slice(Math.max(0,m.index-700),m.index);
          if(/download-item|download-links|download-button/i.test(nearby))
            downloadRowLinks++;
          if(/(^|\.)hubcloud\.[a-z]{2,12}$|(^|\.)vcloud\.[a-z]{2,12}$/i
             .test(target.hostname))entries++;
        }
        if(entries)break;
      }catch{}
    }
    return status(entries?'hubcloud_entry_found':
      cine.externalSourceAnchors?'cinestream_source_anchors':
      downloadRowLinks?'intermediate_download_links':
      posts?'no_hubcloud_entry':'post_unavailable',{
      exactMoviePosts:cards.length,postPagesAccessible:posts,
      hubcloudEntryPages:entries,externalLinks,downloadRowLinks,hostGroups,
      cineStreamAnchors:cine
    });
  }catch(e){
    const reason=String(e?.message||'');
    return status(/^http_[0-9]{3}$/.test(reason)?reason:
      reason==='non_html_response'?'non_html_search':'search_unavailable');
  }
}
