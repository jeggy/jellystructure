#!/usr/bin/env bash
# Guard the spec tree's naming. Exits non-zero on any mismatch.
#
# Since 2026-10-15 (owner, DEB-799) new work carries no phase number: the Paperclip task (DEB-n) is
# its identity and its spec is <slug>.md. The legacy numbered phases keep their numbers, frozen.
# Phase status lives only on the board, which CI on GitHub cannot reach, so this checks what the
# repository itself holds. In specs/requirements/ and specs/ravilo/requirements/:
#
#   BAD NAME    → a *.md that is neither a legacy phase-<N>-… (admin) / phase-R<N>-… (Ravilo) nor a
#                 lower-case hyphenated <slug>.md
#   DUPLICATE   → two legacy files share a phase number, or two slugs in one tree are the same
#   FROZEN      → a legacy phase-… file numbered above the frozen maximum — a new spec is <slug>.md
#   NO TASK     → a <slug>.md without a `Task: DEB-<n>` line within its first five lines
#
# <N> is the digits plus an optional lower-case letter suffix (314c is its own phase).
# README.md and files starting with "_" (cross-cutting notes, not specs) are not checked.
set -euo pipefail
cd "$(dirname "$0")/.."

# Highest legacy phase number in each tree when numbers were retired (DEB-1303). Raising these
# defeats the rule — a new piece of work is a <slug>.md with a Task: line, never a new number.
FROZEN_ADMIN=321
FROZEN_RAVILO=388

# Unnumbered files that predate the slug rule and carry no Task: line. Grandfathered by name; the
# list does not grow.
LEGACY_UNNUMBERED=(design-gap-fix.md)

fail=0
total=0

# check_dir <directory> <prefix> <frozen> — <prefix> is "" for admin specs and "R" for Ravilo specs.
check_dir() {
  local dir="$1" prefix="$2" frozen="$3" f base num n legacy
  local -A seen=() slugs=()
  while IFS= read -r f; do
    base="$(basename "$f")"
    case "$base" in README.md | _*) continue ;; esac
    total=$((total + 1))

    if [[ "$base" =~ ^phase- ]]; then
      if [[ ! "$base" =~ ^phase-${prefix}([0-9]+[a-z]?)-.+\.md$ ]]; then
        echo "BAD NAME   $f  (expected phase-${prefix}<N>-<slug>.md, or a new <slug>.md)"
        fail=1
        continue
      fi
      num="${BASH_REMATCH[1]}"
      n="${num%%[a-z]}"
      if ((10#$n > frozen)); then
        echo "FROZEN     ${prefix}${num}  $f  (phase numbers are retired above ${prefix}${frozen}: name a new spec <slug>.md with a 'Task: DEB-<n>' line, DEB-799)"
        fail=1
        continue
      fi
      if [[ -n "${seen[$num]:-}" ]]; then
        echo "DUPLICATE  ${prefix}${num}  $f  (also ${seen[$num]})"
        fail=1
      else
        seen[$num]="$f"
      fi
      continue
    fi

    legacy=0
    for n in "${LEGACY_UNNUMBERED[@]}"; do [[ "$base" == "$n" ]] && legacy=1; done
    if ((legacy)); then continue; fi

    if [[ ! "$base" =~ ^[a-z0-9]+(-[a-z0-9]+)*\.md$ ]]; then
      echo "BAD NAME   $f  (a new spec is a lower-case hyphenated <slug>.md)"
      fail=1
      continue
    fi
    if [[ -n "${slugs[$base]:-}" ]]; then
      echo "DUPLICATE  $base  $f  (also ${slugs[$base]})"
      fail=1
    else
      slugs[$base]="$f"
    fi
    if ! head -n 5 "$f" | grep -Eq '^Task: DEB-[0-9]+\b'; then
      echo "NO TASK    $f  (needs a 'Task: DEB-<n>' line within its first five lines)"
      fail=1
    fi
  done < <(find "$dir" -maxdepth 1 -name '*.md' | sort)
}

check_dir specs/requirements "" "$FROZEN_ADMIN"
check_dir specs/ravilo/requirements "R" "$FROZEN_RAVILO"

if [ "$fail" -eq 0 ]; then
  echo "OK — $total spec files; every name parses, no number is used twice or above the frozen maximum, every slug spec names its task."
fi
exit "$fail"
