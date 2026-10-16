import test from 'node:test';
import assert from 'node:assert/strict';
import {curatedFilmIds,getCuratedFilm,listCuratedFilms} from '../free-film-library.mjs';
import {createServer} from '../server.mjs';

const validNames={
  'Big Buck Bunny':'Big Buck Bunny alt',
  'Sintel':'Sintel movie 4K',
  'Tears of Steel':'Tears of Steel 1080p',
  'Elephants Dream':'Elephants Dream'
};
const quality={ 'Big Buck Bunny':720, 'Sintel':1744,
  'Tears of Steel':800, 'Elephants Dream':1080 };
function fakeCommons({deny}={}){
  return async url=>{
    const u=new URL(url);
    if(u.hostname!=='commons.wikimedia.org')throw Error('Unexpected third-party dependency');
    const token=[...Object.keys(validNames)].find(name=>
      (u.searchParams.get('gsrsearch')||u.searchParams.get('titles')||'').includes(name));
    const result=token&&token!==deny?{
      title:'File:'+validNames[token]+'.webm',
      imageinfo:[{
        url:'https://upload.wikimedia.org/wikipedia/commons/0/01/'+encodeURIComponent(validNames[token])+'.webm',
        mime:'video/webm',size:96_000_000,height:quality[token],
        descriptionurl:'https://commons.wikimedia.org/wiki/File:'+validNames[token].replaceAll(' ','_')+'.webm',
        extmetadata:{
          LicenseUrl:{value:'https://creativecommons.org/licenses/by/3.0/'},
          LicenseShortName:{value:'CC BY 3.0'},
          Artist:{value:'Blender Foundation'}
        }
      }]
    }:null;
    return new Response(JSON.stringify({query:{pages:result?[result]:[]}}),{
      status:200,headers:{'Content-Type':'application/json'}
    });
  };
}
test('Free shelf contains only four pre-reviewed title IDs',()=>{
  assert.deepEqual(curatedFilmIds(),[
    'big-buck-bunny','sintel','tears-of-steel','elephants-dream'
  ]);
});
test('Unknown or unverified titles do not become playable cards',async()=>{
  assert.equal(await getCuratedFilm('unknown'),null);
  const films=await listCuratedFilms({fetchImpl:fakeCommons({deny:'Sintel'})});
  assert.deepEqual(films.map(x=>x.id),[
    'big-buck-bunny','tears-of-steel','elephants-dream'
  ]);
  assert.ok(films.every(x=>x.available&&x.license.startsWith('https://creativecommons.org/')));
  assert.ok(films.every(x=>!('sources' in x)),
    'Shelf must expose preview metadata only, not unverified cross-title streams');
});
test('Film details contain original rights-verified matching video sources',async()=>{
  const f=await getCuratedFilm('sintel',{fetchImpl:fakeCommons()});
  assert.equal(f.title,'Sintel');
  assert.equal(f.sources.length,1);
  assert.equal(f.sources[0].quality,1744);
  assert.match(f.sources[0].url,/upload\.wikimedia\.org/);
  assert.equal(f.sources[0].licenseUrl,'https://creativecommons.org/licenses/by/3.0/');
});
test('Real HTTP route rejects arbitrary title IDs without attempting a network lookup',async()=>{
  const app=createServer();
  await new Promise(resolve=>app.listen(0,'127.0.0.1',resolve));
  try{
    const root='http://127.0.0.1:'+app.address().port;
    const r=await fetch(root+'/api/free-film?id=nonexistent-film');
    assert.equal(r.status,404);
    const injection=await fetch(root+'/api/free-film?id=https:%2F%2Fexample.com');
    assert.equal(injection.status,400);
  }finally{
    await new Promise(resolve=>app.close(resolve));
  }
});
