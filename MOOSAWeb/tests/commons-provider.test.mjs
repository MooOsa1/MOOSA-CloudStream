import test from 'node:test';
import assert from 'node:assert/strict';
import {fromCommons,selectCommonsMovies,selectElephantsSafariDerivative} from '../commons-provider.mjs';
import {resolveWebSources} from '../providers.mjs';

const movie={media:'movie',id:11787,title:'Big Buck Bunny',
  originalTitle:'Big Buck Bunny',imdbId:'tt1254207',year:2008};
const rights={LicenseShortName:{value:'CC BY 3.0'},
  LicenseUrl:{value:'https://creativecommons.org/licenses/by/3.0/'},
  Artist:{value:'<span>Blender Foundation</span>'}};
const info=(url='https://upload.wikimedia.org/wikipedia/commons/a/ab/Big_Buck_Bunny_alt.webm',
  extmetadata=rights)=>({
    url,descriptionurl:'https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_alt.webm',
    size:89_000_000,height:720,width:1280,mime:'video/webm',extmetadata
  });
function data(pages){return {query:{pages}};}

test('Commons accepts exactly licensed full-title video with 720p imageinfo',()=>{
  const list=selectCommonsMovies(movie,data([
    {title:'File:Big Buck Bunny alt.webm',imageinfo:[info()]}
  ]));
  assert.equal(list.length,1);
  assert.equal(list[0].quality,720);
  assert.equal(list[0].attribution,'Blender Foundation');
  assert.equal(list[0].licenseUrl,'https://creativecommons.org/licenses/by/3.0/');
  assert.equal(list[0].subtitles.length,0);
});

test('Commons rejects unrelated titles, excerpts, missing rights or suspect URLs',()=>{
  const pages=[
    {title:'File:Big Buck Bunny keyframe strobing example.webm',imageinfo:[info()]},
    {title:'File:Big Buck Bunny trailer.webm',imageinfo:[info()]},
    {title:'File:Big Buck Bunny alt.webm',
      imageinfo:[info('https://evil.example/movie.webm')]},
    {title:'File:Big Buck Bunny alt.webm',
      imageinfo:[info(undefined,{LicenseShortName:{value:'CC BY 3.0'}})]},
    {title:'File:Big Buck Bunny alt.webm',
      imageinfo:[info(undefined,{LicenseUrl:{value:'https://creativecommons.org/licenses/by-nc/4.0/'}})]},
    {title:'File:Bad Buck Bunny.webm',imageinfo:[info()]},
    {title:'File:Big Buck Bunny alt.webm',imageinfo:[{...info(),height:480}]},
    {title:'File:Big Buck Bunny alt.webm',imageinfo:[{...info(),size:1000}]}
  ];
  assert.deepEqual(selectCommonsMovies(movie,data(pages)),[]);
});

test('Commons never attaches unrelated TV episodes using title-only matching',()=>{
  const res=selectCommonsMovies({...movie,media:'tv',season:1,episode:2},
    data([{title:'File:Big Buck Bunny alt.webm',imageinfo:[info()]}]));
  assert.deepEqual(res,[]);
});

test('Resolver finds licensed Commons video without Android runtime or any paid add-on',async()=>{
  const network=[];
  const mock=async (raw,opts)=>{
    const u=new URL(raw);network.push(u);
    if(u.hostname==='commons.wikimedia.org'){
      assert.equal(u.searchParams.get('generator'),'search');
      assert.equal(u.searchParams.get('gsrnamespace'),'6');
      assert.ok(opts.headers['User-Agent'].startsWith('MOOSAWeb/'));
      return new Response(JSON.stringify(data([
        {title:'File:Big Buck Bunny alt.webm',imageinfo:[info()]}
      ])),{status:200,headers:{'Content-Type':'application/json'}});
    }
    return new Response(JSON.stringify({response:{docs:[]}}),
      {status:200,headers:{'Content-Type':'application/json'}});
  };
  const streams=await resolveWebSources(movie,{fetchImpl:mock,addons:[],engine:null});
  assert.equal(streams.length,1);
  assert.equal(streams[0].quality,720);
  assert.equal(streams[0].licenseUrl,'https://creativecommons.org/licenses/by/3.0/');
  assert.ok(network.some(u=>u.hostname==='commons.wikimedia.org'));
});

test('Commons gracefully returns [] on upstream outages and rejects title injection',async()=>{
  const unavailable=await fromCommons(movie,{fetchImpl:async()=>{throw Error('Wikimedia not reachable');}});
  assert.deepEqual(unavailable,[]);
  let hit=false;
  const invalid=await fromCommons({...movie,originalTitle:'x" | format=xml'},{
    fetchImpl:async()=>{hit=true;throw Error('should not fetch');}
  });
  assert.deepEqual(invalid,[]);assert.equal(hit,false);
});

test('Only exact separately licensed Elephants Dream original can expose real Commons VP9 derivative',()=>{
  const source={
    url:'https://upload.wikimedia.org/wikipedia/commons/f/f1/Elephants_Dream.webm',
    sourcePage:'https://commons.wikimedia.org/wiki/File:Elephants_Dream_(2006).webm',
    quality:1080,sizeBytes:801000000,licenseUrl:'https://creativecommons.org/licenses/by/2.5/',
    attribution:'Orange Open Movie Team',preferred:true
  };
  const data={query:{pages:[{
    title:'File:Elephants Dream (2006).webm',
    videoinfo:[{
      url:source.url,
      derivatives:[
        {src:'https://upload.wikimedia.org/wikipedia/commons/transcoded/f/f1/Elephants_Dream.webm/Elephants_Dream.webm.1080p.vp9.webm',
          type:'video/webm; codecs="vp9, opus"',height:1080,transcodekey:'1080p.vp9.webm'},
        {src:'https://upload.wikimedia.org/wikipedia/commons/transcoded/f/f1/Elephants_Dream.webm/Elephants_Dream.webm.480p.vp9.webm',
          type:'video/webm',height:480,transcodekey:'480p.vp9.webm'},
        {src:'https://evil.example/a.webm',
          type:'video/webm',height:1080,transcodekey:'1080p.vp9.webm'}
      ]
    }]
  }]}};
  const results=selectElephantsSafariDerivative(source,data);
  assert.equal(results.length,1);
  assert.equal(results[0].quality,1080);
  assert.equal(results[0].codecOptimized,true);
  assert.equal(results[0].licenseUrl,source.licenseUrl);
  assert.equal(results[0].attribution,source.attribution);
  assert.equal(results[0].sourcePage,source.sourcePage);
  assert.equal(results[0].sizeBytes,null,'Never claim transcode size is original file size');
  assert.deepEqual(selectElephantsSafariDerivative({...source,url:'https://evil.example/file.webm'},data),[]);
  assert.deepEqual(selectElephantsSafariDerivative({...source,sourcePage:'https://evil.example/media'},data),[]);
});
