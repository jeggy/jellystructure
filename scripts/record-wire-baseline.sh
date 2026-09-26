#!/usr/bin/env bash
# R319 (FR-R319-2) — regenerate the wire baseline: record the contract of every release from the floor to
# the newest tag, each in a temporary worktree, and merge them into WireBaseline.kt.
#
#   scripts/record-wire-baseline.sh            # keeps the floor in WireBaseline.kt
#   scripts/record-wire-baseline.sh v1.30      # moves the floor (FR-R319-4: drops support for older apps)
#
# Takes a few minutes per release. Commit WireBaseline.kt afterwards.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
D=shared/src/linuxX64Test/kotlin/dev/jellystructure/shared/wire
FLOOR=${1:-$(sed -n 's/.*WIRE_FLOOR = "\([^"]*\)".*/\1/p' "$D/WireBaseline.kt" 2>/dev/null || true)}
[ -n "$FLOOR" ] || { echo "usage: $0 <floor-tag>   (no WireBaseline.kt yet)"; exit 2; }
WORK=${WIRE_WORK:-$(mktemp -d)}
OUT="$WORK/contracts"; mkdir -p "$OUT"
TAGS=$(git tag -l 'v*' --sort=v:refname | awk -v f="$FLOOR" '$0==f{on=1} on')
[ -n "$TAGS" ] || { echo "no tags from $FLOOR"; exit 2; }
for t in $TAGS; do
  [ -s "$OUT/$t.json" ] && { echo "$t: already recorded"; continue; }
  wt="$WORK/wt-$t"
  git worktree add -q --detach "$wt" "$t"
  cp -f local.properties "$wt/" 2>/dev/null || true
  mkdir -p "$wt/$D"
  cp "$D/WireContract.kt" "$D/WireContractRecordTest.kt" "$wt/$D/"
  python3 scripts/wire_roots.py "$wt" > "$wt/$D/WireRoots.kt"
  if (cd "$wt" && WIRE_CONTRACT_OUT="$OUT/$t.json" ./gradlew :shared:linuxX64Test --tests '*WireContractRecordTest*' -q --console=plain >"$WORK/$t.log" 2>&1) && [ -s "$OUT/$t.json" ]; then
    echo "$t: recorded ($(wc -c <"$OUT/$t.json") bytes)"
  else
    echo "$t: FAILED — see $WORK/$t.log"; exit 1
  fi
  git worktree remove --force "$wt"
done
WIRE_BASELINE_DIR="$OUT" WIRE_BASELINE_KT="$ROOT/$D/WireBaseline.kt" WIRE_FLOOR="$FLOOR" \
  ./gradlew :shared:linuxX64Test --tests '*WireBaselineMergeTest*' -q --console=plain
echo "WireBaseline.kt regenerated from $(echo $TAGS | wc -w) releases ($FLOOR ..). Commit it."
