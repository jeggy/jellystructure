#!/usr/bin/env bash
# DEB-4656 FR-13 — Chromium 63 CSS floor for the shipped Tizen widget.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ${1:-} == --selftest ]]; then
  fixture="$(mktemp -d)"
  trap 'rm -rf "$fixture"' EXIT
  printf '.tile { display: flex; gap: 12px; }\n' > "$fixture/planted.css"
  if "$0" "$fixture" >/dev/null 2>&1; then
    echo 'Tizen CSS guard accepted a planted flex gap' >&2
    exit 1
  fi
  echo 'Tizen CSS guard rejects planted gap'
  exit 0
fi

widget="${1:-$root/ravilo-screen/wgt}"
if [[ ! -d $widget ]]; then
  echo "Tizen widget CSS directory missing: $widget" >&2
  exit 1
fi
shopt -s nullglob
files=("$widget"/*.css "$widget"/*.html)
if (( ${#files[@]} == 0 )); then
  echo 'Tizen widget CSS files missing' >&2
  exit 1
fi
if grep -Ein '(^|[;{[:space:]])(-webkit-)?(gap|aspect-ratio|inset|backdrop-filter)[[:space:]]*:|:focus-visible' "${files[@]}"; then
  echo 'Tizen widget CSS uses a feature newer than Chromium 63' >&2
  exit 1
fi
if grep -Ein '<style([[:space:]>])' "$widget"/*.html; then
  echo 'Tizen widget CSS must stay in a scanned stylesheet' >&2
  exit 1
fi
echo 'Tizen widget CSS passes Chromium 63 guard'
