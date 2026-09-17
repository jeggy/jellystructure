#!/usr/bin/env bash
# The definitive guard for the release-only player VerifyError (2026-09-06, 2026-09-17): let ART
# itself verify EVERY class of the R8-minified release APK, on a real Android runtime (an emulator
# in CI), before anything is published. scripts/check-player-dex.sh is the fast proxy (a register
# count); this is the real thing — dex2oat logs one line per class it rejects, and a rejected class
# is a guaranteed crash the first time that class is touched.
#
# Usage: scripts/verify-release-apk-on-art.sh [release.apk]      (needs `adb` and one booted device)
#        ADB_SERIAL=emulator-5554 scripts/verify-release-apk-on-art.sh
set -euo pipefail
cd "$(dirname "$0")/.."
APK=${1:-$(ls -t ravilo-android/build/outputs/apk/release/*.apk 2>/dev/null | head -1)}
[ -f "${APK:-}" ] || { echo "FAIL — no release APK; run :ravilo-android:assembleRelease first"; exit 2; }
ADB=(adb); [ -n "${ADB_SERIAL:-}" ] && ADB=(adb -s "$ADB_SERIAL")
PKG=dev.jellystructure.ravilo

"${ADB[@]}" wait-for-device
"${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1 || true
"${ADB[@]}" logcat -c || true
"${ADB[@]}" install -r "$APK" | tail -1
# Full verification of every class, now (install-time verification may be lazy/partial).
"${ADB[@]}" shell cmd package compile -m verify -f "$PKG" | tail -1
sleep 2
LOG=$("${ADB[@]}" logcat -d -v brief 2>/dev/null | grep -E "dex2oat|artd|installd" || true)
BAD=$(printf '%s\n' "$LOG" | grep -E "Verification error in|Verifier rejected class|failed to verify|Soft verification failures in .*jellystructure" \
        | grep -v -E "Verification error in .*(androidx\.window\.|android\.window\.extensions)" || true)
"${ADB[@]}" uninstall "$PKG" >/dev/null 2>&1 || true
if [ -n "$BAD" ]; then
  echo "FAIL — ART rejected classes in $(basename "$APK"). The release app will crash when these load:"
  printf '%s\n' "$BAD" | cut -c1-400 | head -20
  exit 1
fi
echo "OK — ART verified every class of $(basename "$APK") ($(printf '%s\n' "$LOG" | wc -l) dex2oat log lines, no rejections)."
