/**
 * Optional public-domain/CC movie discovery from Wikimedia Commons.
 * No device, account, API key or paid server needed.
 *
 * Commons is a media repository, not a commercial film-stream index. Fail
 * closed: accept video files only after exact-title matching and explicit,
 * allowlisted file-level rights metadata. Do not proxy third-party media.
 */
const API='https://commons.wikimedia.org/w/api.php';
const LICENSE=/^https?:\/\/creativecommons\.org\/(?:publicdomain\/(?:zero\/1\.0|mark\/1\.0)|licenses\/(?:by|by-sa)\/(?:2\.0|2\.5|3\.0|4\.0))(?:\/|$)/i;
const EXT=/\.(mp4|m4v|webm)$/i;
const ALLOWED_SUFFIX=new Set(['alt','original','full','movie','film','hd','uhd','version','webm','1080p','720p','2160p','4k','2k']);
const DENY=/\b(?:trailer|teaser|excerpt|clip|preview|sample|part|segment|behind|keyframe|first|seconds|strobing)\b/i;
// Exactly identified complete Blender Studio open movies, pre-reviewed on
// Wikimedia Commons. Generic title search MUST NOT interpret unrelated
// videos or trailers as movie matches. Rights, format and file height are
// re-verified against Commons imageinfo for EVERY playback request.
const CURATED_EXACT_FILES=new Map([
  ["Singularity|2026","File:SINGULARITY - Blender Open Movie-full movie.webm"],
  ["Wing It!|2023","File:WING IT! - Blender Open Movie-full movie.webm"],
  ["Charge|2022","File:Charge - Blender Open Movie-full movie.webm"],
  ["Sprite Fright|2021","File:Sprite Fright - Blender Open Movie-full movie.webm"],
  ["Coffee Run|2020","File:Coffee Run - Blender Open Movie-full movie.webm"],
  ["Spring|2019","File:Spring - Blender Open Movie.webm"],
  ["Hero|2018","File:HERO - Blender Open Movie-full movie.webm"],
  ["The Daily Dweebs|2017","File:The Daily Dweebs - Blender Open Movie-full movie.webm"],
  ["Glass Half|2015","File:Glass Half - Blender Open Movie-full movie.webm"],
  ["Cosmos Laundromat: First Cycle|2015","File:Cosmos Laundromat - First Cycle - Official Blender Foundation release.webm"],
  ["Caminandes: Gran Dillama|2013","File:Caminandes, Gran Dillama - Blender Foundation.webm"],
]);
export function curatedExactFile(title,year){
  if(!Number.isInteger(Number(year)))return null;
  return CURATED_EXACT_FILES.get(String(title)+'|'+Number(year))||null;
}

