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
export function classifyStreamPlayRedirectHtml(html){
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
    const hub=/^(?:.+\.)?(?:hubcloud|hubdrive|vcloud)\.[a-z]{2,12}$/i.test(u.hostname);
    return result('decoded_intermediate',{
      decodedIntermediateLinks:1,hubcloudHints:hub?1:0,
      destinationHost:u.hostname.toLowerCase()
    });
  }catch{return result('malformed_obfuscation');}
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
                attempts.push({...classifyStreamPlayRedirectHtml(body),
                  redirectsSeen:1,redirectHost:'greenmotors.cc'});
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
      [x.destinationHost,x.redirectHost].filter(Boolean)))].sort()
  });
}
