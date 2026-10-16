/**
 * Read-only external probe: live HTML endpoint -> locally pinned JS worker
 * -> Wikimedia Commons metadata -> direct CDN HEAD with CORS/MIME.
 * Never download a movie, execute a third-party provider or imply Safari decode.
 */
import assert from 'node:assert/strict';
import {createServer} from '../server.mjs';
import {getCuratedFilm} from '../free-film-library.mjs';
import {resolveBrowserReadyJsSources} from '../js-provider-validation.mjs';
import approved from '../js-approved-films.cjs';
const ids=['big-buck-bunny','sintel','tears-of-steel','elephants-dream'];
const titles=['Big Buck Bunny','Sintel','Tears of Steel','Elephants Dream'];
const server=createServer();
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
try{
  const base='http://127.0.0.1:'+server.address().port;
  const findings=[];
  // Sequential probes avoid hammering the public Commons API.
  for(let i=0;i<ids.length;i++){
    const t=Date.now();
    const res=await fetch(base+'/api/js-engine/demo?id='+ids[i],{
      signal:AbortSignal.timeout(17000)
    });
    assert.equal(res.status,200);
    const result=await res.json();
    assert.equal(result.filmId,ids[i]);
    assert.equal(result.sample,titles[i]);
    assert.equal(result.engine,'reviewed_js_worker');
    assert.equal(result.originalCloudStreamPlugins,false);
    assert.equal(result.count,result.sources.length);
    assert.ok(result.sources.every(s=>
      new URL(s.url).hostname==='upload.wikimedia.org'&&
      s.preflight?.verified===true));
    findings.push({
      id:ids[i],state:result.state,count:result.count,
      qualities:result.sources.map(s=>s.quality),
      elapsedMs:Date.now()-t
    });
  }
  console.log('MULTIFILM_LIVE_JS_RESULTS '+JSON.stringify(findings));
  const anyExtra=findings.slice(1).filter(x=>x.count>0);
  console.log('ADDITIONAL_LICENSED_TITLES_VERIFIED '+anyExtra.length+'/3');
  // Focused diagnostics: metadata discovery vs worker adapter vs browser HEAD.
  // No URLs or headers printed; this is a read-only source of rejection reasons.
  for(const slug of ids.slice(1)){
    const item=approved.bySlug(slug);
    const meta={media:'movie',id:item.id,title:item.title,
      originalTitle:item.title,year:item.year};
    const existing=await getCuratedFilm(slug);
    const checked=await resolveBrowserReadyJsSources(meta,{
      provider:'commons-authorized',licensedMedia:true,
      permittedHosts:['upload.wikimedia.org'],timeoutMs:3500
    });
    console.log('FILM_PIPELINE_DIAGNOSTICS '+JSON.stringify({
      slug,metadataSources:existing.sources.length,
      metadataQualities:existing.sources.map(s=>s.quality),
      candidateCounts:checked.counts,
      issues:checked.issues||{},workerState:checked.state
    }));
  }
  // Confirm the Commons API actually offers a lower-bandwidth 4:2:0
  // transcode of the troublesome Elephants Dream full film.
  const elephant=await getCuratedFilm('elephants-dream');
  console.log('ELEPHANTS_SAFARI_FALLBACK '+JSON.stringify({
    sources:elephant.sources.map(s=>({
      quality:s.quality,codecOptimized:s.codecOptimized===true,
      license:s.licenseUrl,
      hostname:new URL(s.url).hostname
    }))
  }));
  assert.ok(elephant.sources.some(s=>s.codecOptimized===true && s.quality>=720),
    'Commons should provide an official 4:2:0 transcode for Safari');
  for(const media of elephant.sources.filter(s=>!s.codecOptimized).slice(0,2)){
    const raw=decodeURIComponent(new URL(media.sourcePage).pathname.replace(/^\/wiki\//,''));
    const api=new URL('https://commons.wikimedia.org/w/api.php');
    for(const [k,v] of Object.entries({
      action:'query',format:'json',formatversion:'2',
      titles:raw,prop:'videoinfo',viprop:'url|size|extmetadata|derivatives'
    }))api.searchParams.set(k,v);
    const response=await fetch(api,{
      headers:{'User-Agent':'MOOSAWeb/0.1 (https://github.com/MooOsa1/MOOSA-CloudStream; read-only CC media variants)'},
      signal:AbortSignal.timeout(7000)
    });
    if(!response.ok)throw Error('Commons derivatives HTTP '+response.status);
    const data=await response.json();
    const v=data?.query?.pages?.[0]?.videoinfo?.[0];
    console.log('ELEPHANTS_DERIVATIVES '+JSON.stringify({
      sourceTitle:raw,sourceQuality:media.quality,
      originals:v?.url?1:0,
      candidates:(v?.derivatives||[]).map(d=>({
        type:d.type,width:d.width,height:d.height,transcodekey:d.transcodekey,
        host:d.src?new URL(d.src,'https://commons.wikimedia.org').hostname:null
      })).filter(d=>d.height>=360).slice(0,16)
    }));
  }
  const invalid=await fetch(base+'/api/js-engine/demo?id=sintel&url=https://evil.example/');
  assert.equal(invalid.status,400);
}finally{
  await new Promise(resolve=>server.close(resolve));
}
