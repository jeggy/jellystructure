#!/usr/bin/env bash
# Phase 138 (FR-DB1) — regression fence for mobile-only CSS rules that a design-sync silently strips.
# design/app/app.css is exported wholesale by the separate design-mockup project on every "updated
# designs" sync (see CLAUDE.md "Watch the loop") — a rule added here, in the repo, with no matching
# source in that project gets clobbered on the next export with no warning (this exact thing happened
# to the Phase 138 dashboard mobile-hide rule the very next sync after it landed).
#
# Each line below is one rule this repo depends on that a sync has no way to know about. Add a line
# whenever a design-sync-fragile CSS fix lands. Exits non-zero if any listed selector/declaration pair
# is missing from design/app/app.css.
set -euo pipefail
cd "$(dirname "$0")/.."

CSS="design/app/app.css"
fail=0

check() {
  local desc="$1" pattern="$2"
  if ! grep -qF "$pattern" "$CSS"; then
    echo "MISSING      $desc  (pattern not found in $CSS: $pattern)"
    fail=1
  fi
}

# Phase 138 — "Recently processed" + "Quick actions" are desktop-only on the Dashboard.
check "dashboard mobile-hide (.dash-sidecol)" ".dash-sidecol { display: none; }"

if [ "$fail" -eq 0 ]; then
  echo "OK — every design-sync-fragile CSS rule tracked here is present in $CSS."
fi
exit "$fail"
