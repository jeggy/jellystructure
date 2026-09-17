#!/usr/bin/env bash
# Guards the release-only PlayerScreen VerifyError (2026-09-06, again 2026-09-17).
#
# PlayerScreen is a ~3 500-line composable. In the R8 build its single dex method has needed MORE
# THAN 256 REGISTERS — most Dalvik instructions can only address v0–v255, R8 has to shuffle values
# through low registers, and past that point ART's verifier rejects the class ("register vN has type
# Reference but expected Integer"): the app dies the moment any title is played. Nothing else catches
# it — debug builds, unit tests, Wasm and every compile check pass.
#
# Usage: scripts/check-player-dex.sh [path/to/release.apk]   (default: newest ravilo-android release APK)
# Needs a built release APK + its mapping.txt (./gradlew :ravilo-android:assembleRelease).
set -euo pipefail
cd "$(dirname "$0")/.."
LIMIT=${PLAYER_DEX_REGISTER_LIMIT:-250}   # the cliff is 256; leave room
APK=${1:-$(ls -t ravilo-android/build/outputs/apk/release/*.apk 2>/dev/null | head -1)}
# The mapping that belongs to THIS apk (…/outputs/apk/release/x.apk → …/outputs/mapping/release/mapping.txt).
MAP=${PLAYER_DEX_MAPPING:-$(dirname "$(dirname "$(dirname "$APK")")")/mapping/release/mapping.txt}
[ -f "${APK:-}" ] || { echo "FAIL — no release APK; run :ravilo-android:assembleRelease first"; exit 2; }
[ -f "$MAP" ] || { echo "FAIL — no $MAP"; exit 2; }
DEXDUMP=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/dexdump | sort -V | tail -1)
[ -x "$DEXDUMP" ] || { echo "FAIL — dexdump not found under the Android SDK build-tools"; exit 2; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" 'classes*.dex' -d "$TMP"
worst=0; report=""
for kt in PlayerScreenKt LiveTvPlayerScreenKt; do
  obf=$(grep -m1 -E "^dev\.jellystructure\.ravilo\.ui\.screens\.$kt -> " "$MAP" | sed -E 's/.* -> (.*):/\1/')
  [ -n "$obf" ] || { echo "FAIL — $kt not found in mapping.txt"; exit 2; }
  desc="L${obf//./\/};"
  max=$(for d in "$TMP"/classes*.dex; do "$DEXDUMP" -d "$d" 2>/dev/null | awk -v want="$desc" '
      /Class descriptor/ {cls=$4; gsub(/\x27/,"",cls)}
      cls==want && /registers +:/ {print $3}'; done | sort -n | tail -1)
  report+="$kt ($obf): widest method uses ${max:-?} registers"$'\n'
  [ "${max:-0}" -gt "$worst" ] && worst=$max
done
printf '%s' "$report"
if [ "$worst" -gt "$LIMIT" ]; then
  echo "FAIL — $worst registers > $LIMIT. ART rejects the class past 256: the release player will crash on open."
  echo "       Move state/chrome OUT of the function body into its own @Composable or a state holder."
  exit 1
fi
echo "OK — widest player method uses $worst registers (limit $LIMIT, cliff 256) in $(basename "$APK")."
