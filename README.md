# MOOSA CloudStream

This repository publishes **only two CloudStream extensions**:

- **MOOSA Hub**: international films and series, with Arabic subtitle support.
- **MOOSA Arabic**: a separate native Arabic provider whose catalog, search, series, episodes and links come directly from Akwam. It does not use TMDB or cross-site title matching.

## Add repository to CloudStream

`https://raw.githubusercontent.com/MooOsa1/MOOSA-CloudStream/main/repo.json`

The repository does **not** include or import third-party extension directories or the official recloustream extension list. Installed extensions from other repositories are managed separately in the CloudStream app.

## Verification

Successful build and published manifest are required before claiming a release is available. Actual Fire TV playback, link responsiveness, and quality may differ by source and location.

## MOOSA Arabic v2 — 2026-10-14

The Arabic extension remains **Akwam-native**. It does not use TMDB, Google Translate, or cross-provider film/episode matching.

- Four organized shelves: newly listed Arabic films and shows, plus separately labeled Arabic-dubbed films and shows. Shelf URLs are the site's existing Arabic section (section=29); dubbed titles are recognized only from explicit words such as "مدبلج" on the source.
- Native paged search, with one time-bounded Akwam-only recovery of season-one from the source's related-season navigation.
- Deduplicated native episode links, ascending episode order, and seasons read from original Akwam titles such as "العتاولة 2" and "اللعبة ج5".
- Better lazy-loaded artwork and bounded page/extractor requests.
- Streams require a reported resolution of at least 720p; explicit low-quality labels are not silently promoted. Akwam's known highest-quality tabs are explored first, and download-labeled entries are excluded from playback.
- Repository output still contains **only MOOSAArabic v2 and MOOSAHub v69**.

### Verification and limitations

An isolated pre-release CloudStream Gradle build succeeded. Live metadata checks found 30 unique correctly numbered episodes for each tested sample: "جعفر العمدة" season 1 and "اللعبة" seasons 1 and 5. Native catalogs, paged search, and episode pages passed. Several server samples returned HTTP 206 and MP4 signatures from tiny ranged requests. These do not prove uninterrupted playback, actual 1080p/4K encoding, or Fire TV performance everywhere.

## MOOSA Arabic v3 — native dubbed-category correction (2026-10-14)

MOOSA Arabic keeps **one direct Akwam backend**, no TMDB title matching and no machine-translated names.

- Native Arabic movie/series sections use section=29 (explicit "مدبلج" titles are excluded from their Arabic-original shelves).
- The dubbed movie/series shelves use **Akwam's own category=71**, not filtering section=29 by title text. This includes source-tagged dubbed productions that do not say "مدبلج" in the title.
- Live smoke checks: page 1 showed **30 dubbed movies** and **24 dubbed series**, and both second pages contained additional cards.
- Stream discovery retains high-to-low source quality tab order: tab-5 (1080p), tab-4 (720p); quality below 720p is hidden.
- Small-byte live server probes on one movie and one series episode returned **HTTP 206** and MP4 file signatures on Downet over both HTTPS and HTTP. HTTPS is shown first, and any HTTP fallback is labelled as unencrypted. No random unsupported hosts were added.

### Dubbed-content playback regression (2026-10-14)

In addition to the standard Arabic samples, a separate CI smoke test now selects a **dubbed movie and dubbed series episode directly from Akwam category=71**, follows the original item/episode pages, and requests only 64 bytes from the candidate MP4 transport. Both returned valid `HTTP 206` ranges and MP4 signatures **over unencrypted HTTP** (Downet hosts `s201d1` and `s205d1`). The HTTPS attempts for those particular dubbed samples failed in the CI environment. Thus HTTP fallback is important but is explicitly labeled unencrypted. These results do not prove end-to-end playback, sustained throughput, or the actual video dimensions.

### Limits

A valid first-byte server result does not prove actual video width/height or sustained streaming performance on Fire TV in Oman. The website may change categories, hosts and CDN availability later. Tested pages and server candidates are not guarantees for every title.


## MOOSA Hub v101 — opt-in CloudStream → MOOSA Web Bridge

The extension includes an optional **Web Bridge** in MOOSA Hub → Settings. Once a user pairs their device with MOOSA Web, the website can request stream discovery from compatible, enabled CloudStream playback providers installed on that user's Android/Fire TV device. This includes third-party extension repositories where a compatible provider is installed.

CloudStream remains the execution environment for those providers: **the Render Node service does not load .cs3 extensions**. Each lookup starts only after the user presses Play, is matched to the requested title and exact TV season/episode, and returns only declared HD (720p+) HTTPS candidates to the site. Provider quality metadata, connectivity, and browser playback capability can differ. Some streams require headers/CORS/DRM unavailable to the browser, and will not work automatically.

To pair, open the MOOSA Web CloudStream button, copy the 14-character code, and enter it in CloudStream → MOOSA Hub → Settings → Web Bridge. Keep the Android app running. See [MOOSA Web Arabic setup instructions](MOOSAWeb/README_AR.md) for limitations, including browser compatibility and how to disconnect.
