# MOOSA CloudStream Android-free JVM engine — tested status

_Date: 2026-10-16. All experiments remain off the production Render service._

## Verified live integration

GitHub Actions run [38947728291](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38947728291) passed the whole original-provider JVM test suite.

- Official CloudStream `library:jvmJar` builds and exports `MainAPI`.
- Original SHA256-verified CineStream `.cs3` is translated to JVM bytecode; automatic discovery finds three MainAPI-compatible candidate providers.
- Original SHA256-verified StreamPlay `.cs3` is translated to JVM bytecode; discovery finds 17 MainAPI-compatible candidate classes, of which 14 further classes are unresolved during inventory; candidates are not guaranteed runnable.
- Original CineStream provider can instantiate, search, load verified Sintel metadata by IMDb `tt1727587`, and invoke `loadLinks` on JVM. The original provider returned 12 HLS link references, 6 nominally HD, and 11 subtitle callbacks. All 12 indicated that `Referer` is required. Those links were not proven Safari-playable.
- Original StreamPlay provider can instantiate using the same generic JVM runner. For the tested freely licensed film its `loadLinks` returned success but zero link callbacks.
- **NEW:** An isolated localhost-only `MOOSAJVM/headless-http.mjs` Node bridge spawned a real short-lived JVM subprocess to execute the approved original CineStream class, and returned exact Sintel metadata over an HTTP endpoint. The CI round trip passed its negative-request checks. The result expressly sets `playbackVerified:false` and returns `streams:[]`. See `MOOSAJVM/headless-bridge-ci.mjs`.

## What this proves — and what it does NOT

The MOOSA browser-based front end can, in principle, call real Kotlin/Dex CloudStream provider behavior through an Android-free JVM worker on the server. This is more than running Node-only source clones.

However, **it does not prove that arbitrary CloudStream repositories run unmodified, that a 24/7 free JVM host exists, or that browser playback is available**. The active experiment only lets one approved original plugin and a single authorized test movie pass through the HTTP adapter. Untrusted `.cs3` files contain executable code. `URLClassLoader` is not a sandbox.

The `CineStream` HLS manifests returned valid-looking URLs while the first media fragments failed HTTP 403 in earlier tests; these must not be accepted as video servers. There is no cookie, WebView, DRM, or other protection bypass.

## Remaining launch gates

1. Per-plugin OS/container isolation and network/CPU/memory controls; no executing arbitrary third-party `.cs3` from a public request.
2. Bounded repository installation with trusted original repository metadata, checksum enforcement and operator approval.
3. Generic, version-aware compatibility shims for only the headless CloudStream API functionality that is safe to support.
4. Stable, separately hosted JVM worker reachable from the existing MOOSA Node backend; benchmark memory against service limits.
5. Authorized video first-fragment and CORS checks, codec compatibility, exact movie/episode identity, and an actual Safari playback test.
6. Only then expose installed extension controls through the MOOSA Web UI.

Do not merge the experiment or enable it as a public source provider merely because the CI metadata tests pass.
