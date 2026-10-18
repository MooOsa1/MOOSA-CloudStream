#!/usr/bin/env bash
set -euo pipefail

# Deployment-only wrapper: fetch authentic AIOStreams; don't reproduce the
# copyrighted source or develop another partial Stremio engine.
UPSTREAM="https://github.com/Viren070/AIOStreams.git"
PIN="e65aeb0f27b284dfc5d3a7398e82400cf859dccd"
DIR=".moosa-aiostreams-upstream"

node --version
node -e 'if (Number(process.versions.node.split(".")[0])<24) { console.error("AIOStreams requires Node.js 24+"); process.exit(2) }'
npm install --global corepack@0.34.5
corepack enable

if [[ ! -f "$DIR/package.json" ]]; then
  mkdir -p "$DIR"
  git -C "$DIR" init -q
  git -C "$DIR" remote add origin "$UPSTREAM"
  git -C "$DIR" fetch -q --depth=1 origin "$PIN"
  git -C "$DIR" checkout -q --detach FETCH_HEAD
fi
cd "$DIR"
pnpm install --frozen-lockfile
pnpm run build
pnpm run metadata --channel=nightly
echo "MOOSA_HUB_AIOSTREAMS_BUILT upstream=$PIN"
