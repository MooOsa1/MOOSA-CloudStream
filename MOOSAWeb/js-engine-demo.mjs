/**
 * Live, fixed-sample JS engine proof on the existing MOOSA Node server.
 *
 * It runs the locally reviewed Commons Nuvio-style getStreams() module in an
 * independent bounded Node subprocess, then verifies real media HTTP HEAD,
 * MIME and CORS before returning any stream.
 *
 * Only a verified Blender CC-BY film. No caller-supplied URL, plugin path,
 * title, CDN host, permissions or credentials. No Android, phone pairing,
 * proxying or imported Streamline/Vidlink code.
 *
 * This is NOT proof that a returned URL can decode on every browser.
 */
import {resolveBrowserReadyJsSources} from './js-provider-validation.mjs';

export const DEMO_META=Object.freeze({
  media:'movie',id:10378,
  title:'Big Buck Bunny',originalTitle:'Big Buck Bunny'
});
const HOSTS=Object.freeze(['upload.wikimedia.org']);
const GOOD_TTL=5*60_000;
const FAIL_TTL=45_000;

export function createJsEngineDemo({
  run=resolveBrowserReadyJsSources,
  now=Date.now
}={}){
  let cached=null,expires=0,pending=null;

  async function load(){
    const time=now();
    if(cached&&expires>time)return cached;
    if(pending)return pending;
    const attempt=(async()=>{
      let data;
      try{
        const result=await run({...DEMO_META},{
          provider:'commons-authorized',licensedMedia:true,
          permittedHosts:[...HOSTS],timeoutMs:3500
        });
        const sources=(Array.isArray(result.sources)?result.sources:[])
          .filter(s=>{
            try{return new URL(s.url).hostname==='upload.wikimedia.org' &&
              s.preflight?.verified===true && Number(s.quality)>=720;}
            catch{return false;}
          }).slice(0,4).map(s=>({
            name:'MOOSA JS Engine • Wikimedia Commons',
            quality:s.quality,url:s.url,
            subtitles:[],preferred:s.quality<=1080,
            preflight:{verified:true,mime:s.preflight.mime},
            licenseUrl:'https://creativecommons.org/licenses/by/3.0/',
            attribution:'Blender Foundation'
          }));
        data={
          engine:'reviewed_js_worker',
          sample:'Big Buck Bunny',
          authorizedContent:true,
          originalCloudStreamPlugins:false,
          state:sources.length?'browser_preflight_passed':'no_browser_approved_sources',
          sources,count:sources.length,
          caveat:'HEAD/CORS readiness is not guaranteed Safari playback. Original CineStream and StreamPlay .cs3 plugins are NOT running on Render.'
        };
      }catch{
        data={
          engine:'reviewed_js_worker',
          sample:'Big Buck Bunny',
          authorizedContent:true,
          originalCloudStreamPlugins:false,
          state:'temporarily_unavailable',
          sources:[],count:0,
          caveat:'This fixed licensed JS sample has no verified stream at this moment.'
        };
      }
      cached=data;
      expires=now()+(data.count?GOOD_TTL:FAIL_TTL);
      return data;
    })();
    pending=attempt;
    try{return await attempt;}finally{if(pending===attempt)pending=null;}
  }
  return {load};
}
