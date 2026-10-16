/**
 * Live integration smoke for the locally reviewed Nuvio-style JS provider.
 * Real Commons discovery and HTTP HEAD only, no movie download/proxy.
 * This is NOT an original CineStream/StreamPlay streaming test.
 */
import assert from 'node:assert/strict';
import {runLicensedJsProbe} from '../js-plugin-sidecar.mjs';

const result=await runLicensedJsProbe({
  media:'movie',id:10378,title:'Big Buck Bunny',originalTitle:'Big Buck Bunny'
},{
  provider:'commons-authorized',
  licensedMedia:true,
  permittedHosts:['upload.wikimedia.org'],
  timeoutMs:10000
});
console.log('LIVE_NODE_PLUGIN_PROTOCOL',JSON.stringify({
  ok:result.ok,reason:result.reason,
  sources:result.sources.map(x=>({quality:x.quality,
    host:new URL(x.url).hostname,extension:new URL(x.url).pathname.split('.').pop()})),
  rejected:result.rejected,blockedHeaders:result.blockedHeaders,
  latencyMs:result.elapsedMs
}));
assert.equal(result.ok,true,'Live Commons plugin worker must succeed');
assert.equal(result.reason,'reviewed_commons_provider');
const candidate=result.sources.find(x=>x.quality>=720&&x.quality<=1080);
assert.ok(candidate,'Must find at least one lightweight CC full-film candidate');
assert.equal(new URL(candidate.url).hostname,'upload.wikimedia.org');
const res=await fetch(candidate.url,{
  method:'HEAD',redirect:'error',signal:AbortSignal.timeout(10000),
  headers:{'User-Agent':'MOOSAWeb/0.1 (https://github.com/MooOsa1/MOOSA-CloudStream; licensed video HEAD smoke)'}
});
const inspected={
  status:res.status,contentType:res.headers.get('content-type'),
  cors:res.headers.get('access-control-allow-origin'),
  contentLength:Number(res.headers.get('content-length')||0)
};
console.log('LIVE_NODE_PLUGIN_MEDIA_HEAD',JSON.stringify(inspected));
assert.equal(res.status,200);
assert.match(inspected.contentType||'',/^video\/(?:webm|mp4)/);
assert.equal(inspected.cors,'*');
assert.ok(inspected.contentLength>=400_000);
console.log('LIVE_NODE_PLUGIN_VERIFIED: isolated Node process -> Nuvio getStreams -> real CC source -> HTTP 200 media HEAD');
