#!/usr/bin/env bash
# Phase 310 (FR-310-5, dev review item 5) — a CancellationException is only rethrown when it is the caller's own.
#
# On 2026-10-06 the playback writer's drain loop rethrew a TimeoutCancellationException that was not its own (Ktor's
# Curl engine had handed another call's cancellation to it), which ended the loop silently: no progress or stop
# reached Jellyfin for seven hours. The rule: a rethrow of a CancellationException must check that the current
# coroutine is no longer active (`!currentCoroutineContext().isActive`, or `!isActive` in a scope) on the same line.
#
# Exception: code that only runs inside an outbound HTTP call. Since 310, `OutboundHttp.withPermit` turns a foreign
# cancellation into an IOException before it reaches them, so their plain rethrow is the caller's own by construction.
# Those files are listed below, each with its reason. A new rethrow anywhere else fails until it checks isActive or is
# reviewed and listed here.
set -euo pipefail
cd "$(dirname "$0")/.."

ALLOWED=(
  "src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt"      # every call goes through httpGet/httpPost/httpDelete → withPermit
  "src/linuxX64Main/kotlin/dev/jellystructure/music/AcoustIdClient.kt"      # its request runs inside OutboundHttp.withPermit
  "src/linuxX64Main/kotlin/dev/jellystructure/music/MusicBrainzClient.kt"   # its request runs inside OutboundHttp.withPermit
  "src/linuxX64Main/kotlin/dev/jellystructure/music/MusicWebClients.kt"     # its requests run inside OutboundHttp.withPermit
  "src/linuxX64Main/kotlin/dev/jellystructure/music/ProviderKeyChecks.kt"   # its key checks run inside OutboundHttp.withPermit
  "src/linuxX64Main/kotlin/dev/jellystructure/OutboundHttp.kt"              # the boundary itself (mentions the pattern in its doc)
)

hits=$(grep -rnE '(is (kotlinx\.coroutines\.)?CancellationException\)?[^;]*throw|catch \(e: (kotlinx\.coroutines\.)?CancellationException\) \{ *throw)' \
  src/linuxX64Main/kotlin --include=*.kt | grep -v 'isActive' || true)
bad=""
while IFS= read -r line; do
  [ -z "$line" ] && continue
  file="${line%%:*}"
  ok=0
  for a in "${ALLOWED[@]}"; do [ "$file" = "$a" ] && ok=1; done
  [ $ok -eq 0 ] && bad+="$line"$'\n'
done <<< "$hits"

if [ -n "$bad" ]; then
  echo "A CancellationException is rethrown without checking it is the caller's own (phase 310):"
  printf '%s' "$bad"
  echo "Rethrow only when !currentCoroutineContext().isActive — or, if this code only runs inside an outbound HTTP call,"
  echo "add the file to ALLOWED in scripts/check-cancellation-rethrow.sh with its reason."
  exit 1
fi
echo "OK — every CancellationException rethrow checks it is the caller's own (or is behind OutboundHttp)."
