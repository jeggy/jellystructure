#!/usr/bin/env bash
# DEB-4727 FR-13 — a bundle that calls AbortController needs the polyfill ahead of it on Chromium 63.
#   check-tizen-abort.sh [bundle.js]   polyfill behaviour test; with a bundle, also the load-order check
#   check-tizen-abort.sh --selftest    plants a widget with no polyfill and expects the guard to fail
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# check_order <index.html> <bundle.js>
check_order() {
  local html="$1" bundle="$2"
  grep -q 'AbortController' "$bundle" || return 0
  local poly script
  poly=$(grep -n 'src="abort-polyfill.js"' "$html" | head -1 | cut -d: -f1 || true)
  script=$(grep -n 'src="ravilo-screen.js"' "$html" | head -1 | cut -d: -f1 || true)
  if [[ -z $script ]]; then
    echo "$html does not load ravilo-screen.js" >&2
    return 1
  fi
  if [[ -z $poly || $poly -gt $script ]]; then
    echo "$bundle references AbortController (Chromium 66) but $html has no abort-polyfill.js ahead of it" >&2
    return 1
  fi
  [[ -s $(dirname "$html")/abort-polyfill.js ]] || { echo "abort-polyfill.js is missing next to $html" >&2; return 1; }
}

if [[ ${1:-} == --selftest ]]; then
  fixture="$(mktemp -d)"
  trap 'rm -rf "$fixture"' EXIT
  printf 'var c = new AbortController();\n' > "$fixture/ravilo-screen.js"
  printf '<script src="ravilo-screen.js"></script>\n' > "$fixture/index.html"
  if check_order "$fixture/index.html" "$fixture/ravilo-screen.js" 2>/dev/null; then
    echo 'Tizen abort guard accepted a bundle with no polyfill' >&2
    exit 1
  fi
  printf '<script src="ravilo-screen.js"></script>\n<script src="abort-polyfill.js"></script>\n' > "$fixture/index.html"
  echo '// stub' > "$fixture/abort-polyfill.js"
  if check_order "$fixture/index.html" "$fixture/ravilo-screen.js" 2>/dev/null; then
    echo 'Tizen abort guard accepted a polyfill loaded after the bundle' >&2
    exit 1
  fi
  echo 'Tizen abort guard rejects a missing and a late polyfill'
  exit 0
fi

node "$root/ravilo-screen/test/abort-polyfill.test.js"
if [[ -n ${1:-} ]]; then
  test -s "$1" || { echo "Missing ravilo-screen bundle: $1" >&2; exit 1; }
  check_order "$root/ravilo-screen/wgt/index.html" "$1"
  echo "AbortController is covered by the polyfill: $1"
fi
