#!/usr/bin/env bash
# Regression fence for real (de-anonymized) infrastructure/credential values that a design-tool
# sync has reintroduced from its own stale, pre-2026-09-13 local mirror.
#
# On 2026-09-13 this repo's entire git history was rewritten with git-filter-repo to scrub real
# values that had leaked into specs/research reports before the repo was made ready to go public
# (still private as of writing, but treated as if it weren't): real household IPs, a real domain,
# and a real private tracker's name + announce passkey. See memory reference-repo-claude-md-is-
# stale-design-mirror / feedback-design-sync-wipes-css-fixes for the incident history.
#
# 13th incident (2026-09-16): an "updated designs" sync reintroduced ALL of these across ~20 spec
# files and design/app/{metadata,library,seeding}.js — the design tool's own copy of these files
# predates the rewrite and periodically gets re-exported over the scrubbed content, the same
# structural cause as the CSS-wipe incidents check-mobile-css.sh/check-css-scoping.sh guard against.
#
# ADD A LINE HERE whenever a new real value gets scrubbed from history — an unlisted one is one
# sync away from silently reappearing, same as an unfenced CSS rule.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

# term|human description
BANNED=(
  "example.net|real household domain (anonymized to example.net)"
  "FrostSeed|real private tracker name (anonymized to FrostSeed)"
  "NordicVault|real private tracker name (anonymized to NordicVault)"
  "a1b2c3d4e5f60718|real (if-still-valid) tracker announce passkey"
  "192.0.2.10|real household IP (anonymized to 192.0.2.10)"
  "192.0.2.11|real household IP (anonymized to 192.0.2.11)"
  "192.0.2.12|real household IP (anonymized to 192.0.2.12)"
  "192.0.2.20|real backend host IP"
  "192.0.2.22|real stue-TV IP"
  "192.0.2.23|real soveværelse-TV IP"
)

for entry in "${BANNED[@]}"; do
  term="${entry%%|*}"
  desc="${entry#*|}"
  # git-tracked files only — config/data/build/node_modules/.idea legitimately hold real values
  # (live local state, all gitignored) and are not what this fence protects.
  hits=$(git grep -Il -F -- "$term" 2>/dev/null | grep -vF "scripts/check-deanonymization.sh" || true)
  if [ -n "$hits" ]; then
    echo "LEAKED       $desc"
    echo "             (\"$term\" found in:)"
    echo "$hits" | sed 's/^/               /'
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "OK — no de-anonymized real infrastructure/credential values found."
else
  echo
  echo "A design sync has very likely reintroduced real values from its own stale pre-2026-09-13"
  echo "mirror. Confirm each hit is pure regression (no legitimate new content mixed in), then"
  echo "restore with: git show <pre-sync-ref>:<path> > <path>"
fi
exit "$fail"
