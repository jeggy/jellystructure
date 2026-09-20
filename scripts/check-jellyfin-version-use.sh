#!/usr/bin/env bash
# Phase 243 (FR-243-4) — "no compatibility branches, ever", as a rule the build enforces rather than a
# rule people remember.
#
# The product supports Jellyfin 12.0+. A version check is permitted for REPORTING (a /health/full check
# and an advisor finding) and for refusing a known-broken combination; it may never select a request
# shape, a header form, an endpoint or a payload. Phase 243 introduces exactly one symbol that could be
# used to do that — JellyfinServerVersion — so the mechanical form of the rule is: only the files that
# report may mention it at all.
#
# The 12.1 upgrade is why this exists. Three route behaviours changed at once and the tempting fix for
# each was a branch on the version; the decision instead was to move. This keeps that decision from
# being quietly reversed one call site at a time.
set -euo pipefail
cd "$(dirname "$0")/.."

SYMBOL='JellyfinServerVersion'

# Files allowed to name the symbol, and why. Anything else that mentions it is a compatibility branch
# until proven otherwise — if a new REPORTING surface genuinely needs it, add it here with its reason.
ALLOWED=(
  "src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinServerVersion.kt"          # the definition
  "src/linuxX64Main/kotlin/dev/jellystructure/auth/JellyfinClient.kt"                 # records it from the public probe
  "src/linuxX64Main/kotlin/dev/jellystructure/server/Server.kt"                       # FR-243-2/3 reporting: /api/health + /health/full
  "src/linuxX64Main/kotlin/dev/jellystructure/advisor/JellyfinAdvisorService.kt"      # FR-243-3 reporting: the advisor finding
  "src/linuxX64Test/kotlin/dev/jellystructure/JellyfinServerVersionTest.kt"           # its unit tests
)

mapfile -t HITS < <(grep -rl "$SYMBOL" --include='*.kt' --include='*.kts' . \
  | sed 's|^\./||' \
  | grep -v '^build/' | grep -v '/build/' \
  | sort -u)

fail=0
for f in "${HITS[@]}"; do
  ok=0
  for a in "${ALLOWED[@]}"; do [ "$f" = "$a" ] && ok=1 && break; done
  if [ "$ok" -eq 0 ]; then
    echo "$f mentions $SYMBOL but is not an allowed REPORTING site (phase 243 FR-243-4)."
    grep -n "$SYMBOL" "$f" | sed 's/^/    /'
    fail=1
  fi
done

# The definition itself must exist, or this fence passes vacuously forever.
if [ ! -f "${ALLOWED[0]}" ]; then
  echo "MISSING: ${ALLOWED[0]} — phase 243's version reporting is gone, so this fence proves nothing."
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "A version comparison may produce a finding or a health failure and NOTHING else."
  echo "See specs/constitution.md § Supported Jellyfin and"
  echo "specs/requirements/phase-243-jellystructure-targets-jellyfin-12-and-says-so.md"
  exit 1
fi

echo "OK: $SYMBOL is referenced only by its ${#ALLOWED[@]} allowed reporting sites."
