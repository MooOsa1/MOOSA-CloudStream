# MOOSA Hub HTTP-only — confirmed experimental result (19 Oct 2026)

**Production unchanged. No torrent / no P2P / no paid debrid service.**

## Success: real modern film media over direct HTTPS

- [Validated full GitHub Actions run](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39212568603).
- Real **HDHub** HTTPS media, two independent 2025 films. HTTP `206` / MKV containers, distinct first 128 KiB.
- **Sinners (2025):** 1920×1080 H.264 video frames decoded + one-second audio sample decoded from a bounded 10 MiB media prefix.
- **F1: The Movie (2025):** 1920×802 H.264 video frames decoded + one-second audio sample decoded from a bounded 10 MiB media prefix.
- Both had matching name/year hints from the source's metadata. This is not frame-by-frame identification of the entire film or evidence of continuous playback.
- Installed via actual disposable AIOStreams account, **201 created, 200 manifest, 200 stream responses; deleted 200**.
- **Aggregated Sinners: 53 HTTPS media candidates, 0 P2P.**
- **Aggregated F1: 21 HTTPS media candidates, 0 P2P.**
- Only HDHub is enabled as a stream source; Cinemeta provides catalog and OpenSubtitles v3 subtitle discovery.
- Automated provider indexes, not a list of film URLs manually maintained in GitHub.

## Try the install preset (experimental)

[Import MOOSA Hub — Direct HTTP Only](https://aiostreams-nightly.fortheweak.cloud/stremio/configure?template=https%3A%2F%2Fraw.githubusercontent.com%2FMooOsa1%2FMOOSA-CloudStream%2Fexperiment%2Fmoosa-http-only-20261019%2FAIOStreamsHost%2Fmoosa-direct-only.template.json)

1. Choose **Import / Use Template**. Confirm only HdHub is an active stream source; no Torrentio or Comet and no debrid account.
2. Save & Install. Add the resulting private AIOStreams addon URL in your Stremio account; do not share or publish it.
3. On Windows, use Stremio desktop; on iPhone, use **Stremio Web + Outplayer** with external-player mode enabled. It allows HTTPS MKV streams without running a torrent engine or an Android emulator. These are official UI paths, not physical-device confirmation of our particular HDHub films.
4. Try Sinners 2025 and F1 2025 first. Check actual title, picture, sound, seeking, and Arabic subtitles. A response with a large number of candidates is NOT a promise that every item is playable.

## Remaining gates
- **Unverified:** native iPhone end-to-end playback, long continuous playback, reliable seeking in Outplayer, compatibility per server, matching across TV seasons, exact film identity, Arabic subtitle synchronization.
- No guarantee every film works. Free scraper sources often break, switch domains, require cookies, or rate-limit.
- This uses a free third-party hosted AIOStreams community instance, not an MOOSA-owned 24/7 server.
- Avoid serving P2P/torrents from other installed addons: the strict HTTP-only policy applies to the MOOSA Hub addon, not to every unrelated addon in your Stremio account.

## Previously rejected candidates
- Bare PenguPlay returned **identical first 131,072 bytes** for different films (likely a generic instruction/sample clip).
- Showbox bare manifest returned no direct HTTPS media candidates.
- WebStreamrMBG bare manifest: zero streams for both.
- Pipe bare manifest: zero streams for both.
- Sootio bare manifest: configuration required, HTTP 400.
- Separate experimental branch is isolated; main production is unaffected.

**Test code:** `verify-http-only.mjs`, `probe-media-kind.mjs`, `verify-hdhub-modern.mjs`, `end-to-end-http-only.mjs`; all are run under `.github/workflows/moosa-http-only-audit.yml`.
