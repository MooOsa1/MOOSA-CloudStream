import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseArchiveFileUrl,selectArchiveVerifiedFile,validateExternalArchiveStream
} from '../archive-stream-verifier.mjs';
const meta={media:'movie',title:'Big Buck Bunny',
  originalTitle:'Big Buck Bunny',year:2008};
const good='https://archive.org/download/bbb-openmovie/full_1080p.mp4';
const parsed=parseArchiveFileUrl(good);
const base={metadata:{
  title:'Big Buck Bunny',year:'2008',
  licenseurl:'https://creativecommons.org/licenses/by/3.0/'
},files:[{name:'full_1080p.mp4',height:'1080',size:'1900000',format:'h.264'}]};
test('Only exact public Archive.org download file URL is parseable',()=>{
  assert.equal(parsed.identifier,'bbb-openmovie');
  assert.equal(parsed.filename,'full_1080p.mp4');
  for(const url of [
    'http://archive.org/download/bbb-openmovie/full_1080p.mp4',
    'https://evil.example/download/bbb-openmovie/full_1080p.mp4',
    'https://archive.org/metadata/bbb-openmovie',
    'https://archive.org/download/../other/full_1080p.mp4',
    'https://archive.org/download/bbb-openmovie/trailer_1080p.mp4',
    'https://archive.org/download/bbb-openmovie/full_480p.mkv',
    'https://archive.org/download/bbb-openmovie/unknown',
    'javascript:alert(1)'
  ])assert.equal(parseArchiveFileUrl(url),null,url);
});
test('Exact title, year and licensed full-sized matching media file is accepted',()=>{
  assert.deepEqual(selectArchiveVerifiedFile(meta,parsed,base),{
    quality:1080,licenseUrl:'https://creativecommons.org/licenses/by/3.0/'
  });
});
test('Reject demonstrated third party addon wrong-title, trailer and SD media',()=>{
  const cases=[
    {metadata:{...base.metadata,title:'CNN Money Archive: Bugs Bunny and the peacock'}},
    {metadata:{...base.metadata,title:'Way Down East - rehearsal'}},
    {metadata:{...base.metadata,title:'Night Of The Living Dead Trailer (1968)'}},
    {metadata:{...base.metadata,title:'Big Buck Bunny (2007)'}},
    {metadata:{...base.metadata,year:'2012'}},
    {metadata:{...base.metadata,licenseurl:null}},
    {metadata:{...base.metadata,licenseurl:'https://example.net/cc-license'}},
    {files:[{...base.files[0],height:'360'}]},
    {files:[{...base.files[0],name:'not-the-same-file.mp4'}]},
    {files:[{...base.files[0],size:'1800'}]}
  ];
  for(const changes of cases)assert.equal(selectArchiveVerifiedFile(meta,parsed,{
    ...base,...changes
  }),null,JSON.stringify(changes));
});
test('A source file labeled 4K cannot override actual 720p file metadata',()=>{
  const report=selectArchiveVerifiedFile(meta,parsed,{...base,files:[{
    name:'full_1080p.mp4',height:'720',size:'1999999'
  }]});
  assert.equal(report.quality,720);
});
test('Use independent metadata URL under fixed Archive host, never arbitrary addon endpoint',async()=>{
  let seen=[];
  const verified=await validateExternalArchiveStream(good,meta,{
    fetchImpl:async url=>{
      seen.push(url);
      return new Response(JSON.stringify(base),{status:200,
        headers:{'Content-Type':'application/json'}});
    }
  });
  assert.equal(verified.quality,1080);
  assert.deepEqual(seen,['https://archive.org/metadata/bbb-openmovie']);
  assert.equal(await validateExternalArchiveStream(good,{...meta,media:'tv'},{
    fetchImpl:()=>{throw Error('should never fetch');}
  }),null);
});

test('Reject unverifiable production year unless a correct year suffix appears in archive title',()=>{
  const noYear={...base,metadata:{...base.metadata,year:undefined}};
  assert.equal(selectArchiveVerifiedFile(meta,parsed,noYear),null);
  assert.equal(selectArchiveVerifiedFile(meta,parsed,{
    ...noYear,metadata:{...noYear.metadata,title:'Big Buck Bunny (2008)'}
  }).quality,1080);
  assert.equal(selectArchiveVerifiedFile(meta,parsed,{
    ...noYear,metadata:{...noYear.metadata,title:'Big Buck Bunny (2007)'}
  }),null);
});
