# MOOSA Web — free verified web-source milestone

This is a **Node.js-only** path that runs on the existing Render free service. It does not execute original Android `.cs3` extensions or require phone pairing, Oracle, a VPS, or a paid API.

## Deployed functionality (after this PR is merged and Render redeployed)

- `MOOSAWeb/commons-provider.mjs`: independently queries Wikimedia Commons for candidate licensed movies matching the original title, checks the **exact file name**, file MIME type, a video file on `upload.wikimedia.org`, full movie filename (no trailer/excerpt), minimum 720p, and **explicit allowlisted Creative Commons/public-domain deed in Commons file metadata**. Denies unknown licence, unsafe external URLs, embeds, local/intranet hosts, unrelated titles or unverified streaming "quality".
- `/api/resolve` searches this provider alongside the existing Internet Archive, operator-configured licensed web addons and an optional remote Android engine (unconfigured on Render). Results are cached and deduplicated.
- `/api/free-film`: one hardcoded, separate legal test title **Big Buck Bunny** from Blender Foundation, with licence attribution, cached for 5 minutes. This is a fixed demonstration, never a stream falsely matched to another title.
- In the player, a new button **"تجربة فيلم كامل مجاني (Big Buck Bunny)"** fetches this source and clearly changes the player title to the open-licensed demonstration movie. A link to the original Wikimedia Commons file details and plain-text attribution appears when this source is playing.
- When 720p and multi-gigabyte 4K variants are both found, the browser defaults to the lighter 720p for faster startup. The original higher-quality file remains selectable.
- No video is downloaded/proxied by the site; player fetches media directly from Wikimedia, so browser codec compatibility remains relevant.

## Real upstream evidence (2026-10-16)

[Successful Wikimedia API / media HEAD run 38875613439](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38875613439)

- Retrieved **two** exact-name, CC BY 3.0-licensed complete Big Buck Bunny video files, **720p and 2250p**.
- 720p direct WebM source: HTTP **200**, `Content-Type: video/webm`, `Access-Control-Allow-Origin: *`, Content-Length **93,192,761 bytes** (~93 MB).
- 4K source about 2.96 GB, **not** the default.
- The API/HEAD test proves discovery and remote file reachability, **not full movie playback on an iPhone**.
- Playwright mobile-sized UI CI proves that the new button shows the correct title, prioritizes HD and preserves licence attribution, but uses synthetic source responses and does not download video.

## Important limitations

- Wikimedia Commons is not a source of mainstream commercial movies. **Most catalog titles still have no watchable source**. There is no promise of all movies, every CloudStream repository, StreamPlay or CineStream server extraction, bypassing referer/DRM/paywalls, or playing content without distribution rights.
- Some files use VP9/Opus in a **WebM** container; Safari/iOS compatibility needs a device check. A successful API response is not the same as media decoding.
- The [Streamline](https://github.com/itsmeadarsh2008/Streamline) project is a JavaScript port of many CineStream extractors for the **Nuvio device plugin runtime**, not an HTTP stream service for MOOSA Web. Integrating it correctly would require reviewing its license, permissions, execution API, sandboxing and source rights; it has **not** been integrated by this milestone.
- `WEB_SOURCE_ADDONS=[]` remains unchanged; no unverified third-party provider is silently enabled.
- We do not serve as a video caching/proxy service and do not forward private Origin/Cookie/Referer headers for third-party sites.

## Test commands

```sh
cd MOOSAWeb
npm run check
# Tests live Commons API + media HEAD (requires outbound internet):
node tests/commons-live.smoke.mjs
```

The demo player button is a practical diagnostic: If it works on a particular browser, the site/player handles this type of direct licensed media. If it fails, check the codec, remote source or Safari restrictions; do not attribute it to the current movie's catalogue ID.
