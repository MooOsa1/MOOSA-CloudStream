# Original CineStream JVM on Render Free — experimental service

This is a separate Docker service, NOT the live MOOSA Web service.

The Docker build compiles the official CloudStream JVM library, converts
the actual CineStream cs3 DEX method bodies, and runs the existing original
JvmOriginalProviderLinks class through a bearer-authenticated Node endpoint.

- Image: `MOOSAWeb/jvm-pilot/Dockerfile` (build context: repository root).
- Tier: Render Free (512 MiB / 0.1 CPU). Capacity and cold start are not guaranteed.
- `GET /healthz`: process status only, NOT proof of video playback.
- `POST /v1/resolve`: one independently licensed film (`Sintel`, IMDb `tt1727587`, TMDB `45745`).
- Required environment: `MOOSA_JVM_TOKEN`, at least 32 unpredictable chars.
- Optional: `MOOSA_MEDIA_HOSTS`, exact allowlisted CDN hostnames.
- `MOOSA_MEDIA_RIGHTS_APPROVED` remains OFF by default; even a CC-BY film requires
  the precise decoded video to match the credited work before publishing.
- Original CineStream's Castle extractor previously matched an unrelated title
  for a Sintel request. The experimental gate explicitly rejects such results.
- Requests are serialized, JVM heap capped at 250 MiB, no arbitrary
  plugin classes, URLs or video proxies.
- Raw candidate URLs and signed tokens are never written to CI logs.
- Castle upstream title and duration matching are checks, not independent
  proof of film identity.
- Browser playback must be proven independently in a deployed MOOSA client.

The existing live site remains unchanged while the Docker build is tested.
