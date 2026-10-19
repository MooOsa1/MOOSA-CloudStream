# MOOSA Hub — Free Stremio add-on for iPhone + Windows

**Branch:** `experiment/moosa-hub-aiostreams-20261018` · **Draft PR:** [#73](https://github.com/MooOsa1/MOOSA-CloudStream/pull/73). The original CloudStream / MOOSA Web code on `main` is unchanged.

This experiment reuses **AIOStreams** to aggregate changing Torrentio + Comet indexes under one Stremio add-on. No paid debrid service, manual film-link lists, or custom Android emulator is required. It is **not** a guarantee every film works.

## What is actually proven — October 19, 2026

1. [Live Chromium UI import](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39159100503) passed: site and template HTTP 200, "MOOSA Hub" present, no page errors.
2. [Installed Stremio add-on API end-to-end](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39159538831) passed: disposable user created (201), real add-on manifest loaded (200), `stream/movie` returned actual source metadata (200); account deleted.
3. **Modern actual media:** [Sinners (2025) and F1: The Movie (2025)](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39193125979) **both successfully decoded video frames and audio** from real P2P sources via the official Stremio stream engine on a Linux test runner. Sinners: 1920×800; F1: 1920×804. F1 needed a different source after two candidates failed the short audio check.
4. [Seek transport](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39193534596) passed for both 2025 films: a request starting at 30% of the file returned 65,536 bytes as expected via HTTP 206. **Those middle frames were not decoded.**
5. [Longer continuous decoding test](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39194000714) checks >=9 reported output seconds and >=150 decoded video frames from the bounded beginning of each file. Check actual job logs before calling this pass or failure.
6. Windows [automatic configuration installer](./Install-MOOSA-Hub.ps1) succeeded in a disposable Windows CI run. This checks the add-on manifest and available sources, **not a native Windows video window**.

**Never conflate** Linux FFmpeg test decoding with verified iOS/Windows viewing. Native app playback, film identity beyond source title/year hints, subtitle synchronization, hour-long playback and reliable automatic P2P failover **remain unverified**.

## Install the preset

[**Open MOOSA Hub in AIOStreams (free community instance)**](https://aiostreams-nightly.fortheweak.cloud/stremio/configure?template=https%3A%2F%2Fraw.githubusercontent.com%2FMooOsa1%2FMOOSA-CloudStream%2Fexperiment%2Fmoosa-hub-aiostreams-20261018%2FAIOStreamsHost%2Fmoosa-hub-template.json)

Load the template, review settings, **Save & Install**, then install the add-on in your Stremio account. Log into the same Stremio account on iPhone and PC to sync installed add-ons.

**Windows:** Alternatively download and review [`Install-MOOSA-Hub.ps1`](./Install-MOOSA-Hub.ps1), then execute locally in PowerShell. It creates a private AIOStreams user only after fetching the preset; verifies a current **2025 Sinners** Stremio stream listing; copies the **private manifest URL** to your clipboard for Stremio; and encrypts the saved API password with your Windows user's DPAPI. It reuses the same installation on future runs. Do not share this private URL. Only run code you have read and trust. No PowerShell execution policy bypass is required by the script itself.

**iPhone:** Standalone iPhone Safari does **not** provide a P2P torrent engine. Stremio publishes a [first-party sideloadable IPA](https://blog.stremio.com/stremio-fully-featured-sideloadable-ipa-release-for-ios-ipados-apple-tv-tvos/) that supports native torrents. [First-party IPA](https://dl.strem.io/apple/2.0.8b23/ios/stremio_iOS.ipa) (verified available October 18). With a free Apple account, [SideStore setup](https://docs.sidestore.io/docs/installation/install) generally requires initial PC setup and re-signing; check current availability and security guidance. No iPhone physical-device test was performed here.

## Current preset logic

- **Providers:** Torrentio + Comet (free P2P). Cinemeta catalog and OpenSubtitles v3 subtitle data.
- **Quality:** ignore resolutions below 720p and CAM/TS/TC/SCR; resolution/seeders sorting; prefer HTTP if a source really exists, then P2P.
- **Languages:** prefer Arabic/English subtitle metadata, Arabic first. **This is a preference, not a promise of properly synced Arabic subtitles.**
- **Updates:** source providers search their own continually changing indexes; the GitHub preset file is *not* a hand-maintained film-link list.
- **Weakness:** the upstream AIOStreams owned-stream failover system is built for debrid/usenet, **not arbitrary P2P torrents**. The GitHub test harness automatically tries multiple sources, but **native Stremio cannot be assumed to switch on stalls**.
- **Hosting:** this uses a community nightly instance that MOOSA does not own; outages and free-instance restrictions are possible. No MOOSA-owned 24/7 hosted server is claimed.

For raw provider counts and individual failures consult [the actual CI logs](https://github.com/MooOsa1/MOOSA-CloudStream/actions/workflows/moosa-stremio-free-sources.yml). Counts of torrent candidates alone are not playback success.

Upstream project: [AIOStreams](https://github.com/Viren070/AIOStreams) (AGPL-3.0). Keep the experiment isolated until iOS and Windows physical-device playback is verified.
