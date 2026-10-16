import test from 'node:test';
import assert from 'node:assert/strict';
import {fromCommons,selectCommonsMovies} from '../commons-provider.mjs';
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
