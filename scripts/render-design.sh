#!/usr/bin/env bash
# render-design.sh — render one design HTML file to PNGs in Docker with the repo's Playwright.
#
# DEB-2125. Replaces the per-round render.mjs. Needs nothing on the host but Docker. Run from anywhere;
# paths are taken relative to your current directory; --html must be under design/, --out may be anywhere.
#
#   scripts/render-design.sh --html FILE --viewport WxH (--all SEL | --element SEL --name N | --full-page --name N)
#                            [--query '?w=desktop'] [--scale 2] [--out DIR] [--prefix P]
#
#   --html FILE      the page to render, anywhere under design/
#   --viewport WxH   the browser viewport, e.g. 1440x1000
#   --query STRING   appended to the file URL, e.g. '?w=desktop' or '?mode=phone'
#   --scale N        device scale factor (default 1; a 2x asset needs 2)
#   --all SEL        one PNG per element matching SEL, that is displayed, named after its id (e.g. '.frame')
#   --element SEL    one PNG of the single element matching SEL, named by --name
#   --full-page      one PNG of the whole page, named by --name
#   --name N         the file name (without .png) for --element / --full-page
#   --out DIR        output directory (default: png/ beside the HTML file)
#   --prefix P       literal text put in front of every file name, e.g. 'processing-features-'
#
# One line per PNG is printed, then a count. Any off-host request, or any local file that fails to load,
# fails the run and removes the PNGs it wrote: a half-loaded picture never survives.
#
# Examples (the two pilot rounds):
#   scripts/render-design.sh --html design/incoming/2026-10-22-processing-features/frames.html \
#     --query '?w=desktop' --viewport 1440x1000 --all .frame --prefix processing-features-
#   scripts/render-design.sh --html design/incoming/2026-10-22-mac-open-anyway/dmg.html \
#     --viewport 900x700 --scale 2 --element '#dmg' --name dmg-bg-2x --prefix mac-open-anyway-
#
# How it runs. The image is the one docker-compose.test.yml pins (it supplies the OS libraries). The
# browser comes from tests/package-lock.json's Playwright, installed once into a host cache that is
# mounted as PLAYWRIGHT_BROWSERS_PATH (default ~/.cache/ms-playwright, override with
# RENDER_DESIGN_BROWSERS). The image pin and the lockfile differ ON PURPOSE; do not reconcile them.
# The container runs as you, so no PNG is owned by root. The render itself runs with --network none.
# If tests/node_modules is missing, `npm ci` runs in tests/ first.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

die() { echo "render-design: $*" >&2; exit 2; }

html= query= viewport= scale=1 all= element= name= full= out= prefix=
while [ $# -gt 0 ]; do
  case "$1" in
    --html) html=${2-}; shift 2 ;;
    --query) query=${2-}; shift 2 ;;
    --viewport) viewport=${2-}; shift 2 ;;
    --scale) scale=${2-}; shift 2 ;;
    --all) all=${2-}; shift 2 ;;
    --element) element=${2-}; shift 2 ;;
    --name) name=${2-}; shift 2 ;;
    --full-page) full=1; shift ;;
    --out) out=${2-}; shift 2 ;;
    --prefix) prefix=${2-}; shift 2 ;;
    -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown flag $1 (see --help)" ;;
  esac
done

[ -n "$html" ] || die "--html is required"
[ -n "$viewport" ] || die "--viewport is required"

# A path in the repo, as the container sees it (repo mounted at /work).
repo_rel() {
  local abs
  abs="$(realpath -m -- "$1")"
  case "$abs" in
    "$ROOT"/*) printf '%s' "${abs#"$ROOT"/}" ;;
    *) die "$1 is outside the repository" ;;
  esac
}

[ -f "$html" ] || die "$html does not exist"
html_rel="$(repo_rel "$html")"
case "$html_rel" in design/*) ;; *) die "$html is not under design/" ;; esac
[ -n "$out" ] || out="$(dirname "$html")/png"
# An --out outside the repository (a scratch directory) is mounted separately at /out.
out_abs="$(realpath -m -- "$out")"
out_mount=()
case "$out_abs" in
  "$ROOT"/*) out_rel="${out_abs#"$ROOT"/}" ;;
  *) mkdir -p "$out_abs"; out_mount=(-v "$out_abs":/out); out_rel=/out ;;
esac

IMAGE="$(sed -n 's/^[[:space:]]*image:[[:space:]]*\(mcr\.microsoft\.com\/playwright:[^[:space:]]*\).*/\1/p' docker-compose.test.yml | head -n1)"
[ -n "$IMAGE" ] || die "no Playwright image found in docker-compose.test.yml"

CACHE="${RENDER_DESIGN_BROWSERS:-$HOME/.cache/ms-playwright}"
mkdir -p "$CACHE"

run() { # run [docker flags...] -- cmd...
  local flags=()
  while [ "$1" != -- ]; do flags+=("$1"); shift; done
  shift
  docker run --rm -v "$ROOT":/work -w /work \
    -v "$CACHE":/ms-playwright-host -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host \
    -e HOME=/tmp --user "$(id -u):$(id -g)" "${flags[@]}" "$IMAGE" "$@"
}

if [ ! -f tests/node_modules/playwright/package.json ]; then
  echo "render-design: tests/node_modules is missing, running npm ci in tests/" >&2
  run -- sh -c 'cd /work/tests && npm ci --no-audit --no-fund' >&2 \
    || die "npm ci in tests/ failed; run it yourself and retry"
fi

if ! run --network none -- node scripts/render-design.mjs --check 2>/dev/null; then
  echo "render-design: no matching browser in $CACHE, installing (once)" >&2
  run -- sh -c 'cd /work/tests && npx playwright install chromium' >&2 \
    || die "playwright install failed"
fi

args=(--html "$html_rel" --viewport "$viewport" --scale "$scale" --out "$out_rel" --shown "$out")
[ -z "$query" ] || args+=(--query "$query")
[ -z "$all" ] || args+=(--all "$all")
[ -z "$element" ] || args+=(--element "$element")
[ -z "$full" ] || args+=(--full-page)
[ -z "$name" ] || args+=(--name "$name")
[ -z "$prefix" ] || args+=(--prefix "$prefix")

exec docker run --rm -v "$ROOT":/work -w /work "${out_mount[@]}" \
  -v "$CACHE":/ms-playwright-host -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright-host \
  -e HOME=/tmp --user "$(id -u):$(id -g)" --network none "$IMAGE" \
  node scripts/render-design.mjs "${args[@]}"
