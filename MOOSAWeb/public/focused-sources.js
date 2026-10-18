const friendlyStatus={
  not_integrated:'Not connected — search was not run',
  needs_upstream_link:'Waiting for a trusted movie-page link',
  hubcloud_entry_found:'HubCloud movie-page entry discovered — playback not verified',
  no_exact_movie_post:'No exact movie post in the upstream catalog',
  index_unavailable:'HDHub4u index unreachable from Render',
  index_http_403:'Upstream index refused request (HTTP 403)',
  http_403:'Alternative directory also returned HTTP 403',
  non_html_search:'Alternative directory returned no HTML',
  no_exact_movie_card:'No exact title/year card found in alternate directory',
  intermediate_download_links:'Movie post has intermediate download links, but no verified HubCloud URL',
  streamplay_download_anchors:'StreamPlay source-page links found; final video not yet verified',
  no_hubcloud_entry:'Movie post opens, but no HubCloud handoff was exposed',
  search_unavailable:'Alternative directory search unavailable',
  provider_access_restricted:'BingeBang blocked access from Render',
  page_no_direct_media:'Movie page loaded, no video file exposed',
  needs_verified_tmdb_id:'TMDB identity not resolved for BingeBang',
  requires_parent_player:'Vidy requires its parent player session',
  post_unavailable:'Matching movie post exists, but page fetch failed',
  no_hubcloud_entry:'No HubCloud entry links on the matching movie page',
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
        if(source.candidateCount!==null&&source.candidateCount!==undefined)
          panel.append(make('p','Potential video links: '+Number(source.candidateCount)));
        else
          panel.append(make('p','Video links: not tested'));
        if(source.name==='HubCloud'){
          panel.append(make('p','Matching movie posts: '+Number(source.exactMoviePosts||0)));
          panel.append(make('p','HubCloud handoff pages: '+Number(source.hubcloudEntryPages||0)));
          if(source.intermediateLinkCount)
            panel.append(make('p','StreamPlay intermediate links: '+Number(source.intermediateLinkCount)));
          if(source.alternateStatus)
            panel.append(make('p','Alternate directory: '+
              (friendlyStatus[source.alternateStatus]||source.alternateStatus)));
        }
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
