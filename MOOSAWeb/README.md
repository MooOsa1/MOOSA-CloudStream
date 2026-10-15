# MOOSA Web — v1

Independent Arabic-first movie/TV discovery website with a browser player. The CloudStream plugins remain unchanged.

## Features

- Responsive charcoal UI, trending movies and shows, Indian catalogs, search, and TMDB discovery filters (genre, country, sort).
- Title details, ratings, full TV season/episode selection, watchlist and continue-watching saved to this browser.
- MP4, native HLS or HLS.js playback, local SRT/VTT subtitle uploads with +/- 0.5-second offset.
- TV keyboard/remote arrow navigation and top filter panel.
- Without a TMDB key, demo mode provides the openly distributed Big Buck Bunny sample video.

## Start

Requires Node.js 20 or later. In MOOSAWeb run npm start, then open http://localhost:3000.
For the full metadata catalog, set the TMDB_API_KEY server environment variable or TMDB_READ_ACCESS_TOKEN before running npm start.
Get an API key from https://www.themoviedb.org/settings/api.
Run npm test for server/API tests or npm run check for syntax and tests.

## Optional authorized playback sources

The movie catalog is sourced from TMDB and does **not** supply streaming links. The demo film works without any setup.

For films or TV episodes that you own or are authorized to stream, copy `sources.example.json` to `sources.local.json` inside MOOSAWeb and replace the example links with your own HTTPS MP4 or HLS streams. The source registry is server-side, excluded by `.gitignore`, and does not proxy remote streams. Sources are indexed by `movie:TMDB_ID` or `tv:TMDB_ID:SEASON:EPISODE`. Only safe playback types and HTTPS URLs are returned to the browser. The optional environment variable `MOOSA_SOURCES_FILE` allows a different location for the registry; set it in your server configuration if your hosting file system is ephemeral.

**Example:** use `movie:12345` to attach links to TMDB movie 12345, or `tv:123:1:2` for season 1, episode 2 of TV series 123. Never commit private API tokens or copyrighted content sources.

## Deploy

You can deploy the `MOOSAWeb` folder on a Node hosting provider. This repository includes an optional root-level `render.yaml` blueprint that creates a web service on Render. Connect the GitHub repository in your hosting dashboard and configure `TMDB_API_KEY` or `TMDB_READ_ACCESS_TOKEN` as a **server secret**. The environment variable `PORT` is supplied by many hosting providers.

Use `npm start` and serve the site over HTTPS. GitHub Pages alone cannot host this dynamic Node API.

**A live public domain is not automatically created by committing these files.** The hosting account must be connected and the deployment initiated by its owner.

## Playback limitations

TMDB supplies metadata and does NOT give playback rights or video streams. The included sample is the open Big Buck Bunny movie for player testing.
You can paste HTTPS MP4/HLS URLs you have permission to play. Other titles need an authorized/licensed provider integration.
CloudStream Android providers cannot automatically be used in a browser: CORS, DRM, authorization, codecs and cookies may block them.
Only local SRT/VTT subtitle file selection is supported in this first web version.

## Security

No private credentials in client JavaScript. Server restricts TMDB API paths; no arbitrary URL fetch or proxy.
Before public launch add auth, per-user rate limiting, monitoring, privacy policy, accessibility testing and licensed content agreements.

TMDB supplies the catalog data. MOOSA Web is not certified or endorsed by TMDB.
