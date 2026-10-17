# MOOSA CloudStream JVM compatibility: evidence and limitations (2026-10-16)

## Purpose
Run original CloudStream extension DEX providers in a headless Java Virtual Machine without an Android OS. Do not alter MOOSA Web production until actually playable browser video passes an independent verification.

## Verified tests
- Build the official CloudStream Kotlin Multiplatform JVM library with Java 21; it exposes MainAPI and TmdbProvider.
- SHA-256 verification of downloaded original CineStream and StreamPlay cs3 binaries against the authors' published plugin indexes.
- Convert CineStream to 877 JVM classes, StreamPlay to 2,060 classes with dex2jar 2.4 and a true 4 GiB converter heap.
- Load and instantiate the original CineStreamProvider class on JVM and call real original getters without Android.
- Execute the original CineStream search: 22 catalog results for Sintel, but zero exact-title matches. Do not trust search alone.
- Execute the original CineStream load using IMDb tt1727587: correct Sintel MovieLoadResponse.
- Execute CineStream loadLinks in an isolated JVM process using only a minimal, restricted provider-specific settings adapter: 12 sources, six nominally HD, 11 subtitle callbacks.
- Classify the resulting links: 12 HLS sources, all indicate a Referer requirement and all share one upstream media host.
- Test only three sample HLS masters and first video fragments for licensed Sintel: manifests HTTP 200, first media fragments HTTP 403 and HTML without protected headers. These are NOT verified browser-playable.

## Approach
1. Convert verified original cs3 classes.dex to Java bytecode.
2. Load the official CloudStream JVM library and runtime dependencies.
3. Instantiate the original provider class directly rather than the Android Plugin.load(Context) entrypoint.
4. Use an isolated minimal settings adapter for one selected non-DRM HTTP provider; never inject cookies, tokens or browser challenges.
5. Invoke original load and loadLinks and collect metadata only in CI.
6. Require identity, allowed media origin, first media fragment, codecs and eventual real Safari playback before returning a player source.

## Important limitations
- Dependency inventory of the entire original CineStream binary found 92 unresolved types (74 Android framework types and 18 CloudStream-app-only types). Many are UI-only; they do not all have to be implemented for a headless provider.
- Generic installation and execution of any arbitrary CloudStream cs3 extension has NOT been implemented or proven.
- StreamPlay direct JVM instantiation is being evaluated independently; DEX conversion alone proves nothing about execution.
- Existing Render production is Node.js, not a hosted Java worker. Java runtime availability, resource usage, crash recovery and sandbox isolation must be proven first.
- Successful original extractor execution does NOT ensure the resulting video is legal, reachable, usable on Safari, or free of protection.

## Security
A cs3 extension is executable third-party code. Never enable arbitrary uploads in a public server. Require verified and trusted extension sources, per-process isolation, constrained CPU/memory, restricted network destinations and careful secret handling. Never spoof privileged authentication, use stolen cookies, bypass DRM or Cloudflare challenges, or proxy inaccessible media.

## Reproduce
The isolated experimental branch has its JVM source probes in MOOSAJVM and the workflow at .github/workflows/cloudstream-jvm-linkage-20261016.yml. These tests retrieve verified original plugins at run time and do not check in vendor binaries or expose full signed media URLs.

Do not enable these experimental components in MOOSA Web production until independent playback and safety tests pass.
