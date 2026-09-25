#!/usr/bin/env bash
# Phase 250 — the three Dockerfiles the e2e job builds (app, ravilo-web, ravilo-screen) must share one
# BuildKit cache-mount id per toolchain kind, or each build downloads the same ~600 MB Gradle
# distribution and Kotlin/Native toolchain from scratch.
#
# Measured live on CI run 35448419659: `ci / e2e` took 34 minutes, of which the Playwright run itself
# was 33.7 seconds. All three builds independently downloaded gradle-9.2.1-bin.zip and the Konan LLVM
# + sysroot, back to back, in one job — because BuildKit shares a cache mount ONLY when the id matches
# and each Dockerfile used its own suffixed id (gradle-jellystructure / gradle-ravilo-web / ...).
#
# This fence is the mechanical form of FR-250-1: no per-project suffix may come back.
set -euo pipefail
cd "$(dirname "$0")/.."

DOCKERFILES=(Dockerfile ravilo-web/Dockerfile ravilo-screen/Dockerfile)
ALLOWED_IDS='gradle-shared|konan-shared|npm-shared'
fail=0

for f in "${DOCKERFILES[@]}"; do
  [ -f "$f" ] || { echo "MISSING: $f (phase 250 fence expects it)"; fail=1; continue; }
  # Every cache mount in these files must carry an explicit id, and that id must be a shared one.
  while IFS= read -r line; do
    case "$line" in
      *"type=cache"*) ;;
      *) continue ;;
    esac
    if ! grep -qE 'type=cache,id=' <<<"$line"; then
      echo "$f: cache mount with no explicit id (an anonymous mount is per-target, never shared):"
      echo "    $line"
      fail=1
      continue
    fi
    id=$(sed -nE 's/.*type=cache,id=([A-Za-z0-9_.-]+).*/\1/p' <<<"$line")
    if ! grep -qE "^($ALLOWED_IDS)$" <<<"$id"; then
      echo "$f: cache-mount id '$id' is not shared across the three e2e images (phase 250 FR-250-1)."
      echo "    expected one of: gradle-shared, konan-shared, npm-shared"
      fail=1
    fi
    # A shared id without sharing=locked is worse than a per-project id, not better: BuildKit's
    # default is sharing=shared (concurrent access ALLOWED), and a plain `docker compose build`
    # builds targets in parallel, so three Gradle processes then contend on
    # /root/.gradle/caches/journal-1.lock — whose lock has a TIMEOUT and fails the build rather than
    # waiting. Measured locally 2026-09-20: "Timeout waiting to lock journal cache … Owner PID: 59".
    if ! grep -q 'sharing=locked' <<<"$line"; then
      echo "$f: cache mount '$id' is shared but not sharing=locked:"
      echo "    $line"
      echo "    ^ without it, a parallel `docker compose build` fails on Gradle's own journal lock."
      fail=1
    fi
  done < "$f"
done

# FR-250-2: a shared id is worthless if the cache mount is deleted before the next build reads it.
# `docker buildx prune` with no filter removes cache mounts along with layers, and ci.yml prunes
# between the three builds to stay inside the runner's disk. Measured on CI run 36134323199: all three
# builds still downloaded gradle-9.2.1-bin.zip and the Konan LLVM + sysroot. So every prune that is
# followed by another `docker compose ... build` in the same workflow must keep exec.cachemount records.
CI=.github/workflows/ci.yml
if [ -f "$CI" ]; then
  bad=$(awk '
    /^[[:space:]]*#/ { next }
    /docker (buildx|builder) prune/ { p[++n] = NR; t[n] = $0 }
    /docker compose .* build / { last_build = NR }
    END {
      for (i = 1; i <= n; i++)
        if (p[i] < last_build && t[i] !~ /type!=exec\.cachemount/) print p[i] ": " t[i]
    }' "$CI")
  if [ -n "$bad" ]; then
    echo "$CI: a prune between image builds deletes the shared toolchain cache mounts (phase 250 FR-250-2):"
    echo "$bad" | sed 's/^/    /'
    echo "    ^ add --filter 'type!=exec.cachemount'"
    fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "FAILED: see specs/requirements/phase-250-e2e-docker-builds-share-one-toolchain-cache.md"
  exit 1
fi

echo "OK: all cache mounts in ${#DOCKERFILES[@]} Dockerfiles use shared toolchain ids."
