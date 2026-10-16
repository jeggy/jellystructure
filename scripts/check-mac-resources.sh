#!/usr/bin/env bash
# R383 (FR-R383-1, FR-R383-2, FR-R383-7) — what Ravilo costs on a Mac, in one state, against that state's budget.
#
# Run it ON A MAC, with the app in the state you name, e.g. for the "casting music, nothing in the window" row:
#
#     scripts/check-mac-resources.sh cast                  # samples the running Ravilo for 30 s
#     scripts/check-mac-resources.sh hidden --seconds 60 --histogram
#
# States and their budgets are FR-R383-2's table:
#
#     state    what the app is doing                   CPU %    footprint   idle wakeups/s
#     idle     window open, nothing playing            < 2      < 600 MB    < 50
#     hidden   window hidden or minimised              < 0.5    < 600 MB    < 20      (the spec's "≈ 0 %")
#     cast     casting music, nothing in the window    < 5      < 700 MB    < 50
#     music    music playing on the Mac itself         < 10     < 800 MB    < 100
#     film     a 4K film in the window                 baseline + 15 points   < 1.5 GB  (see below)
#
# `film` has no fixed CPU number: the player runs inside the app's own process (Player.swift over JNA), so the
# decoder cannot be told from the app. Play the same file in QuickTime Player on the same Mac first, pass its CPU % as
# `--baseline <percent>`, and Ravilo may use at most 15 points more (build review item 2).
#
# Prints one `key=value` block (so a QA row can paste it), then PASS or the breached budgets, and exits 1 on a breach.
# CPU is `top`'s figure for the sample interval (100 % = one core, as Activity Monitor shows). IDLEW is read as a count
# for that interval and divided by it — top's man page does not say so outright, so the first run on a Mac should be
# checked once against Activity Monitor's "Idle Wake Ups" column; the raw top row is printed to make that easy.
# Frames: start the app with `-Dravilo.debug.frames` (or RAVILO_DEBUG_FRAMES=1) and the last `frames:` line of its
# log (FrameCounter.kt) is printed too — a hidden window should read 0/s (FR-R383-3). It is information, not a budget.
#
#     scripts/check-mac-resources.sh --selftest            # the parsers against canned output; runs on any host
set -euo pipefail

# ─── parsers: stdin → one number ─────────────────────────────────────────────────────────────────

# "512M", "1.5G", "840K", "512M+" → megabytes
to_mb() {
  awk '{ v = $1; sub(/[+-]$/, "", v); u = v; sub(/^[0-9.]+/, "", u); sub(/[^0-9.].*$/, "", v)
         m = (u ~ /^[Gg]/) ? 1024 : (u ~ /^[Kk]/) ? 1/1024 : (u ~ /^[Bb]/) ? 1/1048576 : 1
         printf "%.1f\n", v * m }'
}

# top's last row for the pid, whole line (columns: pid command %cpu mem #th idlew)
top_row() { awk -v pid="$1" '$1 == pid { row = $0 } END { print row }'; }

# `footprint -p <pid>` → "Footprint: 357 MB (16384 bytes per page)" → megabytes
footprint_mb() {
  awk '/^[[:space:]]*Footprint:/ { v = $2; u = $3; m = (u ~ /^G/) ? 1024 : (u ~ /^K/) ? 1/1024 : (u ~ /^B/) ? 1/1048576 : 1
                                  printf "%.1f\n", v * m; exit }'
}

num() { sed 's/[^0-9.].*$//'; }
lt() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a < b) }'; }

selftest() {
  local fail=0
  check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2', want '$3'"; fail=1; fi; }
  check "to_mb M"      "$(echo 512M | to_mb)"   "512.0"
  check "to_mb G"      "$(echo 1.5G | to_mb)"   "1536.0"
  check "to_mb K"      "$(echo 2048K | to_mb)"  "2.0"
  check "to_mb suffix" "$(echo '512M+' | to_mb)" "512.0"
  check "footprint MB" "$(printf 'Ravilo [1234]: 64-bit\n    Footprint: 357 MB (16384 bytes per page)\n' | footprint_mb)" "357.0"
  check "footprint GB" "$(printf '    Footprint: 1.3 GB (16384 bytes per page)\n' | footprint_mb)" "1331.2"
  local row
  row="$(printf 'PID COMMAND %%CPU MEM #TH IDLEW\n1234 Ravilo 366.0 5900M 75 19000\n99 other 1 1M 1 1\n1234 Ravilo 3.2 512M+ 75 120\n' | top_row 1234)"
  check "top_row takes the last sample" "$row" "1234 Ravilo 3.2 512M+ 75 120"
  check "cpu column"     "$(echo "$row" | awk '{print $3}' | num)" "3.2"
  check "threads column" "$(echo "$row" | awk '{print $5}' | num)" "75"
  check "idlew column"   "$(echo "$row" | awk '{print $6}' | num)" "120"
  lt 1.9 2 && check "lt true" ok ok || check "lt true" no ok
  lt 2 2 && check "lt equal is not under" no ok || check "lt equal is not under" ok ok
  exit "$fail"
}

