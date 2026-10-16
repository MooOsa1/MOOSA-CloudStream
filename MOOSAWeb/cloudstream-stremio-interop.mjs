/**
 * Real HTTP Stremio stream protocol adapter supported by CineStream's
 * Settings.AddonType.HTTPS invokeStreamioStreamsGlobal on Android.
 *
 * This service DOES NOT execute CineStream or StreamPlay Android plugins.
 * It exposes independently verified, openly licensed direct sources using
 * the same /stream/movie/tt....json transport those extensions speak.
 * No scraping hosters, arbitrary URLs or client-provided code.
 */
import {getCuratedFilm} from './free-film-library.mjs';

const FIXED_IMDB_TO_FILM=new Map([
  ['tt1254207','big-buck-bunny'],
  ['tt1727587','sintel'],
  ['tt2285752','tears-of-steel'],
  ['tt0807840','elephants-dream']
]);
const COMMONS_FILE=/^https:\/\/commons\.wikimedia\.org\/wiki\/File:/i;
const CC_LICENSE=/^https:\/\/creativecommons\.org\/(?:licenses\/(?:by|by-sa)\/(?:2\.0|2\.5|3\.0|4\.0)|publicdomain\/(?:zero\/1\.0|mark\/1\.0))(?:\/|$)/i;

export function stremioProtocolManifest(){
  return {
    id:'org.moosa.web.licensed',version:'1.0.0',
    name:'MOOSA Web — Licensed Streams',
    description:'Rights-verified open-licensed movies only. Shared HTTPS Stremio protocol with CineStream. No original Android plugins are executed.',
    resources:[{name:'stream',types:['movie'],idPrefixes:['tt']}],
    types:['movie'],catalogs:[],idPrefixes:['tt'],
    behaviorHints:{adult:false,p2p:false,configurable:false}
  };
}
function approvedDirectMovieStream(source){
  if(!source || typeof source.url!=='string'||source.url.length>3000)return null;
  if(!COMMONS_FILE.test(source.sourcePage||'') ||
     !CC_LICENSE.test(source.licenseUrl||''))return null;
  let u;
  try{u=new URL(source.url);}catch{return null;}
  if(u.protocol!=='https:'||u.hostname!=='upload.wikimedia.org' ||
    u.username||u.password||! /\.(?:webm|mp4)$/i.test(u.pathname))return null;
  const q=Number(source.quality);
  if(!Number.isInteger(q)||q<720||q>4320)return null;
  const license=new URL(source.licenseUrl);
  const conciseLicense=license.pathname.replace(/^\/|\/$/g,'');
  const transcode=source.codecOptimized===true?' • Safari 4:2:0':'';
  return {
    name:'MOOSA Commons',
    title:q+'p'+transcode+' • '+conciseLicense,
    description:'Open-license source • '+String(source.attribution||'Wikimedia Commons').slice(0,100),
    url:u.href
  };
}
export function pinnedStremioMovies(){
  return [...FIXED_IMDB_TO_FILM.entries()].map(([imdbId,id])=>({imdbId,id}));
}
export async function resolveStremioProtocolMovie(imdbId,{getFilm=getCuratedFilm}={}){
  const slug=FIXED_IMDB_TO_FILM.get(imdbId);
  if(!slug)return {streams:[]};
  let film;
  try{film=await getFilm(slug);}catch{return {streams:[]};}
  if(film?.id!==slug || film.available!==true ||
     !Array.isArray(film.sources))return {streams:[]};
  const seen=new Set();
  const streams=film.sources.flatMap(s=>{
    const stream=approvedDirectMovieStream(s);
    if(!stream||seen.has(stream.url))return [];
    seen.add(stream.url);
    return [stream];
  });
  streams.sort((a,b)=>Number(b.title.includes('Safari 4:2:0'))-
    Number(a.title.includes('Safari 4:2:0')));
  return {streams:streams.slice(0,4)};
}
