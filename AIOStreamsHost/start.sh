#!/usr/bin/env bash
set -euo pipefail
cd .moosa-aiostreams-upstream
export NODE_ENV=production
exec node --max-semi-space-size=8 packages/server/dist/server.js
