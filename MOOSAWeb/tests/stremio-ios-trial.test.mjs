import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from '../server.mjs';
import {isPublicStremioTrialPath} from '../stremio-ios-trial.mjs';
async function withHost(fn){
  const srv=createServer();
  await new Promise((resolve,reject)=>{srv.once('error',reject);srv.listen(0,'127.0.0.1',resolve);});
  try{return await fn('http://127.0.0.1:'+srv.address().port);}
  finally{await new Promise(done=>srv.close(done));}
}
test('MOOSA iPhone Stremio manifest, catalog and direct HTTPS audio/video source are available',async()=>{
  assert.equal(isPublicStremioTrialPath('/moosa-stremio/manifest.json'),true);
  assert.equal(isPublicStremioTrialPath('/api/media/secret'),false);
  await withHost(async origin=>{
    const manifest=await fetch(origin+'/moosa-stremio/manifest.json');
    assert.equal(manifest.status,200);
    assert.equal(manifest.headers.get('access-control-allow-origin'),'*');
    const meta=await manifest.json();
    assert.equal(meta.id,'org.moosa.ios-test');
    assert.ok(meta.catalogs[0].extra.some(x=>x.name==='search'));
    const search=await fetch(origin+'/moosa-stremio/catalog/movie/moosa-iphone/search=wing%20it.json');
    assert.equal(search.status,200);
    const found=(await search.json()).metas;
    assert.deepEqual(found.map(x=>x.id),['moosa-wing-it-2023']);
    const other=await fetch(origin+'/moosa-stremio/catalog/movie/moosa-iphone/search=not-present.json');
    assert.deepEqual((await other.json()).metas,[]);
    const catalog=await (await fetch(origin+'/moosa-stremio/catalog/movie/moosa-iphone.json')).json();
    assert.ok(catalog.metas.some(row=>row.id==='moosa-ios-player-test'));
    const streams=await (await fetch(origin+'/moosa-stremio/stream/movie/moosa-ios-player-test.json')).json();
    assert.equal(streams.streams.length,1);
    assert.equal(streams.streams[0].url,'https://moosa-two-film-lab.onrender.com/moosa-stremio/sample.mp4');
    const sample=await fetch(origin+'/moosa-stremio/sample.mp4',{headers:{Range:'bytes=0-8191'}});
    assert.equal(sample.status,206);
    assert.match(sample.headers.get('content-type'),/^video\/mp4/);
    assert.equal(sample.headers.get('content-range')?.startsWith('bytes 0-8191/'),true);
    const first=Buffer.from(await sample.arrayBuffer());
    assert.equal(first.toString('latin1',4,8),'ftyp');
    assert.equal(first.byteLength,8192);
  });
});
