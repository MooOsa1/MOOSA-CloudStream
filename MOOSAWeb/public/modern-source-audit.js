const form=document.getElementById('modern-form');
const title=document.getElementById('modern-title');
const year=document.getElementById('modern-year');
const slot=document.getElementById('modern-slot');
const submit=document.getElementById('modern-scan');
const status=document.getElementById('modern-status');
const output=document.getElementById('modern-results');
function el(tag,value,className=''){
  const node=document.createElement(tag);
  node.textContent=String(value);
  if(className)node.className=className;
  return node;
}
function row(parent,key,value){
  parent.append(el('p',key+': '+value));
}
form.addEventListener('submit',async event=>{
  event.preventDefault();
  const name=title.value.trim();
  const yr=Number(year.value);
  const sourceSlot=Number(slot.value);
  if(name.length<3||name.length>110||yr<1950||yr>2026||
     !Number.isInteger(yr)||!Number.isInteger(sourceSlot)){
    status.textContent='Enter a movie name and valid release year.';
    return;
  }
  submit.disabled=true;
  output.hidden=true;output.replaceChildren();
  status.textContent='Inspecting two source links. This can take up to 30 seconds…';
  try{
    const query=new URLSearchParams({
      title:name,year:String(yr),slot:String(sourceSlot)
    });
    const response=await fetch('/api/modern-source-audit?'+query,{
      signal:AbortSignal.timeout(35000),
      headers:{Accept:'application/json'}
    });
    if(!response.ok)throw Error(response.status===429?
      'Another film check is in progress. Retry shortly.':
      'The movie source check did not complete.');
    const result=await response.json();
    const summary=el('div','','metric');
    summary.append(el('h3',result.title+' ('+result.year+')'));
    row(summary,'Indexed film posts',Number(result.matchingPosts||0));
    row(summary,'StreamPlay intermediate links',Number(result.intermediateLinks||0));
    row(summary,'Server links inspected',Number(result.linksInspected||0));
    row(summary,'Playback','Not verified / not added to Watch');
    output.append(summary);
    for(const source of result.hosts||[]){
      const box=el('div','','metric');
      box.append(el('h3',source.sourceHost||'Server'));
      row(box,'Landing',source.landingState||'Unknown');
      if(source.optionHosts?.length)
        row(box,'Storage services',source.optionHosts.join(' / '));
      for(const check of source.videoCandidates||[]){
        const suffix=[check.container,
          check.codecs?.length?check.codecs.join(', '):null,
          check.remuxCandidate?'H264/AAC remux may be feasible (unverified)':null]
          .filter(Boolean).join(' • ');
        row(box,check.name,check.state+(suffix?' — '+suffix:''));
      }
      output.append(box);
    }
    output.hidden=false;
    status.textContent='Provider checks complete. A valid file signature is not proof of decoding, edition match or rights.';
  }catch(e){
    status.textContent=e instanceof Error && !/token|url/i.test(e.message)?
      e.message:'Source check unavailable. Please retry.';
  }finally{submit.disabled=false;}
});
