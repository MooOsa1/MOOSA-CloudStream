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


## Native Arabic source extension (2026-10-14)

**MOOSA Arabic** is an independent CloudStream plugin available from the same repository. Its movie and show listings, original site names, series episode URLs, and media servers come directly from Akwam; it does not use TMDB or cross-provider title matching. Its home sections are Akwam's own `movies?section=29` and `series?section=29`. It is intended for Arabic site content (Akwam's Arabic sections can also contain Arabic-dubbed foreign titles).

**MOOSA Hub v69** no longer exposes Arabic-origin catalog items, categories or Arabic provider registrations. It retains Arabic subtitles for international movies and series. Update and restart CloudStream after installing `MOOSAArabic v1` to refresh provider registration. MOOSA Arabic does not guarantee that every source URL streams on all devices, and quality is not claimed until playback is checked on the device.
