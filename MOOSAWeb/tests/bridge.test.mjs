import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../server.mjs';

async function withServer(run) {
  const server=createServer();
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base='http://127.0.0.1:'+server.address().port;
  try {await run(base);} finally {await new Promise(resolve=>server.close(resolve));}
}
async function post(base,path,body,token) {
  const response=await fetch(base+path,{method:'POST',headers:{
    'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})
  },body:JSON.stringify(body)});
  return {status:response.status,body:await response.json()};
}
async function get(base,path,token) {
  const response=await fetch(base+path,{headers:token?{Authorization:'Bearer '+token}:{}});
  return {status:response.status,body:await response.json()};
}

test('CloudStream browser-agent pairing and on-demand movie streams',async()=>{
  await withServer(async base=>{
    const pair=await post(base,'/api/bridge/pair',{});
    assert.equal(pair.status,200);
    const {code,browserToken}=pair.body;
    assert.match(code,/^[A-F0-9]{14}$/);
    assert.match(browserToken,/^[\w-]+\.[\w-]+$/);
    assert.equal((await get(base,'/api/bridge/status',browserToken)).body.paired,false);
    assert.equal((await post(base,'/api/bridge/claim',{code:'BAD'})).status,404);
    const claim=await post(base,'/api/bridge/claim',{code});
    assert.equal(claim.status,200);
    const agent=claim.body.agentToken;
    assert.equal((await post(base,'/api/bridge/claim',{code})).status,404);
    assert.equal((await get(base,'/api/bridge/next',browserToken)).status,401);
    const inventory=await post(base,'/api/bridge/providers',{providers:['Plugin A','Plugin B']},agent);
    assert.equal(inventory.status,200);
    const status=(await get(base,'/api/bridge/status',browserToken)).body;
    assert.equal(status.online,true);assert.equal(status.providers.length,2);
    const find=await post(base,'/api/bridge/find',{
      media:'movie',id:123,title:'Example Movie',year:2026,language:'en'
    },browserToken);
    assert.equal(find.status,202);
    const task=(await get(base,'/api/bridge/next',agent)).body.task;
    assert.equal(task.id,find.body.requestId);
    assert.equal(task.title,'Example Movie');
    const partial=await post(base,'/api/bridge/report',{
      requestId:task.id, final:false, sources:[
        {name:'Plugin A • 1080p',quality:1080,url:'https://cdn.example.com/movie.m3u8',
          subtitles:[{url:'https://cdn.example.com/sub.vtt',language:'ar',label:'Arabic'}]},
        {name:'Plugin A • 480p',quality:480,url:'https://cdn.example.com/sd.mp4'},
        {name:'Plugin A Download 1080p',quality:1080,url:'https://cdn.example.com/down.mp4'},
        {name:'Private 1080p',quality:1080,url:'http://192.168.0.1/file.mp4'}
      ]
    },agent);
    assert.equal(partial.status,200);assert.equal(partial.body.accepted,1);
    const first=(await get(base,'/api/bridge/result?id='+task.id,browserToken)).body;
    assert.equal(first.done,false);assert.equal(first.sources.length,1);
    assert.equal(first.sources[0].subtitles[0].language,'ar');
    const finish=await post(base,'/api/bridge/report',{
      requestId:task.id,final:true,sources:[
        {name:'Plugin B • 4K',quality:2160,url:'https://cdn.example.com/4k.m3u8'},
        {name:'Duplicate',quality:1080,url:'https://cdn.example.com/movie.m3u8'}
      ]
    },agent);
    assert.equal(finish.status,200);assert.equal(finish.body.accepted,2);
    const last=(await get(base,'/api/bridge/result?id='+task.id,browserToken)).body;
    assert.equal(last.done,true);assert.deepEqual(last.sources.map(x=>x.quality),[2160,1080]);
    assert.equal((await get(base,'/api/bridge/result?id='+task.id,agent)).status,401);
  });
});

test('Bridge fails closed for missing episodes, bogus token and no paired device',async()=>{
  await withServer(async base=>{
    const pair=await post(base,'/api/bridge/pair',{});
    const token=pair.body.browserToken;
    assert.equal((await get(base,'/api/bridge/status','bad')).status,401);
    assert.equal((await post(base,'/api/bridge/find',{media:'movie',id:1,title:'ABC'},token)).status,503);
    const claim=await post(base,'/api/bridge/claim',{code:pair.body.code});
    await post(base,'/api/bridge/providers',{providers:['Sample']},claim.body.agentToken);
    const invalid=await post(base,'/api/bridge/find',{media:'tv',id:55,title:'Example',season:2},token);
    assert.equal(invalid.status,400);
    const valid=await post(base,'/api/bridge/find',{media:'tv',id:55,title:'Example',season:2,episode:3},token);
    assert.equal(valid.status,202);
    const task=(await get(base,'/api/bridge/next',claim.body.agentToken)).body.task;
    assert.equal(task.media,'tv');assert.equal(task.season,2);assert.equal(task.episode,3);
    assert.equal((await post(base,'/api/bridge/report',{requestId:'no-such-task',sources:[]},claim.body.agentToken)).status,404);
    assert.equal((await get(base,'/api/bridge/result?id=no-such-task',token)).status,404);
  });
});
