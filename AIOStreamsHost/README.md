# MOOSA Hub — AIOStreams experimental host

This branch deploys the **real upstream AIOStreams**, not a hand-written
replacement. It clones the pinned source commit
`e65aeb0f27b284dfc5d3a7398e82400cf859dccd`, installs packages with
pnpm and runs the upstream Node server. Upstream source, license and notices
remain at https://github.com/Viren070/AIOStreams (AGPL-3.0-only).

## Free Render deployment

Deploy this exact branch as a **separate**, free Node web service.
Build: `bash AIOStreamsHost/build.sh`
Start: `bash AIOStreamsHost/start.sh`

Environment: `NODE_VERSION=24.18.1`,
`BASE_URL=https://<actual-service>.onrender.com`,
`SECRET_KEY=<64 random lowercase hex>`,
`DATABASE_URI=sqlite://./data/db.sqlite`,
`AIOSTREAMS_AUTH=<operator>:<strong random password>`.
Optional `ADDON_NAME=MOOSA Hub`.

**Important limitations:** Free Render service storage is ephemeral; users
and addon configuration can disappear after restarts/deploys. Use an external
persistent PostgreSQL DB before depending on this. AIOStreams aggregates
Stremio addons, NOT original CloudStream .cs3 Android plugins. It does not
provide movies itself, guarantee 4K/1080p, or create licensed media access.
Safari/iOS can consume supported HTTPS video streams. P2P torrents are not
direct HTTPS and do not work as direct browser playback.

## Operator checklist

1. Open the running instance at `/stremio/configure`.
2. Add working, authorized HTTP stream providers using their manifest URLs.
3. Verify a modern film's real video/audio + seek on iPhone Safari and PC.
4. Only then add additional sources and configure 4K→1080p→720p filtering.
5. Export config backups after any configuration changes.

This branch does not deploy changes to the original MOOSA Web.
