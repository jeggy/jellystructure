#!/usr/bin/env bash
# R316 (FR-R316-5) — the release APK fetches over OkHttp, and only OkHttp.
#
# Ktor's Android engine (`ktor-client-android`, HttpURLConnection) drains a cancelled body on the
# cancelling thread; a fast scroll in Library cancelled image loads on the main thread and killed the app
# with "Unbalanced enter/exit" (five crashes in the Pixel 9's log, three on the Play release). Coil's Ktor
# fetcher picked that engine up from the classpath. No emulator reproduces the timing reliably, so what
# CI can guard is the cause: neither of those classes may be in the release APK, and both OkHttp paths
# must be. The fling itself is scripts/fling-check.sh, run on a signed-in device (FR-R316-3).
#
# Usage: scripts/check-android-http-engine.sh [path/to/release.apk]   (default: newest ravilo-android release APK)
# Reads the APK's mapping.txt (./gradlew :ravilo-android:assembleRelease writes both).
set -euo pipefail
cd "$(dirname "$0")/.."
APK=${1:-$(ls -t ravilo-android/build/outputs/apk/release/*.apk 2>/dev/null | head -1)}
MAP=${HTTP_ENGINE_MAPPING:-$(dirname "$(dirname "$(dirname "$APK")")")/mapping/release/mapping.txt}
[ -f "${APK:-}" ] || { echo "FAIL — no release APK; run :ravilo-android:assembleRelease first"; exit 2; }
[ -f "$MAP" ] || { echo "FAIL — no $MAP"; exit 2; }

fail=0
for forbidden in 'io\.ktor\.client\.engine\.android\.' 'coil3\.network\.ktor3\.'; do
  if hit=$(grep -m1 -E "^${forbidden}" "$MAP"); then
    echo "FAIL — ${hit%% ->*} is in the release APK. R316: Android must not fetch through it (it crashes a fast scroll)."
    fail=1
  fi
done
for required in 'io\.ktor\.client\.engine\.okhttp\.' 'coil3\.network\.okhttp\.'; do
  grep -q -E "^${required}" "$MAP" || { echo "FAIL — no ${required//\\/} class in the release APK. R316: REST and images go over OkHttp."; fail=1; }
done
[ "$fail" -eq 0 ] || exit 1
echo "OK — $(basename "$APK") fetches over OkHttp only (no Ktor Android engine, no Coil Ktor fetcher)."
