#!/usr/bin/env bash
# Phase 310 (FR-310-4) — a timeout never leaks out of a "…OrNull".
#
# On 2026-10-06 a `withTimeoutOrNull(6000)` around parallel requests let a TimeoutCancellationException escape instead of
# returning null. The playback, token, Continue Watching and playstate paths now use `boundedOrNull`
# (dev.jellystructure.ops.Bounded), which returns null on its own timeout and on a TimeoutCancellationException that
# escapes from inside, and rethrows only the caller's own cancellation. This script lists the raw `withTimeoutOrNull`
# sites that remain, and fails when there are more than the reviewed baseline, so new ones can't creep back in.
set -euo pipefail
cd "$(dirname "$0")/.."

BASELINE=$(cat scripts/timeouts-baseline.txt)
sites=$(grep -rn 'withTimeoutOrNull' src/linuxX64Main/kotlin --include=*.kt | grep -v 'import kotlinx.coroutines.withTimeoutOrNull' \
  | grep -v 'src/linuxX64Main/kotlin/dev/jellystructure/ops/Bounded.kt' | grep -vE '^\S+:\s*(\*|//)' || true)
count=$(printf '%s' "$sites" | grep -c . || true)
if [ "$count" -gt "$BASELINE" ]; then
  echo "Raw withTimeoutOrNull sites: $count (baseline $BASELINE). Use boundedOrNull (phase 310) for the new one:"
  printf '%s\n' "$sites"
  exit 1
fi
echo "OK — $count raw withTimeoutOrNull site(s), baseline $BASELINE (playback/token/Continue Watching/playstate use boundedOrNull)."
