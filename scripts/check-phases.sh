#!/usr/bin/env bash
# Reconcile /STATUS.md (the single phase-status overview) against the spec files.
# Run after every "updated designs" sync. Exits non-zero on any mismatch.
#
#   ORPHAN FILE → a spec exists with no row in STATUS.md (newly synced → add a row)
#   DEAD ROW    → STATUS.md links a spec that no longer exists (deleted by sync → investigate)
set -euo pipefail
cd "$(dirname "$0")/.."

STATUS="STATUS.md"
fail=0

# 1) every spec file must be referenced in STATUS.md
while IFS= read -r f; do
  grep -qF "($f)" "$STATUS" || { echo "ORPHAN FILE  $f  (no row in $STATUS)"; fail=1; }
done < <(find specs/requirements specs/ravilo/requirements -maxdepth 1 -name 'phase-*.md' | sort)

# 2) every spec link in STATUS.md must resolve to a real file
while IFS= read -r f; do
  [ -f "$f" ] || { echo "DEAD ROW     $f  (linked in $STATUS, file missing)"; fail=1; }
done < <(grep -oE '\(specs/[^)]*phase-[^)]*\.md\)' "$STATUS" | tr -d '()' | sort -u)

if [ "$fail" -eq 0 ]; then
  echo "OK — $(grep -cE '^\| ' "$STATUS") rows; every spec file has a row and every row resolves."
fi
exit "$fail"
