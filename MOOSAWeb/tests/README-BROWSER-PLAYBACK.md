# Browser video playback proof — MOOSA Web

Run in isolated GitHub Actions: `.github/workflows/moosa-browser-playback.yml`.

## What is actually tested

- Generate a short synthetic 320×180 H.264/AAC MP4 using ffmpeg. No externally sourced video or stream links.
- Derive a real HLS VOD manifest plus MPEG-TS segments from that same video.
- Open the actual MOOSA Web player in **Google Chrome** via Playwright and press its video-demo button. Use a browser-local interception of the demo URL to supply the synthetic MP4. Assert decoded video dimensions, media readyState, and currentTime > 0.18s (not just button visibility).
- Enter an HTTPS HLS link into the player's custom-link input. Intercept this test-only hostname to serve the synthetic manifest and segments with CORS; assert decoded video frame playback.
- Deliberately provide an inaccessible HLS link, and assert the UI displays an honest error instead of incorrectly saying the video is ready.

Video data is only generated in the CI runner; nothing is committed to the repository or deployed to Render. The transient Playwright/hls.js packages installed during testing are not production dependencies.

## First browser finding

The initial unbranded Chromium test did not play the H.264 MP4. We switched the test to branded Chrome, which supports H.264/AAC, and confirmed both MP4 and HLS decoded and advanced playback in [run 38870729563](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38870729563). That run subsequently failed its **broken HLS error-message** assertion: the player showed 'ready, press play' after a decode failure. The player now differentiates autoplay blocking from HLS/network/format errors; later passing run required before claiming this is fixed.

## What this does NOT prove

- This does **not** exercise the CineStream/StreamPlay plugins or verify any of their returned URLs.
- Chrome playback cannot guarantee Safari/iPhone or Fire TV playback; the codecs and native-HLS paths differ.
- A stream that requires browser-inaccessible headers, `Referer`, login cookies, DRM, signed URL expiry, or CORS may fail even when this synthetic test passes.
- The Android CloudStream test engine is not a permanent deployed backend. No VPS is provisioned and the website is not directly using Android .cs3 plugins.

## Production acceptance criteria

First confirm this synthetic baseline. Then use a source for which video rights, origin permissions and the full URL are verified to perform a real browser smoke test against a **running authenticated** headless engine. Explicitly record whether the stream needs custom headers or unsupported browser features before offering it to site visitors.
