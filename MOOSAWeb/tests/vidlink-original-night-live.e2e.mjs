/**
 * Full real-network proof of the FIRST-PARTY MOOSA Vidlink protocol adapter.
 * NO Android/JVM, no GPL provider code, no persistent third-party URLs,
 * no public player access. One exact public-domain original film ID only.
 *
 * Source terms/rights for particular restorations still require review.
 */
import assert from 'node:assert/strict';
import {createMediaGateway} from '../verified-media-gateway.mjs';
import {findNightFromCineStream} from '../vidlink-original-night-pilot.mjs';
const meta={media:'movie',id:10331,year:1968,
  imdbId:'tt0063350',title:'Night of the Living Dead',
  originalTitle:'Night of the Living Dead'};
const preferredHosts=[
  'hcdn3.hakunaymatata.com'
];
const gateway=createMediaGateway({
  origin:'https://moosa-web.onrender.com',
  allowedHosts:preferredHosts,ttlMs:300000
});
const publicHosts=new Set(),kinds=new Set();
const issuer={
  issueIfBytesVerified:async input=>{
    const host=new URL(input.url).hostname.toLowerCase();
    publicHosts.add(host);kinds.add(input.format);
    return gateway.issueIfBytesVerified(input);
  }
};
const started=Date.now();
const result=await findNightFromCineStream({
  meta,mediaGateway:issuer,enabled:true
});
console.log('MOOSA_VIDLINK_FIRST_PARTY_LIVE_ADAPTER '+JSON.stringify({
  tmdbId:10331,imdb:'tt0063350',
  protocol:'First-party Node Vidlink adapter',
  observedPublicHostnames:[...publicHosts],
  types:[...kinds],
  realVideoByteVerified:result.length===1,
  issuedOpaqueSameOriginLink:result.length===1 &&
    result[0].url.startsWith('https://moosa-web.onrender.com/api/media/'),
  seconds:Math.round((Date.now()-started)/1000),
  originalCloudStreamPluginUsed:false,
  productionEnabled:false
}));
assert.deepEqual(result.length,1,
  'First-party adapter did not yield independently verified Vidlink film MP4; do not enable');
assert.equal(result[0].gatewayIssued,true);
assert.equal(result[0].originalMovieImdbId,'tt0063350');
