# MOOSA CloudStream — real search/load/loadLinks compatibility probe

This experiment **does not modify MOOSA Web's production player or supply a production stream service**. It runs a disposable test-only CloudStream extension alongside original, SHA256-verified CineStream and StreamPlay binaries inside an Android emulator on GitHub Actions.

## Evidence (October 16, 2026)

- [First successful test](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38864919279): CineStream search returned 20 candidates, **0 exact matches** for Blender's CC-BY `Big Buck Bunny`; StreamPlay search returned 2 exact results, `load()` produced `MovieLoadResponse`, `loadLinks()` delivered 9 candidate links and 6 subtitle callbacks before timeout.
- [Link characteristics test](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38865543161): StreamPlay 9 HTTPS link candidates; **3 declared >=720p**; 7 HLS URL paths, 0 MP4 URL paths; 7 with Referer, 3 with additional headers; 6 subtitle callbacks (0 Arabic / 3 English detected). `loadLinks()` timed out after 18 seconds; partial callback data arrived before timeout.
- [Test-only module opt-in](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38865705594): probe builds and runs under `MOOSA_ENABLE_PROBE=1` only. The official MOOSA plugin build does not include MOOSAProbe and must keep publishing only MOOSAHub and MOOSAArabic.

## Exact scope

This proves Android plugin registration, search execution and **partial extraction of candidate links** for a freely licensed example. It does NOT prove the links are valid media resources, authorized CDN endpoints, CORS-enabled HLS, currently reachable, in HD, or compatible with the web player's browser. It also does not prove playback of any movie or show from the user-facing website. No extractor URLs, credentials, or cookie values are logged or returned. Do not promote a candidate into a public stream without source/rights validation and actual browser checks.

## Next technical milestone

1. Define a **device-independent server-side runner** on an isolated Android-compatible machine, not the user's phone and not the Render Node process.
2. Provide an authenticated loopback/local API with an exact media request and per-provider progress updates. The current `MOOSAWeb/cloudstream-engine.mjs` has a server-to-runtime contract but no compatible runner is deployed.
3. Verify provider-returned URLs, HTTP status and browser HLS/MP4 compatibility **for licensed sources**. Header counts show that raw URL transfer is insufficient.
4. Re-test cancellation and time budgets so `loadLinks` does not block the UI; return streaming partial results safely.
5. Only after this end-to-end proof, consider persistent compute/VPS cost, sandbox security and plugin update handling.

In GitHub Actions, the workflow `.github/workflows/cloudstream-full-probe.yml` may be run manually. It builds the disposable `MOOSAProbe` module only with `MOOSA_ENABLE_PROBE=1`, downloads and verifies the two official plugin binaries, and starts the Android emulator. It does not require connecting any user device.
