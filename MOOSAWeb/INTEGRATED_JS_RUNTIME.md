# MOOSA Web: integrated Node JS engine (four allowlisted rights-cleared films)

The browser's existing player continues to work on the current **free Render Node** instance. The project now also has an actual server route that talks to a locally reviewed JavaScript provider process. This is an incremental platform milestone, **not CloudStream Android .cs3 runtime compatibility** and not a new catalog of commercial stream sources.

## New live route

`GET /api/js-engine/demo` defaults to Big Buck Bunny. `GET /api/js-engine/demo?id=sintel` (or `tears-of-steel`, `elephants-dream`, `big-buck-bunny`) selects only one of the four server-pinned, pre-reviewed licensed films. GET/HEAD only. All other query parameters, unknown film IDs, remote code, URL, header or rights requests are rejected.

- A fixed film-ID mapping selects Big Buck Bunny (CC BY 3.0), Sintel, Tears of Steel, or Elephants Dream. The server controls exact title and year and never trusts browser-supplied provider IDs, titles, URL hosts, scripts or rights flags.
- Spawns locally reviewed `commons-authorized.cjs` through `js-plugin-sidecar.mjs`; no Streamline/Vidlink original code deployed.
- The plugin returns Commons movie file candidates (title and license verified). The server tests original `upload.wikimedia.org` links with the strict `browser-source-readiness.mjs` HEAD/CORS/MIME/quality gate.
- Returns matching `sources`, counts, `state`, `engine:reviewed_js_worker`, and explicit `originalCloudStreamPlugins:false`. It is intentionally **not** a general open proxy or arbitrary remote-code executor.
- Independent per-film in-memory in-flight deduplication and 5-minute success / 45-second failure caches minimize abuse of Commons and Render free compute.

## Existing player integration

`GET /api/free-film?id=<approved-film-slug>` now tries the independent JS check for any of the four vetted films with a **maximum 1.8-second wait** after fetching the existing metadata-licensed sources. It only promotes source URLs that match those metadata-validated originals **exactly**. If unavailable or slow, the proven previous Commons sources are retained. Verified JS sources never remove alternative metadata-licensed sources: passing HEAD/CORS checks cannot guarantee Safari codec playback. The response includes `sourceEngine:reviewed_js_worker_preflight` or `sourceEngine:commons_metadata` so diagnostic evidence is not overstated. This does not change which film is playing or make an unverified source playable.

The mainstream catalog is untouched. No new provider is turned on for modern films. This conservative compatibility upgrade prevents breaking the user’s existing working Big Buck Bunny playback.

## Real evidence

[GitHub Actions live HTTP integration run 38886055136](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/38886055136): external HTTP request to MOOSA Node server -> independent local Nuvio-interface JS plugin -> real Wikimedia sources -> actual HEAD/CORS/MIME -> JSON with two validated candidate sources (720p and 2250p). Approx 445ms on that CI runner; not a general speed SLA. Unit tests exercise rights allowlists, unknown media/host exclusions, cache coalescing, recovery after failure, and rejection of user-supplied plugin URLs.

A `GET /api/js-engine/demo` response plus media HEAD **does not prove video can decode on all iPhones**; the user already confirmed three of four sampled free films work, while Elephants Dream did not. The separate original Vidlink browser-origin probe received 429 HTML without CORS, so Vidlink remains **disabled** as a commercial content provider; Streamline's license mismatch also remains open.

## Next high-value work

To enable any **original** Streamline/CloudStream source for mainstream TMDB titles, we still require lawful access permission, exact title/episode match, original plugin license compliance, isolation appropriate to third-party code, a link that passes HEAD/CORS and genuine HTML5/Safari playback (not just URL extraction). On this free Render instance original Android `.cs3` files **do not run**, and no always-on Android plugin host has been verified.

## Four-film expansion

The approved JavaScript worker now resolves each fixed open film by an internal movie identity. User-chosen IDs cannot add unreviewed content. Every returned JS source must additionally match the separately licensed Commons metadata URL for that **same title**; no source may leak into a different film. Explicit originals remain untouched. An original CloudStream provider remains out of scope until its rights, runtime, browser and codec requirements are met.
