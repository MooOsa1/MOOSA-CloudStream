import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from '../server.mjs';

const app=createServer();
const port=await new Promise(r=>app.listen(0,'127.0.0.1',()=>r(app.address().port)));
let browser;
try{
 browser=await chromium.launch({headless:true,args:['--autoplay-policy=no-user-gesture-required']});
 const page=await browser.newPage({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
 const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/free-library',route=>route.fulfill({status:200,contentType:'application/json',
   body:JSON.stringify({films:[
    {id:'big-buck-bunny',title:'Big Buck Bunny',year:2008,genre:'أنيميشن • كوميديا',qualities:[720,2250],license:'https://creativecommons.org/licenses/by/3.0/'},
    {id:'sintel',title:'Sintel',year:2010,genre:'فانتازيا • مغامرة',qualities:[1744],license:'https://creativecommons.org/licenses/by/3.0/'},
    {id:'tears-of-steel',title:'Tears of Steel',year:2012,genre:'خيال علمي',qualities:[800],license:'https://creativecommons.org/licenses/by/3.0/'},
    {id:'elephants-dream',title:'Elephants Dream',year:2006,genre:'أنيميشن',qualities:[1080],license:'https://creativecommons.org/licenses/by/3.0/'}
   ]})
 }));
 await page.route('**/api/free-film?id=*',route=>{
   const id=new URL(route.request().url()).searchParams.get('id');
   const title=id==='sintel'?'Sintel':'Big Buck Bunny';
   return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({
     id,title,available:true,count:1,sources:[{quality:720,preferred:true,
      url:'https://upload.wikimedia.org/wikipedia/commons/s/s0/'+title.replaceAll(' ','_')+'.webm',
      name:'Wikimedia Commons • CC BY 3.0',subtitles:[],
      licenseUrl:'https://creativecommons.org/licenses/by/3.0/',
      sourcePage:'https://commons.wikimedia.org/wiki/File:'+title.replaceAll(' ','_')+'.webm',
      attribution:'Blender Foundation'}]
   })});
 });
 await page.route('https://upload.wikimedia.org/**',route=>route.fulfill({status:403,body:'UI-only test'}));
 await page.goto('http://127.0.0.1:'+port+'/',{waitUntil:'domcontentloaded',timeout:18000});
 await page.locator('.free-film-card').first().waitFor({timeout:20000});
 assert.equal(await page.locator('.free-film-card').count(),4);
 assert.equal(await page.locator('.free-film-card').nth(1).innerText().then(x=>x.includes('Sintel')),true);
 await page.locator('[data-free-film="sintel"]').click();
 await page.waitForFunction(()=>document.querySelector('#player-title')?.textContent.includes('Sintel'));
 assert.match(await page.locator('#automatic-source-status').innerText(),/فيلم مستقل/);
 assert.equal(await page.locator('#player-source').isEnabled(),true);
 assert.match(await page.locator('#source-attribution').innerText(),/Blender Foundation/);
 assert.deepEqual(errors,[],'No browser JS errors');
 console.log('FREE_LIBRARY_MOBILE_PASS: 4 confirmed film cards, Sintel exact playback source, CC credits');
} finally{
 await browser?.close();
 await new Promise(r=>app.close(r));
}
