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
- Free/P2P mode by default; the debrid service prompt is skipped.\n- Two popular Stremio stream indexers (Torrentio and MediaFusion) which query
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

## Current factual source check (Oct 18 2026)

[GitHub Actions source diagnostic run](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39155821553) **PASS**:
- Torrentio manifest valid. Returned 39 P2P candidate entries for IMDb tt0063350 and 23 for tt0032599.
- MediaFusion manifest valid but returned zero entries for both old film IDs at this time; do not infer a permanent provider outage.
- **Total 62 P2P candidates and zero direct HTTP links** across those two titles, from the test runner's location.
- All three Node classification tests passed. **No video files were fetched, verified, or played.** The discovery pass is not proof that the movies or subtitles work on an actual iPhone/PC.

A subsequent test also checks *Sinners (2025)* and *Dune: Part Two (2024)* listing metadata and checks the official iOS release index. Listing checks do not download copyrighted content or establish availability.
