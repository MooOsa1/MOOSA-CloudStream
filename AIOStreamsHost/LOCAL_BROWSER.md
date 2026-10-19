# MOOSA Hub — Try Sinners (2025) and F1 (2025) in a website on Windows

**Experiment, NOT a confirmed browser playback release.** Only the Linux Stremio engine's video/audio decode and mid-file byte-range tests are proven; HLS browser playback is a separate gate.

## Easy PC path (local browser)

1. Install **Node.js 20 or newer** from https://nodejs.org/en/download and the **official Stremio Service** from https://www.stremio.com/downloads . Start Stremio Service. The service must answer on `http://127.0.0.1:11470/settings`. Do not forward this port.
2. Download and **review** [Start-MOOSA-Browser.ps1](./Start-MOOSA-Browser.ps1) from this GitHub branch, then run it in Windows PowerShell. It checks Node, downloads this repo's current experimental version into your current user's LocalAppData and starts a **loopback-only** Node server. It opens `http://127.0.0.1:3000/stremio-lab.html`. No admin rights required.
3. The page needs a private MOOSA Hub AIOStreams installation manifest URL. Use the [MOOSA preset import](https://aiostreams-nightly.fortheweak.cloud/stremio/configure?template=https%3A%2F%2Fraw.githubusercontent.com%2FMooOsa1%2FMOOSA-CloudStream%2Fexperiment%2Fmoosa-hub-aiostreams-20261018%2FAIOStreamsHost%2Fmoosa-hub-template.json) or the independently tested [Windows MOOSA addon installer](./Install-MOOSA-Hub.ps1). The latter copies the private manifest URL to the clipboard. Paste it only in your own local Browser Lab; **never post it in screenshots or issue comments**.
4. Choose **Sinners (2025)** or **F1: The Movie (2025)**. Press **Find sources**, then **Play in browser**. The page attempts Stremio HLS through your own PC's engine, not a paid external debrid service. The playback lab can try up to five sources if startup or buffer fails.

**What this setup doesn't do:** it does not make free standard BitTorrent work in standalone iPhone Safari; it does not prove movies play correctly through HTML5 on your device, and it does not provide a permanent P2P-to-HTTPS cloud server. The public website `https://moosa-web.onrender.com` has **not** been updated with this staging branch.

**Why local website?** A public HTTPS website trying to control `127.0.0.1:11470` can be stopped by CORS/private-network permissions. Running MOOSA Web on `127.0.0.1:3000` uses a trusted local origin supported by the streaming server. The launcher sets `MOOSA_HOST=127.0.0.1` to avoid serving the site on your LAN. The web engine and its torrent traffic stay on your PC.

**Technical testing:**
- [Known-good video/audio decoding and 30% Range seek, both 2025 films](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39193534596).
- [Known-good ~10-second audio/video decode, both 2025 films](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39194000714).
- [Stremio HLS browser + Chromium pipeline](https://github.com/MooOsa1/MOOSA-CloudStream/actions/runs/39198175295) — check live job status. Don't claim browser playback succeeded while pending/failed.

**Security note:** This app uses a third-party community AIOStreams host, to which your own private manifest URL is sent to resolve streams; the local MOOSA server does not write it to a config file. The site includes no arbitrary URL proxy. Never make the unauthenticated Stremio Service public to the Internet. The Windows script downloads code from your GitHub branch and executes your local copy with Node; review the exact branch content and scripts before running. Only use sources you're authorized to access.
