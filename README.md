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
