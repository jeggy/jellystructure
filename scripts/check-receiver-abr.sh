#!/usr/bin/env bash
# 309 / R291 (2026-10-09) — the Cast receiver's ABR wrapper (ReceiverAbr.kt's FACTORY, a JavaScript string Shaka
# runs) must never switch Shaka to a variant of a previous manifest right after a reload (Shaka error 7999,
# "Cannot read property 'retryParameters' of null"), and must still step down on a genuinely falling buffer.
# Runs the real string under Node with a fake Shaka manager. Node: on PATH, else the one Gradle's Kotlin/JS downloads.
set -euo pipefail
cd "$(dirname "$0")/.."
NODE="$(command -v node || true)"
if [ -z "$NODE" ]; then NODE="$(ls -1 "$HOME"/.gradle/nodejs/*/bin/node 2>/dev/null | tail -1 || true)"; fi
if [ -z "$NODE" ]; then echo "check-receiver-abr: no node found (install Node or run a Kotlin/JS build first)" >&2; exit 2; fi
exec "$NODE" scripts/receiver/receiver-abr-test.js ravilo-cast/src/jsMain/kotlin/dev/jellystructure/ravilo/cast/ReceiverAbr.kt