function norm(value){
  return String(value||'').normalize('NFKD').toLowerCase()
    .replace(/[\u0300-\u036f]/g,'').replace(/[^a-z0-9]+/g,' ').trim().replace(/\s+/g,' ');
}
function fullMovieFile(fileTitle,title,year){
  if(typeof fileTitle!=='string'||!fileTitle.startsWith('File:')||!EXT.test(fileTitle))return false;
  // Full films with uploader-added subtitles such as "Blender Open Movie"
  // qualify only by EXACT reviewed file name + exact film year.
  if(fileTitle===curatedExactFile(title,year))return true;
  const name=norm(fileTitle.slice(5).replace(EXT,''));
  const expected=norm(title);
  if(!expected || expected.length<3 || DENY.test(name))return false;
  if(name===expected)return true;
  if(!name.startsWith(expected+' '))return false;
  const suffix=name.slice(expected.length+1).split(' ');
  // "Elephants Dream (2006).webm" is an exact full-film source, not a
  // similarly named movie. Accept the release year ONLY when separately
  // supplied catalog metadata confirms that exact year.
  if(suffix.length===1 && Number.isInteger(Number(year)) &&
     Number(year)>=1888 && suffix[0]===String(year))return true;
  return suffix.every(part=>ALLOWED_SUFFIX.has(part));
}
function commonsSourceUrl(value){
  try{
    const u=new URL(value);
    return u.protocol==='https:'&&!u.username&&!u.password
      &&u.hostname==='upload.wikimedia.org'&&EXT.test(u.pathname)?u.href:null;
  }catch{return null;}
}
function cleanLicense(value){
  if(typeof value!=='string')return null;
  let u;try{u=new URL(value)}catch{return null;}
  // Require the actual CC deed URL in the imageinfo licensing metadata.
  if(!LICENSE.test(u.origin+u.pathname))return null;
  return u.origin+u.pathname;
}
function humanCredit(meta){
  // Artist/Credit are often HTML fragments; never render as HTML.
  const text=String(meta?.Credit?.value||meta?.Artist?.value||'')
    .replace(/<[^>]*>/g,' ').replace(/&(?:nbsp|amp|quot|lt|gt);/g,' ')
    .replace(/\s+/g,' ').trim();
  return text.slice(0,135);
}
export function selectCommonsMovies(meta,data){
  if(meta?.media!=='movie'||!meta.title)return [];
  const target=String(meta.originalTitle||meta.title);
  const pages=Object.values(data?.query?.pages||{});
  return pages.flatMap(page=>{
    if(!fullMovieFile(page.title,target,meta.year))return [];
    const info=Array.isArray(page.imageinfo)?page.imageinfo[0]:null;
    if(!info)return [];
    const quality=Number(info.height);
    const url=commonsSourceUrl(info.url);
    const licenseUrl=cleanLicense(info.extmetadata?.LicenseUrl?.value);
    if(!url||!licenseUrl||!/video\/(?:webm|mp4)/i.test(String(info.mime||''))||
      !Number.isInteger(quality)||quality<720||quality>4320||
      Number(info.size)<400_000)return [];
    const sourcePage=String(info.descriptionurl||'');
    if(!/^https:\/\/commons\.wikimedia\.org\/wiki\/File:/i.test(sourcePage))return [];
    const licenseName=String(info.extmetadata?.LicenseShortName?.value||'Creative Commons')
      .replace(/<[^>]*>/g,'').slice(0,35);
    const credit=humanCredit(info.extmetadata);
    return [{
      name:'Wikimedia Commons • '+licenseName,
      url,quality,sizeBytes:Number(info.size),subtitles:[],preferred:quality<=1080,
      licenseUrl,sourcePage,attribution:credit||'Wikimedia Commons'
    }];
  }).sort((a,b)=>b.quality-a.quality).slice(0,3);
}
/**
 * Resolve official 4:2:0 VP9 transcoding for the licensed Elephants Dream
 * master, whose original VP9 4:4:4 is not decoded by Safari.
 * Trust only direct derivative URLs supplied by Commons for the exact
 * independently licensed original page. No synthesized or proxied links.
 */
export function selectElephantsSafariDerivative(original,data){
  if(!original || !/\/wiki\/File:Elephants_Dream_\(2006\)\.webm$/i.test(original.sourcePage||''))
    return [];
  const page=Object.values(data?.query?.pages||{}).find(p=>
    p.title==='File:Elephants Dream (2006).webm');
  const video=page?.videoinfo?.[0];
  if(!video||commonsSourceUrl(video.url)!==original.url)return [];
  const variants=Array.isArray(video.derivatives)?video.derivatives:[];
  return variants.filter(d=>{
    const key=String(d.transcodekey||'');
    return /^(?:720|1080)p\.vp9\.webm$/i.test(key) &&
      /^video\/webm(?:;|$)/i.test(String(d.type||'')) &&
      Number(d.height)>=720 && Number(d.height)<=1080;
  }).flatMap(d=>{
    let url;
    try{url=commonsSourceUrl(new URL(d.src,'https://commons.wikimedia.org').href);}
    catch{return [];}
    if(!url||url===original.url)return [];
    return [{
      ...original,url,quality:Number(d.height),sizeBytes:null,
      name:'Wikimedia Commons • Safari-compatible VP9',
      preferred:true,codecOptimized:true
    }];
  }).sort((a,b)=>b.quality-a.quality).slice(0,2);
}

