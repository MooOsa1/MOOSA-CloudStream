#!/usr/bin/env bash
# Actual execution probe: run ORIGINAL CineStream .cs3 within an ephemeral Android
# emulator, using the ORIGINAL CloudStream app. NOT a movie scraper or production server.
set -Eeuo pipefail
REPORT_DIR="$GITHUB_WORKSPACE/smoke-artifacts"
mkdir -p "$REPORT_DIR"
APP_URL="https://github.com/recloudstream/cloudstream/releases/download/pre-release/app-prerelease-release.apk"
INDEX_URL="https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/plugins.json"
FILE_URL="https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CineStream.cs3"

finish() {
  local rc=$?
  adb logcat -d -v time >"$REPORT_DIR/emulator-logcat.txt" 2>/dev/null || true
  if [[ "$rc" != 0 ]]; then
    echo "MOOSA smoke status=FAILED (this does NOT prove plugins are impossible to run)"
    if [[ -f "$REPORT_DIR/emulator-logcat.txt" ]]; then
      grep -E "CineStream|PluginManager|PluginInstance|FATAL EXCEPTION|ClassNotFoundException|NoClassDefFoundError|UnsupportedOperationException" "$REPORT_DIR/emulator-logcat.txt" | tail -70 || true
    fi
  fi
  exit "$rc"
}
trap finish EXIT

echo "[1/6] Download original official CloudStream app (no fork / local rewrite)"
curl --fail --location --retry 3 --connect-timeout 20 --max-time 240 -o "$RUNNER_TEMP/cloudstream.apk" "$APP_URL"

echo "[2/6] Download manifest and verify CineStream exact artifact hash"
curl --fail --location --retry 3 --connect-timeout 20 --max-time 30 -o "$RUNNER_TEMP/plugins.json" "$INDEX_URL"
FILE_HASH="$(jq -r '[.[] | select(.internalName == "CineStream" and .url == "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CineStream.cs3")] | sort_by(.version) | last | .fileHash // empty' "$RUNNER_TEMP/plugins.json")"
if [[ ! "$FILE_HASH" =~ ^sha256-[a-f0-9]{64}$ ]]; then
  echo "Missing valid sha256 checksum in CineStream official manifest"; exit 1
fi
curl --fail --location --retry 3 --connect-timeout 20 --max-time 90 -o "$RUNNER_TEMP/CineStream.cs3" "$FILE_URL"
echo "$(printf '%s' "$FILE_HASH" | sed 's/^sha256-//')  $RUNNER_TEMP/CineStream.cs3" | sha256sum -c -

echo "[3/6] Install original CloudStream app"
sha256sum "$RUNNER_TEMP/cloudstream.apk" > "$REPORT_DIR/apk-sha256.txt"
adb install -r -g "$RUNNER_TEMP/cloudstream.apk"
# CI runner SDKs are not guaranteed to expose aapt in PATH. Read the
# installed package ID from Android's own package manager instead.
adb shell pm list packages -3 > "$REPORT_DIR/installed-packages.txt"
PACKAGE="$(sed -n 's/^package:\(com\.lagradost\.cloudstream3[^[:space:]]*\)$/\1/p' "$REPORT_DIR/installed-packages.txt" | tr -d '\r' | head -1)"
if [[ -z "$PACKAGE" ]]; then echo "Official CloudStream package not found after install"; exit 1; fi
echo "Package: $PACKAGE"
adb shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow || true

echo "[4/6] Put hash-verified .cs3 in CloudStream's official local plugins directory"
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push "$RUNNER_TEMP/CineStream.cs3" /sdcard/Cloudstream3/plugins/CineStream.cs3 >/dev/null
adb shell ls -la /sdcard/Cloudstream3/plugins/ > "$REPORT_DIR/plugin-directory.txt"
adb logcat -c

echo "[5/6] Open CloudStream and wait for actual MainAPI registration"
adb shell am force-stop "$PACKAGE" || true
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 > "$REPORT_DIR/launch.txt" 2>&1
FOUND=0
for i in $(seq 1 15); do
  sleep 8
  adb logcat -d -v time > "$REPORT_DIR/emulator-logcat.txt"
  # CloudStream BasePlugin.registerMainAPI logs 'Adding <name> ... MainAPI'.
  if grep -Ei 'Adding CineStream .* MainAPI|Adding CineStream \(.*\) MainAPI' "$REPORT_DIR/emulator-logcat.txt" >/dev/null; then
    FOUND=1
    break
  fi
  if grep -E 'Failed to load .*CineStream\.cs3|Plugin load failed.*CineStream' "$REPORT_DIR/emulator-logcat.txt" >/dev/null; then
    echo "CloudStream itself reported plugin loading failure"
    break
  fi
done

echo "[6/6] Report actual execution evidence"
if [[ "$FOUND" -eq 1 ]]; then
  echo "MOOSA_SMOKE_REGISTERED: CineStream MainAPI registered in original CloudStream"
  grep -Ei 'Adding CineStream .* MainAPI|Adding CineStream \(.*\) MainAPI' "$REPORT_DIR/emulator-logcat.txt" | tail -5
  printf '{"result":"provider_registered","plugin":"CineStream","proof":"Original Android app runtime log: MainAPI registered","next":"Test actual search/load/loadLinks for a permitted sample. No web playback verified."}\n' >"$REPORT_DIR/result.json"
else
  printf '{"result":"not_proven","plugin":"CineStream","next":"Inspect emulator log and identify initialization or app setup blocker."}\n' >"$REPORT_DIR/result.json"
  echo "Could not verify a CineStream MainAPI registration on emulator"
  exit 1
fi
