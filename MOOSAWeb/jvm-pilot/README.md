# Original CineStream JVM on Render Free — experimental service

This is a separate Docker service, NOT the live MOOSA Web service.

The Docker build compiles the official CloudStream JVM library, converts
the actual CineStream cs3 DEX method bodies, and runs the existing original
JvmOriginalProviderLinks class through a bearer-authenticated Node endpoint.

- Image: `MOOSAWeb/jvm-pilot/Dockerfile` (build context: repository root).
- Tier: Render Free (512 MiB / 0.1 CPU). Capacity and cold start are not guaranteed.
- `GET /healthz`: process status only, NOT proof of video playback.
- `POST /v1/resolve`: one exact film (`Dead Night`, IMDb `tt5719076`).
- Required environment: `MOOSA_JVM_TOKEN`, at least 32 unpredictable chars.
- Optional: `MOOSA_MEDIA_HOSTS`, exact allowlisted CDN hostnames.
- `MOOSA_MEDIA_RIGHTS_APPROVED` is OFF by default; no third-party commercial
  stream may be returned without independently confirmed rights.
- Requests are serialized, JVM heap capped at 250 MiB, no arbitrary
  plugin classes, URLs or video proxies.
- Raw candidate URLs and signed tokens are never written to CI logs.
- Castle upstream title and duration matching are checks, not independent
  proof of film identity.
- Browser playback must be proven independently in a deployed MOOSA client.

The existing live site remains unchanged while the Docker build is tested.
