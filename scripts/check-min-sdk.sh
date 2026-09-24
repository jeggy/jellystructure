#!/usr/bin/env bash
# R289 (FR-R289-4) — Play's automatic protection refuses any App Bundle whose minSdkVersion is below 24
# (v1.37's upload was rejected on exactly this, run 35748110733, and every release after it would have
# failed the same way). The floor is declared once, as android-minSdk in gradle/libs.versions.toml; this
# reads what the built APK actually declares, so a module that drifts back is caught before Google sees it.
#
# Usage: scripts/check-min-sdk.sh [path/to/release.apk]   (default: newest ravilo-android release APK)
set -euo pipefail
cd "$(dirname "$0")/.."
FLOOR=${MIN_SDK_FLOOR:-24}
APK=${1:-$(ls -t ravilo-android/build/outputs/apk/release/*.apk 2>/dev/null | head -1)}
[ -f "${APK:-}" ] || { echo "FAIL — no release APK; run :ravilo-android:assembleRelease first"; exit 2; }
AAPT2=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
[ -x "${AAPT2:-}" ] || { echo "FAIL — aapt2 not found under the Android SDK build-tools"; exit 2; }
declared=$("$AAPT2" dump badging "$APK" | sed -nE "s/^(minSdkVersion|sdkVersion):'([0-9]+)'.*/\2/p" | head -1)
[ -n "$declared" ] || { echo "FAIL — aapt2 printed no sdkVersion for $(basename "$APK")"; exit 2; }
if [ "$declared" -lt "$FLOOR" ]; then
  echo "FAIL — $(basename "$APK") declares minSdk $declared; Play's automatic protection refuses anything below $FLOOR."
  echo "       The floor is android-minSdk in gradle/libs.versions.toml, read by every shipped Android module."
  exit 1
fi
echo "OK — $(basename "$APK") declares minSdk $declared (floor $FLOOR)."
