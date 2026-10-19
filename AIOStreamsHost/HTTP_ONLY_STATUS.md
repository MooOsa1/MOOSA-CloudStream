# MOOSA Hub — strict HTTP-only audit (19 October 2026)

## User requirement
- Use current films and series, iPhone and PC, **no torrents or debrid**.
- No manually maintained film-link list; use updateable upstream provider indexes.
- Do **not** represent HTTPS link or HTTP 206 alone as playable movie.
- Arabic subtitles preferred, then English. These have not been verified here.

## Results proven in GitHub Actions
- `verify-http-only.test.mjs`: 2/2 tests passed, strict source rejection for infoHash/magnet/P2P.
- PenguPlay bare `/manifest.json`: returned HTTPS 206 MP4 bytes for **Sinners (2025)** and **F1 (2025)**, but **the first 131,072 bytes were exactly identical**. No movie identity or native playback was proven. Treat it as **invalid/likely setup/sample media**, not a success. Fail the audit instead of pretending it worked. [Evidence](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39211001600).
- Showbox bare manifest: no direct HTTP candidates for either title.
- The earlier successful 10-second video/audio tests used a P2P Stremio engine; they do **not** satisfy HTTP-only or iPhone Safari.

## Additional public HTTP provider scouting
- `scout-http-providers.mjs` probes WebStreamrMBG and Pipe (and checks HDHub/Sootio). Keep results exploratory until a **configured** provider returns distinct actual media that decodes correctly.
- A working manifest, stream count, MP4 header, HLS playlist, or byte-range support is **not** evidence that a specific complete film plays.
- A third-party stream may require an authenticated, configured addon URL, cookies, referer headers, or a proxy. None of that is automatically granted by a public `/manifest.json`.

## What is left before promotion
1. Identify a free, legal-to-access configured HTTP provider that updates its own links.
2. For **two different modern films** obtain distinct media bytes and validate film identity, correct audio/video, and working seek.
3. Test 10+ minutes of playback and subtitle synchronization **on actual iPhone Safari** and Windows.
4. Only then promote to main. **No changes to deployed production MOOSA Web** before success.

## Branch and current test
- Branch: `experiment/moosa-http-only-20261019`
- CI: `.github/workflows/moosa-http-only-audit.yml`
- Existing preset `moosa-direct-only.template.json` is **candidate-only**. Never advertise it as a ready-to-watch addon.
