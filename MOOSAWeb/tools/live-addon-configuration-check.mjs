/**
 * Read-only production source-adapter health check.
 * Does not request movies, signed stream URLs or credentials.
 * Distinguishes metadata-level "addon configured" from playable FHD.
 */
import assert from 'node:assert/strict';
const BASE='https://moosa-web.onrender.com';
async function fetchJson(path){
 const r=await fetch(BASE+path,{
  headers:{Accept:'application/json'},
  signal:AbortSignal.timeout(25000),redirect:'error'
 });
 if(!r.ok)throw Error('Production endpoint '+path+' returned HTTP '+r.status);
 const body=await r.json();
 assert.ok(body && typeof body==='object');
 return body;
}
const [app,native]=await Promise.all([
 fetchJson('/api/config'),
 fetchJson('/api/web-native/status')
]);
const count=native?.httpStremioSubtitles?.configuredAddonCount;
assert.ok(Number.isInteger(count)&&count>=0&&count<=6,
 'Count must be explicit from production configuration; do not guess');
console.log('MOOSA_PROD_HTTP_ADDON_CONFIGURATION '+JSON.stringify({
 liveHost:new URL(BASE).host,
 catalogReady:app.catalogReady===true,
 stremioAddonsConfigured:count,
 cloudstreamRuntimeConfigured:app.cloudstreamEngineConfigured===true,
 nativeJsActive:Array.isArray(native.activeReviewedProviders)&&native.activeReviewedProviders.length>0,
 licensedFhdProofRequiresSeparatePlayback:true,
 doesNotVerifyMovieServers:true
}));
console.log('MOOSA_NEXT_ACTION '+(count===0
 ? 'NO_EXTERNAL_STREMIO_ADDONS_CONFIGURED__FIND_ONE_AUTHORIZED_DIRECT_HTTP_MANIFEST_BEFORE_ANY_MORE_PROTOCOL_CHANGES'
 : 'EXTERNAL_ADDONS_CONFIGURED__VALIDATE_ONE_LICENSED_TITLE_AND_REAL_BROWSER_DECODING'));