/**
 * MediaWiki's OWN 1080p transcoded rendition of an independently verified
 * CC original. Unlike a guessed CDN path, every URL comes from the official
 * videoinfo API for the SAME exact Commons file and retains attribution.
 * The result is optional: a missing transcode never hides the original.
 */
export function selectVerifiedCommonsFhdDerivative(original,data){
  if(!original || !commonsSourceUrl(original.url) ||
     !cleanLicense(original.licenseUrl) ||
     !/^https:\/\/commons\.wikimedia\.org\/wiki\/File:/i.test(original.sourcePage||'') ||
     Number(original.quality)<=1080)return [];
  let title;
  try{
    const page=new URL(original.sourcePage);
    if(page.hostname!=='commons.wikimedia.org')return [];
    title=decodeURIComponent(page.pathname.split('/wiki/')[1]||'').replaceAll('_',' ');
    if(!title.startsWith('File:')||!EXT.test(title))return [];
  }catch{return [];}
  const pages=Object.values(data?.query?.pages||{});
  const file=pages.find(p=>p.title===title);
  const info=file?.videoinfo?.[0];
  if(!info || commonsSourceUrl(info.url)!==original.url)return [];
  const derivatives=Array.isArray(info.derivatives)?info.derivatives:[];
  return derivatives.filter(d=>{
    const height=Number(d?.height),width=Number(d?.width);
    return /^1080p\.vp9\.webm$/i.test(String(d?.transcodekey||'')) &&
      /^video\/webm(?:;|$)/i.test(String(d?.type||'')) &&
      Number.isInteger(height) && height>=800 && height<=1080 &&
      (!Number.isFinite(width) || width>=1880);
  }).flatMap(d=>{
    let url;
    try{url=commonsSourceUrl(new URL(d.src,'https://commons.wikimedia.org').href);}
    catch{return [];}
    if(!url||url===original.url)return [];
    return [{
      ...original,url,quality:1080,sizeBytes:null,
      name:'Wikimedia Commons • official FHD (VP9)',
      preferred:true,codecOptimized:true,
      // Clearly separate the labelled 1080p rendition from real dimensions;
      // user playback confirms videoWidth/videoHeight after decoding.
      encodedHeight:Number(d.height),
      encodedWidth:Number(d.width)||null
    }];
  }).slice(0,1);
}

