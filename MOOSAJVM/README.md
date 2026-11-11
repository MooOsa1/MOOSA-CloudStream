# MOOSA headless CloudStream JVM engine — verified feasibility

This is an **experimental, non-production, Android-free JVM compatibility
proof**, intentionally isolated from the main MOOSA Web Node.js server.
It is not a functioning universal .cs3 repository host yet. Original
extension binaries are treated as untrusted executable code.

## What is proven by live GitHub Actions, 16 October 2026

1. The actual official CloudStream \`:library:jvmJar\` builds without an
   Android emulator and provides \`MainAPI.class\`.
2. The SHA256-checked published original \`CineStream.cs3\` DEX converts
   to 877 JVM classes; \`StreamPlay.cs3\` converts to 2,060 using a 4GiB
   dex2jar heap.
3. The original converted \`CineStreamProvider\` instantiates and exposes
   its correct name and supported types on the official JVM runtime.
4. Its original \`search\` returns catalog results, and original \`load\`
   returns the exact \`Sintel\` film by IMDb ID \`tt1727587\`.
5. Original \`loadLinks\` returns success with source + subtitle callbacks.
   This is **native original code execution**, not web-stremio emulation.
   With only the configured single original VaPlayer provider it returned
   12 source references, 6 reporting >=720p, 11 subtitle references in
   one observed run. Counts are variable.
6. The emitted HLS references all required \`Referer\`, and first segment
   checks on the first three returned HTTP **403**. Therefore **none of
   these sources were proven playable in Safari**; counts are not success.
7. The converted original \`StreamPlay\` class instantiates on JVM. Its
   \`loadLinks\` test returned \`true\` with zero sources for the chosen
   licensed test input. No playable StreamPlay source is proven.
8. Original CineStream DEX depends on 92 absent class names in the
   official JVM classpath (74 \`android.*\`, 18 app-only CloudStream).
   The current shims only provide selected symbols, not Android behavior.

## Architecture intended

\`repo/plugins.json\` -> select explicit trusted repository entry ->
verify upstream SHA256 -> read \`.cs3\` archive \`classes.dex\` -> convert
DEX to JVM bytecode offline -> inspect provider classes automatically
through \`PluginProviderDiscovery\` -> spawn a restricted separate JVM
process -> call \`search\`, \`load\`, \`loadLinks\` ->
validate returned title/IMDb/episode and permitted HTTPS video ->
check first media segment + browser CORS -> offer only verified streams.

**Untrusted .cs3 binaries must never execute in the existing MOOSA
Web Render process**, nor in a shared unrestricted JVM. Future runtime
needs child-process/container isolation, per-plugin network/CPU/memory
limits, signatures/hashes, logging redaction and clear permission
boundaries. \`ClassLoader\` itself is NOT a security sandbox.

## Verified technical files

- \`ProviderSearchProbe.java\`, \`ProviderLoadProbe.java\` and
  \`ProviderLinksProbe.java\`: real original CineStream method calls.
- \`StreamPlayClassProbe.java\`, \`StreamPlayLiteProbe.java\`: real
  StreamPlay binary probing.
- \`PluginProviderDiscovery.java\`: enumerate concrete \`MainAPI\`
  implementations without running plugin constructors.
- \`HlsSourceProbe.java\`: consented first HLS segment + CORS checks,
  no protected cookies, fabricated Referer, DRM workarounds or proxy.
- \`shims/\`: **test-only** narrow compatibility symbols, not a full
  reimplementation of Android.

Run \`.github/workflows/cloudstream-jvm-linkage-20261016.yml\` for
the full signed-original-plugin compatibility suite.

## Remaining, not yet proven

- Generic \`.cs3\` manifest installer and dependency resolution.
- Reliable automatic loading/execution of **arbitrary** extensions.
- Safe persistent session and preferences for supported adapters.
- A production headless HTTP worker bridge for MOOSA Node.js.
- Safari-playable verified streams beyond existing authorized sources.
- Sufficient CPU/RAM on the free Render Node hosting tier.

Do not enable a "Run any CloudStream repository" UI until these
boundaries have been implemented and exercised end-to-end.
