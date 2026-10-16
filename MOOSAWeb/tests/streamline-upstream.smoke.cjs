/**
 * Compatibility smoke for the ORIGINAL Streamline Nuvio bundle, read from
 * its pinned Git commit inside an isolated GitHub Actions runner.
 *
 * Everything is mocked; no movie host, decrypt endpoint or TMDB request
 * leaves the machine. We do not run upstream code on MOOSA Web/Render.
 *
 * This is only an interface proof, not actual playable source discovery.
 */
const assert=require('node:assert/strict');
const path=require('node:path');
const upstreamRoot=path.resolve(process.cwd(),'third-party/streamline');
const provider=require(path.join(upstreamRoot,'providers/vidlink.js'));

const visited=[];
const SAMPLE_URL='https://licensed.example.org/creative-commons-test-720.m3u8';
const FAKE_MOVIE_ID=11223344;
const FAKE_IMDB='tt999888777';
const realSetTimeout=setTimeout;
global.setTimeout=(handler,delay,...args)=>{
  const t=realSetTimeout(handler,delay,...args);
  // The upstream Nuvio timeout helper leaves an orphan timer after a
  // successful response. Avoid keeping this disposable CI worker alive.
  t.unref?.();
  return t;
};
const DATA={
  title:'Licensed Test Film',
  original_title:'Licensed Test Film',
  release_date:'2022-08-01',
  external_ids:{imdb_id:FAKE_IMDB},
  production_countries:[]
};
global.fetch=async function(url,options={}){
  const x=new URL(String(url));
  visited.push(x.hostname);
  if(x.hostname==='api.themoviedb.org'){
    assert.ok(['/3/movie/'+FAKE_MOVIE_ID,'/3/tv/'+FAKE_MOVIE_ID].includes(x.pathname));
    const payload=x.pathname.includes('/tv/')?{
      name:'Licensed Test Series',
      original_name:'Licensed Test Series',
      first_air_date:'2022-08-01',
      external_ids:{imdb_id:FAKE_IMDB},
      production_countries:[]
    }:DATA;
    return new Response(JSON.stringify(payload),{status:200,headers:{'Content-Type':'application/json'}});
  }
  if(x.hostname==='enc-dec.app'){
    assert.match(x.pathname,/\/api\/enc-vidlink/);
    return new Response(JSON.stringify({result:'fake-encrypted-id'}),{status:200});
  }
  if(x.hostname==='vidlink.pro'){
    assert.ok(
      ['/api/b/movie/fake-encrypted-id',
       '/api/b/tv/fake-encrypted-id/2/3'].includes(x.pathname),
      'Movie and season/episode paths must be preserved'
    );
    return new Response(JSON.stringify({
      stream:{qualities:{'720':{
        url:SAMPLE_URL,type:'m3u8',headers:{}
      }},captions:[{
        url:'https://captions.example.org/test-en.vtt',language:'en'
      }]}
    }),{status:200});
  }
  throw new Error('Unexpected network host: '+x.hostname);
};
async function main(){
  assert.equal(typeof provider.getStreams,'function',
    'Nuvio getStreams() must exist in original JS provider bundle');
  const result=await provider.getStreams(FAKE_MOVIE_ID,'movie',null,null);
  assert.ok(Array.isArray(result));
  assert.ok(result.some(x=>x.url===SAMPLE_URL),
    'Original Streamline Vidlink parser should produce simulated licensed media source');
  assert.ok(visited.includes('vidlink.pro'));
  assert.ok(visited.includes('api.themoviedb.org'));
  const series=await provider.getStreams(FAKE_MOVIE_ID,'tv',2,3);
  assert.ok(Array.isArray(series) && series.some(x=>x.url===SAMPLE_URL),
    'Original Streamline JS provider must pass season 2 episode 3 through to the TV endpoint');
  console.log('STREAMLINE_ORIGINAL_NODE_COMPAT_PASS',JSON.stringify({
    movieSources:result.length,tvSources:series.length,
    quality:result.find(x=>x.url===SAMPLE_URL)?.quality,
    mockedHosts:[...new Set(visited)].sort(),
    note:'Mocked endpoints. No actual media hosts contacted, and no production provider enabled.'
  }));
}
main().catch(e=>{console.error('STREAMLINE_ORIGINAL_NODE_COMPAT_FAIL',e.stack||e.message);process.exitCode=1;});
