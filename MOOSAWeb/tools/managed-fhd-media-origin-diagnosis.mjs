/**
 * Why a managed CDN can answer HEAD 200 while an HTML5 video never starts.
 * Rights-cleared Sintel ONLY; fixed addon + fixed video host allowlist.
 * No video fetched beyond a small byte range (4KB), no cookies, bypasses,
 * signed URLs or tokens in CI logs. Treat 200 responses carefully: close
 * the response body immediately after reading first 4KB.
 */
const MANIFEST='https://plaviohd.vercel.app/stream/movie/tt1727587.json';
const VIDEO_HOST='bcdn.hakunaymatata.com';
async function getSource(){
  const r=await fetch(MANIFEST,{redirect:'error',signal:AbortSignal.timeout(7000),
    headers:{Accept:'application/json'}});
  if(!r.ok||Number(r.headers.get('content-length')||0)>250000)
    throw Error('manifest_unavailable');
  const t=await r.text();
  if(t.length>250000)throw Error('manifest_oversize');
  const data=JSON.parse(t);
  for(const item of Array.isArray(data.streams)?data.streams:[]){
    if(item?.headers||item?.referer||item?.behaviorHints?.proxyHeaders)continue;
    const q=String(item.name||'')+' '+String(item.title||'');
    if(!/(?:^|[^0-9])1080p?(?:[^0-9]|$)/i.test(q))continue;
    try{
      const u=new URL(item.url);
      if(u.protocol==='https:'&&u.hostname===VIDEO_HOST&&!u.port&&
        !u.username&&!u.password&&u.pathname.toLowerCase().endsWith('.mp4'))
        return u.href;
    }catch{}
  }
  return null;
}
async function sample(url,withOrigin){
  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),6500);
  let response;
  try{
    const headers={'Range':'bytes=0-4095','Accept':'video/mp4'};
    if(withOrigin)headers.Origin='https://moosa-web.onrender.com';
    response=await fetch(url,{headers,redirect:'error',signal:controller.signal});
    const status=response.status;
    const mime=String(response.headers.get('content-type')||'').split(';')[0].slice(0,55);
    const range=String(response.headers.get('content-range')||'').slice(0,65);
    const cors=String(response.headers.get('access-control-allow-origin')||'');
    let bytes=new Uint8Array();
    if(status===206||status===200){
      const reader=response.body?.getReader();
      if(reader){
        const chunks=[];let total=0;
        while(total<4096){
          const next=await reader.read();
          if(next.done)break;
          const kept=next.value.subarray(0,Math.min(next.value.length,4096-total));
          chunks.push(kept);total+=kept.length;
          if(!kept.length)break;
        }
        await reader.cancel().catch(()=>{});
        bytes=new Uint8Array(total);let i=0;
        for(const chunk of chunks){bytes.set(chunk,i);i+=chunk.length;}
      }
    }
    const header=new TextDecoder('latin1').decode(bytes.subarray(0,128));
    const signature=bytes.length>=12&&(header.includes('ftyp')||header.includes('styp'));
    return {
      status,mime,rangeResponse:range.startsWith('bytes '),
      corsReadable:cors==='*'||cors==='https://moosa-web.onrender.com',
      bytesRead:bytes.length,mp4ContainerHeader:signature,
      // Request path deliberately never prints raw signed URLs.
      browserFhdVerified:false
    };
  }catch(e){return {status:e?.name==='AbortError'?'timeout':'fetch_failed',browserFhdVerified:false};}
  finally{
    clearTimeout(timer);
    try{await response?.body?.cancel()}catch{}
  }
}
let url;
try{url=await getSource()}
catch{console.log('MOOSA_FHD_ORIGIN source=unavailable');process.exit(0)}
if(!url){console.log('MOOSA_FHD_ORIGIN source=no_whitelisted_cc_fhd');process.exit(0)}
for(const origin of [false,true]){
  const res=await sample(url,origin);
  console.log('MOOSA_FHD_ORIGIN route='+ (origin?'with_site_origin':'without_origin')+
    ' result='+JSON.stringify(res)+' sourceUrlOmitted=true');
}
