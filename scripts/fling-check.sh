#!/usr/bin/env bash
# R316 (FR-R316-3) — the fast-scroll crash check, run on a real device, not assumed.
#
# A fast scroll recycles grid cells mid-download, and Ravilo on Android died of it ("Unbalanced
# enter/exit", Ktor's Android engine). The reproduction that found it: fast swipes on Library, fifteen
# down and ten up per round. No emulator reproduces the timing reliably, so this runs against a phone or
# TV that is signed in, with the screen under test already open (Library, Discover's Studios wall, the
# Request tab). It swipes, then reads the device's own crash log (`dumpsys dropbox`), which keeps what
# `logcat -b crash` loses. Mandatory before a release that touches networking (FR-R316-5).
#
# Usage: ADB_SERIAL=<serial> scripts/fling-check.sh [rounds]        (default 5 rounds)
#        PKG=dev.jellystructure.ravilo.debug scripts/fling-check.sh  (default: the debug build's package)
set -euo pipefail
ROUNDS=${1:-5}
PKG=${PKG:-dev.jellystructure.ravilo.debug}
ADB=(adb); [ -n "${ADB_SERIAL:-}" ] && ADB=(adb -s "$ADB_SERIAL")

dropbox() { "${ADB[@]}" shell dumpsys dropbox --print data_app_crash 2>/dev/null | tr -d '\r'; }
crashes() { dropbox | grep -c "^Process: ${PKG}\$" || true; }  # Ravilo entries in the device's app-crash log
pid() { "${ADB[@]}" shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true; }

[ -n "$(pid)" ] || { echo "FAIL — $PKG is not running; open it on the screen to test first"; exit 2; }
size=$("${ADB[@]}" shell wm size | sed -nE 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/p' | tail -1)
read -r W H <<<"$size"
X=$((W / 2)); LOW=$((H * 85 / 100)); HIGH=$((H * 20 / 100))
before=$(crashes); start_pid=$(pid)

for r in $(seq 1 "$ROUNDS"); do
  for _ in $(seq 1 15); do "${ADB[@]}" shell input swipe "$X" "$LOW" "$X" "$HIGH" 40; done
  for _ in $(seq 1 10); do "${ADB[@]}" shell input swipe "$X" "$HIGH" "$X" "$LOW" 40; done
  now=$(pid)
  if [ "$now" != "$start_pid" ]; then
    echo "FAIL — round $r: $PKG died (pid $start_pid → ${now:-gone})"
    dropbox | grep -A12 "^Process: ${PKG}\$" | tail -14
    exit 1
  fi
done

after=$(crashes)
if [ "$after" -gt "$before" ]; then
  echo "FAIL — $((after - before)) new $PKG crash(es) in dumpsys dropbox:"
  dropbox | grep -A12 "^Process: ${PKG}\$" | tail -14
  exit 1
fi
echo "OK — $ROUNDS rounds of fast flings (15 down, 10 up, 40 ms), $PKG still running, no new crash in dropbox."
