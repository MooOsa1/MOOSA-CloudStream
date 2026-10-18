# MOOSA Hub — Stremio for iPhone and PC

**Current direction: the original AIOStreams application, not MOOSA Web or a CloudStream emulator.**
The checked-in `moosa-hub-template.json` is an importable AIOStreams preset
using automatically queried source indexes, with no paid debrid requirement. It is **not yet proven** to
provide any particular modern copyrighted movie or a playable iPhone source.

## Quick start on the free community instance

[Open the MOOSA Hub setup in AIOStreams](https://aiostreams-nightly.fortheweak.cloud/stremio/configure?template=https%3A%2F%2Fraw.githubusercontent.com%2FMooOsa1%2FMOOSA-CloudStream%2Fexperiment%2Fmoosa-hub-aiostreams-20261018%2FAIOStreamsHost%2Fmoosa-hub-template.json)

Import the preset, review what the providers do, create a configuration,
and choose **Install to Stremio Web** while logged into your Stremio account.

The template currently includes:
- Official Cinemeta metadata catalogs (continuously updated movie/TV metadata).
- Official OpenSubtitles v3 subtitle addon (availability/sync vary per movie).
- Free/P2P mode by default; the debrid service prompt is skipped.\n- Two live-checked free P2P indexes (Torrentio and Comet) which query
  their indexes for each title, *not* a static local list of videos.
- Prefer direct HTTP candidates, then P2P; this does **not** guarantee any direct HTTPS results.\n- Sorting 4K → 1440p → 1080p → 720p; exclude sub-720p and CAM/TS/TC/SCR;
  deduplicate repeated sources.

**Caution:** Indexer results are *candidates*, not tested playable video.\nAn experimental GitHub Actions check at\n[`moosa-stremio-free-sources.yml`](../.github/workflows/moosa-stremio-free-sources.yml)\nqueries current manifests and source listings for two older public-domain film titles.\nIt classifies P2P versus HTTP *candidates*, downloads no video, and does not assert that the films play.\nThe test results reflect GitHub runner network access, not iPhone playback in Oman.
Torrent/P2P streams will **not play in Stremio Web on standalone iPhone Safari**.
A paid HTTPS debrid provider or a companion Stremio Service can provide
different paths, but neither is included here, and license/region matters.
Never infer that a metadata poster or torrent listing means the movie is
available to stream.

## Full native Stremio on iPhone (alternative to Safari)

Stremio publishes its own fully featured sideloadable IPA, which it says
supports torrent and Usenet playback. In Oman, the AltStore PAL regional
store is not available; use SideStore or AltStore Classic to sideload the
official IPA from Stremio's own download page.

- Official release: https://blog.stremio.com/stremio-fully-featured-sideloadable-ipa-release-for-ios-ipados-apple-tv-tvos/
- Official Stremio release index (currently iOS 2.0.8 build 23, Sep 2026): https://dl.strem.io/apple/altstore/source.json\n- First-party Stremio CDN IPA for version 2.0.8 build 23: https://dl.strem.io/apple/2.0.8b23/ios/stremio_iOS.ipa\n- The main Downloads page currently emphasizes regional AltStore PAL, so use the official CDN release/source rather than an unknown mirror.
- SideStore setup: https://docs.sidestore.io/docs/installation/install

SideStore requires initial Apple-ID setup, normally a PC once, and
re-signing every 7 days with a free account. It may ask for local VPN
configuration and Developer Mode. Sideloading is optional and carries more
setup/security risk than using the App Store or Safari. Do not enter an Apple
account password into an unverified installer site.

## Reliability gate before claiming success

On iPhone and Windows, verify the SAME modern title opens with
real video, audio, seeking, and subtitles. The Stremio app being
able to display a source list is not proof of playback. Provider availability
changes independently; **no software can guarantee every current movie
for free**, even when the addon updates itself.

## Deployment

An isolated self-hosting wrapper is in this branch for future authorized
deployment; the earlier Render API request failed/was blocked, so no live
MOOSA-owned AIOStreams server has been created. The public instance is
not controlled by MOOSA, and its operators can impose limits or outages.

Source project (AGPL-3.0): https://github.com/Viren070/AIOStreams

## Live source audits — Oct 18, 2026

- [Torrentio/Comet provider audit](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39157369906) **PASS**. Four IMDb title lookups returned **324 Torrentio P2P candidates** and **2,036 Comet P2P candidates**. Both are now the only active stream providers in MOOSA Hub v1.3.0.
- The same audit found zero MediaFusion P2P candidates for those four titles; its preset was removed, without claiming the provider is permanently offline.
- StremThru Torz returned 404 on direct stream requests and is NOT activated.
- All direct HTTP candidate counts were zero in this audit. On iPhone this means the full native IPA (torrent support) is needed for these candidates; iOS Safari cannot stream P2P unaided.
- These are **source index listings only**. The count includes repeats and can contain dead torrents, wrong matches or non-playable formats. There has been NO actual playback of these titles in iOS/Windows verified by this experiment, and no copyrighted video downloaded.
- Min-quality policy still excludes <720p candidates; HTTP is ranked before P2P when present, then resolution and seeders. OpenSubtitles v3 supplies the subtitle metadata endpoint, but per-film Arabic/English availability/sync is unproved.
- [Latest diagnostics workflow](https://github.com/MooOsa1/MOOSA-CloudStream/actions/workflows/moosa-stremio-free-sources.yml) verifies source metadata, preset policy tests and Stremio's first-party iOS IPA on future branch commits. A workflow pass does NOT prove movie playback.

Do not merge/deploy the old MOOSA Web project from this branch. Only install the AIOStreams preset when ready to test on real devices.
