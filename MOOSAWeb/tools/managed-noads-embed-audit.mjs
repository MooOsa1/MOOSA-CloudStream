/**
 * Read-only external iframe feasibility audit for operator-maintained embeds.
 * No film playback, media segment downloads, cookies, credentials or proxies.
 * Only known fixed TMDB IDs: Sintel (CC-BY) and two metadata samples.
 * Never log signed tokens or cross-origin response bodies.
 *
 * Does not certify lack of ads. An iframe can run ads after a user click.
 */
const CASES=[
  {name:'EmbedWave licensed Sintel',url:'https://embedwave.cc/embed/movie/45745'},
  {name:'EmbedWave movie metadata',url:'https://embedwave.cc/embed/movie/27205'},
  {name:'EmbedWave episode metadata',url:'https://embedwave.cc/embed/tv/1399/1/1'},
  {name:'ezvidapi licensed Sintel',url:'https://ezvidapi.com/embed/movie/45745'},
  {name:'ezvidapi movie metadata',url:'https://ezvidapi.com/embed/movie/27205'}
];
const allowed=new Set(['embedwave.cc','ezvidapi.com']);
for(const sample of CASES){
 const u=new URL(sample.url);
 if(!allowed.has(u.hostname)||u.protocol!=='https:')throw Error('fixed-host-violation');
 const controller=new AbortController();
 const t=setTimeout(()=>controller.abort(),6000);
 try {
   const response=await fetch(u,{redirect:'manual',signal:controller.signal,
     headers:{Accept:'text/html'}});
   const type=String(response.headers.get('content-type')||'').split(';')[0].slice(0,80);
   const xframe=String(response.headers.get('x-frame-options')||'').slice(0,40);
   const csp=String(response.headers.get('content-security-policy')||'').slice(0,100);
   const location=response.headers.get('location');
   const redirectDomain=location?
     (()=>{try{return new URL(location,u).hostname}catch{return 'unreadable'}})():'none';
   let html='',scripts=0,thirdPartyScripts=0,adMarkers=0,iframeMarkup=0;
   if(response.ok&&type.includes('text/html')){
      const raw=await response.text();
      if(raw.length<300_000){
        html=raw;
        const tags=[...html.matchAll(/<script\b[^>]*>/gi)].map(x=>x[0]);
        scripts=tags.length;
        thirdPartyScripts=tags.filter(x=>/\bsrc\s*=\s*['"]https?:\/\//i.test(x)).length;
        adMarkers=(html.match(/(?:doubleclick|googlesyndication|adsbygoogle|popunder|onpopstate|onclick=|window\.open\s*\()/gi)||[]).length;
        iframeMarkup=(html.match(/<iframe\b/gi)||[]).length;
      }
   }
   console.log('MOOSA_NOADS_IFRAME name='+JSON.stringify(sample.name)+
     ' status='+response.status+' mime='+JSON.stringify(type)+
     ' xframe='+JSON.stringify(xframe)+
     ' cspFramePolicy='+(/frame-ancestors/i.test(csp)?'declared':'missing')+
     ' redirectDomain='+JSON.stringify(redirectDomain)+
     ' scripts='+scripts+' thirdPartyScripts='+thirdPartyScripts+
     ' adMarkers='+adMarkers+' childFrames='+iframeMarkup+
     ' actualVideoDecoded=false adFreeVerified=false');
 }catch(e){
   console.log('MOOSA_NOADS_IFRAME name='+JSON.stringify(sample.name)+
     ' status='+(e.name==='AbortError'?'timeout':'network_or_parse_failed')+
     ' actualVideoDecoded=false adFreeVerified=false');
 }finally{clearTimeout(t)}
}
