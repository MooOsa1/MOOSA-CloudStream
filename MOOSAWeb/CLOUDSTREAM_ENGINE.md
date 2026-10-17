# MOOSA Web CloudStream runtime — feasibility foundation

## What is working today

- A stable allowlisted registry for two original CloudStream extensions:
  - **CineStream**: \`SaurabhKaperwan/CSX\`, \`CineStream.cs3\`
  - **StreamPlay**: \`phisher98/cloudstream-extensions-phisher\`, \`StreamPlay.cs3\`
- \`GET /api/cloudstream/repositories\` returns the current real plugin version/hash/URL when reachable, plus a truthful compatibility state. The registry runs on Render and requires **no user pairing**.
- The default existing web resolver still supports legal direct video sources. When an independently deployed runtime is configured by the operator, it also submits an exact movie/episode request to that runtime and accepts validated sources.
- Without such a runtime, the endpoint says \`not_configured\`, and **no .cs3 plugins are executed or represented as working playback sources**.

## What is NOT working yet

- **Node.js does not execute CloudStream Android .cs3 packages.**
- The protocol is an interface specification, not a deployed Android executor. An Android runtime (or a demonstrably compatible JVM implementation) remains necessary.
- No claim of actual CineStream/StreamPlay playback until a licensed end-to-end video has been tested through the browser.
- No general web-video proxy, no DRM/CORS or access-control bypass.

## Runtime responsibilities

Deploy a separately permissioned execution service (not the site's Render free Node process). The executable needs to:

1. Run Android-compatible CloudStream classes, including actual \`PathClassLoader\` and platform dependencies needed by plugins.
2. Download only approved plugin URLs, check \`sha256-\` hashes from the repository manifests, and run each untrusted extension inside an isolated environment with resource limits.
3. Register providers/extractors and expose \`search → load → loadLinks\` via a minimal server interface.
4. Verify IMDb/TMDB ID, original name/year, and exact S/E for shows; reject wrong or ambiguous content.
5. Return only user-authorized browser-playable MP4/WebM/HLS URLs with declared 720p+ quality and Arabic/English subtitles. Never send device cookies or private credentials.
6. Expose \`POST /v1/resolve\` over **HTTPS**, require a private \`Authorization: Bearer <token>\` header, and bound execution to ~12 seconds per request. Rate-limit requests and disable all arbitrary HTTP URL loading.

### Request (web app → runtime)

\`\`\`json
{
  "media": "tv",
  "tmdbId": 123,
  "imdbId": "tt1234567",
  "title": "Example",
  "year": 2020,
  "season": 2,
  "episode": 4,
  "plugins": ["CineStream", "StreamPlay"]
}
\`\`\`

### Response (runtime → web app)

\`\`\`json
{
  "sources": [
    {
      "provider": "CineStream",
      "name": "1080p licensed source",
      "quality": 1080,
      "url": "https://licensed-cdn.example/video.m3u8",
      "subtitles": [{
        "language": "ar",
        "label": "العربية",
        "url": "https://licensed-cdn.example/ar.vtt"
      }]
    }
  ]
}
\`\`\`

## Actual HTTP Stremio protocol interoperability

CineStream's upstream Kotlin implementation has a configurable
HTTPS Stremio add-on interface
(Settings.AddonType.HTTPS / invokeStreamioStreamsGlobal):
GET /stream/movie/{imdbId}.json
GET /stream/series/{imdbId}:{season}:{episode}.json

Our **server-native** MOOSA Web exports the movie half of that HTTP
interface, independently of any Android plugin:

- Add-on root: https://moosa-web.onrender.com/api/stremio
- Manifest: https://moosa-web.onrender.com/api/stremio/manifest.json
- An approved stream example:
  https://moosa-web.onrender.com/api/stremio/stream/movie/tt1727587.json
- The exported content is currently just four separately verified
  Creative Commons films (Big Buck Bunny, Sintel, Tears of Steel and
  Elephants Dream). Fixed, independently matched IMDb identities
  prevent wrong-title and TV episode responses.
- This endpoint returns a **real Stremio manifest/stream JSON** and
  actual metadata-licensed Wikimedia media URLs. It can be consumed by
  clients supporting standard Stremio HTTP add-ons; it does not download
  or execute original Android .cs3 packages.
- No open HTTP proxy, torrent or DRM/header circumvention is provided.
- Series, unknown titles and arbitrary user-supplied streams are unsupported
  and cannot generate source URLs.

Because CineStream's upstream uses this transport, it provides a
first concrete protocol-level compatibility path in both ecosystems.
To connect a different authorized Stremio HTTP addon **into** MOOSA
Web, the existing operator-only WEB_SOURCE_ADDONS configuration is the
corresponding inbound adapter. The endpoints are asymmetric: providing
Stremio JSON is not running CineStream's Kotlin sources.

## External Archive.org Stremio addon verification (October 16, 2026)

Independent live probes found that the community Archive.org addon at
dev.nebulawp.org/stremio/archive.org-addon does return HTTPS video URLs,
but some IMDb lookups are **incorrect**. Examples:
Big Buck Bunny -> unrelated CNN Bugs Bunny video,
Elephants Dream -> different video, The Cabinet of Dr. Caligari -> Way Down
East rehearsal, Night of the Living Dead -> movie trailer. One accurate
Tears of Steel video was only 360p. See live diagnostic GitHub Action
run 38897938836.

Do not turn this addon on as a general source provider. The MOOSA Web
operator-only Stremio input adapter now independently verifies every
Archive.org direct file returned by any externally configured Stremio
addon against Archive item metadata: exact original movie title and
compatible release year, original file filename and size, >=720p
height, and explicitly declared acceptable Creative Commons license.
Wrong movies, trailers, preview clips, missing license and SD mislabeled
4K sources are rejected. Up to three independent metadata requests run
concurrently per add-on response, preventing unnecessary site delay.
No new external provider has been enabled on the public Render website.

## Optional Stremio HTTP subtitle protocol compatibility

Upstream CineStream and StreamPlay both call Stremio subtitle endpoints by
exact IMDb movie or season/episode identity:

- Movies: /subtitles/movie/tt<imdb>.json
- TV: /subtitles/series/tt<imdb>:<season>:<episode>.json

MOOSA Web now recognizes subtitle resources on operator-configured HTTPS
Stremio addons in WEB_SOURCE_ADDONS. It fetches Arabic/English SRT/VTT
links only, rejects authorization/cookies/Referer-dependent links and unsafe
URLs, and uses a *separate parallel /api/stremio-subtitles request* for the
original IMDb film or exact TV season/episode. The web player adds the
tracks to the selected title without resetting video playback, and cancels
old subtitle requests when the player closes or a different title opens.
Subtitle-only addons can supply tracks without fabricating video sources.
A failing subtitle addon cannot erase or delay the main /api/resolve video
response. Subtitle HTTP work has a bounded 2.8-second deadline per addon.
Neither this feature nor Stremio source transport executes original
CineStream/StreamPlay Android code.

The operator must separately configure and review authorized add-on manifests.
OpenSubtitles v3 is now included as a pre-approved **subtitle-only** HTTP addon:
https://opensubtitles-v3.strem.io/manifest.json .
On 2026-10-16 its actual movie/IMDB endpoints returned two Arabic and four English subtitle results for Sintel, with direct SRT text at subs5.strem.io. We independently fetched Arabic and English payloads, verified valid SRT cue times, Arabic Unicode text and permissive CORS. Official CDN subtitle URLs do not have .srt/.vtt suffixes, so the Node and browser URL validator only accepts the narrowly verified /en/download/subencoding-stremio-utf8/src-api/file/<numeric> format on subs*.strem.io. The site does not proxy subtitle content, and its built-in native SRT -> VTT converter handles direct playback in HTML5. Additional operator-provided subtitle addons remain optional. No original Android .cs3 executes and source video compatibility is unchanged. Individual subtitles can still be out of sync; the existing subtitle offset slider stays available.

## Upstream CineStream video provider adapter: VaPlayer (October 2026)

A direct Node.js port of the upstream Kotlin function
`CineStreamExtractors.invokeVaPlayer` now lives in
`cinestream-vaplayer.mjs` and is included in the ordinary movie
resolver as a strictly operator-opt-in source. This is an **actual
CineStream video provider adapter**, not another demo-film shelf.

Before any VaPlayer URL can appear in the player it must pass:
- exact TMDB-provided original movie name, year, IMDb match against
  the upstream API response (no fuzzy series/episode confusion)
- HTTPS origin and explicitly operator-allowlisted PUBLIC media hosts
- a usable 1280px-or-wider HLS stream variant
- **successfully fetched first video segment** with CORS and video magic;
  a 200 status on a master or variant playlist is explicitly insufficient
- rejection of encrypted HLS, third-party protected cookies/headers,
  wrong HTML responses, 403/404 and invalid stream host.

Live upstream checks 2026-10-16:
- VaPlayer's API returned 3 URLs for Sintel, Tears of Steel, and
  public-domain classics; master HLS advertised up to 1920px AVC/AAC.
- Actual first segments consistently returned HTTP 403, even when
  both playlists returned HTTP 200. Thus **zero VaPlayer sources were
  verified browser-playable** on the test runner. Do not present them
  as usable 1080p sources.
- StreamPlay's older VidsrcSU HTML parser is currently incompatible
  with the React-based upstream. Browser session on Sintel returned
  `No available servers found`, with no video requests.
- CineStream PrimeSrc returned 403 and Vidrock could not be fetched.

The adapter is **OFF by default** and imposes no extra Play lookup latency.
It can be enabled only by the operator after upstream media segments pass
real browser playback checks:
`CINESTREAM_VAPLAYER_ENABLED=1`
`CINESTREAM_ALLOWED_MEDIA_HOSTS=trusted-host.example,other-host.example`
Do not enable a broad wildcard: media domains can change and must be
individually verified. This feature does not run Kotlin .cs3 plugins,
proxy video segments, fake custom Referer, or bypass DRM/access controls.

## Deployment boundary

Use \`CLOUDSTREAM_RUNTIME_URL\` and \`CLOUDSTREAM_RUNTIME_TOKEN\` in **Render environment variables** only after a tested secure Android execution service exists. Do **not** publish a token in the GitHub repository or browser code. Never point them to an unrelated third-party endpoint.

Running Android on a persistent isolated VPS may require additional spending and capacity, and **has not been provisioned or verified**. Render's free Node instance is not a tested Android execution host.

## Test protocol before investing in a paid runner

1. Run one trusted test extension in a disposable Android-compatible runner.
2. Observe a real provider register and complete \`search → load → loadLinks\` using a public domain / freely licensed media sample.
3. Confirm the exact title/episode and whether the video works in the web player **without** embedding third-party cookies or bypassing access restrictions.
4. Then test actual authorized CineStream and StreamPlay sources separately; require observable successful playback and provider logs before expanding to other repositories.
5. Measure reliability, responsiveness, memory footprint and security isolation before deciding whether a persistent VPS is worth paying for.

The GitHub CI tests validate the registry, request contract, URL/quality filtering, and behavior when the runtime is absent. They **do not prove** that Android plugins run or that copyrighted films can be played in a browser.

## HLS rendition fallback and browser CORS preflight (October 16, 2026)

A master playlist that returns HTTP 200 is not sufficient to recommend a
source. The web-native CineStream VaPlayer adapter now probes up to three
720p-or-higher variants in parallel, with browser-Origin CORS checks on both
playlists and the actual first segment. If 4K or 1080p responds 403, a verified
lower HD rendition can be returned instead. **The player receives that exact
working media playlist URL, not the master playlist**, which might auto-select
the broken 4K rendition again. Every URL and segment host remains an explicit
operator allowlist; no cookies, forged Referer, DRM bypass or proxy is added.

This is a compatibility improvement to the existing disabled-by-default
CineStream adapter, not evidence that VaPlayer streams currently work.
Enabling it still requires licensed sources, approved public hosts, and
independent real-browser playback evidence. Original CloudStream .cs3 plugin
execution remains unsupported on the free Node host.

## Reliable HD fallback and build queue isolation (October 16, 2026)

The HLS adapter now checks a representative set of at most four media
renditions in parallel: the two highest variants, an available true 1080p
(or next lower HD) variant, and the lowest approved >=720p variant. This
fixes masters whose first three highest renditions fail while 720p still
works. Candidate resolution requires **both** width >=1280 and height >=720,
so an ultrawide 1280x360 stream cannot masquerade as HD.

Live video is still denied by the upstream segment host (HTTP 403 in the
observed tests); the fallback is a compatibility improvement, not a bypass
or proof of newly playable commercial sources. No new source is activated.

Website-only commits no longer trigger the CloudStream Android plugin build:
that workflow is path-scoped to Android plugin and Gradle inputs. The
independent MOOSA Web CI workflow cancels superseded runs on the same ref,
reducing duplicated free runner work. Explicit manual plugin builds remain
available; Android sources and existing plugin packages are untouched.

## CineStream VaPlayer October 16 — confirmed final segment failure

On GitHub Actions, two independently identified feature films (Night of the
Living Dead IMDb tt0063350, His Girl Friday IMDb tt0032599) each returned
three VaPlayer HLS candidates. All six masters and HD media playlists were
HTTP 200 with matching IMDb metadata and browser CORS. The actual 720p
playlists were 171567 and 164775 bytes respectively: the old 160000-byte
limit wrongly rejected them. The bounded video probe now supports up to
512000 bytes using a streaming reader and cancels oversized responses.

**Playback remains unavailable from VaPlayer:** every first media segment
returned HTTP 403 using ordinary GET and byte-range GET, with and without
CineStream's original Referer header. Thus no playable video source was
promoted, the provider stays OFF, and this issue must not be described as
solved or bypassed. HTTP 200 on a playlist is NOT a playback success.

Evidence: https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38954027202

## Browser HLS: extensionless media segments (October 16, 2026)

Original CineStream JVM extraction emitted 13 links for Sintel; twelve tested HLS
candidates were rejected by the strict filename-extension check without even
fetching the segment. MOOSA Web now permits extensionless media paths on the
same explicit approved HTTPS host, but still requires real media magic bytes,
HTTP 200/206 and correct browser CORS. Cross-host HLS segment URLs remain
blocked until independently verified and explicitly approved. This does not
make copyrighted or inaccessible streams playable.

Reference: https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38960872508

## Range fallback and precise segment failures

Browser HLS segment probes now retry a single ordinary GET if the source
returns 400, 403, 405 or 416 to the optional byte-range request. Both
attempts are bounded and canceled after the first video chunk, with no
proxy, forged Referer, cookies or bypass. Distinct failure reasons report
segment_http_<status> versus segment_cors_missing; direct playback is
never announced unless video magic bytes and browser CORS both pass.

## Fix: no assumed 720p for bare HLS media playlists

Live Chrome decoder returned **852x480** for a CineStream segment whose bare
HLS media manifest lacks a resolution declaration. Previous first-segment
probe incorrectly assumed `{height:720,width:1280}` for every standalone
media playlist. The HLS probe now reports quality `0` / width `0` when
resolution is unknown, while still accurately reporting successful first
video bytes. The VaPlayer source aggregator rejects unknown dimensions as
ineligible for the user's minimum 720p policy. A master playlist declaring
HD is still only a provider quality *claim*, not a full decoded-frame proof.

Evidence: https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38964692426

## Enforce actual MPEG-TS H.264 frame dimensions (verified quality gate)

The backend now optionally reads H.264 SPS dimensions from up to 96 KiB of
real MPEG-TS video bytes before promoting a stream as 720p+. The
`fromCineStreamVaPlayer` adapter always uses this strict mode; if video has
no readable AVC SPS, if a codec is unsupported, or if its actual encoded
height is below 720 / width below 1280, no HD stream is advertised.
Both a genuine FFmpeg 852×480 fixture and 1280×720 fixture exercise this
gate, including a deliberate false `RESOLUTION=1920x1080` master. The
original CineStream JVM test also checks the strict gate on its same media
sample, without mistaking first-segment accessibility for HD.

## Single-film original-JVM pilot (17 October 2026)

An isolated opt-in gate exists for exactly one IMDb identity:
Dead Night (`tt5719076`; 2017 festival premiere / 2018 release).
It is OFF without a deployed external executor.

- `CLOUDSTREAM_SINGLE_FILM_PILOT=1` enables accepting a low-resolution
  (144p+) source for this film only, instead of the normal 720p floor.
- `CLOUDSTREAM_RUNTIME_URL` and `CLOUDSTREAM_RUNTIME_TOKEN` must point to an
  independently deployed HTTPS JVM executor implementing POST `/v1/resolve`.
  Render Node does NOT spawn the original `.cs3` executable.
- The executor must return `provider: CineStream`, exact
  `resolvedImdbId: tt5719076`, `resolvedTitle: Dead Night`,
  `resolvedYear: 2017` (or 2018), and `operatorAuthorized: true`.
  These checks do NOT establish content identity or distribution rights.
- `/api/cloudstream/pilot-status` is configuration only, not playback proof.
- Existing 720p+ sources and other catalog movies are unaffected.
- GitHub Actions proof is not a persistent web service. Never commit
  temporary HLS URLs or signed tokens.
