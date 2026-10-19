import {localStremioHls,validMovieId} from './local-stremio-client.js';
const $=selector=>document.querySelector(selector);
const video=$('#stremio-video');
let sources=[],hls=null,sequence=0,startTimeout=null,stallTimeout=null,attempts=0,played=false;
const MAX_AUTOMATIC_TRIES=5;
const isIos=/iPhone|iPad|iPod/i.test(navigator.userAgent)||(/Macintosh/i.test(navigator.userAgent)&&navigator.maxTouchPoints>1);
function status(element,message,kind=''){
  const target=$(element);
  if(!target)return;
  target.className='status'+(kind?' '+kind:'');
  target.textContent=message;
}
function clearTimers(){
  clearTimeout(startTimeout);clearTimeout(stallTimeout);
  startTimeout=null;stallTimeout=null;
}
function clearPlayer(){
  ++sequence;clearTimers();
  if(hls){hls.destroy();hls=null;}
  video.pause();
  video.removeAttribute('src');video.load();
  played=false;
  $('#video-hint').classList.remove('hidden');
}
function resultError(err){
  if(err?.name==='TimeoutError')return 'The request timed out. The P2P index or engine may be slow.';
  return err?.message||'Could not load sources.';
}
async function serviceStatus(){
  if(isIos){
    status('#platform-note','iPhone Safari has no local Stremio P2P engine. This page cannot play the indexed torrents on iPhone. Use the full native Stremio app where available.','bad');
    return false;
  }
  status('#platform-note','Checking Stremio Service at 127.0.0.1:11470...');
  try{
    const res=await fetch('http://127.0.0.1:11470/settings',{
      cache:'no-store',signal:AbortSignal.timeout(3500)});
    if(!res.ok)throw Error('HTTP '+res.status);
    status('#platform-note','Stremio Service detected locally. Ready to test browser playback.','good');
    return true;
  }catch{
    status('#platform-note','Stremio Service is not reachable from this browser. Start it on this PC. Browser security permissions or local-network restrictions can also block the connection.','bad');
    return false;
  }
}
function updateNativeAppLink(){
  const id=$('#imdb').value.trim(),link=$('#open-native-stremio');
  if(!link)return;
  if(!validMovieId(id)){link.removeAttribute('href');return;}
  link.href='stremio:///detail/movie/'+id+'/'+id;
}
async function findSources(){
  const manifestUrl=$('#addon-url').value.trim(),imdbId=$('#imdb').value.trim();
  updateNativeAppLink();
  if(!validMovieId(imdbId)){status('#lookup-status','Enter a real IMDb ID, for example tt31193180.','bad');return;}
  if(!/^(?:https|stremio):\/\/aiostreams-nightly\.fortheweak\.cloud\/stremio\/[^?#]+\/manifest\.json$/.test(manifestUrl)){
    status('#lookup-status','Paste the private AIOStreams Stremio manifest URL from your installed MOOSA Hub configuration.','bad');return;
  }
  const button=$('#find');button.disabled=true;
  clearPlayer();sources=[];$('#source-select').replaceChildren(new Option('Searching...',''));$('#source-select').disabled=true;$('#play').disabled=true;
  status('#lookup-status','Querying your MOOSA Hub source index for '+imdbId+'...');
  try {
    const response=await fetch('/api/stremio-local/lookup',{
      method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({manifestUrl,imdbId}),
      signal:AbortSignal.timeout(62000),cache:'no-store'
    });
    const result=await response.json().catch(()=>({}));
    if(!response.ok)throw Error(result.error||'Source index returned HTTP '+response.status);
    sources=(Array.isArray(result.sources)?result.sources:[]).filter(x=>localStremioHls(x));
    if(!sources.length)throw Error('No P2P streams for this film. No playback is claimed.');
    const options=$('#source-select');
    options.replaceChildren();
    for(let i=0;i<sources.length;i++){
      const x=sources[i];
      options.add(new Option(String(i+1)+'. '+x.name+(x.quality==='unknown'?'':' • '+x.quality+'p'),String(i)));
    }
    options.disabled=false;$('#play').disabled=isIos;
    status('#lookup-status',sources.length+' movie source candidates indexed. This is NOT proof they play. Press Play on PC to verify video/audio.','good');
  }catch(e){status('#lookup-status',resultError(e),'bad');}
  finally{button.disabled=false;}
}
let hlsJsPromise;
function loadHls(){
 if(window.Hls)return Promise.resolve(window.Hls);
 if(hlsJsPromise)return hlsJsPromise;
 hlsJsPromise=new Promise((resolve,reject)=>{
   const script=document.createElement('script');
   script.async=true;script.src='https://cdn.jsdelivr.net/npm/hls.js@1.7.3/dist/hls.min.js';
   script.onload=()=>window.Hls?resolve(window.Hls):reject(Error('HLS.js failed to load'));
   script.onerror=()=>reject(Error('Unable to load HLS.js from CDN'));
   document.head.append(script);
 }).catch(e=>{hlsJsPromise=null;throw e;});
 return hlsJsPromise;
}
async function playIndex(index,{automatic=false}={}){
 if(isIos){status('#play-status','iPhone Safari cannot stream P2P with this browser-only setup.','bad');return;}
 if(index<0||index>=sources.length||!Number.isInteger(index))return;
 if(!automatic)attempts=0;
 if(++attempts>MAX_AUTOMATIC_TRIES){
   clearPlayer();
   status('#play-status','No working source was confirmed in '+MAX_AUTOMATIC_TRIES+' attempts. Try another source later.','bad');
   return;
 }
 clearPlayer();
 const myTurn=sequence;
 const source=sources[index],url=localStremioHls(source);
 if(!url)return;
 $('#source-select').value=String(index);
 status('#play-status','Requesting torrent stream '+(index+1)+' of '+sources.length+' from local Stremio Service...');
 const next=reason=>{
   if(myTurn!==sequence)return;
   const nextIndex=index+1;
   if(nextIndex>=sources.length||attempts>=MAX_AUTOMATIC_TRIES){
     status('#play-status',reason+' No confirmed playable alternative remains.','bad');
     clearTimers();return;
   }
   status('#play-status',reason+' Trying next source on the SAME IMDb title...');
   void playIndex(nextIndex,{automatic:true});
 };
 startTimeout=setTimeout(()=>{if(myTurn===sequence&&!played)next('Source startup timed out.');},25000);
 try {
   const Hls=await loadHls();
   if(sequence!==myTurn)return;
   if(!Hls.isSupported()){
     if(video.canPlayType('application/vnd.apple.mpegurl')){
       video.src=url;
       await video.play().catch(()=>{});
       if(sequence===myTurn)$('#video-hint').classList.add('hidden');
       return;
     }
     next('HLS not supported by this browser.');return;
   }
   const player=new Hls({enableWorker:true,lowLatencyMode:false,maxBufferLength:22,
      manifestLoadingTimeOut:16000,fragLoadingTimeOut:25000});
   hls=player;
   player.on(Hls.Events.MEDIA_ATTACHED,()=>{if(sequence===myTurn)player.loadSource(url);});
   player.on(Hls.Events.MANIFEST_PARSED,()=>{
     if(sequence!==myTurn)return;
     $('#video-hint').classList.add('hidden');
     void video.play().catch(e=>{
       if(sequence===myTurn&&e?.name==='NotAllowedError')
         status('#play-status','Video is ready. Tap Play in the video controls.');
     });
   });
   player.on(Hls.Events.ERROR,(_event,e)=>{
     if(sequence===myTurn&&e.fatal)next('HLS playback failed: '+e.type+'.');
   });
   player.attachMedia(video);
 }catch(e){if(sequence===myTurn)next(resultError(e));}
}
video.addEventListener('playing',()=>{
 played=true;clearTimeout(startTimeout);
 $('#video-hint').classList.add('hidden');
 status('#play-status','Playing through your LOCAL Stremio engine. Check picture, sound, and seek position.','good');
});
video.addEventListener('waiting',()=>{
 if(!played||video.paused)return;
 clearTimeout(stallTimeout);
 const token=sequence;
 stallTimeout=setTimeout(()=>{
   if(sequence===token&&!video.paused&&video.readyState<3){
     const current=Number($('#source-select').value);
     const next=current+1;
     if(next<sources.length&&attempts<MAX_AUTOMATIC_TRIES)void playIndex(next,{automatic:true});
     else status('#play-status','Playback stalled; no verified alternate was automatically found.','bad');
   }
 },30000);
});
video.addEventListener('playing',()=>clearTimeout(stallTimeout));
video.addEventListener('error',()=>{
 if(!video.error||!sources.length)return;
 const current=Number($('#source-select').value);
 if(Number.isInteger(current)&&current+1<sources.length&&attempts<MAX_AUTOMATIC_TRIES)
   void playIndex(current+1,{automatic:true});
 else status('#play-status','The browser failed to decode this stream. Try another source.','bad');
});
$('#verify-engine').addEventListener('click',()=>void serviceStatus());
$('#find').addEventListener('click',()=>void findSources());
$('#imdb').addEventListener('keydown',e=>{if(e.key==='Enter')void findSources();});
$('#play').addEventListener('click',()=>void playIndex(Number($('#source-select').value)));
$('#source-select').addEventListener('change',()=>{if(!video.paused||hls)void playIndex(Number($('#source-select').value));});
for(const button of document.querySelectorAll('[data-film]')){
  button.addEventListener('click',()=>{
    $('#imdb').value=button.dataset.film;
    updateNativeAppLink();
    void findSources();
  });
}
const url=new URL(location.href);
if(validMovieId(url.searchParams.get('imdb')))$('#imdb').value=url.searchParams.get('imdb');
$('#imdb').addEventListener('input',updateNativeAppLink);
updateNativeAppLink();
void serviceStatus();
