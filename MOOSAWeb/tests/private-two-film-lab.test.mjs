import test from 'node:test';
import assert from 'node:assert/strict';

const previous={
  lab:process.env.MOOSA_TWO_FILM_PRIVATE_LAB,
  password:process.env.MOOSA_LAB_PASSWORD
};
process.env.MOOSA_TWO_FILM_PRIVATE_LAB='1';
process.env.MOOSA_LAB_PASSWORD='test_secret_password_123';
const {createServer}=await import('../server.mjs');
const server=createServer();
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const base='http://127.0.0.1:'+server.address().port;
const auth={Authorization:'Basic '+Buffer.from(
  'moosa:test_secret_password_123').toString('base64')};
try{
  await test('Render lab health can be checked without exposing video or credentials',async()=>{
    const res=await fetch(base+'/healthz');
    assert.equal(res.status,200);
    assert.equal((await res.json()).status,'ok');
  });
  await test('Unauthorized visitor cannot load site, search, resolve or stream',async()=>{
    for(const pathname of ['/', '/api/config','/api/search?q=Metropolis',
      '/api/resolve?media=movie&id=19','/api/media/'+('A'.repeat(43))]){
      const r=await fetch(base+pathname);
      assert.equal(r.status,401,pathname);
      assert.match(r.headers.get('www-authenticate')||'',/Basic/);
    }
  });
  await test('Private movie catalog exposes four independently named title identities',async()=>{
    const result=await fetch(base+'/api/catalog?section=trending_movies',{headers:auth});
    assert.equal(result.status,200);
    const data=await result.json();
    assert.deepEqual(data.results.map(x=>[x.id,x.title]),[
      [19,'Metropolis'],[832,'M'],[961,'The General'],[3085,'His Girl Friday']]);
    for(const [q,title] of [['Metropolis','Metropolis'],['M 1931','M'],
      ['The General 1926','The General'],['His Girl Friday','His Girl Friday']]){
      const r=await fetch(base+'/api/search?q='+encodeURIComponent(q),{headers:auth});
      assert.equal(r.status,200);
      assert.deepEqual((await r.json()).results.map(x=>x.title),[title]);
    }
    const unknown=await fetch(base+'/api/search?q=unknown',{headers:auth});
    assert.deepEqual((await unknown.json()).results,[]);
    const cfg=await fetch(base+'/api/config',{headers:auth});
    assert.equal((await cfg.json()).catalogReady,true);
    const cookie=cfg.headers.get('set-cookie')||'';
    assert.match(cookie,/__Host-moosa_lab=/);
    assert.match(cookie,/Secure; HttpOnly; SameSite=Lax/);
    const sessionCookie=cookie.split(';')[0];
    const cookieOnly=await fetch(base+'/api/config',{
      headers:{Cookie:sessionCookie}
    });
    assert.equal(cookieOnly.status,200,
      'Safari media requests may carry the session cookie without Basic Auth');
    const protectedMedia=await fetch(base+'/api/media/'+('A'.repeat(43)),{
      headers:{Cookie:sessionCookie}
    });
    assert.equal(protectedMedia.status,404,
      'Cookie-authenticated media must reach gateway routing, not 401');

  });
  await test('Exact IMDb is present in each private movie details route',async()=>{
    for(const [id,imdb] of [[19,'tt0017136'],[832,'tt0022100'],
      [961,'tt0017925'],[3085,'tt0032599']]){
      const res=await fetch(base+'/api/details?media=movie&id='+id,{headers:auth});
      const data=await res.json();
      assert.equal(data.imdb_id,imdb);
    }
  });
}finally{
  await new Promise(resolve=>server.close(resolve));
  for(const [key,value] of [['MOOSA_TWO_FILM_PRIVATE_LAB',previous.lab],
    ['MOOSA_LAB_PASSWORD',previous.password]]){
      if(value===undefined)delete process.env[key];
      else process.env[key]=value;
  }
}
