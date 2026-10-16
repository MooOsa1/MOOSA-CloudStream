#!/usr/bin/env bash
# Disposable experiment: original Android CloudStream + original verified CineStream/
# StreamPlay .cs3 + local-only MOOSAProbe.cs3, no video playback/download.
set -Eeuo pipefail

REPORT="$GITHUB_WORKSPACE/probe-evidence"
mkdir -p "$REPORT"
trap 'rc=$?; adb logcat -d -v time > "$REPORT/logcat.txt" 2>/dev/null || true; grep "MOOSA_ENGINE_PROBE" "$REPORT/logcat.txt" > "$REPORT/probe-only.log" || true; exit "$rc"' EXIT

printf '[1/6] Build local-only probe artifact\n'
test -f "$GITHUB_WORKSPACE/MOOSAProbe/build/MOOSAProbe.cs3" || {
  echo 'Probe artifact missing: inspect Gradle build output'; exit 1;
}

printf '[2/6] Fetch official CloudStream Android prerelease app\n'
curl -fsSL --connect-timeout 20 --max-time 240 --retry 2 \
  https://github.com/recloudstream/cloudstream/releases/download/pre-release/app-prerelease-release.apk \
  -o "$RUNNER_TEMP/cloudstream.apk"

printf '[3/6] Verify both downloaded original extensions against published hashes\n'
for ext in CineStream StreamPlay; do
  if [[ "$ext" == CineStream ]]; then
    prefix="https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds"
  else
    prefix="https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds"
  fi
  curl -fsSL --retry 2 --max-time 30 "$prefix/plugins.json" -o "$RUNNER_TEMP/$ext.json"
  digest="$(jq -r --arg n "$ext" --arg u "$prefix/$ext.cs3" '[.[] | select(.internalName==$n and .url==$u)] | sort_by(.version) | last | .fileHash // empty' "$RUNNER_TEMP/$ext.json")"
  if [[ ! "$digest" =~ ^sha256-[a-f0-9]{64}$ ]]; then echo "$ext lacks SHA256 checksum"; exit 1; fi
  curl -fsSL --retry 2 --max-time 90 "$prefix/$ext.cs3" -o "$RUNNER_TEMP/$ext.cs3"
  echo "$(printf %s "$digest" | sed s/^sha256-//)  $RUNNER_TEMP/$ext.cs3" | sha256sum --check
done

printf '[4/6] Install CloudStream + local-only probe + hash-verified extensions\n'
adb install -r -g "$RUNNER_TEMP/cloudstream.apk"
PACKAGE="$(adb shell pm list packages -3 | sed -n 's/^package:\(com\.lagradost\.cloudstream3[^[:space:]]*\)$/\1/p' | tr -d '\r' | head -1)"
if [[ -z "$PACKAGE" ]]; then echo 'CloudStream package not installed'; exit 1; fi
adb shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow || true
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push "$RUNNER_TEMP/CineStream.cs3" /sdcard/Cloudstream3/plugins/CineStream.cs3 >/dev/null
adb push "$RUNNER_TEMP/StreamPlay.cs3" /sdcard/Cloudstream3/plugins/StreamPlay.cs3 >/dev/null
adb push "$GITHUB_WORKSPACE/MOOSAProbe/build/MOOSAProbe.cs3" /sdcard/Cloudstream3/plugins/MOOSAProbe.cs3 >/dev/null

printf '[5/6] Launch app and allow bounded test of search -> load -> loadLinks\n'
adb logcat -c
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 > "$REPORT/launch.txt" 2>&1
for index in $(seq 1 35); do
  sleep 5
  adb logcat -d -v time >"$REPORT/logcat.txt"
  if grep -F 'MOOSA_ENGINE_PROBE' "$REPORT/logcat.txt" | grep -F 'stage=complete result=finished' >/dev/null; then break; fi
  if grep -E 'FATAL EXCEPTION|Failed to load .*MOOSAProbe.cs3' "$REPORT/logcat.txt" >/dev/null; then
    echo 'Android initialization failed. See probe evidence artifact.'
    break
  fi
done

printf '[6/6] Summarize observed stage results (no stream URLs collected)\n'
grep -F 'MOOSA_ENGINE_PROBE' "$REPORT/logcat.txt" | tail -65 || true
if ! grep -F 'MOOSA_ENGINE_PROBE' "$REPORT/logcat.txt" | grep -F 'stage=complete result=finished' >/dev/null; then
  echo 'Probe did not complete; no proof of search/load/loadLinks.'
  exit 1
fi
for name in CineStream StreamPlay; do
  if ! grep -F 'MOOSA_ENGINE_PROBE' "$REPORT/logcat.txt" | grep -F "provider=$name stage=search outcome=" >/dev/null; then
    echo "Missing search result for $name"; exit 1;
  fi
done
echo 'MOOSA_PROBE_COMPLETE: both real providers returned search status. Read stage logs for load/loadLinks results.'
