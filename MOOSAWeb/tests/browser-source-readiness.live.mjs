/**
 * End-to-end live check:
 * pinned local Node JS provider -> Commons licensed full movie ->
 * direct HTTPS video HEAD -> content type + CORS -> browser-ready sources.
 *
 * No video bytes downloaded. Does not claim to execute original .cs3
 * packages or to make Vidlink's blocked media CDN browser-compatible.
 */
import assert from 'node:assert/strict';
import {resolveBrowserReadyJsSources} from '../js-provider-validation.mjs';
const start=Date.now();
const result=await resolveBrowserReadyJsSources({
  media:'movie',id:10378,title:'Big Buck Bunny',originalTitle:'Big Buck Bunny'
},{
  provider:'commons-authorized',licensedMedia:true,
  permittedHosts:['upload.wikimedia.org'],timeoutMs:6500
});
const report={
  state:result.state,
  count:result.sources.length,
  formats:result.sources.map(s=>s.preflight?.mime),
  qualities:result.sources.map(s=>s.quality),
  rejected:result.issues,
  elapsedMs:Date.now()-start
};
console.log('BROWSER_READY_REAL_OPEN_LICENSED_MEDIA',JSON.stringify(report));
assert.equal(result.state,'browser_preflight_passed',JSON.stringify(report));
assert.ok(result.sources.some(s=>s.quality>=720&&s.quality<=1080
  &&s.preflight.verified&&s.preflight.mime==='video/webm'));
assert.ok(result.sources.every(s=>
  new URL(s.url).hostname==='upload.wikimedia.org'));
console.log('BROWSER_READY_GATE_LIVE_OK: node worker + genuine CC metadata + real HEAD video/CORS pass');
