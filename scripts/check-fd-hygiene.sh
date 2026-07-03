#!/usr/bin/env bash
# Phase 134 (FR-OPS2 §B) — regression fence for the FD-leak class that took a live server down.
# SystemFileSystem.source()/.sink() is a raw fopen() with no finalizer: any call not immediately
# .use{}-scoped leaks one file descriptor per invocation, permanently. dev.jellystructure.io.FileIo
# is the one sanctioned way to read/write a whole file; every other call site must be a streaming
# use{} (writes already follow this pattern in NfoWriter.kt etc.).
#
# Flags any `SystemFileSystem.source(`/`.sink(` call, outside FileIo.kt itself, whose *same line*
# doesn't also contain `.use` — excluding comment lines (`//` or the `*` continuation of a
# `/** ... */` block). Exits non-zero on any match.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
while IFS= read -r hit; do
  file="${hit%%:*}"
  rest="${hit#*:}"
  line="${rest%%:*}"
  content="${rest#*:}"
  case "$file" in
    */io/FileIo.kt) continue ;;  # the one file allowed to hold an unwrapped source/sink
  esac
  trimmed="$(echo "$content" | sed -e 's/^[[:space:]]*//')"
  if [[ "$trimmed" == //* || "$trimmed" == \** ]]; then
    continue  # comment line (// or the * continuation of a /** ... */ block)
  fi
  if echo "$content" | grep -qE '\.use[[:space:]]*[({]'; then
    continue  # already .use{...} / .use(...) on this line
  fi
  echo "LEAK RISK    $file:$line  $trimmed"
  fail=1
done < <(grep -rn "SystemFileSystem\.\(source\|sink\)(" src/linuxX64Main/kotlin/ 2>/dev/null || true)

if [ "$fail" -eq 0 ]; then
  echo "OK — every SystemFileSystem.source()/.sink() call outside FileIo.kt is .use{}-scoped."
fi
exit "$fail"
