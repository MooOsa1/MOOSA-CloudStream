/**
 * Fixed internal identities for four pre-reviewed Commons full films.
 * Never accept a title, URL or rights assertion from a request as approval.
 * These numeric keys belong to the local JS interface, not TMDB.
 */
const films=Object.freeze([
  Object.freeze({id:10378,slug:'big-buck-bunny',title:'Big Buck Bunny',year:2008}),
  Object.freeze({id:10001,slug:'sintel',title:'Sintel',year:2010}),
  Object.freeze({id:10002,slug:'tears-of-steel',title:'Tears of Steel',year:2012}),
  Object.freeze({id:10003,slug:'elephants-dream',title:'Elephants Dream',year:2006})
]);
function byId(id){return films.find(f=>f.id===id)||null;}
function bySlug(slug){return films.find(f=>f.slug===slug)||null;}
function approvedMedia(meta){
  const f=byId(meta?.id);
  return meta?.media==='movie' && f &&
    meta.title===f.title && meta.originalTitle===f.title &&
    (meta.year===undefined||meta.year===f.year)?f:null;
}
module.exports={films,byId,bySlug,approvedMedia};
