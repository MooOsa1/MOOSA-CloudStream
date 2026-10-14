# MOOSA Hub — Arabic Source Quality Policy (2026-10-14)

## Playback quality floor

- Minimum accepted video height: **720p**, preferred: **1080p or higher**.
- Skip known 240p, 360p, 480p, 540p and 576p before extraction/probing where providers expose resolution metadata.
- Skip extracted links with unknown height instead of pretending they are HD.
- Prefer a provider-supplied resolution from a quality selector/HLS rendition over a URL or file name.
- Only verified playable media links are shown. No failed-link fallback reinsertion.
- The HTTP Downet fallback remains specifically labeled as **unencrypted**; no global TLS bypass.

## Sources and evidence

| Source | Status | How it is used |
|---|---|---|
| Akwam | User-confirmed S1–S5 playback | First Arabic source. Preserve working HD/downet behavior. |
| ShahidWBas | Candidate; site/play pages currently reachable, playback unconfirmed | Second source; only confirmed 720p+ HLS rendition or quality-labeled extractor links. |
| ArabSeed / MySeed | Search and episode HTML work; external hosts not reliably playable | Secondary search, exact season/episode match, high-resolution mirrors only. |
| WeCima | Cloudflare HTTP 403 in probe | Standalone only; excluded from automatic Arabic lookup. |
| CimaClub | HTTP 200 but no content cards returned on sampled searches | Standalone only; excluded from automatic Arabic lookup. |
| TopCinema | No confirmed playable source | Standalone only; excluded from automatic Arabic lookup. |

## Updating

- Provider searches and playback pages are requested **live**; MOOSA Hub's cached Arabic episode targets expire after **3 minutes**.
- Site/player structure changes still require code updates, a passing GitHub Actions build, and publishing a new `.cs3`. **No plugin can guarantee instant hoster-fix deployment without a maintained update pipeline.**
- Never import a third-party extension based only on its README claiming that it works. First verify reachable search, exact title/season/episode match, a real 720p+ media manifest or video signature, and test actual playback.
- Promote candidate ShahidWBas to confirmed only after a user successfully tests at least two different Arabic titles.
- Never regress the user-confirmed Akwam season and quality behavior just to add new sources.
