const friendlyStatus={
  not_integrated:'Not connected — search was not run',
  needs_upstream_link:'Waiting for a trusted movie-page link',
  no_exact_title:'No exact title/year match in MovieBox catalog',
  provider_access_denied:'Provider blocked playback lookup (HTTP 403)',
  no_compatible_candidates:'No compatible video candidates returned',
  candidates_found:'Potential video candidates found',
  guest_session_unavailable:'MovieBox guest session unavailable',
  provider_unavailable:'MovieBox currently unreachable',
  invalid_title_or_year:'Invalid title or release year'
};
const form=document.getElementById('focus-form');
const title=document.getElementById('focus-title');
const year=document.getElementById('focus-year');
const button=document.getElementById('focus-scan');
const message=document.getElementById('focus-status');
const results=document.getElementById('focus-results');
function make(tag,content,cls=''){
  const el=document.createElement(tag);
  if(cls)el.className=cls;
  el.textContent=content;return el;
}
form.addEventListener('submit',async(e)=>{
  e.preventDefault();
  const name=title.value.trim(),yr=year.value.trim();
  if(name.length<1||name.length>110||!/^(?:18[89]\d|19\d\d|20\d\d|2100)$/.test(yr)){
    message.textContent='Enter an original title and release year.';
    return;
  }
  button.disabled=true;results.hidden=true;results.replaceChildren();
  message.textContent='Searching MovieBox; checking connection status for the other three…';
  try{
    const q=new URLSearchParams({title:name,year:yr});
    const r=await fetch('/api/focused-sources?'+q.toString(),{
      signal:AbortSignal.timeout(30000),headers:{Accept:'application/json'}
    });
    if(!r.ok)throw Error('Request unavailable');
    const data=await r.json();
    for(const source of data.sources||[]){
      const panel=make('div','','metric');
      panel.append(make('h3',source.name));
      panel.append(make('p',friendlyStatus[source.status]||'Status not verified'));
      if(source.instances?.length)
        panel.append(make('p','Observed servers: '+source.instances.join(' / ')));
      if(source.searchExecuted===true){
        panel.append(make('p','Potential links: '+Number(source.candidateCount||0)));
        if(Number.isInteger(source.searchHits))
          panel.append(make('p','MovieBox catalog matches: '+Number(source.searchHits)+
            ' results; exact title: '+Number(source.exactTitleHits||0)+
            '; matching year: '+Number(source.exactTitleAndYearHits||0)));
        if(source.qualities?.length)
          panel.append(make('p','Offered quality labels: '+source.qualities.join(' / ')));
      }else{
        panel.append(make('p','Links: not checked (not zero).'));
      }
      panel.append(make('p',source.explanation||''));
      results.append(panel);
    }
    results.hidden=false;
    message.textContent='Only connected source searches return a link count. None of these results proves playback.';
  }catch{
    message.textContent='This source check is unavailable. Retry shortly.';
  }finally{button.disabled=false;}
});
