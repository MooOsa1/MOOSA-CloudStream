import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer, tmdbRoute } from '../server.mjs';

function q(path) {return tmdbRoute(new URL(path,'http://localhost'));}
test('all front-page shelves map to actual supported TMDB endpoints',()=>{
  for(const [slug,kind] of [['trending_movies','/trending/movie/week'],['trending_series','/trending/tv/week'],['top_movies','/movie/top_rated'],['indian_trending','/discover/movie']]){
    const route=q('/api/catalog?section='+slug);
    assert.equal(route.endpoint,kind);
    assert.equal(route.params.page,'1');
  }
  assert.equal(q('/api/catalog?section=made_up'),null);
});
test('discover whitelists media, genre, country and sort and clamps page',()=>{
  const valid=q('/api/discover?media=tv&genre=80&sort=vote_average.desc&country=KR&page=999999');
  assert.equal(valid.endpoint,'/discover/tv');
  assert.equal(valid.params.with_genres,'80');
  assert.equal(valid.params.with_origin_country,'KR');
  assert.equal(valid.params['vote_count.gte'],'50');
  assert.equal(valid.params.page,'500');
  const no=q('/api/discover?media=tv&genre=28&sort=x&country=ZZ&page=-9');
  assert.equal(no.params.with_genres,undefined);assert.equal(no.params.with_origin_country,undefined);assert.equal(no.params.sort_by,'popularity.desc');assert.equal(no.params.page,'1');
  const date=q('/api/discover?media=movie&sort=date.desc');
  assert.equal(date.params.sort_by,'primary_release_date.desc');assert.ok(/^\d\d\d\d-\d\d-\d\d$/.test(date.params['primary_release_date.lte']));
});
test('search and details validate inputs before calling TMDB',()=>{
  assert.ok(q('/api/search?q=a').invalid);
  assert.equal(q('/api/search?q=Inside').params.query,'Inside');
  assert.ok(q('/api/details?media=movie&id=../config').invalid);
  assert.ok(q('/api/details?media=tv&id=0').invalid);
  assert.equal(q('/api/details?media=tv&id=123').endpoint,'/tv/123');
});

test('server serves static HTML, API metadata and empty authorized source list without TMDB key', async()=>{
  const server=createServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${server.address().port}`;
  try{
    const page=await fetch(base);assert.equal(page.status,200);assert.match(await page.text(),/MOOSA Web/);
    const cfg=await(await fetch(base+'/api/config')).json();assert.equal(cfg.sourcePolicy,'authorized_only');
    const src=await(await fetch(base+'/api/streams?media=movie&id=1')).json();assert.deepEqual(src.sources,[]);
    assert.equal((await fetch(base+'/api/streams?media=movie&id=not-a-number')).status,400);
    assert.equal((await fetch(base+'/..%2F..%2Fserver.mjs')).status,403);
    assert.equal((await fetch(base+'/private.txt')).status,404);
  }finally{await new Promise(resolve=>server.close(resolve));}
});

test('TV seasons resolve only real TMDB episode routes and reject invalid inputs',()=>{
  const e=q('/api/episodes?id=441&season=2');
  assert.equal(e.endpoint,'/tv/441/season/2');
  assert.equal(e.params.language,'ar-SA');
  assert.ok(q('/api/episodes?id=0&season=1').invalid);
  assert.ok(q('/api/episodes?id=441&season=-1').invalid);
  assert.ok(q('/api/episodes?id=441&season=101').invalid);
  assert.ok(q('/api/episodes?id=441').invalid);
});
test('episode stream URLs never fall back to another episode or generic show link', async()=>{
  const folder=await mkdtemp(join(tmpdir(),'moosa-episode-'));
  const file=join(folder,'streams.json');
  await writeFile(file,JSON.stringify({
    'tv:42':[{name:'Show-wide only',url:'https://example.org/overview.mp4'}],
    'tv:42:1:2':[{name:'Correct episode',url:'https://example.org/s01e02.m3u8'}]
  }));
  const previous=process.env.STREAMS_FILE;
  process.env.STREAMS_FILE=file;
  const {createServer:configuredServer}=await import('../server.mjs?episode-suite=1');
  if(previous===undefined)delete process.env.STREAMS_FILE;else process.env.STREAMS_FILE=previous;
  const server=configuredServer();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base='http://127.0.0.1:'+server.address().port;
  try {
    const correct=await(await fetch(base+'/api/streams?media=tv&id=42&season=1&episode=2')).json();
    assert.equal(correct.sources.length,1);
    assert.equal(correct.sources[0].name,'Correct episode');
    const wrong=await(await fetch(base+'/api/streams?media=tv&id=42&season=1&episode=3')).json();
    assert.equal(wrong.sources.length,0);
    const generic=await(await fetch(base+'/api/streams?media=tv&id=42')).json();
    assert.equal(generic.sources[0].name,'Show-wide only');
    assert.equal((await fetch(base+'/api/streams?media=tv&id=42&season=1')).status,400);
    assert.equal((await fetch(base+'/api/streams?media=movie&id=42&season=1&episode=2')).status,400);
  } finally {
    await new Promise(resolve=>server.close(resolve));
    await rm(folder,{recursive:true,force:true});
  }
});
