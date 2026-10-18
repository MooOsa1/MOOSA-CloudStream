/**
 * One bounded, read-only diagnosis of StreamPlay's getRedirectLinks() input.
 * No redirects, JavaScript, captcha/session bypass, media fetch, or second-
 * stage blog_url requests. No URL, token, or signed path appears in output.
 */
const HOST='greenmotors.club',MAX_HTML=85000;
const result=(state,x={})=>({provider:'StreamPlay Greenmotors',state,
  decodedIntermediateLinks:0,hubcloudHints:0,videoVerified:false,...x});
function rot13(s){return s.replace(/[a-zA-Z]/g,c=>String.fromCharCode(
  (c.charCodeAt(0)&32?97:65)+(c.toLowerCase().charCodeAt(0)-97+13)%26));}
function decode64(s){
  if(!/^[a-zA-Z0-9+/=]{1,15000}$/.test(s))throw Error('invalid_base64');
  return Buffer.from(s,'base64').toString('utf8');
}
export function classifyStreamPlayRedirectHtml(html,onDecodedTarget=null){
  const tokens=[];
  const rx=/s\('o','([A-Za-z0-9+/=]+)'|ck\('_wp_http_[0-9]+','([^']+)'/g;
  for(const m of String(html).matchAll(rx))tokens.push(m[1]||m[2]);
  if(!tokens.length)return result('no_obfuscated_redirect');
  const joined=tokens.join('');
  if(joined.length>15000)return result('oversized_redirect');
  try{
    const json=JSON.parse(decode64(rot13(decode64(decode64(joined)))));
    const plain=decode64(String(json?.o||''));
    if(!plain)return result('requires_second_stage');
    const u=new URL(plain);
    if(u.protocol!=='https:'||u.username||u.password||u.port||u.hash)
      return result('non_https_redirect');
    if(typeof onDecodedTarget==='function')onDecodedTarget(u.href);
    const hub=/^(?:.+\.)?(?:hubcloud|hubdrive|vcloud)\.[a-z]{2,12}$/i.test(u.hostname);
    return result('decoded_intermediate',{
      decodedIntermediateLinks:1,hubcloudHints:hub?1:0,
      destinationHost:u.hostname.toLowerCase()
    });
  }catch{return result('malformed_obfuscation');}
}
// Only the two hosts confirmed in the operator's Inception probe are
// permitted for a bounded read-only landing-page inspection.
const LANDING_HOSTS=new Set(['hubcloud.ist','hubdrive.pics']);
function inspectLandingHtml(html,host){
  // StreamPlay: HubCloud checks a#download, then a.btn on the next page.
  // Hubdrive checks .btn.btn-primary.btn-user.btn-success1.m-1.
  const anchors=[...String(html).matchAll(/<a\b[^>]{0,1800}>/gi)].slice(0,110);
  const getAttr=(tag,attr)=>{
    const m=tag.match(new RegExp('\\b'+attr+'\\s*=\\s*(["\\x27])([^"\\x27<>]{1,850})\\1','i'));
    return m?.[2]||'';
  };
  let downloadButtons=0,hubdriveButtons=0,serverButtons=0;
  const downloadHosts=new Set(),buttonHosts=new Set();
  for(const entry of anchors){
    const tag=entry[0],id=getAttr(tag,'id'),classes=getAttr(tag,'class');
    const names=new Set(classes.toLowerCase().split(/\s+/).filter(Boolean));
    const href=getAttr(tag,'href');
    let targetHost=null;
    if(href){
      try{
        const target=new URL(href,'https://'+host+'/');
        if(target.protocol==='https:'&&!target.username&&!target.password&&
           !target.port&&!target.hash)targetHost=target.hostname.toLowerCase();
      }catch{}
    }
    if(id==='download' && href){
      downloadButtons++;
      if(targetHost)downloadHosts.add(targetHost);
    }
    if(['btn','btn-primary','btn-user','btn-success1','m-1']
      .every(name=>names.has(name)))hubdriveButtons++;
    if(names.has('btn')&&href){
      serverButtons++;
      if(targetHost)buttonHosts.add(targetHost);
    }
  }
  return {host,status:'page_accessible',downloadButtons,
    hubdriveButtons,serverButtons,
    downloadHosts:[...downloadHosts].sort().slice(0,8),
    buttonHosts:[...buttonHosts].sort().slice(0,12)};
}
function trustedDownloadHref(markup,currentUrl){
  // Mirror the HubCloud extractor's #download selector, but never follow
  // another host, protocol, port, fragment, or authentication field.
  for(const m of String(markup).matchAll(/<a\b[^>]{0,1800}>/gi)){
    const tag=m[0];
    if(!/\bid\s*=\s*(["'])download\1/i.test(tag))continue;
    const href=tag.match(/\bhref\s*=\s*(["'])([^"'<>]{1,850})\1/i)?.[2];
    if(!href)continue;
    let target;
    try{target=new URL(href,currentUrl);}catch{continue;}
    if(target.protocol==='https:'&&target.hostname==='hubcloud.ist'&&
       !target.username&&!target.password&&!target.port&&!target.hash&&
       target.href!==currentUrl&&target.href.length<1600)
      return target.href;
  }
  return null;
}
function sameHostHubDriveButton(markup,currentUrl){
  for(const m of String(markup).matchAll(/<a\b[^>]{0,1800}>/gi)){
    const tag=m[0];
    if(!/\bclass\s*=\s*["'][^"']*\bbtn\b/i.test(tag))continue;
    const href=tag.match(/\bhref\s*=\s*(["'])([^"'<>]{1,850})\1/i)?.[2];
    if(!href)continue;
    let target;
    try{target=new URL(href,currentUrl);}catch{continue;}
    if(target.protocol==='https:'&&target.hostname==='hubdrive.pics'&&
       !target.username&&!target.password&&!target.port&&!target.hash&&
       target.href!==currentUrl&&target.href.length<1600)
      return target.href;
  }
  return null;
}
// HubCloud's actual #download button on the operator's Inception sample
// points to gamerxyt.com, not the HubCloud host. This one-hop HTML inspection
// checks its server buttons without trusting or following their destinations.
function approvedGamerxytHref(markup,current){
  for(const match of String(markup||'').matchAll(/<a\b[^>]{0,1800}>/gi)){
    const tag=match[0];
    if(!/\bid\s*=\s*(["'])download\1/i.test(tag))continue;
    const href=tag.match(/\bhref\s*=\s*(["'])([^"'<>]{1,850})\1/i)?.[2];
    if(!href)continue;
    let target;
    try{target=new URL(href,current);}catch{continue;}
    if(target.protocol==='https:'&&target.hostname==='gamerxyt.com'&&
      !target.port&&!target.username&&!target.password&&!target.hash&&
      target.href.length<1600)return target.href;
  }
  return null;
}
function trustedPixelHubcloud(markup,current,targetHost='pixel.hubcloud.ist'){
  const result=[];
  for(const match of String(markup||'').matchAll(/<a\b[^>]{0,1800}>/gi)){
    const tag=match[0];
    if(!/\bclass\s*=\s*["'][^"']*\bbtn\b/i.test(tag))continue;
    const href=tag.match(/\bhref\s*=\s*(["'])([^"'<>]{1,850})\1/i)?.[2];
    if(!href)continue;
    let target;
    try{target=new URL(href,current);}catch{continue;}
    if(target.protocol!=='https:'||target.hostname!==targetHost||
       target.port||target.username||target.password||target.hash||
       target.href.length>1600||result.includes(target.href))continue;
    result.push(target.href);
    if(result.length>=1)break;
  }
  return result;
}
async function inspectPixelHubcloud(url,fetchImpl,timeoutMs){
  // Range bytes only, and at most 16 bytes read. No redirects, no stream
  // promotion, no full video download, no URL or signed token in output.
  let entry;
  try{entry=new URL(url);}catch{return {status:'invalid_url'};}
  if(entry.protocol!=='https:'||
     !['pixel.hubcloud.ist',
       'little-hat-b47b.legalo74863004.workers.dev'].includes(entry.hostname)||
     entry.username||entry.password||entry.port||entry.hash||
     entry.href.length>1800)return {status:'unapproved_host'};
  let response;
  try{
    const rangeHeaders={Accept:'video/mp4,application/octet-stream',
      Range:'bytes=0-4095'};
    response=await fetchImpl(url,{
      method:'GET',redirect:'manual',headers:rangeHeaders,
      signal:AbortSignal.timeout(Math.min(timeoutMs,6000))
    });
    if([301,302,303,307,308].includes(response.status)){
      let redirectHost=null,redirectUrl=null;
      try{
        const u=new URL(response.headers.get('location')||'',url);
        if(u.protocol==='https:'&&!u.username&&!u.password&&!u.port&&!u.hash &&
           u.href.length<=1800){
          redirectHost=u.hostname.toLowerCase();
          if(redirectHost==='pixel.rohitkiskk.workers.dev')
            redirectUrl=u.href;
        }
      }catch{}
      if(entry.hostname!=='pixel.hubcloud.ist'||!redirectUrl)
        return {status:'redirect_not_followed',redirectHost};
      await response.body?.cancel?.().catch(()=>{});
      response=await fetchImpl(redirectUrl,{
        method:'GET',redirect:'manual',headers:rangeHeaders,
        signal:AbortSignal.timeout(Math.min(timeoutMs,6000))
      });
      if([301,302,303,307,308].includes(response.status))
        return {status:'second_redirect_blocked',redirectHost};
      // A normal same-source HTTP redirect is not a bypass of site access
      // controls. HTTP 403/429 still fail closed below.
    }
    if(response.status===429)return {status:'rate_limited'};
    if(response.status!==206)return {status:'http_'+response.status};
    const type=String(response.headers.get('content-type')||'').toLowerCase();
    if(!/(?:video\/mp4|application\/octet-stream)/.test(type))
      return {status:'not_video_mime'};
    if(!response.headers.get('content-range')?.startsWith('bytes 0-'))
      return {status:'missing_content_range'};
    const reader=response.body?.getReader();
    if(!reader)return {status:'empty_body'};
    let bytes=Buffer.alloc(0);
    try{
      while(bytes.length<16){
        const part=await reader.read();
        if(part.done)break;
        bytes=Buffer.concat([bytes,Buffer.from(part.value).subarray(0,16-bytes.length)]);
      }
    }finally{await reader.cancel().catch(()=>{});}
    return {status:bytes.length>=12 &&
      ['ftyp','styp'].includes(bytes.subarray(4,8).toString('ascii'))?
      'mp4_bytes_verified':'wrong_media_bytes'};
  }catch(e){
    return {status:e?.name==='TimeoutError'?'timeout':'request_unavailable'};
  }finally{
    if(response?.body&&!response.body.locked)
      await response.body.cancel().catch(()=>{});
  }
}
async function inspectGamerxyt(url,fetchImpl,timeoutMs){
  const target=new URL(url);
  if(target.protocol!=='https:'||target.hostname!=='gamerxyt.com'||
     target.username||target.password||target.port||target.hash)return null;
  try{
    const response=await fetchImpl(target.href,{
      redirect:'manual',headers:{Accept:'text/html'},
      signal:AbortSignal.timeout(Math.min(timeoutMs,6000))
    });
    if([301,302,303,307,308].includes(response.status)){
      await response.body?.cancel?.().catch(()=>{});
      let nextHost=null;
      try{
        const u=new URL(response.headers.get('location')||'',target);
        if(u.protocol==='https:'&&!u.username&&!u.password&&!u.port&&!u.hash)
          nextHost=u.hostname.toLowerCase();
      }catch{}
      return {status:'redirect_not_followed',nextHost};
    }
    const content=await boundedText(response);
    const metadata=inspectLandingHtml(content,'gamerxyt.com');
    const pixel=trustedPixelHubcloud(content,target.href);
    const worker=trustedPixelHubcloud(content,target.href,
      'little-hat-b47b.legalo74863004.workers.dev');
    const [pixelAudit,workerAudit]=await Promise.all([
      pixel.length?inspectPixelHubcloud(pixel[0],fetchImpl,timeoutMs):null,
      worker.length?inspectPixelHubcloud(worker[0],fetchImpl,timeoutMs):null
    ]);
    return {
      status:'page_accessible',serverButtons:metadata.serverButtons,
      buttonHosts:metadata.buttonHosts,
      downloadButtons:metadata.downloadButtons,
      ...(pixelAudit?{pixelAudit}:{}),
      ...(workerAudit?{workerAudit}:{})
    };
  }catch(e){
    const reason=String(e?.message||'');
    return {status:/^http_[0-9]{3}$/.test(reason)?reason:
      reason==='not_html'?'not_html':e?.name==='TimeoutError'?'timeout':
      'request_unavailable'};
  }
}
async function inspectLanding(link,fetchImpl,timeoutMs){
  let u;
  try{u=new URL(link);}catch{return null;}
  if(u.protocol!=='https:'||!LANDING_HOSTS.has(u.hostname)||
     u.username||u.password||u.port||u.hash||u.href.length>1800)return null;
  try{
    const r=await fetchImpl(u.href,{
      redirect:'manual',headers:{Accept:'text/html'},
      signal:AbortSignal.timeout(Math.min(timeoutMs,6000))
    });
    if([301,302,303,307,308].includes(r.status)){
      await r.body?.cancel?.().catch(()=>{});
      return {host:u.hostname,status:'redirect_not_followed'};
    }
    const markup=await boundedText(r);
    const summary=inspectLandingHtml(markup,u.hostname);
    if(u.hostname==='hubcloud.ist' && summary.downloadHosts.includes('gamerxyt.com')){
      const approved=approvedGamerxytHref(markup,u.href);
      if(approved)return {...summary,
        nextPageStatus:'cross_host_reviewed',
        nextPageHost:'gamerxyt.com',
        gatewayPage:await inspectGamerxyt(approved,fetchImpl,timeoutMs)
      };
    }
    const nextPage=u.hostname==='hubcloud.ist'?
      trustedDownloadHref(markup,u.href):
      u.hostname==='hubdrive.pics'?
      sameHostHubDriveButton(markup,u.href):null;
    if(!nextPage)return summary;
    try{
      const detail=await fetchImpl(nextPage,{
        redirect:'manual',headers:{Accept:'text/html'},
        signal:AbortSignal.timeout(Math.min(timeoutMs,6000))
      });
      if([301,302,303,307,308].includes(detail.status)){
        let redirectHost=null;
        try{
          const url=new URL(detail.headers.get('location')||'',nextPage);
          if(url.protocol==='https:'&&!url.username&&!url.password&&
             !url.port&&!url.hash)redirectHost=url.hostname.toLowerCase();
        }catch{}
        await detail.body?.cancel?.().catch(()=>{});
        return {...summary,nextPageStatus:'redirect_not_followed',
          ...(redirectHost?{nextRedirectHost:redirectHost}:{})};
      }
      const html=await boundedText(detail);
      const inspected=inspectLandingHtml(html,u.hostname);
      return {...summary,nextPageStatus:'page_accessible',
        finalServerButtons:inspected.serverButtons,
        finalButtonHosts:inspected.buttonHosts};
    }catch(e){
      const reason=String(e?.message||'');
      return {...summary,nextPageStatus:/^http_[0-9]{3}$/.test(reason)?
        reason:'request_unavailable'};
    }
  }catch(e){
    const reason=String(e?.message||'');
    const state=/^http_[0-9]{3}$/.test(reason)?reason:
      reason==='not_html'?'not_html':reason==='oversized'?'page_oversized':
      e?.name==='TimeoutError'?'timeout':'request_unavailable';
    return {host:u.hostname,status:state};
  }
}

async function boundedText(res){
  if(!res.ok)throw Error('http_'+res.status);
  if(!String(res.headers.get('content-type')||'').includes('text/html'))
    throw Error('not_html');
  if(Number(res.headers.get('content-length')||0)>MAX_HTML)
    throw Error('oversized');
  if(!res.body)throw Error('empty');
  const reader=res.body.getReader(),data=[];let length=0;
  try{
    while(true){
      const p=await reader.read();if(p.done)break;
      length+=p.value.byteLength;if(length>MAX_HTML)throw Error('oversized');
      data.push(Buffer.from(p.value));
    }
  }finally{await reader.cancel().catch(()=>{});}
  return Buffer.concat(data).toString('utf8');
}
export async function probeStreamPlayRedirects({
  postHtml,fetchImpl=fetch,timeoutMs=7500
}={}){
  const unique=new Set(),hrefs=[];
  // Input is a previously verified matching movie post. Only the fixed
  // provider host is contacted; all other download links are ignored.
  for(const m of String(postHtml||'').matchAll(/<a\b[^>]*href\s*=\s*(["'])([^"'<>]{1,850})\1/gi)){
    let u;
    try{u=new URL(m[2]);}catch{continue;}
    if(u.protocol!=='https:'||u.hostname!==HOST||u.port||u.hash||
       u.username||u.password||unique.has(u.href))continue;
    unique.add(u.href);hrefs.push(u.href);
    if(hrefs.length>=2)break;
  }
  if(!hrefs.length)return result('no_greenmotors_links');
  const attempts=[];
  for(const url of hrefs){
    try{
      // "error" obscures a normal HTTP 3xx as a generic fetch failure in
      // Node. "manual" observes redirects WITHOUT following them.
      const response=await fetchImpl(url,{
        redirect:'manual',headers:{Accept:'text/html'},
        signal:AbortSignal.timeout(timeoutMs)
      });
      if([301,302,303,307,308].includes(response.status)){
        let redirectHost=null;
        try{
          const location=response.headers.get('location')||'';
          const dst=new URL(location,url);
          if(dst.protocol==='https:'&&!dst.username&&!dst.password&&
             !dst.port&&!dst.hash)redirectHost=dst.hostname.toLowerCase();
        }catch{}
        await response.body?.cancel?.().catch(()=>{});
        // Provider redirects .club to .cc. Follow only this explicitly
        // reviewed HTTPS host once, and reject any further redirect.
        if(redirectHost==='greenmotors.cc'){
          let destination;
          try{
            destination=new URL(response.headers.get('location')||'',url);
            if(destination.protocol!=='https:'||destination.hostname!=='greenmotors.cc'||
               destination.username||destination.password||destination.port||
               destination.hash||destination.href.length>1600)
              destination=null;
          }catch{destination=null;}
          if(destination){
            try{
              const step=await fetchImpl(destination.href,{
                redirect:'manual',headers:{Accept:'text/html'},
                signal:AbortSignal.timeout(timeoutMs)
              });
              if([301,302,303,307,308].includes(step.status)){
                await step.body?.cancel?.().catch(()=>{});
                attempts.push(result('second_redirect_blocked',{
                  redirectsSeen:2,redirectHost:'greenmotors.cc'
                }));
              }else{
                const body=await boundedText(step);
                let landingUrl=null;
                const decoded=classifyStreamPlayRedirectHtml(body,u=>{
                  landingUrl=u;
                });
                const landing=landingUrl?
                  await inspectLanding(landingUrl,fetchImpl,timeoutMs):null;
                attempts.push({...decoded,
                  redirectsSeen:1,redirectHost:'greenmotors.cc',
                  ...(landing?{landing}:{} )});
              }
              continue;
            }catch(e){
              const reason=String(e?.message||'');
              const safe=/^(?:http_[0-9]{3}|not_html|oversized|empty)$/.test(reason)?
                reason:e?.name==='TimeoutError'?'timeout':'connection_failed';
              attempts.push(result('second_stage_'+safe,{
                redirectsSeen:1,redirectHost:'greenmotors.cc'
              }));
              continue;
            }
          }
        }
        attempts.push(result('http_redirect',{
          redirectsSeen:1,...(redirectHost?{redirectHost}:{})
        }));
        continue;
      }
      const page=await boundedText(response);
      attempts.push(classifyStreamPlayRedirectHtml(page));
    }catch(e){
      const reason=String(e?.message||'');
      const known=/^(?:http_[0-9]{3}|not_html|oversized|empty)$/.test(reason);
      const networkError=e?.name==='TimeoutError'||e?.name==='AbortError'?
        'timeout':e?.name==='TypeError'?'connection_failed':'request_failed';
      attempts.push(result(known?reason:networkError));
    }
  }
  const resolved=attempts.some(x=>x.decodedIntermediateLinks);
  const redirected=attempts.some(x=>x.redirectsSeen);
  const statuses=[...new Set(attempts.map(x=>x.state))];
  return result(resolved?'resolved':
    attempts[0]?.state||'unavailable',{
    inspected:attempts.length,
    statuses,
    redirectsSeen:attempts.reduce((s,x)=>s+Number(x.redirectsSeen||0),0),
    decodedIntermediateLinks:attempts.reduce((s,x)=>s+x.decodedIntermediateLinks,0),
    hubcloudHints:attempts.reduce((s,x)=>s+x.hubcloudHints,0),
    destinationHosts:[...new Set(attempts.flatMap(x=>
      [x.destinationHost,x.redirectHost].filter(Boolean)))].sort(),
    landings:attempts.map(x=>x.landing).filter(Boolean)
  });
}
