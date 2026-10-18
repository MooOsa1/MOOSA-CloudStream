/**
 * Standalone public Stremio iPhone compatibility check on the PRIVATE lab.
 * No user-input streams, no copyrighted studio titles, no user credentials.
 * This does not claim that Stremio plays CloudStream providers.
 */
import {spawnSync} from 'node:child_process';
import {mkdtempSync,readFileSync,rmSync} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createRequire} from 'node:module';
import {getCuratedFilm} from './free-film-library.mjs';

const require=createRequire(import.meta.url);
const ROOT='/moosa-stremio';
const TEST_ID='moosa-ios-player-test';
const WING_ID='moosa-wing-it-2023';
const LICENSED_HOST='upload.wikimedia.org';
const MANIFEST={
  id:'org.moosa.ios-test',version:'1.0.0',
  name:'MOOSA iPhone Playback Test',
  description:'Checks direct MP4 video and audio on iPhone Safari, plus a 2023 Creative Commons Blender short. This is not a commercial movie streaming addon.',
  types:['movie'],
  resources:['catalog','meta','stream'],
  idPrefixes:['moosa-'],
  catalogs:[{type:'movie',id:'moosa-iphone',
    name:'MOOSA — iPhone compatibility check',
    extra:[{name:'search',isRequired:false}]}],
  behaviorHints:{adult:false,p2p:false,configurable:false}
};
const ITEMS=[
  {id:TEST_ID,type:'movie',name:'MOOSA iPhone — video and audio check',
    releaseInfo:'2026',description:'A short generated video with moving images and audio. Tests Stremio playback on iPhone without any additional movie addon.',
    poster:'https://raw.githubusercontent.com/Stremio/stremio-addon-sdk/master/docs/logo.png'},
  {id:WING_ID,type:'movie',name:'Wing It! (2023) — Creative Commons short',
    releaseInfo:'2023',description:'Open-licensed Blender Studio short film, delivered from Wikimedia Commons (WebM; iOS codec support may vary).'}
];
const ROUTES=new Set([
  ROOT+'/manifest.json',ROOT+'/catalog/movie/moosa-iphone.json',
  ...ITEMS.flatMap(x=>[
    ROOT+'/meta/movie/'+x.id+'.json',
    ROOT+'/stream/movie/'+x.id+'.json'
  ]),
  ROOT+'/sample.mp4'
]);
let cache;
let cacheFailure=false;
function send(res,status,payload){
  const text=JSON.stringify(payload);
  res.writeHead(status,{'Content-Type':'application/json; charset=utf-8',
    'Access-Control-Allow-Origin':'*','Cache-Control':'public,max-age=30',
    'X-Content-Type-Options':'nosniff'});
  res.end(text);
}
function buildSample(){
  if(cache||cacheFailure)return cache;
  const dir=mkdtempSync(path.join(os.tmpdir(),'moosa-ios-sample-'));
  const output=path.join(dir,'clip.mp4');
  try{
    const ffmpeg=require('ffmpeg-static');
    const r=spawnSync(ffmpeg,[
      '-nostdin','-hide_banner','-loglevel','error','-y',
      '-f','lavfi','-i','testsrc2=size=640x360:rate=24:duration=7',
      '-f','lavfi','-i','sine=frequency=660:sample_rate=44100:duration=7',
      '-c:v','libx264','-preset','ultrafast','-tune','zerolatency',
      '-threads','2','-pix_fmt','yuv420p','-crf','28',
      '-c:a','aac','-b:a','96k','-shortest',
      '-movflags','+faststart',output
    ],{timeout:14000,stdio:'ignore'});
    if(r.status!==0){cacheFailure=true;return null;}
    cache=readFileSync(output);
    if(cache.length<10000||cache.length>6*1024*1024){
      cache=null;cacheFailure=true;
    }
    return cache;
  }catch{cacheFailure=true;return null;}
  finally{rmSync(dir,{recursive:true,force:true});}
}
function approvedWingSource(source){
  try{
    const u=new URL(source.url),page=new URL(source.sourcePage),
      lic=new URL(source.licenseUrl);
    if(u.protocol!=='https:'||u.hostname!==LICENSED_HOST||
       !/\.webm$/i.test(u.pathname)||page.hostname!=='commons.wikimedia.org'||
       !/^\/wiki\/File:/.test(page.pathname)||
       lic.hostname!=='creativecommons.org'||Number(source.quality)<720)
      return null;
    return {name:'MOOSA — Commons 2023',title:String(source.quality)+'p • open licensed',
      url:u.href,description:'2023 Blender Studio Creative Commons source'};
  }catch{return null;}
}
const SEARCH_ROUTE=/^\/moosa-stremio\/catalog\/movie\/moosa-iphone\/search=([^/?#]{1,140})\.json$/;
export function isPublicStremioTrialPath(raw){
  return typeof raw==='string'&&(ROUTES.has(raw)||SEARCH_ROUTE.test(raw));
}
export async function handleStremioTrial(req,res,requestUrl){
  if(!isPublicStremioTrialPath(requestUrl.pathname))
    return send(res,404,{error:'Not found'});
  // Embedded iOS browsers may append tracking/query values to otherwise
  // direct media links. These fixed, read-only endpoints never consume them;
  // route by exact pathname only, with a bounded query length.
  if(requestUrl.search.length>1024||requestUrl.hash)
    return send(res,400,{error:'Invalid request'});
  if(!['GET','HEAD'].includes(req.method))
    return send(res,405,{error:'Method not allowed'});
  const sendJSON=(status,data)=>{
    if(req.method==='HEAD'){
      res.writeHead(status,{'Content-Type':'application/json',
        'Access-Control-Allow-Origin':'*'});return res.end();
    }
    return send(res,status,data);
  };
  if(requestUrl.pathname===ROOT+'/manifest.json')return sendJSON(200,MANIFEST);
  if(requestUrl.pathname===ROOT+'/catalog/movie/moosa-iphone.json')
    return sendJSON(200,{metas:ITEMS});
  const searchRoute=requestUrl.pathname.match(SEARCH_ROUTE);
  if(searchRoute){
    let term;
    try{term=decodeURIComponent(searchRoute[1]).normalize('NFKC')
      .trim().toLowerCase();}catch{return sendJSON(400,{error:'Invalid search term'});}
    if(!term||term.length>80)return sendJSON(200,{metas:[]});
    // Exact operator-owned demo catalog only. No external provider queries.
    const matches=term.includes('moosa')?ITEMS:ITEMS.filter(film=>
      (film.name+' '+film.description).toLowerCase().includes(term) ||
      (term.includes('wing')&&film.id===WING_ID));
    return sendJSON(200,{metas:matches});
  }
  const suffix=requestUrl.pathname.split('/').at(-1);
  if(requestUrl.pathname.startsWith(ROOT+'/meta/movie/')){
    const item=ITEMS.find(x=>suffix===x.id+'.json');
    return sendJSON(item?200:404,item?{meta:item}:{error:'Unknown film'});
  }
  if(requestUrl.pathname.startsWith(ROOT+'/stream/movie/')){
    if(suffix===TEST_ID+'.json')
      return sendJSON(200,{streams:[{
        name:'MOOSA iPhone — HTTPS MP4 with AAC sound',
        title:'H264 + AAC • moving video + audio • direct HTTPS',
        url:'https://moosa-two-film-lab.onrender.com'+ROOT+'/sample.mp4'
      }]});
    if(suffix===WING_ID+'.json'){
      const film=await getCuratedFilm('wing-it');
      const sources=film?.available?film.sources.map(approvedWingSource).filter(Boolean):[];
      return sendJSON(200,{streams:sources.slice(0,2)});
    }
    return sendJSON(404,{error:'Unknown film'});
  }
  if(requestUrl.pathname===ROOT+'/sample.mp4'){
    const bytes=buildSample();
    if(!bytes)return sendJSON(503,{error:'MP4 encoder unavailable'});
    const raw=req.headers.range;
    let first=0,last=bytes.length-1,status=200;
    if(raw){
      const m=String(raw).match(/^bytes=(\d+)-(\d*)$/);
      if(!m||Number(m[1])>=bytes.length||m[2]&&Number(m[2])<Number(m[1])){
        res.writeHead(416,{'Content-Range':'bytes */'+bytes.length});
        return res.end();
      }
      first=Number(m[1]);last=m[2]?Math.min(last,Number(m[2])):last;
      status=206;
    }
    res.writeHead(status,{'Content-Type':'video/mp4',
      'Access-Control-Allow-Origin':'*','Accept-Ranges':'bytes',
      'Content-Length':last-first+1,
      ...(status===206?{'Content-Range':'bytes '+first+'-'+last+'/'+bytes.length}:{}),
      'X-Content-Type-Options':'nosniff','Cache-Control':'public,max-age=300'});
    if(req.method==='HEAD')return res.end();
    return res.end(bytes.subarray(first,last+1));
  }
  return sendJSON(404,{error:'Not found'});
}
