import test from 'node:test';
import assert from 'node:assert/strict';
import {safeSubtitleFileUrl,isOfficialStremioSubtitleFile} from '../public/subtitle-file-url.js';
import {
  VERIFIED_OPENSUBTITLES_MANIFEST,officialSubtitleAddons,
  collectStremioAddonSubtitles
} from '../web-stremio-subtitles.mjs';

const arabic='https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952533551';
const english='https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952537611';
const manifest={id:'org.stremio.opensubtitlesv3',name:'OpenSubtitles v3',
  types:['movie','series'],resources:['subtitles'],idPrefixes:['tt']};
const titles={subtitles:[
  {lang:'eng',id:'3871796',url:english},
  {lang:'ara',id:'3868082',url:arabic},
  {lang:'spa',id:'123',url:'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952323231'},
  {lang:'ara',id:'evil',url:'https://subs5.strem.io/en/download/private/anything'},
  {lang:'ara',id:'fake',url:'https://subs5.strem.io.evil.example/en/download/subencoding-stremio-utf8/src-api/file/1952533551'}
]};
test('Official provider is pinned for subtitle search, independent of streams',()=>{
  assert.equal(VERIFIED_OPENSUBTITLES_MANIFEST,'https://opensubtitles-v3.strem.io/manifest.json');
  assert.deepEqual(officialSubtitleAddons(),[VERIFIED_OPENSUBTITLES_MANIFEST]);
  assert.deepEqual(officialSubtitleAddons([
    VERIFIED_OPENSUBTITLES_MANIFEST,'https://custom.example/manifest.json'
  ]),[VERIFIED_OPENSUBTITLES_MANIFEST,'https://custom.example/manifest.json']);
});
test('Official real extensionless subtitle file is allowed only under canonical CDN path',()=>{
  assert.equal(safeSubtitleFileUrl(arabic),arabic);
  assert.equal(safeSubtitleFileUrl(english),english);
  assert.equal(isOfficialStremioSubtitleFile(arabic),true);
  assert.equal(safeSubtitleFileUrl('https://example.org/subtitles/file.srt'),
    'https://example.org/subtitles/file.srt');
  for(const url of [
    'http://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952533551',
    'https://subs5.strem.io.evil.example/en/download/subencoding-stremio-utf8/src-api/file/1952533551',
    'https://subs5.strem.io/en/download/private/file/1952533551',
    'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952533551?provider=evil',
    'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/bad-id',
    'https://subs5.strem.io/attacker/subtitle/no-extension',
    'https://127.0.0.1/secret.srt',
    'https://localhost/private.srt',
    'https://subs5.strem.io@evil.example/evil.srt',
    'javascript:alert(1)',
    'https://other.example/file.zip'
  ])assert.equal(safeSubtitleFileUrl(url),null,url);
});
test('Mock official manifest yields verified bilingual tracks from actual OpenSubtitles URL structure',async()=>{
  const called=[];
  const fakeFetch=async input=>{
    const url=new URL(input);called.push(url.pathname);
    let data;
    if(url.pathname==='/manifest.json')data=manifest;
    else if(url.pathname==='/subtitles/movie/tt1727587.json')data=titles;
    else throw Error('Unexpected URL '+url);
    return new Response(JSON.stringify(data),{
      status:200,headers:{'Content-Type':'application/json'}
    });
  };
  const out=await collectStremioAddonSubtitles({
    media:'movie',imdbId:'tt1727587'
  },{addons:officialSubtitleAddons(),fetchImpl:fakeFetch});
  assert.deepEqual(out.map(s=>s.language),['ar','en']);
  assert.deepEqual(out.map(s=>s.url),[arabic,english]);
  assert.ok(out.every(s=>s.source==='OpenSubtitles v3'));
  assert.deepEqual(called,['/manifest.json','/subtitles/movie/tt1727587.json']);
});

test('Actual Stremio order: Arabic tracks beyond 35 entries are still discovered',async()=>{
  const englishRows=Array.from({length:45},(_,i)=>({
    id:String(100+i),lang:i<4?'eng':'fre',
    url:'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/'+(1953000000+i)
  }));
  const realArabic=[
    {id:'3868082',lang:'ara',
      url:'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952533551'},
    {id:'3912402',lang:'ara',
      url:'https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952580438'}
  ];
  const response={subtitles:[...englishRows,...realArabic]};
  const received=await collectStremioAddonSubtitles({media:'movie',imdbId:'tt1727587'},{
    addons:officialSubtitleAddons(),
    fetchImpl:async url=>new Response(JSON.stringify(
      new URL(url).pathname.endsWith('/manifest.json')?manifest:response
    ),{headers:{'Content-Type':'application/json'}})
  });
  assert.equal(received.filter(x=>x.language==='ar').length,2);
  assert.equal(received.filter(x=>x.language==='en').length,4);
  assert.equal(received[0].language,'ar');
  assert.equal(received[1].language,'ar');
});
