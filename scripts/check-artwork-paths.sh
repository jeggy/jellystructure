#!/usr/bin/env bash
# Phase 316 (FR-316-2) — a film's artwork path comes only from assetFilePath (media/ArtworkDownloader.kt), which gives a
# film loose in a library root Jellyfin's per-file names. A folder-level image name (poster.jpg, fanart.jpg,
# clearlogo.png …) built from a directory anywhere else could land in a library root, where Jellyfin reads it as the
# whole library's image: on 2026-10-08 one film's logo showed on other films. This fails on any string literal that
# builds `<dir>/<folder-level image>` outside the files that own those paths.
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0
while IFS= read -r hit; do
  file="${hit%%:*}"; rest="${hit#*:}"; line="${rest%%:*}"; code="${rest#*:}"
  case "$file" in
    */media/ArtworkDownloader.kt|*/media/ArtworkPaths.kt|*/media/LooseFilms.kt) continue ;;
    # Music: an album or an artist always has a folder of its own (its folder images are the album's/artist's).
    */dev/jellystructure/music/*) continue ;;
  esac
  trimmed="$(printf '%s' "$code" | sed 's/^[[:space:]]*//')"
  case "$trimmed" in '//'*|'*'*|'/*'*) continue ;; esac
  echo "$file:$line: $trimmed"
  echo "    ^ a film's artwork path must come from assetFilePath (phase 316), never <dir>/<folder-level image name>."
  fail=1
done < <(grep -rnE '/(poster|fanart|clearlogo|logo|landscape|backdrop|folder|banner|clearart|thumb)\.(jpg|jpeg|png|webp)"' --include='*.kt' src/linuxX64Main src/commonMain 2>/dev/null || true)
if [ "$fail" -ne 0 ]; then
  echo; echo "See specs/requirements/phase-316-a-loose-film-keeps-its-artwork-to-itself.md"; exit 1
fi
echo "OK — every film's artwork path comes from assetFilePath."
