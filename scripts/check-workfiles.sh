#!/usr/bin/env bash
# Phase 311 (FR-311-4) — a work file is only ever named by WorkFiles.pathFor (src/commonMain/.../media/WorkFiles.kt),
# which puts it in <dir>/.jellystructure/, a folder Radarr, Sonarr and Jellyfin skip. A work file beside the video (a
# dot-FILE) is seen by Radarr's/Sonarr's disk scan mid-write: five times since June a film read as missing and was
# replaced. This fails on any string literal that builds such a name outside WorkFiles.kt.
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0
while IFS= read -r hit; do
  file="${hit%%:*}"; rest="${hit#*:}"; line="${rest%%:*}"; code="${rest#*:}"
  case "$file" in *WorkFiles.kt|*WorkFileSweep.kt) continue ;; esac
  trimmed="$(printf '%s' "$code" | sed 's/^[[:space:]]*//')"
  case "$trimmed" in '//'*|'*'*|'/*'*) continue ;; esac
  echo "$file:$line: $trimmed"
  echo "    ^ a work file must come from WorkFiles.pathFor (phase 311), never a dot-file beside the video."
  fail=1
done < <(grep -rnE '"[^"]*(\.jstmp_|\.jsreplace_|/\.js[a-z]*_)' --include='*.kt' src/*Main shared/src/*Main 2>/dev/null || true)
if [ "$fail" -ne 0 ]; then
  echo; echo "See specs/requirements/phase-311-a-remux-is-never-seen-half-done-by-radarr-or-sonarr.md"; exit 1
fi
echo "OK — every work file is named by WorkFiles.pathFor."
