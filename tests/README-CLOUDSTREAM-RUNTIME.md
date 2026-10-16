# CloudStream original .cs3 Android smoke test

**Result (2026-10-16): Both original extensions registered successfully in the original Android CloudStream application, in independent GitHub Actions emulators.**

[Successful CI run 38863704162](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38863704162)

- CineStream: `PluginInstance: Adding CineStream (https://cinemeta-catalogs.strem.io) MainAPI`
- StreamPlay: `PluginInstance: Adding StreamPlay (NONE) MainAPI`

The test downloads the official CloudStream prerelease APK and the original .cs3 from their maintainers' public repositories, verifies the plugin's **SHA-256 hash against its repository index**, installs each extension into the Android app's local plugin directory, starts CloudStream, and verifies actual `MainAPI` registration from the app's Android log. Two isolated Android emulator instances run independently.

**What this proves:** original unmodified Android plugins can load and register providers in a disposable server-hosted emulator without pairing the end user's device. It also validates authenticity against the repository's declared hash.

**What this does NOT prove:** plugin search, metadata matching, `load`, `loadLinks`, valid streams, browser compatibility, licensing, playback, reliability, or suitability for 24/7 operation. GitHub Actions is a temporary test runner **not a permanent streaming server**. No production site feature or Render service configuration is changed.

## Next test acceptance criteria

1. In the emulator, invoke `search` for a rights-authorized test media title.
2. Verify `load` produces a media response matching the exact title/IMDb/TMDB identifier and season/episode.
3. Invoke `loadLinks` and record any legal, browser-playable test stream with source, declared quality, and language metadata. A provider returning zero results is a valid test result.
4. Test authorized media playback separately in MOOSA Web via HTTPS/HLS/MP4. Do not assume that a CloudStream extractor URL will play in a browser; some require headers, DRM, cookies or origin permissions.
5. Test sandbox resource limits and abuse resistance before considering a VPS. Third-party plugins execute arbitrary extension code; keep all tests in disposable, isolated runner environments.

The test workflows under `.github/workflows/cloudstream-android-smoke.yml` target the dedicated `experiment/android-cs3-execution-smoke` branch or a manual workflow dispatch. To rerun, use the GitHub Actions interface. Do not copy downloaded APK/.cs3 files to the public web root.

As with any third-party extension, only use media sources the operator and viewers are authorized to access and play.
