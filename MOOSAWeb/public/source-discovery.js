const form=document.querySelector('#discovery-form');
const input=document.querySelector('#movie-id');
const status=document.querySelector('#status');
const output=document.querySelector('#output');
const action=document.querySelector('#scan');
const set=(id,value)=>{document.getElementById(id).textContent=value;};
form.addEventListener('submit',async event=>{
  event.preventDefault();
  const id=input.value.trim();
  if(!/^[1-9]\d{0,9}$/.test(id)){
    status.textContent='Enter a valid numeric TMDB movie ID.';
    output.hidden=true;return;
  }
  action.disabled=true;
  output.hidden=true;
  status.textContent='Querying the provider…';
  try{
    const response=await fetch('/api/source-discovery?media=movie&id='+encodeURIComponent(id),{
      headers:{Accept:'application/json'},
      signal:AbortSignal.timeout(33000)
    });
    if(!response.ok)throw Error('Provider search request failed ('+response.status+')');
    const result=await response.json();
    set('provider',String(result.provider||'Unknown'));
    set('count',String(result.availableVariants??0));
    const qualities=Array.isArray(result.advertisedQualities)?result.advertisedQualities:[];
    set('qualities',qualities.length?qualities.join(' / '):'None');
    set('formats','MP4: '+Number(result.formatCounts?.mp4||0)+
        ' / HLS: '+Number(result.formatCounts?.hls||0));
    set('disclaimer','Result: '+String(result.status||'unknown')+
      '. Quality is only a provider label. Streams are not authenticated, licensed or browser-decoded by this scan.');
    status.textContent=result.availableVariants>0?
      'Potential sources found; playback verification is still required.':
      'No compatible candidate returned by this provider.';
    output.hidden=false;
  }catch{
    status.textContent='The provider lookup is unavailable right now. Try again later.';
  }finally{action.disabled=false;}
});
