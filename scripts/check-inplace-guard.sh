#!/usr/bin/env bash
# Phase 315 (FR-315-2) — every writer that changes a media file's own bytes asks the seeding guard first.
#
# A library file is often a hard link to a seeding torrent's data (cross-seed's link dirs, *arr hard-link imports):
# changing it in place changes the torrent's pieces. `SeedingGuard.check` is the one answer to "may this file be
# changed in place?" (path match or a name outside the library ⇒ refused). This script fails when:
#   1. a source file calls `MkvpropeditRunner.set…` (an in-place edit) without calling the guard;
#   2. a source file other than the known ones builds an `mkvpropedit '…'` command (only MkvpropeditRunner runs it;
#      the others only print a plan or check the binary);
#   3. a file passes `inPlace = false` (a writer that renames a new file in) without being one of the known
#      rename-based writers — so a new in-place writer can't opt out of the hard-link check by accident.
set -euo pipefail
cd "$(dirname "$0")/.."
src=src/linuxX64Main/kotlin
fail=0

# 1 — in-place mkvpropedit calls need the guard in the same file.
while IFS= read -r f; do
  if ! grep -qE '(seedingGuard|seeding)\??\.check\(' "$f"; then
    echo "IN-PLACE WRITE WITHOUT GUARD  $f  (calls MkvpropeditRunner.set… but never asks SeedingGuard.check)"; fail=1
  fi
done < <(grep -rlE 'MkvpropeditRunner\.set[A-Za-z]*\(' "$src" --include=*.kt | grep -v '/media/MkvpropeditRunner\.kt$' || true)

# 2 — only these files may contain an mkvpropedit command line.
allowed_cmd='/media/MkvpropeditRunner\.kt$|/server/routes/TrackRoutes\.kt$|/server/routes/MediaRoutes\.kt$|/server/Server\.kt$'
while IFS= read -r f; do
  if ! [[ "$f" =~ $allowed_cmd ]]; then
    echo "MKVPROPEDIT OUTSIDE THE RUNNER  $f  (in-place edits go through MkvpropeditRunner after SeedingGuard.check)"; fail=1
  fi
done < <(grep -rlE "mkvpropedit '" "$src" --include=*.kt || true)

# 3 — only rename-based writers may skip the hard-link check.
allowed_rename='/media/MediaJobQueue\.kt$|/music/MusicTagWriter\.kt$|/music/MusicConvert\.kt$|/audiobooks/AudiobooksMediaService\.kt$|/torrent/SeedingGuard\.kt$'
while IFS= read -r f; do
  if ! [[ "$f" =~ $allowed_rename ]]; then
    echo "HARD-LINK CHECK SKIPPED  $f  (passes inPlace = false but is not a known rename-based writer)"; fail=1
  fi
done < <(grep -rlE 'inPlace = false' "$src" --include=*.kt || true)

if [[ $fail -ne 0 ]]; then
  echo "See specs/requirements/phase-315-a-hard-linked-file-counts-as-seeded.md"
  exit 1
fi
echo "OK — every in-place media writer asks the seeding guard first."
