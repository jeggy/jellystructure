#!/usr/bin/env bash
# DEB-3118 (FR-317-43, FR-317-44) — the A/V sync sidecar must not be able to stop the rest of Publish, and its build must
# not need the weights' university host. Publish failed on 2026-10-30 when www.robots.ox.ac.uk timed out in the
# sidecar's matrix leg: it cancelled the two other image builds and skipped every `needs: publish` deploy job. Four cases:
#   - a non-comment line of avsync/Dockerfile names robots.ox.ac.uk (the weights come from our own pinned image)
#   - the sidecar's job has no job of its own, or its `needs` lacks `ci` (FR-231-4: nothing publishes before CI)
#   - some job's `needs` names the sidecar's job
#   - the sidecar's job carries `continue-on-error` (the CI watch reads the run's conclusion; a hidden red is the defect)
# PUBLISH_YML and AVSYNC_DOCKERFILE override the paths so the red cases can be tried on a copy.
set -euo pipefail
cd "$(dirname "$0")/.."
P=${PUBLISH_YML:-.github/workflows/publish.yml}
D=${AVSYNC_DOCKERFILE:-avsync/Dockerfile}
JOB=publish-avsync
fail=0

if grep -vE '^[[:space:]]*#' "$D" | grep -q 'robots\.ox\.ac\.uk'; then
  echo "$D: a non-comment line names robots.ox.ac.uk -- the weights come from the pinned image (FR-317-43)"; fail=1
fi

# One job's block: from `  <name>:` to the next two-space-indented key.
block() { awk -v j="  $1:" '$0==j {on=1; next} on && /^  [A-Za-z0-9_-]+:/ {exit} on' "$P"; }

body=$(block "$JOB")
if [ -z "$body" ]; then
  echo "$P: no job '$JOB' -- the sidecar needs its own job outside the matrix (FR-317-44)"; fail=1
else
  grep -qE '^    needs:.*\bci\b' <<<"$body" || { echo "$P: job '$JOB' does not need ci (FR-231-4, FR-317-44)"; fail=1; }
  if grep -qE '^[[:space:]]*continue-on-error:' <<<"$body"; then
    echo "$P: job '$JOB' carries continue-on-error -- the run must still end failure (FR-317-44)"; fail=1
  fi
fi

# Any job's `needs:` that names the sidecar's job.
for j in $(grep -E '^  [A-Za-z0-9_-]+:$' "$P" | tr -d ' :'); do
  [ "$j" = "$JOB" ] && continue
  if block "$j" | grep -E '^    needs:' | grep -qE "(\[|,|[[:space:]])$JOB(\]|,|[[:space:]]|\$)"; then
    echo "$P: job '$j' needs '$JOB' -- a red sidecar would skip it (FR-317-44)"; fail=1
  fi
done

[ "$fail" -eq 0 ] || exit 1
echo "OK: the sidecar builds from its pinned weights and stops only itself (DEB-3118)."
