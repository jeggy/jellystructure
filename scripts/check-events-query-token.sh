#!/usr/bin/env bash
# R293 (FR-R293-7, acceptance 5) — no native Ravilo build puts the device token in a WebSocket URL.
# The Android client used to pass `?token=` on `/api/tv/events` and `/api/remote/events`; the reverse
# proxy logs the full URL on any error, and did so for a real device's token. Only a browser cannot set
# a handshake header, so the query form is allowed in exactly one place: `WS_TOKEN_IN_QUERY`'s browser
# actuals (shared/src/wasmJsMain, shared/src/jsMain). Everything else authenticates with a Bearer header.
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0

# 1. The only source lines composing a socket URL with a token are the one helper in TvApiClient, which
#    is gated on WS_TOKEN_IN_QUERY. No other production source may spell `events?token=`.
while IFS= read -r hit; do
  file=${hit%%:*}
  case "$file" in
    */linuxX64Test/*|*/commonTest/*|*Test.kt|scripts/*|*.md|*.sh) continue ;;
  esac
  echo "FAIL: a socket URL carries the token: $hit"; fail=1
done < <(grep -rn --include=*.kt --include=*.ts --include=*.js -E 'events\?token=' ravilo-ui/src ravilo-android/src shared/src/commonMain shared/src/androidMain shared/src/linuxX64Main 2>/dev/null || true)

# 2. The flag's native actuals say false; the browser actuals say true; the helper is gated on it.
for f in shared/src/androidMain/kotlin/dev/jellystructure/shared/tv/WebSocketAuth.kt shared/src/linuxX64Main/kotlin/dev/jellystructure/shared/tv/WebSocketAuth.kt; do
  grep -q 'WS_TOKEN_IN_QUERY: Boolean = false' "$f" || { echo "FAIL: $f must set WS_TOKEN_IN_QUERY = false"; fail=1; }
done
for f in shared/src/wasmJsMain/kotlin/dev/jellystructure/shared/tv/WebSocketAuth.kt shared/src/jsMain/kotlin/dev/jellystructure/shared/tv/WebSocketAuth.kt; do
  grep -q 'WS_TOKEN_IN_QUERY: Boolean = true' "$f" || { echo "FAIL: $f must set WS_TOKEN_IN_QUERY = true"; fail=1; }
done
grep -q 'if (WS_TOKEN_IN_QUERY) "?token="' shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/TvApiClient.kt \
  || { echo "FAIL: TvApiClient.wsUrl must gate the query form on WS_TOKEN_IN_QUERY"; fail=1; }

if [ "$fail" -ne 0 ]; then echo "check-events-query-token: FAILED"; exit 1; fi
echo "check-events-query-token: ok (the token rides a Bearer header on every native build)"
