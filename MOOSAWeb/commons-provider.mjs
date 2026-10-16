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
function norm(value){
  return String(value||'').normalize('NFKD').toLowerCase()
    .replace(/[\u0300-\u036f]/g,'').replace(/[^a-z0-9]+/g,' ').trim().replace(/\s+/g,' ');
}
function fullMovieFile(fileTitle,title){
  if(typeof fileTitle!=='string'||!fileTitle.startsWith('File:')||!EXT.test(fileTitle))return false;
  const name=norm(fileTitle.slice(5).replace(EXT,''));
  const expected=norm(title);
  if(!expected || expected.length<3 || DENY.test(name))return false;
  if(name===expected)return true;
  if(!name.startsWith(expected+' '))return false;
  return name.slice(expected.length+1).split(' ').every(part=>ALLOWED_SUFFIX.has(part));
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
    if(!fullMovieFile(page.title,target))return [];
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
export async function fromCommons(meta,{fetchImpl=fetch}={}){
  if(meta?.media!=='movie'||!meta.title || meta.title.length>100||
    !/^[a-zA-Z0-9 ,.'-]{3,100}$/.test(meta.originalTitle||meta.title))return [];
  const title=String(meta.originalTitle||meta.title);
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
  Object.entries({...base,titles:[
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
  const [searched,variants]=await Promise.all([query(search),query(exact)]);
  const seen=new Set();
  return [...variants,...searched].filter(source=>{
    if(seen.has(source.url))return false;
    seen.add(source.url);
    return true;
  }).sort((a,b)=>{
    // Prefer small full-HD/HD files for normal browser/mobile streaming;
    // still include 4K as a selectable alternative.
    const light=q=>q<=1080?1:0;
    return light(b.quality)-light(a.quality)||b.quality-a.quality;
  }).slice(0,4);
}
