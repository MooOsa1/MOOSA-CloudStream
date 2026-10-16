import test from 'node:test';
import assert from 'node:assert/strict';
import {resolveWebSources} from '../providers.mjs';
import {createServer} from '../server.mjs';

const fileUrl='https://upload.wikimedia.org/wikipedia/commons/a/ab/Sintel.webm';
const licensed={
  title:'File:Sintel.webm',
  imageinfo:[{
    url:fileUrl,height:1080,size:10000000,mime:'video/webm',
    descriptionurl:'https://commons.wikimedia.org/wiki/File:Sintel.webm',
    extmetadata:{LicenseUrl:{value:'https://creativecommons.org/licenses/by/3.0/'},
      LicenseShortName:{value:'CC BY 3.0'},Artist:{value:'Blender Foundation'}}
  }]
};
function fakeFetch(url){
  const u=new URL(url);
  if(u.hostname==='commons.wikimedia.org')return Promise.resolve(new Response(JSON.stringify({
    query:{pages:{one:licensed}}
  }),{status:200,headers:{'content-type':'application/json'}}));
  if(u.hostname==='archive.org')return Promise.resolve(new Response(JSON.stringify({
    response:{docs:[]}
  }),{status:200,headers:{'content-type':'application/json'}}));
  throw new Error('Unexpected host '+u.hostname);
}
function film(id){return {
  id,media:'movie',title:'Sintel',originalTitle:'Sintel',year:2010,
  imdbId:'tt1727587'
};}
function approved(){return {
  filmId:'sintel',state:'browser_preflight_passed',sources:[
    {url:fileUrl,quality:1080,preflight:{verified:true,mime:'video/webm'}},
    {url:'https://evil.invalid/Fake.webm',quality:2160,
      preflight:{verified:true,mime:'video/webm'}}
  ]
};}
test('ordinary movie /api/resolve backend invokes reviewed JS provider without device pairing',async()=>{
  let calls=0;
  const sources=await resolveWebSources(film(313101),{
    addons:[],fetchImpl:fakeFetch,engine:null,
    jsLoad:async(slug)=>{
      assert.equal(slug,'sintel');calls++;
      return approved();
    }
  });
  assert.equal(calls,1);
  assert.equal(sources.length,1);
  assert.equal(sources[0].runtime,'web_native_js');
  assert.equal(sources[0].preflight.verified,true);
  assert.equal(sources[0].url,fileUrl);
  assert.equal(sources[0].licenseUrl,'https://creativecommons.org/licenses/by/3.0/');
  assert.equal(sources[0].sourcePage,'https://commons.wikimedia.org/wiki/File:Sintel.webm');
});
test('wrong year and wrong original title cannot trigger JS source claims',async()=>{
  let calls=0;
  for(const [meta,id] of [
    [{...film(313102),year:2011},313102],
    [{...film(313103),originalTitle:'Other film'},313103]
  ]){
    const list=await resolveWebSources(meta,{
      addons:[],fetchImpl:fakeFetch,engine:null,
      jsLoad:async()=>{calls++;return approved();}
    });
    assert.ok(list.every(s=>!s.runtime));
  }
  assert.equal(calls,0);
});
test('a fake JS URL cannot become a browser-playable source',async()=>{
  const list=await resolveWebSources(film(313104),{
    addons:[],fetchImpl:fakeFetch,engine:null,
    jsLoad:async()=>({
      filmId:'sintel',state:'browser_preflight_passed',
      sources:[{url:'https://evil.invalid/Fake.webm',quality:1080,
        preflight:{verified:true,mime:'video/webm'}}]
    })
  });
  assert.equal(list.length,1);
  assert.equal(list[0].url,fileUrl);
  assert.equal(list[0].runtime,undefined);
});
test('standalone runtime status API is truthful and refuses arbitrary web provider input',async()=>{
  const app=createServer();
  await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));
  const base='http://127.0.0.1:'+app.address().port;
  try{
    const r=await fetch(base+'/api/web-native/status');
    assert.equal(r.status,200);
    const data=await r.json();
    assert.equal(data.engine,'node_js');
    assert.equal(data.pairingRequired,false);
    assert.equal(data.catalogIntegrated,true);
    assert.equal(data.cloudstreamCs3Supported,false);
    assert.equal(data.cineStreamAndStreamPlayOriginals,false);
    assert.deepEqual(data.activeReviewedProviders[0].approvedFilmIds,
      ['big-buck-bunny','sintel','tears-of-steel','elephants-dream']);
    assert.equal((await fetch(base+'/api/web-native/status?provider=https://malware.invalid')).status,400);
  }finally{await new Promise(resolve=>app.close(resolve));}
});

test('approved JS starts in parallel with Commons instead of after search',async()=>{
  let commonsResolved=false,workerBeforeCommons=false;
  const fetchSlow=async url=>{
    if(new URL(url).hostname==='commons.wikimedia.org'){
      await new Promise(resolve=>setTimeout(resolve,40));
      commonsResolved=true;
    }
    return fakeFetch(url);
  };
  const sources=await resolveWebSources(film(313105),{
    addons:[],fetchImpl:fetchSlow,engine:null,
    jsLoad:async()=>{
      workerBeforeCommons=!commonsResolved;
      return approved();
    }
  });
  assert.equal(workerBeforeCommons,true);
  assert.equal(sources[0].runtime,'web_native_js');
});
