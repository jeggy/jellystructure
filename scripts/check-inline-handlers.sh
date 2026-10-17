#!/usr/bin/env bash
# DEB-1670 — no inline `on…="…"` event-handler attribute in the admin frontend.
#
# The admin's Content-Security-Policy is `script-src 'self' 'wasm-unsafe-eval' 'unsafe-eval'` with no `'unsafe-inline'`
# (Server.kt), so the browser refuses every inline handler: the button renders, the click does nothing and nothing in
# the console says why. That is how the Copy buttons were dead from 2026-08-02. Markup carries a data attribute and
# ui/DelegatedActions.kt (or a listener bound from Kotlin) does the work.
set -euo pipefail
cd "$(dirname "$0")/.."

hits=$(grep -rnE "[[:space:]]on[a-z]+=\\\\?[\"']" src/wasmJsMain --include=*.kt --include=*.js --include=*.html \
  | grep -vE '^\S+:[0-9]+:\s*//' || true)
if [ -n "$hits" ]; then
  echo "Inline event-handler attributes in the admin frontend (the CSP blocks them — use ui/DelegatedActions.kt):"
  printf '%s\n' "$hits" | cut -c1-200
  exit 1
fi
echo "OK — no inline on…= handler in src/wasmJsMain."
