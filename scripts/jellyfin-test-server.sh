#!/usr/bin/env bash
# Phase 240 (FR-240-5) — provision a disposable, SEEDED Jellyfin and print the three env vars the
# live route guard and the model guard read.
#
# ## Why seeded, and not "just a fresh container"
#
# Open question 1 asked: fresh-and-empty, or seeded? It answers itself once you try it. A fresh
# `jellyfin/jellyfin` issues no token at all until its **startup wizard** is completed
# (`/Startup/Configuration`, `/Startup/User`, `/Startup/Complete`), so the wizard has to be scripted
# either way — and once it is, adding a media file and triggering a scan is a few more lines. Eight of
# the eleven shapes in `JellyfinLiveRouteGuardTest` are item-specific and return early without an
# item, so an **unseeded** container buys a guard that tests roughly a third of what it claims. That
# weakness is the thing phase 240 exists to remove.
#
# ## Never the household
#
# This script only ever talks to a container it created itself, on a random high port, with a
# throwaway password. The household server is not reachable from anything here by construction.
#
# ## Usage
#
#   eval "$(./scripts/jellyfin-test-server.sh up)"     # prints the three exports
#   ./scripts/check-jellyfin-models.sh
#   ./gradlew linuxX64Test --tests '*JellyfinLiveRouteGuard*'
#   ./scripts/jellyfin-test-server.sh down
set -euo pipefail
cd "$(dirname "$0")/.."

NAME="${JELLYFIN_TEST_CONTAINER:-jellystructure-jellyfin-guard}"
IMAGE="${JELLYFIN_TEST_IMAGE:-jellyfin/jellyfin:latest}"
PORT="${JELLYFIN_TEST_PORT:-18196}"
USER_NAME="guard"
USER_PASS="guard-throwaway-$$"
STATE_DIR="${TMPDIR:-/tmp}/$NAME"

api() {  # api <method> <path> [json-body]
  local method=$1 path=$2 body=${3:-}
  if [ -n "$body" ]; then
    curl -s -m 30 -X "$method" -H 'Content-Type: application/json' \
      -H 'Authorization: MediaBrowser Client="guard", Device="guard", DeviceId="guard", Version="1.0"' \
      -d "$body" "http://127.0.0.1:$PORT$path"
  else
    curl -s -m 30 -X "$method" \
      -H 'Authorization: MediaBrowser Client="guard", Device="guard", DeviceId="guard", Version="1.0"' \
      "http://127.0.0.1:$PORT$path"
  fi
}

cmd_down() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  # Fall back to removing the state from inside a container: if a previous run let Jellyfin write as
  # root, the host user cannot delete /config, and the next `up` then fails on a half-removed dir.
  rm -rf "$STATE_DIR" 2>/dev/null || \
    docker run --rm -v "$STATE_DIR:/gone" alpine:3 sh -c 'rm -rf /gone/* /gone/.[!.]* 2>/dev/null || true' >/dev/null 2>&1 || true
  rm -rf "$STATE_DIR" 2>/dev/null || true
  echo "removed $NAME" >&2
}

