# MOOSA JS provider sidecar: verified feasibility and hard blockers (2026-10-16)

## What is implemented

- `MOOSAWeb/js-plugin-worker.cjs`: local-only, allowlisted JS module runner reading a bounded JSON IPC message. It executes only two explicitly pinned **reviewed local modules** in this experiment: a synthetic test fixture and our independent Commons licensed-video adapter. **No remote arbitrary JavaScript**, raw CloudStream `.cs3` files or browser-provided paths can execute.
- `MOOSAWeb/js-plugin-sidecar.mjs`: subprocess launcher with a finite deadline, 96 MiB Node heap cap, limited IPC payload/output, scrubbed environment (no TMDB/Render secrets), kill-on-timeout, post-processing with `adaptNuvioStreams`. It is not referenced by the website's production source resolver, and is not a true OS sandbox.
- `MOOSAWeb/js-plugins/commons-authorized.cjs`: our own `getStreams(tmdbId,mediaType,season,episode)` interface for one permitted Creative Commons sample. It reuses the verified Commons metadata/rights checker, and has **no** CineStream/StreamPlay third-party source code.
- `MOOSAWeb/tests/js-plugin-sidecar.test.mjs`: six passing tests, including rights/host gating, refusal of protected Referer/Cookie links and process cancellation.
- `MOOSAWeb/tests/js-plugin-live.smoke.mjs`: genuine licensed HD source resolution through the subprocess and HTTP HEAD of video without downloading the movie.

## Actual evidence

[Successful independent JS process -> original license-checked video 38882980540](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38882980540):

- 720p + 2250p sources discovered, host `upload.wikimedia.org`, no required Referer
- 720p HEAD: **200**, `video/webm`, `Access-Control-Allow-Origin: *`, size 93,192,761 bytes
- Worker time ~444ms in that run; six subprocess/deadline/security tests passed

[Original upstream Streamline Vidlink, real endpoints, two openly licensed films 38883401132](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38883401132):

- `Big Buck Bunny` (TMDB 10378) -> **0 sources** despite API 200
- `Tears of Steel` (TMDB 133701) -> two candidate MP4 sources, declared **720p and 1080p**, both marked as requiring **Referer**
- These are actual output of the pinned original Streamline JS bundle (checked out ephemerally in CI), **not mock data**. TMDB metadata was locally supplied from verified movie IDs, while Vidlink/ID encoder APIs were real.
- [Browser-style HEAD follow-up 38883548212](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38883548212) without a forged Referer returned **HTTP 429**, `Content-Type: text/html`, **no CORS** from one returned HD media URL.
- Accordingly **ZERO original Vidlink candidate streams are approved for public MOOSA Web playback**. A 200 API response or returned stream URL does NOT imply a working media link.

## Why not turn on the original Vidlink module now?

1. **No verified browser-playable URL.** Do not bypass 429/referer, proxy third-party media, or advertise a dead server.
2. **Source redistribution and software licence:** Streamline root `LICENSE` contains AGPL-3.0 while README and `package.json` say GPL-3.0-only. The original module is checked out in ephemeral GitHub Actions only, **not copied into this project or deployed**. Clarify licence before redistribution or a hosted service.
3. **Security:** A child Node process with scrubbed env and a heap cap provides failure isolation but **not a hostile-code sandbox**. Running third-party, unaudited scripts requires OS/container isolation, egress restrictions, and explicit trust.
4. **Coverage:** One plugin and two CC tests cannot prove that mainstream commercial films have authorized streams, that all Nuvio providers work, or that Android `.cs3` packages run on Render.

## Measurable next step

Investigate another rights-authorized direct-web content provider with explicit API/distribution permission and video MIME/CORS/codec compatibility; require response status 200, video type, matching title/episode and browser playback before exposing results. Keep `WEB_SOURCE_ADDONS=[]` until a permitted provider is verified. No VPS needed for a compatible Node provider, but that does **not** make free, unauthorized film hosting or original CloudStream Android execution available.

**There is no production switch in this PR.** No change to public source coverage, Render configuration, public APIs or phone pairing; its purpose is to replace guesses with measured, safe compatibility gates.
