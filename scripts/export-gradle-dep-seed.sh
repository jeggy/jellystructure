#!/usr/bin/env bash
# DEB-5992 FR-8 — write the Gradle dependency set the app image's build ends with to <dest>/modules-2, for actions/cache to
# carry to the next build. Run right after `docker compose build app`, before the prune that deletes its layers, so the
# build below is a cache hit that only copies. It never fails the build: a set that cannot be exported is a set that is not
# carried, and the next build downloads as it did before.
#
#   export-gradle-dep-seed.sh <dest> [<seed dir>]
#
# <seed dir> is the carried set the build was seeded with (the named context its layers were keyed on); it must be the same
# directory, or the fetch layer is not a hit and the whole image builds a second time.
set -uo pipefail
cd "$(dirname "$0")/.."

dest="${1:?usage: export-gradle-dep-seed.sh <dest> [<seed dir>]}"
seed="${2:-./scripts/gradle-dep-seed-empty}"
cache="${BUILDX_CACHE_DIR:-/tmp/.buildx-cache}/app"

rm -rf "$dest"; mkdir -p "$dest"
args=(--target gradle-dep-export --output "type=local,dest=$dest" --build-context "gradle-dep-seed=$seed")
[ -d "$cache" ] && args+=(--cache-from "type=local,src=$cache")
if ! timeout 600 docker buildx build "${args[@]}" -f Dockerfile .; then
  echo "export-gradle-dep-seed: the export build failed; no set is carried from this run" >&2
  rm -rf "$dest"; exit 0
fi
if [ ! -d "$dest/modules-2/files-2.1" ]; then
  echo "export-gradle-dep-seed: the build left no dependency cache; no set is carried from this run" >&2
  rm -rf "$dest"; exit 0
fi
echo "export-gradle-dep-seed: carried set is $(du -sh "$dest" | cut -f1) ($(find "$dest/modules-2/files-2.1" -mindepth 1 -maxdepth 1 | wc -l) groups)"
