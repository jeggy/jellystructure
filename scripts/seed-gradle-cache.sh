#!/usr/bin/env bash
# DEB-5992 FR-8 — start the image build's Gradle dependency cache from the set a recent build on `main` carried, so a
# warm build asks Maven Central and the Plugin Portal for nothing.
#
#   seed-gradle-cache.sh <seed dir>
#
# <seed dir> holds a `modules-2` (the `caches/modules-2` of an earlier build's Gradle home). It is copied into
# $GRADLE_USER_HOME only when that home has no modules-2 yet: a cache mount that already holds artifacts is newer than
# any carried set. Seeding the writable home, rather than pointing GRADLE_RO_DEP_CACHE at the set, is what keeps the
# set whole: a read-only cache is never written, so a build that used one would export only what it downloaded itself.
# A missing, empty or unreadable seed is not an error: the build downloads everything, as it did before FR-8.
set -uo pipefail

seed="${1:-}"
home="${GRADLE_USER_HOME:-$HOME/.gradle}"

if [ ! -d "$seed/modules-2/files-2.1" ]; then
  echo "seed-gradle-cache: no carried dependency set; everything is downloaded"
  exit 0
fi
if [ -d "$home/caches/modules-2/files-2.1" ] && [ -n "$(ls -A "$home/caches/modules-2/files-2.1" 2>/dev/null)" ]; then
  echo "seed-gradle-cache: $home already holds a dependency cache; the carried set is not used"
  exit 0
fi

mkdir -p "$home/caches"
if cp -a "$seed/modules-2" "$home/caches/"; then
  echo "seed-gradle-cache: seeded $home/caches/modules-2 from the carried set ($(du -sh "$home/caches/modules-2" | cut -f1))"
else
  echo "seed-gradle-cache: copying the carried set failed; discarding it and downloading everything"
  rm -rf "$home/caches/modules-2"
fi
exit 0
