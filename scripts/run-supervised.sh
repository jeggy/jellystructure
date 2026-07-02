#!/usr/bin/env bash
# Phase 118 (FR B.3) — bare-metal supervision for jellystructure.kexe.
#
# The fatal FD_SETSIZE selector crash (KTOR-8703, unfixed upstream — see
# specs/requirements/phase-118-crash-resilience-fd-budget.md) cannot be caught or survived from inside
# the process; the crash hook (installCrashHook in Main.kt) is a last-gasp reporter, not a survival
# mechanism. An external supervisor restarting the process is the actual recovery story. Docker
# deployments already get this for free via `restart: unless-stopped` in docker-compose.yml — this
# script is the equivalent for a bare-metal/systemd-less run. See the sample unit at
# scripts/jellystructure.service for the systemd-managed alternative (preferred where available).
set -uo pipefail
cd "$(dirname "$0")/.."

BINARY="${JELLYSTRUCTURE_BIN:-./jellystructure.kexe}"
BACKOFF_BASE_SECS="${JELLYSTRUCTURE_BACKOFF_BASE:-2}"
BACKOFF_MAX_SECS="${JELLYSTRUCTURE_BACKOFF_MAX:-60}"

backoff=$BACKOFF_BASE_SECS
while true; do
    start_ts=$(date +%s)
    echo "[run-supervised] starting $BINARY"
    "$BINARY"
    exit_code=$?
    end_ts=$(date +%s)
    uptime=$((end_ts - start_ts))
    echo "[run-supervised] $BINARY exited with code $exit_code after ${uptime}s"

    # A process that ran for a while before dying gets the backoff reset — it's the crash-loop case
    # (bad config, corrupt data) that the capped exponential backoff is protecting against.
    if [ "$uptime" -ge 60 ]; then
        backoff=$BACKOFF_BASE_SECS
    fi

    echo "[run-supervised] restarting in ${backoff}s..."
    sleep "$backoff"
    backoff=$((backoff * 2))
    if [ "$backoff" -gt "$BACKOFF_MAX_SECS" ]; then
        backoff=$BACKOFF_MAX_SECS
    fi
done
