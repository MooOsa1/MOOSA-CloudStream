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
  if(name.length<2||name.length>110||!/^(?:18[89]\d|19\d\d|20\d\d|2100)$/.test(yr)){
    message.textContent='Enter an original title and release year.';
    return;
  }
  button.disabled=true;results.hidden=true;results.replaceChildren();
  message.textContent='Checking the three selected sources…';
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
      panel.append(make('p','Status: '+source.status));
      panel.append(make('p','Candidate links: '+(source.candidateCount||0)));
      if(source.qualities?.length)panel.append(
        make('p','Offered labels: '+source.qualities.join(' / ')));
      panel.append(make('p',source.explanation||''));
      results.append(panel);
    }
    results.hidden=false;
    message.textContent='Search finished. Candidate counts are not playback confirmation.';
  }catch{
    message.textContent='This source check is unavailable. Retry shortly.';
  }finally{button.disabled=false;}
});
