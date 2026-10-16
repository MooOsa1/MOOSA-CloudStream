# MOOSA headless CloudStream API — first real transport experiment

## Purpose
Prove the **host → Android environment → original CloudStream extension → JSON status response** data path with **no pairing of an end-user device**. This is *not* a production streaming engine and does not serve video.

The private test module `MOOSAProbe` runs inside the official Android CloudStream app on a disposable GitHub Actions emulator. It opens a `127.0.0.1:18878`-only HTTP test port. GitHub Actions forwards that port with `adb forward` and sends a signed local-only test request. Original, hash-verified CineStream and StreamPlay `.cs3` artifacts execute inside the Android sandbox.

### Endpoints (test-only)

- `GET /v1/health`: responds `{ready:true,runtime:'original-cloudstream-android-test',production:false}`.
- `POST /v1/probe` with fixed test-only bearer credential and `{media:'movie',title:'Big Buck Bunny',purpose:'licensed-emulator-test'}`: performs actual `search → load → loadLinks` against registered providers and returns status/count metadata without source URLs, private headers, or cookies.
- Other film requests, missing/incorrect credentials, and unsupported routes are rejected. Requests are loopback-only and body/header sizes are limited.

The CI workflow builds the probe **only when `MOOSA_ENABLE_PROBE=1`**. The normal MOOSA plugin release build includes only MOOSAHub and MOOSAArabic and must never publish MOOSAProbe.

## Evidence and limitations

[Successful proof run: 38867153410](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38867153410). The GitHub Actions runner connected to Android via ADB TCP forwarding, called the test endpoint and received JSON summarizing the two original extensions. CineStream searched but found 0 exact licensed-sample matches. StreamPlay found 2 exact matches, loaded one movie payload, and reported 9 candidate sources, of which 3 were declared 720p+ and 7 required Referer. No source URLs were returned from the API.

[Android CI workflow](https://github.com/MooOsa1/MOOSA-CloudStream/actions/workflows/cloudstream-full-probe.yml)

A passing test proves the HTTP transport and at least one genuine provider search call, not valid playback. Provider results are intentionally limited to counts and declared quality, HLS, Referer, and extra-header metadata. The actual video URLs still need authorization, CDN availability, and browser playback validation.

GitHub Actions provides **temporary emulators only**, not an always-on public web service. This local HTTP server does not implement secure multi-tenant operation, updates, health management, streaming results, process isolation between extensions, or OAuth/DRM/website permission handling. The fixed bearer value is a disposable test fixture, not a production secret.

## Next production prerequisites

1. An always-on, isolated Android-compatible runtime on an operator-controlled host, with non-root extension sandbox, resource quotas, and verified plugin artifacts.
2. A hardened authenticated server API with request IDs, cancellation, per-plugin deadlines, and asynchronous/progressive responses. Do not expose the loopback proof server publicly.
3. Resolve and validate exact metadata; reject ambiguous seasons/episodes and unauthorized media.
4. Check source response and browser HLS/MP4 compatibility for licensed sample media. In production, React/HTML5 media players may not set arbitrary Referer/Cookie headers; avoid claiming universal playback.
5. Only then configure the production `MOOSAWeb/cloudstream-engine.mjs` runtime URL and secret after end-to-end testing. Do not commit secrets into GitHub.
