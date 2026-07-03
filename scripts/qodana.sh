#!/usr/bin/env bash
# Runs JetBrains Qodana (Community linter, fully local — no Qodana Cloud, no token) against this
# project and writes a SARIF + HTML report to a gitignored results dir.
#
# A running Gradle daemon holds .gradle/<ver>/fileHashes/fileHashes.lock, which deadlocks Qodana's
# containerised Gradle project-import ("Timeout waiting to lock file hash cache") — so stop it first.
#
# Baseline (Phase 127): qodana-baseline.sarif.json (committed, repo root) is the accepted-as-of state
# — the mechanical/suppressed/documented-keep findings from Phases 126-127. A normal run only reports
# NEW problems against it. Pass --update-baseline to refresh it after a deliberate remediation pass.
set -euo pipefail
cd "$(dirname "$0")/.."

RESULTS_DIR="${QODANA_RESULTS_DIR:-.qodana}"
LINTER="jetbrains/qodana-jvm-community:2026.1"
BASELINE_FILE="qodana-baseline.sarif.json"
UPDATE_BASELINE=0
[[ "${1:-}" == "--update-baseline" ]] && UPDATE_BASELINE=1

log() { echo "[qodana] $*"; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 not found — install $1"; exit 1; }
}

require_cmd docker
require_cmd ./gradlew

log "stopping any running Gradle daemon (avoids a fileHashes.lock deadlock during Qodana's project import)"
./gradlew --stop

mkdir -p "$RESULTS_DIR"

BASELINE_ARGS=()
if [[ -f "$BASELINE_FILE" && "$UPDATE_BASELINE" -eq 0 ]]; then
  log "comparing against committed baseline: $BASELINE_FILE (only new problems will be reported)"
  BASELINE_ARGS=(--baseline "/data/project/$BASELINE_FILE")
fi

log "running $LINTER (offline, no QODANA_TOKEN) — this can take several minutes"
docker run --rm \
  -v "$PWD":/data/project/ \
  -v "$PWD/$RESULTS_DIR":/data/results/ \
  "$LINTER" \
  --save-report --results-dir /data/results/ \
  "${BASELINE_ARGS[@]}"

# The container runs as root, so its project-import step (via the mounted /data/project/) leaves
# root-owned files under build/ (verified: 100+ generated-resource/build-cache files per run) that
# then block the next local `./gradlew` build with AccessDeniedException. Hand ownership back.
log "restoring file ownership (the container writes some build/ artifacts as root)"
docker run --rm -v "$PWD":/w -w /w alpine chown -R "$(id -u):$(id -g)" .

if [[ "$UPDATE_BASELINE" -eq 1 ]]; then
  cp "$RESULTS_DIR/qodana.sarif.json" "$BASELINE_FILE"
  log "baseline updated: $BASELINE_FILE (commit this)"
fi

log "report: $RESULTS_DIR/qodana.sarif.json"
log "HTML:   $RESULTS_DIR/report/  (open $RESULTS_DIR/report/index.html)"
