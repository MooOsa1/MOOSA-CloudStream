/**
 * Target CineStream's actual hdhub4uAPI from SaurabhKaperwan/Utils/urls.json,
 * not an unrelated 4KHDHub website. Safe read-only inventory; fixed HTTPS
 * origin, strict bounded HTML, no redirects, no file or remote host requests.
 */
import {classifyCineStreamAnchors} from './4khdhub-handoff.mjs';
const ROOT='https://new8.hdhub4u.cl',MAX=160000;
async function limitedHtml(fetchImpl,url,timeoutMs){
  const r=await fetchImpl(url,{redirect:'error',headers:{Accept:'text/html'},
    signal:AbortSignal.timeout(timeoutMs)});
  if(!r.ok)throw Error('http_'+r.status);
  if(!String(r.headers.get('content-type')||'').includes('text/html'))
    throw Error('not_html');
  if(Number(r.headers.get('content-length')||0)>MAX)throw Error('too_large');
  if(!r.body)throw Error('no_body');
  const reader=r.body.getReader(),parts=[];let len=0;
  try{
    while(true){
      const item=await reader.read();if(item.done)break;
      len+=item.value.byteLength;if(len>MAX)throw Error('too_large');
      parts.push(Buffer.from(item.value));
    }
  }finally{await reader.cancel().catch(()=>{});}
  return Buffer.concat(parts).toString('utf8');
}
function safePost(raw){
  let u;
  try{u=new URL(raw,ROOT);}catch{return null;}
  if(u.origin!==ROOT||u.port||u.hash||u.username||u.password||
     u.pathname==='/'||u.pathname.startsWith('//'))return null;
  return u.href;
}
function normalized(t){return String(t||'').replace(/<[^>]+>/g,' ').replace(/[^a-z0-9]+/ig,' ').toLowerCase().trim();}
export function directHdhubMoviePosts(markup,title,year){
  const name=normalized(title),found=[];
  const candidate=String(markup||'').matchAll(/<a\b([^>]{0,1100})>([\s\S]{0,3500}?)<\/a>/gi);
  for(const match of candidate){
    const att=match[1].match(/\bhref\s*=\s*(['"])([^'"]+)\1/i);
    if(!att)continue;
    const text=normalized(match[2]),href=safePost(att[2]);
    if(href&&text.includes(name)&&text.includes(String(year))&&!found.includes(href))
      found.push(href);
    if(found.length>=2)break;
  }
  return found;
}
export async function probeDirectCineStreamHub({title,year,fetchImpl=fetch,timeoutMs=7000}={}){
  const output=(state,extra={})=>({
    provider:'CineStream hdhub4uAPI',state,
    moviePagesAccessible:0,externalSourceAnchors:0,hubHints:0,
    videoVerified:false,...extra
  });
  if(!title||typeof title!=='string'||title.length>100||
     !Number.isInteger(Number(year))||year<1888||year>2100)
    return output('bad_title');
  try{
    const search=new URL('/',ROOT);search.searchParams.set('s',title);
    const html=await limitedHtml(fetchImpl,search.href,timeoutMs);
    const pages=directHdhubMoviePosts(html,title,year);
    if(!pages.length)return output('search_page_no_matching_post');
    let result=output('post_inaccessible',{matchingPosts:pages.length});
    for(const page of pages){
      try{
        const post=await limitedHtml(fetchImpl,page,timeoutMs);
        const inspected=classifyCineStreamAnchors(post,page);
        result=output(inspected.externalSourceAnchors?'intermediate_source_found':'no_cinestream_anchors',{
          matchingPosts:pages.length,moviePagesAccessible:1,
          externalSourceAnchors:inspected.externalSourceAnchors,
          hubHints:inspected.directHubHints,
          redirectorHints:inspected.redirectorHints
        });
        if(inspected.externalSourceAnchors)break;
      }catch{}
    }
    return result;
  }catch(e){
    const msg=String(e?.message||'');
    return output(/^http_[0-9]{3}$/.test(msg)?msg:'search_unavailable');
  }
}
