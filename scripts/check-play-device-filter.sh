#!/usr/bin/env bash
# R215 (FR-R215-3) — one universal listing must stay installable on both a TV and a phone. Play decides
# that from the MERGED manifest, and the merge can quietly demand hardware a TV does not have: on
# 2026-09-03 `.phone.MainActivity`'s screenOrientation="portrait" implied android.hardware.screen.portrait
# as REQUIRED and Play's device filter dropped every TV from the listing — no build, test or lint saw it.
# A permission does the same (CAMERA implies a camera, RECORD_AUDIO a microphone, ACCESS_WIFI_STATE Wi-Fi),
# and any library's manifest can bring one in. This reads what the built release APK actually declares.
#
# Fails when:
#   - there is no LEANBACK_LAUNCHER activity or no <application android:banner> (Play's TV requirements),
#   - there is no ordinary LAUNCHER activity (the phone half),
#   - leanback, touchscreen or screen.portrait is not explicitly required="false",
#   - ANY feature is required (explicitly or implied by a permission) that is not allowlisted below.
#
# Usage: scripts/check-play-device-filter.sh [path/to/release.apk]   (default: newest release APK)
# Env:   ALLOWED_REQUIRED_FEATURES — space-separated features that MAY be required (default: none).
set -euo pipefail
cd "$(dirname "$0")/.."
APK=${1:-$(ls -t ravilo-android/build/outputs/apk/release/*.apk 2>/dev/null | head -1)}
[ -f "${APK:-}" ] || { echo "FAIL — no release APK; run :ravilo-android:assembleRelease first"; exit 2; }
AAPT2=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
[ -x "${AAPT2:-}" ] || { echo "FAIL — aapt2 not found under the Android SDK build-tools"; exit 2; }

badging=$("$AAPT2" dump badging "$APK")
name=$(basename "$APK")
fail=0
bad() { echo "FAIL — $name: $1"; fail=1; }

# The two entry points, and the banner the TV launcher draws.
grep -q '^leanback-launchable-activity:' <<<"$badging" \
  || bad "no LEANBACK_LAUNCHER activity — Android TV would not list the app at all"
grep -q '^launchable-activity:' <<<"$badging" \
  || bad "no LAUNCHER activity — phones would not list the app at all"
grep -E "^application:.*banner='[^']+'" -q <<<"$badging" \
  || bad "no <application android:banner> — Play refuses a TV app without one"

# What a TV does not have, and a phone does, must be explicitly optional.
for f in android.software.leanback android.hardware.touchscreen android.hardware.screen.portrait; do
  grep -q "uses-feature-not-required: name='$f'" <<<"$badging" \
    || bad "$f is not declared required=\"false\" — the universal listing needs it optional"
done

# Every REQUIRED feature, explicit or implied, has to be one we chose. The reason line names the
# permission behind an implied one so the fix is obvious.
allowed=" ${ALLOWED_REQUIRED_FEATURES:-} "
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$allowed" in *" $f "*) continue;; esac
  reason=$(sed -nE "s/^ *uses-implied-feature: name='$f' reason='([^']*)'.*/\1/p" <<<"$badging" | head -1)
  if [ -n "$reason" ]; then
    bad "requires $f (implied: $reason) — devices without it, TVs included, are filtered off the listing; declare <uses-feature android:name=\"$f\" android:required=\"false\"/>"
  else
    bad "requires $f — devices without it are filtered off the listing; declare it required=\"false\" unless every TV and phone has it"
  fi
done < <(sed -nE "s/^ *uses-feature: name='([^']+)'.*/\1/p" <<<"$badging" | sort -u)

[ "$fail" -eq 0 ] || exit 1
echo "OK — $name lists on TV (leanback + banner) and phone, and requires no hardware feature."
