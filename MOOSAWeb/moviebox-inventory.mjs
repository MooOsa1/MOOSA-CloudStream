/**
 * Source-focused, read-only MovieBox inventory for MOOSA.
 *
 * Real provider protocol, but never publishes CDN URLs, guest bearer tokens,
 * arbitrary mirrors, or playback claims. This is NOT a player integration.
 * BingeBang is a separate multi-provider site with a short-lived play pass;
 * HubCloud is a terminal resolver needing a specific upstream link first.
 */
const BASE='https://h5-api.aoneroom.com';
const DETAIL='https://h5.aoneroom.com';
const UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 13_6) AppleWebKit/537.36 Chrome/124.0 Safari/537.36';
const MAX=180000;
function normalized(s){
  return String(s||'').normalize('NFKD').toLowerCase()
    .replace(/[\u0300-\u036f]/g,'').replace(/[^a-z0-9]+/g,' ').trim();
}
function unpack(j){return j?.data?.data||j?.data||j||{};}
async function responseJson(r){
  if(!r?.ok||Number(r.headers.get('content-length')||0)>MAX)
    throw Error('provider_unavailable');
  if(!String(r.headers.get('content-type')||'').includes('json'))
    throw Error('invalid_provider_response');
  const raw=await r.text();
  if(raw.length>MAX)throw Error('large_response');
  return JSON.parse(raw);
}
function status(state,extras={}){
  return {provider:'MovieBox',state,found:0,sourceVariants:0,
    formats:{hls:0,mp4:0,dash:0},offeredQualities:[],
    verifiedPlayable:false,rightsVerified:false,...extras};
}
export async function movieBoxInventory({
  title,year,fetchImpl=fetch,timeoutMs=11000
}={}){
  const want=normalized(title),release=Number(year);
  if(want.length<2||want.length>110||!Number.isInteger(release)||
     release<1888||release>2100)return status('invalid_title_or_year');
  const send=async(path,init={})=>{
    const url=new URL(path,BASE);
    if(url.origin!==BASE)throw Error('unexpected_endpoint');
    return fetchImpl(url.href,{
      ...init,redirect:'error',signal:AbortSignal.timeout(timeoutMs)
    });
  };
  try{
    const guest=await send('/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox',{
      headers:{'User-Agent':UA,Accept:'application/json'}
    });
    const header=guest.headers.get('x-user');
    let token='';
    try{token=JSON.parse(header||'{}').token||'';}catch{}
    await guest.body?.cancel?.().catch(()=>{});
    if(typeof token!=='string'||token.length<8||token.length>300)
      return status('guest_session_unavailable');
    const headers={
      'Accept':'application/json','Content-Type':'application/json',
      'User-Agent':UA,'Authorization':'Bearer '+token,
      'X-Client-Info':'{"timezone":"Asia/Muscat"}',
      'Accept-Language':'en-US,en;q=0.5',Referer:BASE+'/'
    };
    const response=await send('/wefeed-h5api-bff/subject/search',{
      method:'POST',headers,
      body:JSON.stringify({keyword:title,page:1,perPage:24,subjectType:1})
    });
    const data=unpack(await responseJson(response));
    const items=Array.isArray(data.items)?data.items.slice(0,24):[];
    // Never pick the first search hit when exact identity is not confirmed.
    // Language annotations are allowed; years must agree where supplied.
    const matched=items.filter(item=>{
      const raw=String(item.title||item.name||'').trim();
      const base=raw.replace(/\s+\[[^\]]{1,35}\]\s*$/,'');
      const providerYear=Number(item.year||item.releaseYear||item.release_date?.slice?.(0,4)||0);
      return normalized(base)===want &&
        (!providerYear||providerYear===release) &&
        /^[a-zA-Z0-9_-]{1,70}$/.test(String(item.subjectId||item.id||''));
    }).slice(0,4);
    if(!matched.length)return status('no_exact_title', {searchHits:items.length});
    const all=[];
    let subjectRequests=0;
    for(const item of matched){
      const id=String(item.subjectId||item.id);
      const details=new URL('/wefeed-h5-bff/web/post/list/subject',DETAIL);
      details.searchParams.set('id',id);
      let detailPath='';
      try{
        const resp=await fetchImpl(details.href,{redirect:'error',
          signal:AbortSignal.timeout(timeoutMs),headers:{'User-Agent':UA}});
        const doc=await responseJson(resp);
        detailPath=String(doc.data?.items?.[0]?.subject?.detailPath||'');
      }catch{}
      if(!detailPath||detailPath.length>280||
         /[\r\n?#]/.test(detailPath))continue;
      const params=new URLSearchParams({subjectId:id,detailPath});
      const referer='https://fmoviesunblocked.net/spa/videoPlayPage/movies/'+
        encodeURIComponent(detailPath)+'?id='+encodeURIComponent(id);
      const playHeaders={...headers,Referer:referer,
        Origin:'https://fmoviesunblocked.net'};
      const endpoints=['/wefeed-h5api-bff/subject/play?',
        '/wefeed-h5api-bff/subject/download?'];
      const responses=await Promise.allSettled(endpoints.map(async endpoint=>{
        const r=await send(endpoint+params, {headers:playHeaders});
        return unpack(await responseJson(r));
      }));
      subjectRequests++;
      for(const result of responses){
        if(result.status!=='fulfilled')continue;
        const d=result.value;
        for(const prop of ['streams','downloads','dash']){
          const arr=Array.isArray(d[prop])?d[prop].slice(0,30):[];
          for(const s of arr){
            if(!s?.url||s.vipLocked===true)continue;
            let u;
            try{u=new URL(s.url);}catch{continue;}
            if(u.protocol!=='https:'||u.username||u.password||
               u.port||u.hash)continue;
            const format=prop==='dash'?'dash':
              /\.m3u8$/i.test(u.pathname)||/hls/i.test(String(s.type||''))?'hls':
              /\.mp4$/i.test(u.pathname)?'mp4':null;
            if(!format)continue;
            let q=Number(s.resolution||s.resolutions||0);
            if(!Number.isInteger(q)||q<144||q>1080)q=0;
            // Return counts only: no credentials, source URLs or CDN hostnames.
            all.push({format,quality:q,language:String(item.lanName||'Original')});
          }
        }
      }
    }
    const qualities=[...new Set(all.map(s=>s.quality).filter(q=>q>0))].sort((a,b)=>b-a);
    return status(all.length?'candidates_found':'no_compatible_candidates',{
      found:matched.length,sourceVariants:all.length,subjectsProbed:subjectRequests,
      formats:{
        hls:all.filter(x=>x.format==='hls').length,
        mp4:all.filter(x=>x.format==='mp4').length,
        dash:all.filter(x=>x.format==='dash').length
      },
      offeredQualities:qualities
    });
  }catch{
    return status('provider_unavailable');
  }
}