export async function fromCommons(meta,{fetchImpl=fetch}={}){
  if(meta?.media!=='movie'||!meta.title||meta.title.length>100)return [];
  const title=String(meta.originalTitle||meta.title);
  const reviewed=curatedExactFile(title,meta.year);
  // Allow punctuation in the *exact reviewed* Blender film title (Wing It!,
  // Cosmos Laundromat: First Cycle). Unreviewed input keeps strict validation.
  if(!reviewed && !/^[a-zA-Z0-9 ,.'-]{3,100}$/.test(title))return [];
  const base={
    action:'query',prop:'imageinfo',iiprop:'url|size|mime|extmetadata',
    format:'json',formatversion:'2'
  };
  const search=new URL(API);
  Object.entries({...base,generator:'search',gsrsearch:'filetype:video "'+title+'"',
    gsrnamespace:'6',gsrlimit:'12'}).forEach(([k,v])=>search.searchParams.set(k,v));
  // Commons sometimes ranks huge 4K uploads first and omits the playable
  // 720p version from search results. Read only these exact possible filenames
  // and still verify every returned file's license, quality and full title.
  const exact=new URL(API);
  Object.entries({...base,titles:reviewed || [
    'File:'+title+' alt.webm',
    'File:'+title+'.webm',
    'File:'+title+'.mp4'
  ].join('|')}).forEach(([k,v])=>exact.searchParams.set(k,v));
  async function query(u){
    try {
      const response=await fetchImpl(u.href,{
        method:'GET',redirect:'error',headers:{
          Accept:'application/json',
          'User-Agent':'MOOSAWeb/0.1 (https://github.com/MooOsa1/MOOSA-CloudStream; CC video source discovery)'
        },signal:AbortSignal.timeout(5000)
      });
      if(!response.ok||Number(response.headers.get('content-length')||0)>800_000)return [];
      const json=await response.text();
      if(json.length>800_000)return [];
      return selectCommonsMovies(meta,JSON.parse(json));
    }catch{return [];}
  }
  // The pre-reviewed modern films have ONE known full film filename.
  // Do not search thousands of unrelated category uploads; request the exact
  // item in one round trip. This keeps Render free-tier/API load bounded.
  const [searched,variants]=reviewed?[[],await query(exact)]:
    await Promise.all([query(search),query(exact)]);
  const seen=new Set();
  const originals=[...variants,...searched].filter(source=>{
    if(seen.has(source.url))return false;
    seen.add(source.url);
    return true;
  });
  let compatible=[];
  if(title==='Elephants Dream'){
    // Only this known 4:4:4 original needs a dedicated Safari transcode.
    // Derivatives are official Commons API results, not guessed URLs.
    const master=originals.find(x=>/\/wiki\/File:Elephants_Dream_\(2006\)\.webm$/i.test(x.sourcePage));
    if(master){
      const api=new URL(API);
      Object.entries({
        action:'query',prop:'videoinfo',viprop:'url|derivatives',
        titles:'File:Elephants Dream (2006).webm',
        format:'json',formatversion:'2'
      }).forEach(([k,v])=>api.searchParams.set(k,v));
      try{
        const result=await fetchImpl(api.href,{
          method:'GET',redirect:'error',
          headers:{Accept:'application/json',
            'User-Agent':'MOOSAWeb/0.1 (https://github.com/MooOsa1/MOOSA-CloudStream; CC video variants)'},
          signal:AbortSignal.timeout(3500)
        });
        if(result.ok && Number(result.headers.get('content-length')||0)<350_000){
          const raw=await result.text();
          if(raw.length<350_000)compatible=selectElephantsSafariDerivative(master,JSON.parse(raw));
        }
      }catch{} // The original independently licensed source still works as fallback.
    }
  }
  // The upstream approved 4K file is often several GB; fetch its official
  // FHD derivative for these four precisely identified CC open movies, when
  // Wikimedia exposes one. Never compute URL strings or fetch ad hosters.
  const curatedYears={'Big Buck Bunny':2008,Sintel:2010,'Tears of Steel':2012};
  let fhd=[];
  if(curatedYears[title]===Number(meta.year)||reviewed){
    const master=originals.find(source=>source.quality>1080 &&
      /^https:\/\/commons\.wikimedia\.org\/wiki\/File:/i.test(source.sourcePage||''));
    if(master){
      try{
        const fileTitle=decodeURIComponent(new URL(master.sourcePage).pathname
          .split('/wiki/')[1]||'').replaceAll('_',' ');
        if(fileTitle.startsWith('File:') && fileTitle.length<=130){
          const api=new URL(API);
          Object.entries({
            action:'query',prop:'videoinfo',viprop:'url|derivatives',
            titles:fileTitle,format:'json',formatversion:'2'
          }).forEach(([k,v])=>api.searchParams.set(k,v));
          const response=await fetchImpl(api.href,{
            headers:{Accept:'application/json',
              'User-Agent':'MOOSAWeb/0.1 (https://github.com/MooOsa1/MOOSA-CloudStream; CC FHD derivatives)'},
            redirect:'error',signal:AbortSignal.timeout(3500)
          });
          if(response.ok && Number(response.headers.get('content-length')||0)<350000){
            const raw=await response.text();
            if(raw.length<350000)fhd=selectVerifiedCommonsFhdDerivative(master,JSON.parse(raw));
          }
        }
      }catch{} // Original film remains playable when Commons is slow.
    }
  }
  return [...compatible,...fhd,...originals].sort((a,b)=>{
    if(Boolean(a.codecOptimized)!==Boolean(b.codecOptimized))
      return Number(Boolean(b.codecOptimized))-Number(Boolean(a.codecOptimized));
    const light=q=>q<=1080?1:0;
    return light(b.quality)-light(a.quality)||b.quality-a.quality;
  }).slice(0,4);
}
