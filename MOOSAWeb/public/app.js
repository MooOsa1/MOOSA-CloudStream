import { showPlayer, hidePlayer, startDemo } from './player.js';
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const state = {
  view:'home', items:[], page:1, hasMore:false, busy:false,
  browse:{media:'movie',genre:'',sort:'popularity.desc',country:''},
  mode:'discover', section:'', query:'', current:null, sources:[], featured:null, episode:null,
  saved:loadSaved(), requestId:0, lastFocus:null, catalogReady:false
};
const kinds = {
  trending_movies:'أفلام رائجة',trending_series:'مسلسلات رائجة',
  top_movies:'أعلى الأفلام تقييمًا',top_series:'أعلى المسلسلات تقييمًا',
  indian_trending:'أفلام هندية رائجة',indian_top:'أفضل الأفلام الهندية'
};
const GENRES = {
  movie:[[28,'أكشن'],[12,'مغامرات'],[16,'أنيميشن'],[35,'كوميديا'],[80,'جريمة'],[99,'وثائقي'],[18,'دراما'],[10751,'عائلي'],[14,'فانتازيا'],[36,'تاريخ'],[27,'رعب'],[9648,'غموض'],[10749,'رومانسي'],[878,'خيال علمي'],[53,'إثارة'],[10752,'حرب'],[37,'ويسترن']],
  tv:[[10759,'أكشن ومغامرات'],[16,'أنيميشن'],[35,'كوميديا'],[80,'جريمة'],[99,'وثائقي'],[18,'دراما'],[10751,'عائلي'],[9648,'غموض'],[10764,'واقعي'],[10765,'خيال علمي وفانتازيا'],[10768,'حرب وسياسة'],[37,'ويسترن']]
};
const SORT_LABELS={'popularity.desc':'الأكثر شعبية','vote_average.desc':'الأعلى تقييمًا','vote_count.desc':'الأكثر تصويتًا','date.desc':'الأحدث'};
const COUNTRY_LABELS={IN:'الهند',US:'أمريكا',GB:'بريطانيا',KR:'كوريا الجنوبية',JP:'اليابان',TR:'تركيا',ES:'إسبانيا',FR:'فرنسا',EG:'مصر',SA:'السعودية'};
const overlay = $('#overlay');
let toastTimer;
let searchTimer;
function toast(msg){const node=$('#toast');node.textContent=msg;node.classList.remove('hidden');clearTimeout(toastTimer);toastTimer=setTimeout(()=>node.classList.add('hidden'),3700);}
async function api(path){const res=await fetch(path,{headers:{Accept:'application/json'}});const data=await res.json();if(!res.ok)throw new Error(data.error||'تعذر تحميل البيانات');return data;}
function loadSaved(){try{const x=JSON.parse(localStorage.getItem('moosa-watchlist')||'[]');return Array.isArray(x)?x.slice(0,200):[]}catch{return[]}}
function saveSaved(){try{localStorage.setItem('moosa-watchlist',JSON.stringify(state.saved))}catch{toast('تعذر حفظ القائمة على هذا المتصفح')}}
function imgSrc(path,size='w500'){return /^\/[\w.\-]+$/.test(path||'')?`https://image.tmdb.org/t/p/${size}${path}`:''}
function mediaFor(item,forced){return forced || item.media_type || (item.name && !item.title ? 'tv':'movie')}
function titleFor(item){return item.title || item.name || item.original_title || item.original_name || 'بدون عنوان'}
function yearFor(item){return (item.release_date || item.first_air_date || '').slice(0,4)}
function ratingFor(item){return Number(item.vote_average||0)>0 && Number(item.vote_count||0)>0?Number(item.vote_average).toFixed(1):''}
function makeEl(tag,cls='',text=''){const el=document.createElement(tag);if(cls)el.className=cls;if(text)el.textContent=text;return el}
function card(item, forced){
  if(!item||!Number.isInteger(item.id))return null;
  const type=mediaFor(item,forced);if(!['tv','movie'].includes(type))return null;
  const button=makeEl('button','poster-card');button.type='button';button.setAttribute('aria-label',`تفاصيل ${titleFor(item)}`);
  const frame=makeEl('div','poster-frame');const pic=makeEl('img');pic.alt=`بوستر ${titleFor(item)}`;pic.loading='lazy';pic.decoding='async';
  const src=imgSrc(item.poster_path||item.backdrop_path,'w342');
  if(src){pic.src=src;pic.onload=()=>pic.classList.add('ready');pic.onerror=()=>{pic.remove();frame.append(makeEl('span','poster-missing',titleFor(item)))};frame.append(pic)}else{frame.append(makeEl('span','poster-missing',titleFor(item)))}
  frame.append(makeEl('div','poster-gradient'));
  const rating=ratingFor(item);if(rating)frame.append(makeEl('span','rating',rating));
  button.append(frame,makeEl('span','poster-name',titleFor(item)),makeEl('span','poster-year',yearFor(item)));
  button.addEventListener('click',()=>openDetails({id:item.id,type,partial:item}));return button;
}
function renderCards(where,items,forced){where.replaceChildren();let n=0;for(const item of items){const node=card(item,forced);if(node){where.append(node);n++;}}if(!n)where.append(makeEl('div','empty-state','ما حصلنا نتائج مطابقة. جرّب فلتر ثاني.'));}
function showView(which){state.view=which;$('#home-view').classList.toggle('hidden',which!=='home');$('#browse-view').classList.toggle('hidden',which!=='browse');$('#saved-view').classList.toggle('hidden',which!=='saved');$$('.nav-icon[data-nav]').forEach(b=>b.classList.toggle('selected',b.dataset.nav=== (which==='browse'?(state.browse.media==='tv'?'series':'movies'):which)));document.documentElement.scrollTop=0;}
function showBusyGrid(){const grid=$('#browse-grid');grid.replaceChildren();const loader=makeEl('div','loader');grid.append(loader)}
function chips(){const el=$('#active-chips');el.replaceChildren();const name=state.browse.media==='tv'?'مسلسلات':'أفلام';let items=[name];if(state.mode==='section'&&state.section)items.push(kinds[state.section]||state.section);else{const genre=GENRES[state.browse.media].find(g=>String(g[0])===state.browse.genre);if(genre)items.push(genre[1]);items.push(SORT_LABELS[state.browse.sort]);if(state.browse.country)items.push(COUNTRY_LABELS[state.browse.country]);}if(state.mode==='search')items=[`نتائج: ${state.query}`];for(const name of items)el.append(makeEl('span','chip',name));}
function updateGenreSelector(){const select=$('#filter-genre'),old=select.value;select.replaceChildren(new Option('جميع التصنيفات',''));for(const [id,name] of GENRES[$('#filter-media').value])select.add(new Option(name,String(id)));select.value=[...select.options].some(o=>o.value===old)?old:'';}
function syncFilters(){for(const [key,id] of [['media','#filter-media'],['sort','#filter-sort'],['country','#filter-country']])$(id).value=state.browse[key];updateGenreSelector();$('#filter-genre').value=state.browse.genre;}
function paramsForBrowse(){const p=new URLSearchParams({media:state.browse.media,sort:state.browse.sort,page:String(state.page)});if(state.browse.genre)p.set('genre',state.browse.genre);if(state.browse.country)p.set('country',state.browse.country);return '/api/discover?'+p;}
async function loadBrowse(append=false){
  if(state.busy && append)return;
  state.busy=true;const request=++state.requestId;
  const loadMore=$('#load-more');loadMore.disabled=true;
  if(!append){state.page=1;state.items=[];showBusyGrid();}
  const url=state.mode==='section'?`/api/catalog?section=${encodeURIComponent(state.section)}&page=${state.page}`:state.mode==='search'?`/api/search?q=${encodeURIComponent(state.query)}&page=${state.page}`:paramsForBrowse();
  try {
    const body=await api(url);
    if(request!==state.requestId)return;
    const next=(body.results||[]).filter(i=>i && i.id && (i.media_type!=='person'));
    const known=new Set(state.items.map(i=>mediaFor(i)+':'+i.id));const unique=next.filter(x=>{const key=mediaFor(x)+':'+x.id;if(known.has(key))return false;known.add(key);return true});
    state.items.push(...unique);state.hasMore=state.page<Math.min(body.total_pages||1,500)&&next.length>0;
    renderCards($('#browse-grid'),state.items,state.mode==='search'?undefined:state.browse.media);
    loadMore.classList.toggle('hidden',!state.hasMore);
  }catch(e){if(request===state.requestId){if(!append)$('#browse-grid').replaceChildren(makeEl('div','empty-state',e.message));toast(e.message)}}
  finally{if(request===state.requestId){state.busy=false;loadMore.disabled=false;}}
}
function browseSection(section){state.section=section;state.mode='section';state.browse={media:section.includes('series')?'tv':'movie',genre:'',sort:'popularity.desc',country:section.startsWith('indian')?'IN':''};$('#browse-title').textContent=kinds[section]||'استكشف';chips();syncFilters();$('#filters-panel').classList.add('hidden');showView('browse');loadBrowse();}
function browseMedia(type){state.mode='discover';state.section='';state.browse={media:type,genre:'',sort:'popularity.desc',country:''};$('#browse-title').textContent=type==='movie'?'اكتشف الأفلام':'اكتشف المسلسلات';chips();syncFilters();$('#filters-panel').classList.add('hidden');showView('browse');loadBrowse();}
async function showSearch(value){const q=value.trim();if(q.length<2){navigate('home');return}state.query=q;state.mode='search';state.browse={media:'movie',genre:'',sort:'popularity.desc',country:''};$('#browse-title').textContent='نتائج البحث';chips();$('#filters-toggle').classList.add('hidden');$('#filters-panel').classList.add('hidden');showView('browse');loadBrowse();}
function navigate(nav){if(nav==='home'){showView('home');}else if(nav==='movies'){browseMedia('movie');}else if(nav==='series'){browseMedia('tv');}else if(nav==='saved'){showView('saved');renderSaved();}}
function renderSaved(){const grid=$('#saved-grid');grid.replaceChildren();if(!state.saved.length){grid.append(makeEl('div','empty-state','قائمتك فاضية حاليًا. افتح فيلمًا واضغط ＋ قائمتي لإضافته.'));return;}for(const it of state.saved){grid.append(card(it,mediaFor(it)));}}
async function loadShelf(name){const row=$(`.shelf[data-section="${name}"] .poster-row`);try{const res=await api(`/api/catalog?section=${name}&page=1`);row.classList.remove('skeleton-row');renderCards(row,(res.results||[]).slice(0,16),name.includes('series')?'tv':'movie');if(name==='trending_movies' && res.results?.length){const choice=res.results.find(x=>x.backdrop_path) || res.results[0];state.featured={id:choice.id,type:'movie',partial:choice};$('#hero-title').textContent=titleFor(choice);$('#hero-desc').textContent=choice.overview||'اكتشف أحدث الإصدارات على MOOSA Web.';$('#hero-meta').textContent=[yearFor(choice),ratingFor(choice)?'★ '+ratingFor(choice):'', 'فيلم'].filter(Boolean).join('  •  ');const backdrop=imgSrc(choice.backdrop_path||choice.poster_path,'w1280');if(backdrop)$('#hero-image').style.backgroundImage=`url("${backdrop}")`;$('#hero-play').textContent='استكشف الفيلم';$('#hero-details').textContent='التفاصيل';}}
catch(e){row.classList.remove('skeleton-row');row.replaceChildren(makeEl('div','error-inline','التصنيف غير متاح الآن. '+(e.message.includes('TMDB')?'أضف مفتاح TMDB لعرض نتائج حقيقية.':'تأكد من الاتصال ثم جرّب مجددًا.')));}}
function keyFor(it){return `${it.type||mediaFor(it)}:${it.id}`;}
async function openDetails(entry){
  state.lastFocus=document.activeElement;state.current=entry;state.sources=[];state.episode=null;
  $('#episode-selector').classList.add('hidden');
  $('#episode-grid').replaceChildren();
  $('#season-picker').replaceChildren();
  $('#watch-title').textContent=entry.type==='tv'?'▤ اختر حلقة':'▶ مشاهدة';
  const {id,type,partial}=entry;
  $('#details-name').textContent=titleFor(partial);$('#details-kind').textContent=type==='tv'?'TV SERIES':'MOVIE';$('#details-description').textContent=partial.overview||'جاري تحميل الوصف...';$('#details-meta').textContent=[yearFor(partial),ratingFor(partial)?'★ '+ratingFor(partial):''].filter(Boolean).join('  •  ');
  const poster=imgSrc(partial.backdrop_path||partial.poster_path,'w1280');$('#details-art').style.backgroundImage=poster?`url("${poster}")`:'';
  $('#source-info').textContent='السيرفرات متاحة فقط عندما تضيف مصدرًا تملك حق استخدامه.';
  $('#save-title').textContent=state.saved.some(x=>keyFor(x)===keyFor(entry))?'✓ في قائمتي':'＋ قائمتي';
  overlay.classList.remove('hidden');$('#detail-panel').classList.remove('hidden');$('#close-details').focus();
  const token=keyFor(entry);
  // Metadata and video-provider availability are independent. One
  // network failure must not hide authorized video sources.
  const [fullResult, streamResult] = await Promise.allSettled([
    api(`/api/details?media=${type}&id=${id}`),
    api(`/api/streams?media=${type}&id=${id}`)
  ]);
  if(!state.current || keyFor(state.current)!==token ||
    $('#detail-panel').classList.contains('hidden'))return;
  if(fullResult.status==='fulfilled'){
    const full=fullResult.value;
    state.current.partial={...partial,...full};
    $('#details-description').textContent=full.overview||'لا يوجد وصف متاح.';
    $('#details-meta').textContent=[
      (full.release_date||full.first_air_date||'').slice(0,4),
      ratingFor(full)?'★ '+ratingFor(full):'',
      full.runtime?full.runtime+' دقيقة':(full.number_of_seasons?full.number_of_seasons+' مواسم':'')
    ].filter(Boolean).join('  •  ');
    if(type==='tv' && Array.isArray(full.seasons)) {
      const seasons=full.seasons.filter(se=>Number.isInteger(se.season_number) && se.season_number>0);
      if(seasons.length) {
        $('#episode-selector').classList.remove('hidden');
        $('#season-picker').replaceChildren(...seasons.map(se=>
          new Option(se.name || 'الموسم '+se.season_number,String(se.season_number))));
        loadEpisodes(id,seasons[0].season_number);
      }
    }
  }
  state.sources=streamResult.status==='fulfilled'?(streamResult.value.sources||[]):[];
  $('#source-info').textContent=state.sources.length
    ?`${state.sources.length} مصدر مصرح به متاح للتشغيل`
    :'لا توجد روابط مشاهدة مضافة لهذا العنوان؛ يمكنك تشغيل فيديو تملكه أو تجربة المشغل.';
}
async function loadEpisodes(id,season){
  const episodeGrid=$('#episode-grid'),titleKey=state.current?keyFor(state.current):'';
  episodeGrid.replaceChildren(makeEl('div','error-inline','جاري تحميل الحلقات...'));
  try {
    const data=await api('/api/episodes?'+new URLSearchParams({id,season}));
    if(!state.current || keyFor(state.current)!==titleKey ||
      $('#detail-panel').classList.contains('hidden') ||
      $('#season-picker').value!==String(season))return;
    episodeGrid.replaceChildren();
    const episodes=data.episodes||[];
    if(!episodes.length)episodeGrid.append(makeEl('div','error-inline','لا توجد حلقات مسجلة لهذا الموسم.'));
    for(const episode of episodes){
      const number=Number(episode.episode_number);
      if(!Number.isInteger(number) || number<1)continue;
      const button=makeEl('button','episode-choice');
      button.type='button';
      const image=imgSrc(episode.still_path,'w300');
      if(image){const im=document.createElement('img');im.src=image;im.loading='lazy';im.alt='';button.append(im);}
      const summary=makeEl('div','episode-copy');
      summary.append(makeEl('strong','',number+'. '+(episode.name||'الحلقة '+number)));
      summary.append(makeEl('span','',episode.air_date||''));
      summary.append(makeEl('small','',String(episode.overview||'').slice(0,170)));
      button.append(summary);
      button.addEventListener('click',()=>playEpisode(id,season,number,episode.name||''));
      episodeGrid.append(button);
    }
  } catch {
    if(state.current && keyFor(state.current)===titleKey)
      episodeGrid.replaceChildren(makeEl('div','error-inline','تعذر تحميل حلقات هذا الموسم.'));
  }
}
async function playEpisode(id,season,number,name){
  const current=state.current;if(!current || current.type!=='tv' || String(current.id)!==String(id))return;
  const token=keyFor(current);
  $('#source-info').textContent='جاري البحث عن سيرفر هذه الحلقة...';
  let sources=[];
  try {
    const response=await api('/api/streams?'+new URLSearchParams({media:'tv',id,season,episode:number}));
    sources=response.sources||[];
  }catch{ /* The player can still accept an authorized custom/local video. */ }
  if(!state.current || keyFor(state.current)!==token)return;
  const title=titleFor(current.partial)+' • S'+season+'E'+number+(name?' • '+name:'');
  state.episode={season,number};
  closeDetails();
  showPlayer({title,sources});
  if(!sources.length)toast('لا يوجد سيرفر مصرّح به لهذه الحلقة؛ تقدر تستخدم رابطك أو ملفًا محليًا.');
}
function closeDetails(){$('#detail-panel').classList.add('hidden');if($('#player-panel').classList.contains('hidden'))overlay.classList.add('hidden');state.lastFocus?.focus?.();}
function saveTitle(){if(!state.current)return;const item={...state.current.partial,media_type:state.current.type};const key=keyFor(state.current);const idx=state.saved.findIndex(x=>mediaFor(x)+':'+x.id===key);if(idx>=0)state.saved.splice(idx,1);else state.saved.unshift(item);saveSaved();$('#save-title').textContent=idx>=0?'＋ قائمتي':'✓ في قائمتي';toast(idx>=0?'حُذف من قائمتك':'أُضيف إلى قائمتك');}
function showWatch(){
  const current=state.current;
  if(current?.type==='tv'){
    if(!$('#episode-selector').classList.contains('hidden')){
      $('#episode-selector').scrollIntoView({behavior:'smooth',block:'nearest'});
      $('#episode-grid .episode-choice')?.focus();
    }else toast('انتظر تحميل المواسم، ثم اختر الحلقة التي تبي تشغلها.');
    return;
  }
  const title=current?.partial?titleFor(current.partial):'مشغل MOOSA';
  const sources=state.sources||[];
  closeDetails();showPlayer({title,sources});
}
function openStandalone(){state.lastFocus=document.activeElement;state.current=null;state.sources=[];showPlayer({title:'مشغل MOOSA',sources:[]});}
async function init(){
  $('#year').textContent=new Date().getFullYear();
  $$('[data-nav]').forEach(el=>el.addEventListener('click',()=>navigate(el.dataset.nav)));
  $$('[data-section-view]').forEach(el=>el.addEventListener('click',()=>browseSection(el.dataset.sectionView)));
  $('#hero-play').addEventListener('click',()=>state.featured?openDetails(state.featured):openStandalone());
  $('#hero-details').addEventListener('click',()=>state.featured?openDetails(state.featured):browseMedia('movie'));
  $('#open-demo').addEventListener('click',()=>{openStandalone();startDemo()});
  $('#filters-toggle').addEventListener('click',()=>{const panel=$('#filters-panel');panel.classList.toggle('hidden');if(!panel.classList.contains('hidden'))$('#filter-media').focus();});
  $('#filter-media').addEventListener('change',updateGenreSelector);
  $('#filters-apply').addEventListener('click',()=>{state.browse={media:$('#filter-media').value,genre:$('#filter-genre').value,sort:$('#filter-sort').value,country:$('#filter-country').value};state.mode='discover';state.section='';$('#browse-title').textContent=state.browse.media==='movie'?'أفلام حسب اختيارك':'مسلسلات حسب اختيارك';chips();$('#filters-toggle').classList.remove('hidden');$('#filters-panel').classList.add('hidden');loadBrowse();});
  $('#filter-reset').addEventListener('click',()=>{state.browse={media:$('#filter-media').value,genre:'',sort:'popularity.desc',country:''};syncFilters();});
  $('#load-more').addEventListener('click',()=>{if(!state.busy&&state.hasMore){state.page++;loadBrowse(true)}});
  $('#search-input').addEventListener('input',e=>{clearTimeout(searchTimer);const q=e.target.value;searchTimer=setTimeout(()=>q.trim().length>=2?showSearch(q):q.trim()===''&&navigate('home'),330);});
  $('#close-details').addEventListener('click',closeDetails);$('#watch-title').addEventListener('click',showWatch);$('#save-title').addEventListener('click',saveTitle);
  $('#season-picker').addEventListener('change',e=>{
    if(state.current?.type==='tv')loadEpisodes(state.current.id,Number(e.target.value));
  });
  $('#help-button').addEventListener('click',()=>toast('MOOSA Web: مكتبة TMDB حقيقية ومشغل للمصادر المصرح بها. تفاصيل الإعداد داخل README.'));
  overlay.addEventListener('click',()=>{if(!$('#player-panel').classList.contains('hidden'))hidePlayer();else closeDetails();});
  document.addEventListener('keydown',e=>{
    if(e.key==='/'&&!/INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName||'')){e.preventDefault();$('#search-input').focus();return;}
    if(e.key==='Escape'){if(!$('#player-panel').classList.contains('hidden')){hidePlayer();return}if(!$('#detail-panel').classList.contains('hidden')){closeDetails();return}if(!$('#filters-panel').classList.contains('hidden')){$('#filters-panel').classList.add('hidden');$('#filters-toggle').focus();return}}
    if(e.key==='ArrowRight'||e.key==='ArrowLeft'||e.key==='ArrowDown'||e.key==='ArrowUp'){if(/INPUT|TEXTAREA|SELECT|VIDEO/.test(document.activeElement?.tagName||''))return;navigateTV(e);}
  });
  try{const config=await api('/api/config');state.catalogReady=config.catalogReady;$('#configuration-banner').classList.toggle('hidden',config.catalogReady);if(config.catalogReady)await Promise.allSettled(Object.keys(kinds).filter(k=>$$(`.shelf[data-section="${k}"]`).length).map(loadShelf));else $$('.shelf .poster-row').forEach(row=>{row.classList.remove('skeleton-row');row.replaceChildren(makeEl('div','error-inline','اربط بيانات TMDB لتظهر هنا عناوين حقيقية، بدون نتائج عشوائية.'))});}
  catch(e){toast('تعذر الاتصال بالخادم');}
}
function navigateTV(e){
  const openModal=!$('#player-panel').classList.contains('hidden')?'#player-panel':!$('#detail-panel').classList.contains('hidden')?'#detail-panel':null;
  const root=openModal?$(openModal):document;
  const focusable=[...root.querySelectorAll('button:not(:disabled),select,input,.poster-card')].filter(n=>{const r=n.getBoundingClientRect();return r.width>0&&r.height>0&&getComputedStyle(n).visibility!=='hidden'});
  if(focusable.length>250)return;
  const from=document.activeElement;
  if(!focusable.includes(from))return;
  const a=from.getBoundingClientRect();const cx=a.left+a.width/2,cy=a.top+a.height/2;
  const axis=e.key==='ArrowDown'?'down':e.key==='ArrowUp'?'up':e.key==='ArrowLeft'?'left':'right';
  let best=null,score=Infinity;
  for(const n of focusable){if(n===from)continue;const b=n.getBoundingClientRect(),dx=b.left+b.width/2-cx,dy=b.top+b.height/2-cy;const along=axis==='down'?dy:axis==='up'?-dy:axis==='right'?dx:-dx;const across=axis==='down'||axis==='up'?Math.abs(dx):Math.abs(dy);if(along<12)continue;const metric=along+across*2.2;if(metric<score){score=metric;best=n;}}
  if(best){e.preventDefault();best.focus({preventScroll:true});best.scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth',block:'nearest',inline:'nearest'});}
}
init();
