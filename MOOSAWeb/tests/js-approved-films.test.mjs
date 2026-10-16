import test from 'node:test';
import assert from 'node:assert/strict';
import approved from '../js-approved-films.cjs';
import {runLicensedJsProbe} from '../js-plugin-sidecar.mjs';

const {films,approvedMedia,bySlug}=approved;
test('Exactly four fixed titles have immutable internal IDs',()=>{
  assert.deepEqual(films.map(f=>f.slug),[
    'big-buck-bunny','sintel','tears-of-steel','elephants-dream'
  ]);
  assert.equal(new Set(films.map(f=>f.id)).size,4);
  for(const f of films)assert.equal(approvedMedia({
    media:'movie',id:f.id,title:f.title,originalTitle:f.title,year:f.year
  }),f);
});
test('Cannot impersonate a second title, wrong year, TV show or arbitrary approved ID',()=>{
  const film=bySlug('sintel');
  const meta={media:'movie',id:film.id,title:film.title,
    originalTitle:film.title,year:film.year};
  for(const bad of [
    {...meta,title:'Tears of Steel'}, {...meta,id:999999},
    {...meta,year:2000}, {...meta,media:'tv',season:1,episode:1},
    {...meta,originalTitle:'Big Buck Bunny'}
  ])assert.equal(approvedMedia(bad),null);
});
test('Node provider denies unlisted film IDs before launching a subprocess',async()=>{
  const denied=await runLicensedJsProbe({
    media:'movie',id:42,title:'Sintel',originalTitle:'Sintel'
  },{provider:'commons-authorized',licensedMedia:true,
    permittedHosts:['upload.wikimedia.org']});
  assert.equal(denied.ok,false);
  assert.equal(denied.reason,'sample_not_approved');
});
