/**
 * End-to-end readiness pipeline for *reviewed and rights-approved* local
 * Nuvio-compatible Node plugins.
 *
 * Phase 1 executes the pinned local JS plugin with a deadline.
 * Phase 2 validates each candidate's MIME, browser Origin/CORS and status
 * against the operator's explicit exact CDN allowlist.
 *
 * Never return a source to the website merely because getStreams() returned
 * a syntactically plausible URL. No browser header spoofing, proxy, or DRM.
 */
import {runLicensedJsProbe} from './js-plugin-sidecar.mjs';
import {checkApprovedSources} from './browser-source-readiness.mjs';

const REASONS=new Set(['rate_limited','access_denied','browser_origin_not_allowed',
  'not_media','network_error','timeout','http_error','not_found','head_unsupported',
  'redirect_blocked','invalid_size','unapproved_media_host_or_format',
  'quality_not_verified']);

export async function resolveBrowserReadyJsSources(meta,{
  provider='commons-authorized',licensedMedia=false,permittedHosts=[],
  fetchImpl=fetch,timeoutMs=3500
}={}){
  if(!licensedMedia||!permittedHosts.length)return {
    sources:[],counts:{candidates:0,verified:0},state:'rights_not_approved'
  };
  const result=await runLicensedJsProbe(meta,{
    provider,licensedMedia,permittedHosts,timeoutMs:10000
  });
  if(!result.ok)return {
    sources:[],counts:{candidates:0,verified:0},state:result.reason||'provider_failed'
  };
  const {ready,rejected}=await checkApprovedSources(result.sources,{
    allowedHosts:permittedHosts,fetchImpl,timeoutMs
  });
  const issues={};
  for(const item of rejected){
    const reason=REASONS.has(item.reason)?item.reason:'unknown';
    issues[reason]=(issues[reason]||0)+1;
  }
  return {
    sources:ready,
    counts:{
      candidates:result.sources.length,
      verified:ready.length,
      blockedHeaders:result.blockedHeaders,
      blockedProviderResults:result.rejected
    },
    issues,
    state:ready.length?'browser_preflight_passed':'no_browser_approved_sources'
  };
}
