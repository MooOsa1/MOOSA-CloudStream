#!/usr/bin/env bash
# Disposable experiment: original Android CloudStream + original verified CineStream/
# StreamPlay .cs3 + local-only MOOSAProbe.cs3, no video playback/download.
set -Eeuo pipefail

REPORT="$GITHUB_WORKSPACE/probe-evidence"
mkdir -p "$REPORT"
trap 'rc=$?; adb logcat -d -v time 2>/dev/null | grep -E "MOOSA_HTTP_PROBE|MOOSA_ENGINE_PROBE|FATAL EXCEPTION|Failed to load .*MOOSAProbe" > "$REPORT/sanitized-logcat.txt" || true; exit "$rc"' EXIT

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

printf '[5/6] Launch CloudStream and forward a LOCAL test port to the Android HTTP probe\n'
adb logcat -c
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 > "$REPORT/launch.txt" 2>&1
adb forward tcp:18878 tcp:18878
READY=0
for index in $(seq 1 30); do
  sleep 2
  if curl -fsS --connect-timeout 2 --max-time 3 http://127.0.0.1:18878/v1/health >"$REPORT/health.json"; then
    READY=1
    break
  fi
done
if [[ "$READY" != 1 ]]; then
  echo 'Local Android test runtime did not open /v1/health'
  exit 1
fi
python3 - "$REPORT/health.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
assert r['ready'] and not r['production']
print('Android HTTP probe is reachable from CI host via adb forward')
PY

printf '[6/6] Request a licensed sample from OUTSIDE Android and inspect bounded statuses\n'
# A client cannot probe arbitrary films or omit the test credential.
DENIED_AUTH="$(curl -sS --max-time 5 -o "$REPORT/unauthorized.json" -w '%{http_code}' \
  -X POST 'http://127.0.0.1:18878/v1/probe' \
  -H 'Content-Type: application/json' \
  --data '{"media":"movie","title":"Big Buck Bunny","purpose":"licensed-emulator-test"}')"
if [[ "$DENIED_AUTH" != 401 ]]; then
  echo "Security regression: unauthenticated probe returned HTTP $DENIED_AUTH (expected 401)"; exit 1
fi

DENIED_MEDIA="$(curl -sS --max-time 5 -o "$REPORT/rejected-media.json" -w '%{http_code}' \
  -X POST 'http://127.0.0.1:18878/v1/probe' \
  -H 'Authorization: Bearer moosa-ci-local-test-token-not-for-production' \
  -H 'Content-Type: application/json' \
  --data '{"media":"movie","title":"Unlicensed Other Film","purpose":"licensed-emulator-test"}')"
if [[ "$DENIED_MEDIA" != 400 ]]; then
  echo "Security regression: unallowlisted media returned HTTP $DENIED_MEDIA (expected 400)"; exit 1
fi
echo 'SECURITY_PROBE_VERIFIED: unauthorized requests and non-test media rejected'

code="$(curl -sS --connect-timeout 4 --max-time 115 -o "$REPORT/api-probe-result.json" -w '%{http_code}' \
  -X POST 'http://127.0.0.1:18878/v1/probe' \
  -H 'Authorization: Bearer moosa-ci-local-test-token-not-for-production' \
  -H 'Content-Type: application/json' \
  --data '{"media":"movie","title":"Big Buck Bunny","purpose":"licensed-emulator-test"}')"
if [[ "$code" != 200 ]]; then
  echo "Android probe API replied with HTTP $code; inspect emulator log"
  exit 1
fi

python3 - "$REPORT/api-probe-result.json" <<'PY'
import json,sys
data=json.load(open(sys.argv[1]))
assert data['sample']=='Big Buck Bunny'
assert data['production'] is False
assert data['sources']==[], 'No unverified stream URLs may leak from the test API'
results=data['providers']
assert {x['provider'] for x in results}=={'CineStream','StreamPlay'}
assert all(x.get('search') in ('returned','timeout','error','plugin_not_registered') for x in results)
assert any(x.get('search')=='returned' for x in results)
for provider in results:
    print('PROBE_API_RESULT provider=%s search=%s matches=%s load=%s links=%s hd=%s referer=%s' % (
       provider['provider'],provider.get('search'),provider.get('exactMatches','-'),
       provider.get('load','-'),provider.get('links','-'),
       provider.get('hd720Plus','-'),provider.get('refererCount','-')))
print('MOOSA_HTTP_PROBE_VERIFIED: host-to-Android authenticated HTTP request -> original plugin search -> sanitized JSON response')
PY
