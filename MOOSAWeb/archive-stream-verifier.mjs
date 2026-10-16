/**
 * Independently validates Archive.org direct streams supplied by external
 * Stremio-compatible plugins. A Stremio title/name/IMDb association is NOT
 * evidence that a returned video is the requested film.
 *
 * Validation requires the actual Archive item metadata, exact original
 * film title (+ optional correct year), file path/size/height, and an
 * explicit Creative Commons license. No HTTP proxy or file downloading.
 */
const LICENSE=/^https?:\/\/creativecommons\.org\/(?:licenses\/(?:by|by-sa)\/(?:2\.0|2\.5|3\.0|4\.0)|publicdomain\/(?:zero\/1\.0|mark\/1\.0))(?:\/|$)/i;
const BAD_FILE=/(?:^|[._\s-])(?:trailer|teaser|preview|excerpt|clip|sample|part|segment|behind)(?:[._\s-]|$)/i;
function norm(value){
  return String(value||'').normalize('NFKD').toLowerCase()
    .replace(/[\u0300-\u036f]/g,'').replace(/[^a-z0-9]+/g,' ').trim()
    .replace(/\s+/g,' ');
}
export function parseArchiveFileUrl(value){
  let u;
  try{u=new URL(value);}catch{return null;}
  if(u.protocol!=='https:'||u.hostname!=='archive.org'||u.username||u.password)return null;
  if(!u.pathname.startsWith('/download/'))return null;
  const parts=u.pathname.slice('/download/'.length).split('/');
  if(parts.length<2)return null;
  let identifier,filename;
  try{
    identifier=decodeURIComponent(parts.shift());
    filename=decodeURIComponent(parts.join('/'));
  }catch{return null;}
  if(!/^[a-zA-Z0-9_][\w.-]{1,95}$/.test(identifier) ||
    !filename || filename.length>225 ||
    filename.split('/').some(part=>part==='.'||part==='..'||!part) ||
    !/\.(?:mp4|m4v|webm)$/i.test(filename) || BAD_FILE.test(filename))return null;
  return {identifier,filename};
}
function licenseAllowed(value){
  if(typeof value!=='string')return false;
  try{const x=new URL(value);return LICENSE.test(x.origin+x.pathname);}
  catch{return false;}
}
export function selectArchiveVerifiedFile(request,parsed,doc){
  if(request?.media!=='movie' || !parsed || !doc?.metadata ||
     !Array.isArray(doc.files))return null;
  const title=norm(request.originalTitle||request.title);
  const archiveTitle=norm(doc.metadata.title);
  const year=Number(request.year);
  if(!title || !archiveTitle || title.length<3)return null;
  const exact=archiveTitle===title;
  const yearSuffix=Number.isInteger(year)&&year>=1888&&year<=2100 &&
    archiveTitle===title+' '+year;
  if(!exact&&!yearSuffix)return null;
  if(doc.metadata.year!==undefined && doc.metadata.year!==null){
    const archiveYear=Number(String(doc.metadata.year).slice(0,4));
    if(Number.isInteger(year) && year>0 &&
      (!Number.isInteger(archiveYear)||archiveYear!==year))return null;
  }
  if(!licenseAllowed(doc.metadata.licenseurl||doc.metadata.license_url))return null;
  const file=doc.files.find(f=>f?.name===parsed.filename);
  if(!file)return null;
  const height=Number(file.height),size=Number(file.size);
  if(!Number.isInteger(height)||height<720||height>4320||
    !Number.isFinite(size)||size<400000)return null;
  return {quality:height,licenseUrl:doc.metadata.licenseurl||doc.metadata.license_url};
}
export async function validateExternalArchiveStream(url,meta,{fetchImpl=fetch}={}){
  const parsed=parseArchiveFileUrl(url);
  if(!parsed || meta?.media!=='movie')return null;
  try {
    const response=await fetchImpl('https://archive.org/metadata/'+encodeURIComponent(parsed.identifier),{
      headers:{Accept:'application/json'},redirect:'error',
      signal:AbortSignal.timeout(3500)
    });
    if(!response.ok)return null;
    if(Number(response.headers.get('content-length')||0)>900000)return null;
    const txt=await response.text();
    if(txt.length>900000)return null;
    return selectArchiveVerifiedFile(meta,parsed,JSON.parse(txt));
  }catch{return null;}
}
