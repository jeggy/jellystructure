#!/usr/bin/env bash
# Guard the spec tree's phase numbering. Exits non-zero on any mismatch.
#
# Phase status lives only on the board, which CI on GitHub cannot reach, so this checks what the
# repository itself holds:
#
#   BAD NAME  → a phase-*.md file whose name is not phase-<N>-… (admin) or phase-R<N>-… (Ravilo)
#   DUPLICATE → two files in one directory share a phase number
#
# <N> is the digits plus an optional lower-case letter suffix (314c is its own phase).
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
total=0

# check_dir <directory> <prefix> — <prefix> is "" for admin specs and "R" for Ravilo specs.
check_dir() {
  local dir="$1" prefix="$2" f base num
  local -A seen=()
  while IFS= read -r f; do
    base="$(basename "$f")"
    total=$((total + 1))
    if [[ ! "$base" =~ ^phase-${prefix}([0-9]+[a-z]?)-.+\.md$ ]]; then
      echo "BAD NAME   $f  (expected phase-${prefix}<N>-<slug>.md)"
      fail=1
      continue
    fi
    num="${BASH_REMATCH[1]}"
    if [[ -n "${seen[$num]:-}" ]]; then
      echo "DUPLICATE  ${prefix}${num}  $f  (also ${seen[$num]})"
      fail=1
    else
      seen[$num]="$f"
    fi
  done < <(find "$dir" -maxdepth 1 -name 'phase-*.md' | sort)
}

check_dir specs/requirements ""
check_dir specs/ravilo/requirements "R"

if [ "$fail" -eq 0 ]; then
  echo "OK — $total spec files; every name parses and no phase number is used twice."
fi
exit "$fail"
