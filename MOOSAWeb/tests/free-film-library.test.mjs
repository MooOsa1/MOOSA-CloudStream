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
test('Free shelf contains 16 independently pre-reviewed complete movie IDs, newest 2026 first',()=>{
  const ids=curatedFilmIds();
  assert.equal(ids.length,16);
  assert.equal(new Set(ids).size,16,'Never duplicate movie cards');
  assert.deepEqual(ids.slice(0,4),[
    'singularity-2026','wing-it','charge','sprite-fright'
  ]);
  assert.ok(ids.includes('sita-sings-the-blues'));
  assert.deepEqual(ids.slice(-5),[
    'tears-of-steel','sintel','sita-sings-the-blues','big-buck-bunny','elephants-dream'
  ]);
});
test('Unknown or unverified titles do not become playable cards',async()=>{
  assert.equal(await getCuratedFilm('unknown'),null);
  const films=await listCuratedFilms({fetchImpl:fakeCommons({deny:'Sintel'})});
  assert.deepEqual(films.map(x=>x.id),[
    'tears-of-steel','big-buck-bunny','elephants-dream'
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

test('Direct movie lookup lists newer complete open films by immutable IDs, not arbitrary catalog matches',async()=>{
  const info=await getCuratedFilm('singularity-2026',{fetchImpl:fakeCommons({deny:'Singularity'})});
  assert.equal(info.id,'singularity-2026');
  assert.equal(info.year,2026);
  assert.equal(info.available,false);
  assert.equal(info.sources.length,0);
  assert.equal(await getCuratedFilm('singularity-2025',{fetchImpl:fakeCommons()}),null);
});

test('Sita film is a distinct curated feature and refuses unrelated video metadata',async()=>{
  const reviewed=await getCuratedFilm('sita-sings-the-blues',{
    fetchImpl:async raw=>{
      const u=new URL(raw);
      assert.equal(u.hostname,'commons.wikimedia.org');
      assert.equal(u.searchParams.get('generator'),null,
        'Reviewed film must use exact imageinfo, not fuzzy search');
      assert.equal(u.searchParams.get('titles'),'File:Sita Sings the Blues.webm');
      return new Response(JSON.stringify({query:{pages:[{
        title:'File:Sita Sings the Blues.webm',
        imageinfo:[{
          url:'https://upload.wikimedia.org/wikipedia/commons/8/86/Sita_Sings_the_Blues.webm',
          descriptionurl:'https://commons.wikimedia.org/wiki/File:Sita_Sings_the_Blues.webm',
          height:720,width:1280,size:1_180_000_000,mime:'video/webm',
          extmetadata:{
            LicenseUrl:{value:'https://creativecommons.org/publicdomain/zero/1.0/'},
            LicenseShortName:{value:'CC0 1.0'},
            Artist:{value:'Nina Paley'}
          }
        }]
      }]}}),{status:200,headers:{'content-type':'application/json'}});
    }
  });
  assert.equal(reviewed.id,'sita-sings-the-blues');
  assert.equal(reviewed.available,true);
  assert.equal(reviewed.sources.length,1);
  assert.equal(reviewed.sources[0].quality,720);
  assert.equal(reviewed.sources[0].licenseUrl,
    'https://creativecommons.org/publicdomain/zero/1.0/');
  assert.equal(reviewed.sources[0].attribution,'Nina Paley');
});
