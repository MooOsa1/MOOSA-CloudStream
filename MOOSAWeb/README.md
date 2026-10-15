# MOOSA Web — v1

Independent Arabic-first movie/TV discovery website with a browser player. The CloudStream plugins remain unchanged.

## Features

- Responsive charcoal UI, trending movies and shows, Indian catalogs, search, and TMDB discovery filters (genre, country, sort).
- Title details, ratings, watchlist and continue-watching saved to this browser.
- MP4, native HLS or HLS.js playback, local SRT/VTT subtitle uploads with +/- 0.5-second offset.
- TV keyboard/remote arrow navigation and top filter panel.
- Without a TMDB key, demo mode provides the openly distributed Big Buck Bunny sample video.

## Start

Requires Node.js 20 or later. In MOOSAWeb run npm start, then open http://localhost:3000.
For the full metadata catalog, set the TMDB_API_KEY server environment variable or TMDB_READ_ACCESS_TOKEN before running npm start.
Get an API key from https://www.themoviedb.org/settings/api.
Run npm test for server/API tests or npm run check for syntax and tests.

## Deploy

Deploy MOOSAWeb as a Node web service or on your own server. Use npm start and the hosting PORT environment variable.
Store TMDB_API_KEY or TMDB_READ_ACCESS_TOKEN as a SERVER SECRET. Use HTTPS in production.
GitHub Pages alone cannot run the backend. This project is source code and is not automatically hosted on a public URL.

## Playback limitations

TMDB supplies metadata and does NOT give playback rights or video streams. The included sample is the open Big Buck Bunny movie for player testing.
You can paste HTTPS MP4/HLS URLs you have permission to play. Other titles need an authorized/licensed provider integration.
CloudStream Android providers cannot automatically be used in a browser: CORS, DRM, authorization, codecs and cookies may block them.
Only local SRT/VTT subtitle file selection is supported in this first web version.

## Security

No private credentials in client JavaScript. Server restricts TMDB API paths; no arbitrary URL fetch or proxy.
Before public launch add auth, per-user rate limiting, monitoring, privacy policy, accessibility testing and licensed content agreements.

TMDB supplies the catalog data. MOOSA Web is not certified or endorsed by TMDB.
