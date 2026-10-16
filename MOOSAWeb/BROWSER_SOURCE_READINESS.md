# MOOSA browser stream readiness — tested source acceptance gate

## Why it is needed

A CloudStream or Nuvio plugin can return 720p/1080p MP4/HLS URLs that **do not play** in browsers. Real original Streamline Vidlink testing returned links for the Creative Commons film Tears of Steel, but a no-Referer browser-origin request to the returned CDN produced **HTTP 429, HTML not video, and no CORS**. This is why a plugin's `getStreams()` success alone is not enough to list a server.

## New modules (operator-controlled, NOT open to site visitors)

- `browser-source-readiness.mjs`: HEAD-only preflight for **explicitly permitted exact HTTPS hosts**. Validates HTTP 200/206, strict media MIME against MP4/WebM/HLS extension, browser Origin compatibility, minimum 720p, rejects redirects, access-denied, rate-limited responses, HTML and unknown hosts. Limits to twelve candidates and three concurrent requests. No media data is downloaded or proxied; no Referrer spoofing, cookies, DRM workarounds or arbitrary URLs from query parameters.
- `js-provider-validation.mjs`: chains the previously proven local, developer-reviewed Nuvio-like JS sidecar process with the readiness check. Returns only validated candidates and aggregate rejected reasons, with no rejected signed URLs or secrets. A trusted operator must explicitly declare the domain and rights authorization; a plugin cannot grant itself permission.
- Tests: `browser-source-readiness.test.mjs` and `js-provider-validation.test.mjs` cover HTML returned with status 200, missing CORS, rate limiting, origin mismatches, redirects, localhost/unapproved hosts, bad quality, deadline and concurrency. A real live test runs the authorized Wikimedia Commons provider from an isolated Node process, then checks a real 720p WebM file.

## Proven evidence — October 16, 2026

[GitHub Actions browser gate real live smoke](https://github.com/MooOsa1/MOOSA-CloudStream/actions/workflows/browser-source-readiness.yml)

In a representative green run, the pipeline returned:

```text
state: browser_preflight_passed
count: 2
qualities: 2250, 720
formats: video/webm
```

It took around 0.5s on that runner for Commons metadata + parallel HEAD. This is a measured example, **not a performance promise** for every video source.

## Important limitations

- HEAD response + MIME + CORS is **necessary but not sufficient** to certify playable video on iOS Safari, especially for unsupported codecs, encrypted HLS manifests, expired URLs, or segment URLs with different authorization/CORS. Actual player decode tests are required. The earlier *Elephants Dream* failure on the user's iPhone is a counterexample: metadata and HTTP reachability alone do not guarantee playback.
- Direct MP4 may sometimes play without CORS using an uncredentialed `<video>`, but this gate intentionally takes the conservative strict-CORS approach to avoid overstating browser compatibility; HLS.js requires CORS to fetch manifests/segments.
- This module only runs from explicitly trusted server code. A Node subprocess alone **is not an untrusted JavaScript sandbox**.
- **No Vidlink or other upstream Streamline plugin is activated on production MOOSA Web.** No Chrome/Safari playback of those original plugin URLs has passed. The original GPL/AGPL upstream licensing discrepancy remains unresolved, and the original Android `.cs3` extensions do not run in Node.
- This change has no production API integration or public UI feature. It prepares the acceptance gate for future rights-approved JS providers on the existing free Render instance while retaining current site functionality.

## Promotion criterion

A provider is eligible for a controlled rollout only when: exact movie/episode match, lawful streaming rights, reliable local JS execution, browser-playable URL without privileged headers, MIME/status/CORS preflight, and actual video playback on a target browser all pass. A failing preflight must be recorded as unavailable, not displayed as a misleading server.