cmd_up() {
  cmd_down
  mkdir -p "$STATE_DIR/config" "$STATE_DIR/cache" "$STATE_DIR/media/Movies/Guard Sample (2026)"

  # One tiny, real media file so the item-specific route shapes have something to resolve. ffmpeg is
  # already a hard dependency of this project (README Requirements), so this adds nothing new.
  local sample="$STATE_DIR/media/Movies/Guard Sample (2026)/Guard Sample (2026).mp4"
  ffmpeg -nostdin -loglevel error -y \
    -f lavfi -i "testsrc=size=320x240:rate=10:duration=2" \
    -f lavfi -i "sine=frequency=440:duration=2" \
    -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$sample"

  # Runs as the invoking user, not root: the state dir lives under the host's temp and `down` has to
  # be able to remove it. (Also the project's own rule — a Docker image is tested as non-root.)
  docker run -d --name "$NAME" -p "127.0.0.1:$PORT:8096" \
    --user "$(id -u):$(id -g)" \
    -v "$STATE_DIR/config:/config" -v "$STATE_DIR/cache:/cache" \
    -v "$STATE_DIR/media:/media:ro" \
    "$IMAGE" >/dev/null

  echo "waiting for $NAME on :$PORT …" >&2
  for _ in $(seq 1 90); do
    if curl -sf -m 3 "http://127.0.0.1:$PORT/System/Info/Public" >/dev/null 2>&1; then break; fi
    sleep 2
  done
  curl -sf -m 5 "http://127.0.0.1:$PORT/System/Info/Public" >/dev/null || {
    echo "jellyfin never came up; last logs:" >&2; docker logs --tail 40 "$NAME" >&2; exit 1
  }

  # ── The startup wizard, the part that makes "just run a container" not actually cheap ──
  # Retried as a whole: /System/Info/Public answers before the wizard endpoints are ready to accept a
  # POST, so a single straight-line pass leaves the wizard half-done — and the only symptom is that
  # AuthenticateByName later returns the wizard's HTML page instead of a token. Poll the server's own
  # StartupWizardCompleted rather than trusting the 204s.
  local wizard_done=false
  for _ in $(seq 1 10); do
    api POST "/Startup/Configuration" '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}' >/dev/null
    api GET  "/Startup/User" >/dev/null
    api POST "/Startup/User" "{\"Name\":\"$USER_NAME\",\"Password\":\"$USER_PASS\"}" >/dev/null
    api POST "/Startup/RemoteAccess" '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}' >/dev/null
    api POST "/Startup/Complete" '' >/dev/null
    if curl -s -m 5 "http://127.0.0.1:$PORT/System/Info/Public" \
         | grep -q '"StartupWizardCompleted":true'; then wizard_done=true; break; fi
    sleep 2
  done
  [ "$wizard_done" = true ] || { echo "startup wizard never completed on $NAME" >&2; docker logs --tail 40 "$NAME" >&2; exit 1; }

  local auth token user_id
  auth=$(api POST "/Users/AuthenticateByName" "{\"Username\":\"$USER_NAME\",\"Pw\":\"$USER_PASS\"}")
  token=$(printf '%s' "$auth" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("AccessToken",""))' 2>/dev/null || true)
  user_id=$(printf '%s' "$auth" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("User",{}).get("Id",""))' 2>/dev/null || true)
  # Truncated: an incomplete wizard answers this call with a full HTML page, and dumping it buries
  # the actual message.
  [ -n "$token" ] || { echo "sign-in failed: $(printf '%s' "$auth" | head -c 200)" >&2; exit 1; }

  # ── Seed: one library over the sample, then scan and wait for the item to appear ──
  curl -s -m 30 -X POST \
    -H "Authorization: MediaBrowser Client=\"guard\", Device=\"guard\", DeviceId=\"guard\", Version=\"1.0\", Token=\"$token\"" \
    -H 'Content-Type: application/json' \
    "http://127.0.0.1:$PORT/Library/VirtualFolders?name=Movies&collectionType=movies&paths=/media/Movies&refreshLibrary=true" \
    -d '{"LibraryOptions":{"EnableRealtimeMonitor":false}}' >/dev/null

  echo "scanning …" >&2
  local items=0
  for _ in $(seq 1 60); do
    items=$(curl -s -m 15 \
      -H "Authorization: MediaBrowser Client=\"guard\", Device=\"guard\", DeviceId=\"guard\", Version=\"1.0\", Token=\"$token\"" \
      "http://127.0.0.1:$PORT/Items?Recursive=true&IncludeItemTypes=Movie&Limit=1" \
      | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("TotalRecordCount",0))
except Exception: print(0)' 2>/dev/null || echo 0)
    [ "$items" -gt 0 ] && break
    sleep 2
  done
  if [ "$items" -eq 0 ]; then
    echo "WARNING: the seed never appeared — the guard will fall back to its empty-library path," >&2
    echo "         which tests roughly a third of what it claims. Check 'docker logs $NAME'." >&2
  fi

  echo "export JELLYFIN_LIVE_TEST_URL=http://127.0.0.1:$PORT"
  echo "export JELLYFIN_LIVE_TEST_TOKEN=$token"
  echo "export JELLYFIN_LIVE_TEST_USER_ID=$user_id"
}

case "${1:-up}" in
  up) cmd_up ;;
  down) cmd_down ;;
  *) echo "usage: $0 [up|down]" >&2; exit 2 ;;
esac
