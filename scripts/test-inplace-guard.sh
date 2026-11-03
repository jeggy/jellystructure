#!/usr/bin/env bash
# 315 FR-315-6 — the guard script passes on the real tree and still fails on what it exists to catch. Each case runs the
# script in a scratch copy of the two route files plus the one file the case adds.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
routes=src/linuxX64Main/kotlin/dev/jellystructure/server/routes

fresh() {
  rm -rf "$tmp/t"; mkdir -p "$tmp/t/scripts" "$tmp/t/$routes"
  cp "$here/scripts/check-inplace-guard.sh" "$tmp/t/scripts/"
  cp "$here/$routes/TrackRoutes.kt" "$here/$routes/MediaRoutes.kt" "$here/$routes/RemuxGuard.kt" "$tmp/t/$routes/"
}
run() { (cd "$tmp/t" && bash scripts/check-inplace-guard.sh >"$tmp/out" 2>&1); }

fresh
run || { cat "$tmp/out"; echo "FAIL: the four remux call sites should pass"; exit 1; }

fresh
mkdir -p "$tmp/t/src/linuxX64Main/kotlin"
printf 'fun x() { MkvpropeditRunner.setLanguage("a", "b") }\n' > "$tmp/t/src/linuxX64Main/kotlin/Rogue.kt"
run && { echo "FAIL: an unguarded MkvpropeditRunner.set… call should fail"; exit 1; }
grep -q 'IN-PLACE WRITE WITHOUT GUARD' "$tmp/out" || { cat "$tmp/out"; echo "FAIL: wrong message"; exit 1; }

fresh
printf '        seedingGuard.check(p, cfg, inPlace = false)\n' >> "$tmp/t/$routes/TrackRoutes.kt"
run && { echo "FAIL: an inPlace = false written in TrackRoutes.kt should fail"; exit 1; }
grep -q 'exactly 2 refuseRemuxIfSeeded calls' "$tmp/out" || { cat "$tmp/out"; echo "FAIL: wrong message"; exit 1; }

fresh
sed -i '0,/refuseRemuxIfSeeded(/s//refuseRemuxIfNothing(/' "$tmp/t/$routes/MediaRoutes.kt"
run && { echo "FAIL: a remux route that stops calling the helper should fail"; exit 1; }
grep -q 'exactly 2 refuseRemuxIfSeeded calls' "$tmp/out" || { cat "$tmp/out"; echo "FAIL: wrong message"; exit 1; }

echo "OK — check-inplace-guard.sh passes the four remux call sites and fails the three cases it exists for."
