/**
 * Checks hosted embed APIs/iframes as a lower-maintenance alternative to
 * custom video scraping. Metadata/status only: no movie bytes downloaded.
 * No arbitrary endpoints, no cookies, no forged Referer, no login or proxy.
 */
const CASES=[
  {name:'VidSrc 1080 API movie',url:'https://vidsrc.sh/info/movie/tt1375666.json',kind:'json'},
  {name:'VidSrc 1080 API TV episode',url:'https://vidsrc.sh/info/tv/tt0944947/1/1.json',kind:'json'},
  {name:'VidSrc movie iframe',url:'https://vidsrc.sh/embed/movie/tt1375666',kind:'frame'},
  {name:'VidSrc TV iframe',url:'https://vidsrc.sh/embed/tv/tt0944947/1/1',kind:'frame'},
  {name:'VidKing historical iframe',url:'https://www.vidking.net/embed/movie/27205',kind:'frame'},
  {name:'VidLink movie iframe',url:'https://vidlink.pro/movie/27205',kind:'frame'}
];
function safe(s){return String(s??'').replace(/[^a-zA-Z0-9 ,.;_+\/-]/g,'_').slice(0,100)}
async function probe({name,url,kind}){
  const u=new URL(url);
  const hosts=new Set(['vidsrc.sh','www.vidking.net','vidlink.pro']);
  if(!hosts.has(u.hostname)||u.protocol!=='https:')throw Error('untrusted_host');
  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),5000);
  try{
    const response=await fetch(url,{method:kind==='json'?'GET':'HEAD',redirect:'manual',
      headers:{Accept:kind==='json'?'application/json':'text/html'},
      signal:controller.signal});
    const type=String(response.headers.get('content-type')||'').split(';')[0].slice(0,50);
    const xframe=String(response.headers.get('x-frame-options')||'').slice(0,40);
    const csp=String(response.headers.get('content-security-policy')||'').slice(0,100);
    let q='unknown',title='',validJson=false;
    if(kind==='json'&&response.ok&&type.includes('json')){
      const text=await response.text();
      if(text.length<=65536){
        const data=JSON.parse(text);
        validJson=true;
        q=safe(data?.quality||data?.data?.quality||'unknown');
        title=safe(data?.title||data?.show_title||data?.name||'');
      }
    }
    console.log('MOOSA_EMBED_AUDIT name='+JSON.stringify(name)+
      ' status='+response.status+' contentType='+JSON.stringify(type)+
      ' jsonValid='+validJson+' declaredQuality='+JSON.stringify(q)+
      ' title='+JSON.stringify(title)+
      ' xFrameOptions='+JSON.stringify(xframe)+
      ' cspFrameAncestorsPresent='+/frame-ancestors/i.test(csp)+
      ' videoDecoded=false');
  }catch(e){
    console.log('MOOSA_EMBED_AUDIT name='+JSON.stringify(name)+
      ' status='+(e?.name==='AbortError'?'timeout':'network_or_parse_failed')+
      ' videoDecoded=false');
  }finally{clearTimeout(timer)}
}
for(const item of CASES)await probe(item);
