# MOOSA CloudStream

CloudStream repository for Fire TV Stick 4K.

## Sources
- Official extensions: https://github.com/recloudstream/extensions
- Custom plugins: `plugins.json` (currently empty)

## Content goals
Free and lawful sources for foreign, Arabic and Indian films and series (old and new), original audio, Arabic subtitles, 1080p and 4K where available.

## Verification record — 2026-10-14 (v68)
- Repository installation on Fire TV: **previously confirmed by user**
- Build pipeline: **must pass for the v68 commit before release is confirmed**
- Fire TV playback of v68, actual displayed 1080p/4K, Arabic subtitle synchronization: **not yet verified**
- Display names: familiar English TMDB titles for non-Arabic productions; Arabic-script titles for Arabic productions where sourced from TMDB localization or original Arabic metadata. No Google Translate.
- Arabic lookups: episode-specific queries, real TopCinema WordPress pagination, corrected Akwam episode parsing, and independently checked source identity.
- Exact-title metadata probes (2026-10-14): the tested Akwam, TopCinema and MySeed searches did not return a confirmed `هوجان` item. TopCinema returned `فرصة ثانية للحب` for `فرصة ثانية`, which is **not** automatically considered the same series.
- CDN measurements are samples, not stream-stability guarantees. A passing build or a listed 1080p quality tab does not prove 1080p playback.
- No source is guaranteed to contain every film or episode. The resolver must not substitute unrelated titles to make the list look populated.

## Add to CloudStream
Settings > Extensions > Repositories > Add Repository

`https://raw.githubusercontent.com/MooOsa1/MOOSA-CloudStream/main/repo.json`

## Before adding any provider
Check source rights, Fire TV compatibility, playable titles, original audio, Arabic subtitle sync, quality and fallback servers. No server reliability is guaranteed.


## TMDB attribution
MOOSAMovies uses TMDB metadata through CloudStream's built-in TMDB provider.

This product uses the TMDB API but is not endorsed or certified by TMDB.
