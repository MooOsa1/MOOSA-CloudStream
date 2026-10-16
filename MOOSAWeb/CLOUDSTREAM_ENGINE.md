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
