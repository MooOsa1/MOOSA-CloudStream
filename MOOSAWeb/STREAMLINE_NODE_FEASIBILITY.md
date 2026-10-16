# Streamline → MOOSA Web: verified Node.js compatibility, NOT live streams

## Technical proof

A disposable GitHub Actions job checks out the **original pinned** [Streamline](https://github.com/itsmeadarsh2008/Streamline) repository at commit `5160850f93b519755dc0878abd3c43482e3e1449`, without copying the code into this repository, and loads its authentic `providers/vidlink.js` CommonJS Nuvio bundle in Node.js 22.

The test replaces `global.fetch` with deterministic synthetic JSON responses for the expected TMDB metadata, Vidlink ID encoder, and Vidlink movie/episode endpoints. It verifies that original `getStreams()` produces an expected fake 720p HLS result for both:

- Movie: fake TMDB ID `11223344` → `/api/b/movie/fake-encrypted-id`
- TV: fake TMDB ID `11223344`, season 2, episode 3 → `/api/b/tv/fake-encrypted-id/2/3`

**Real passing CI:** [38881465684](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38881465684). Upstream provider compatibility and MOOSA adapter unit tests passed.

### Meaning of this result

- **Yes:** This Nuvio JS plugin's `getStreams()` is executable as JavaScript in an ordinary Node.js process (no Android, no pairing or VPS required for syntax/runtime compatibility).
- **No:** The test **did not contact a live Vidlink, TMDB, or decryption site**, fetch a commercial stream, test header/cookie requirements or prove browser playback. All network calls were mocked, and returned HLS URLs were fake.
- **No:** This does not make all 23 Streamline providers or original Android CloudStream `.cs3` repositories work on Node.js.
- The upstream Nuvio `withTimeout()` utility can leave a 20-second timer alive after the request has resolved. The isolated test calls `unref()` on timer handles; production execution needs proper cancellation, request limits and process isolation.

## Source license and safe deployment boundaries

The original Streamline repo's root `LICENSE` says **AGPL-3.0**, whereas its `package.json` and README label the project **GPL-3.0-only**. This discrepancy should be clarified before copying, modifying or hosting its source. Since the code is currently being tested via a temporary checkout only, it is **not bundled into MOOSA Web**.

Streamline's scripts are Nuvio *on-device plugins*, not ready-made HTTP APIs. Many providers return Referer, Origin, Cookie or token-dependent results, which Safari cannot automatically attach to cross-site HTML5 media requests. A returned URL is not proof of browser-playable video and no access control/DRM should be bypassed.

## Next implementation stages

1. Resolve licensing and validate a particular provider's right to redistribute/stream test media before production activation.
2. Build a **separate, sandboxed JS executor process** with explicitly allowlisted modules and outbound hosts, no secrets inherited from MOOSA's public web server, bounded requests, cancellation, no remote code loading, and no public proxy of arbitrary URLs.
3. Translate Nuvio results through `nuvio-js-adapter.mjs`. It rejects torrents, mismatched/unrecognized formats, `<720p` results, localhost/private URLs, unapproved media hosts, and links that depend on browser-inaccessible Referer/Cookie/Authorization headers. The adapter requires an independently verified playback/distribution entitlement, which **must not be assumed** from a plugin result.
4. Test actual authorized video resolution and browser HLS/MP4 playback, exact title/year/IMDb and TV season/episode matching. Only after these checks would it be correct to expose new sources to ordinary MOOSA Web visitors.

The experiment has **no user-visible site features** and does not change Render configuration, existing sources, or MOOSA Hub plugin builds. The intended end goal is a reliable source engine, not a misleading catalog of links.
