/**
 * Read-only probe of external *direct HLS* API claims. Strict fixed hosts
 * and openly licensed Sintel TMDB ID only. Never download/relay actual film.
 * This is not authorization to stream arbitrary films from these services.
 */
const PREFIXES=[
  'https://api.ezvidapi.com',
  'https://ezvidapi.com'
];
const PATHS=['/list','/movie/vidsrc/45745'];
for(const prefix of PREFIXES)for(const path of PATHS){
  const u=new URL(prefix+path);
  if(u.protocol!=='https:'||!['api.ezvidapi.com','ezvidapi.com'].includes(u.hostname))
    throw Error('untrusted_host');
  const c=new AbortController();
  const timer=setTimeout(()=>c.abort(),6000);
  try{
    const r=await fetch(u.href,{redirect:'manual',signal:c.signal,headers:{Accept:'application/json'}});
    const mime=String(r.headers.get('content-type')||'').split(';')[0].slice(0,40);
    let streamCount=0,hasHlsUrl=false,hasProxyHeaders=false;
    if(r.ok&&mime.includes('json')){
      const t=await r.text();
      if(t.length<128_000){
        const data=JSON.parse(t);
        const a=Array.isArray(data)?data:Array.isArray(data?.providers)?data.providers:[];
        streamCount=a.length;
        const val=data?.stream_url||data?.url||data?.data?.stream_url||data?.data?.url;
        try{const stream=new URL(val);
          hasHlsUrl=stream.protocol==='https:'&&/\.m3u8$/i.test(stream.pathname);
        }catch{}
        hasProxyHeaders=!!(data?.headers||data?.data?.headers);
      }
    }
    console.log('MOOSA_EZVID_DIRECT endpoint='+u.hostname+path+
      ' status='+r.status+' mime='+JSON.stringify(mime)+
      ' providerCount='+streamCount+' directHlsCandidate='+hasHlsUrl+
      ' nativeHeaderRequirement='+hasProxyHeaders+
      ' decodedFhd=false mediaBytesFetched=false');
  }catch(e){
    console.log('MOOSA_EZVID_DIRECT endpoint='+u.hostname+path+
      ' status='+(e?.name==='AbortError'?'timeout':'network_or_parse_failed')+
      ' decodedFhd=false');
  }finally{clearTimeout(timer)}
}
