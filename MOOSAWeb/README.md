# MOOSA Web — browser-first cinema platform

A **separate, dependency-light Node.js web app** for MOOSA: responsive movie/TV discovery, Arabic UI, real TMDB metadata, and an HTML5 player for **authorized sources**. The original MOOSA CloudStream plugin is unchanged.

## Requirements

- Node.js 20 or newer. No `npm install` needed.
- A valid [TMDB](https://www.themoviedb.org/settings/api) API v3 key or read-access token to load the **real catalog**.
- HTTPS video sources that the browser supports and that the owner authorizes you to play. Cross-origin HLS and subtitle downloads need permissive CORS headers.

## Start

```bash
cd MOOSAWeb
cp .env.example .env
# Edit .env to set TMDB_API_KEY or TMDB_READ_ACCESS_TOKEN
node --env-file=.env server.mjs
# Visit http://localhost:3000
```

Alternatively export `TMDB_API_KEY` in your environment and run `npm start`.

## Current features

- Real TMDB shelves: trending movies and series, highest-rated films, trending Indian cinema.
- Search, genuine server-side genre/type/country/order filters with pagination; responsive, remote/keyboard-friendly cards.
- Actual TMDB titles, backdrops, ratings and details (not random synthetic results).
- Favorites/watchlist persisted **only on your device** via localStorage.
- Watch dialog with selectable authorized source, MP4/WebM, native HLS (Safari), or hls.js for HLS-capable browsers. Hls.js 1.6.15 is loaded on demand from jsDelivr; the browser needs access to that CDN when using HLS without native support.
- HLS quality selector where provided by source; Arabic/English subtitle dropdown; local/remote SRT-to-VTT; offset control ±15 seconds; local video and subtitle files.
- Optional CC0 sample video from MDN to verify the player is working without associating it with any catalog movie.

## Adding videos you have rights to show

Edit `data/streams.json` (or point `STREAMS_FILE` to a JSON file). Example:

```json
{
  "movie:12345": [
    {
      "name": "Official HLS (1080p)",
      "url": "https://your-licensed-domain.example/media/movie.m3u8",
      "subtitles": [
        {"language":"ar", "source":"Licensed provider", "label":"Arabic", "url":"https://your-licensed-domain.example/subs/ar.vtt"}
      ]
    }
  ]
}
```

Keys use `movie:<TMDB ID>` or `tv:<TMDB ID>`; the manifests are **not** generated from CloudStream plugin sources. Add only streams permitted for browser playback. There is **no proxy to bypass licensing, authentication, CORS or DRM**. The stream list is returned through `/api/streams`, so don't put confidential bearer URLs in public manifests. For private access use an authenticated backend in a later phase.

For the local video demo, open **تجربة المشغل** and click **تشغيل فيديو تجريبي (CC0)**. Other titles **are not guaranteed to have watchable streams** until configured.

## Deployment

Host the Node server on a provider that supports a Node 20+ runtime (Render, Railway, a VPS, etc.), set `TMDB_API_KEY` or `TMDB_READ_ACCESS_TOKEN` as a **secret environment variable**, and attach a custom domain with HTTPS. **GitHub Pages alone cannot host this TMDB-proxy backend.** A live public URL is not provisioned by the source code alone.

`npm run check` runs JavaScript syntax checks and backend tests. No TMDB secret is committed to the repository. This is an MVP, not yet a multi-user service with accounts, access controls, DRM, or rights management.

### Accessibility & speed

The UI respects `prefers-reduced-motion`, lazily loads poster images, has a keyboard/remote direction algorithm and avoids a large frontend JavaScript framework. Actual speed still depends on device and poster/network conditions.

**Notice:** This website uses TMDB data but is not endorsed or certified by TMDB. Video playback requires legitimate access rights.