# ─── the run ────────────────────────────────────────────────────────────────────────────────────

[ "${1:-}" = "--selftest" ] && selftest

state="${1:-}"; shift || true
seconds=30; pid=""; baseline=""; histogram=0
while [ $# -gt 0 ]; do
  case "$1" in
    --seconds) seconds="$2"; shift 2 ;;
    --pid) pid="$2"; shift 2 ;;
    --baseline) baseline="$2"; shift 2 ;;
    --histogram) histogram=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

case "$state" in
  idle)   cpu_max=2;    mb_max=600;  wake_max=50 ;;
  hidden) cpu_max=0.5;  mb_max=600;  wake_max=20 ;;
  cast)   cpu_max=5;    mb_max=700;  wake_max=50 ;;
  music)  cpu_max=10;   mb_max=800;  wake_max=100 ;;
  film)   cpu_max="";   mb_max=1536; wake_max="" ;;
  *) echo "usage: $0 idle|hidden|cast|music|film [--seconds N] [--pid PID] [--baseline CPU%] [--histogram]  |  --selftest" >&2; exit 2 ;;
esac
if [ "$state" = film ]; then
  [ -n "$baseline" ] || { echo "film needs --baseline <QuickTime Player's CPU % for the same file>" >&2; exit 2; }
  cpu_max="$(awk -v b="$baseline" 'BEGIN { print b + 15 }')"
fi
[ "$(uname)" = Darwin ] || { echo "this measures a Mac's process: run it on the Mac (use --selftest elsewhere)" >&2; exit 2; }

if [ -z "$pid" ]; then
  pid="$(pgrep -x Ravilo | head -n 1 || true)"
  [ -n "$pid" ] || { echo "no running Ravilo (pgrep -x Ravilo); pass --pid for a 'gradle run' java process" >&2; exit 2; }
fi

# Two samples: the first is since-launch, the second covers exactly the interval we asked for.
raw="$(top -l 2 -s "$seconds" -pid "$pid" -stats pid,command,cpu,mem,threads,idlew)"
row="$(printf '%s\n' "$raw" | top_row "$pid")"
[ -n "$row" ] || { echo "top printed no row for pid $pid" >&2; exit 2; }
cpu="$(echo "$row" | awk '{print $3}' | num)"
threads="$(echo "$row" | awk '{print $5}' | num)"
idlew="$(echo "$row" | awk '{print $6}' | num)"
wakeups="$(awk -v n="$idlew" -v s="$seconds" 'BEGIN { printf "%.1f", n / s }')"
# Footprint is what Activity Monitor's Memory column shows (it counts compressed pages); top's MEM does not.
mb="$(footprint -p "$pid" 2>/dev/null | footprint_mb || true)"
[ -n "$mb" ] || mb="$(echo "$row" | awk '{print $4}' | to_mb)"

log="$HOME/Library/Logs/Ravilo/ravilo.log"
frames="$( { grep ' frames: ' "$log" 2>/dev/null || true; } | tail -n 1 )"

echo "state=$state pid=$pid seconds=$seconds"
echo "cpu_percent=$cpu"
echo "footprint_mb=$mb"
echo "threads=$threads"
echo "idle_wakeups_per_s=$wakeups   (top idlew=$idlew over $seconds s)"
echo "top_row=$row"
echo "last_frames_line=${frames:-none (start the app with -Dravilo.debug.frames)}"
if [ "$histogram" = 1 ]; then
  if command -v jcmd >/dev/null; then
    echo "--- jcmd $pid GC.class_histogram (top 25) ---"
    jcmd "$pid" GC.class_histogram | head -n 28
  else
    echo "no jcmd on PATH: install a JDK 21 (the release runtime has none; jcmd attaches from outside)" >&2
  fi
fi

fail=0
breach() { echo "BREACH $1"; fail=1; }
lt "$cpu" "$cpu_max" || breach "cpu $cpu % is not under $cpu_max %"
lt "$mb" "$mb_max" || breach "footprint $mb MB is not under $mb_max MB"
if [ -n "$wake_max" ]; then lt "$wakeups" "$wake_max" || breach "idle wakeups $wakeups/s is not under $wake_max/s"; fi
[ "$fail" = 0 ] && echo "PASS ($state)"
exit "$fail"
