# Phase R383 — Ravilo on the Mac stays light

> Owner, 2026-10-09: *"I opened the Activity Monitor on the Mac and can see that Ravilo uses 13 GB memory and 400 % CPU
> and has 19 000 idle wakeups, which I don't really know what it means, but it's way above every other application in
> the list. Let's not fix this now, but let's create a spec for it."*

## Status

`Planned` — written 2026-10-09 (dev-authored). Not dev-reviewed, not built. The Mac app (R328–R331, R337): the Compose
Desktop/JVM app, its native player (`ravilo-desktop/native/Player.swift`) and the music/cast engines it runs.

## What was seen

- **Activity Monitor, owner's MacBook (Apple silicon), 2026-10-09 afternoon:** Ravilo **13 GB memory**, **~400 % CPU**,
  **~19 000 idle wakeups**, far above every other app.
- **Read-only check the same minute (`ps`/`top` over ssh):** the process was the **installed release, Ravilo 1.50.0**
  (`/Applications/Ravilo.app`, not a test build), running 49 min, **366 % CPU averaged over its life**, **5.9 GB resident**
  (Activity Monitor's 13 GB is the memory footprint incl. compressed memory), **75 threads**. It was casting music to a
  speaker (handing the speaker its next songs); no film was playing in the window.
- *Idle wakeups* = how often the app wakes a CPU core from sleep per second-ish window (timers, polling, a render loop).
  High numbers drain the battery and keep the Mac warm even when the app looks idle.

## Likely causes (to measure, not to assume)

1. **A render loop that never stops:** Compose Desktop (Skiko) redraws every frame while any animation, progress bar
   or `withFrameNanos` loop is active — e.g. a now-playing progress line, lyrics scrolling, the mini bar or the
   ambient/focus animations — even when the window is hidden or minimised.
2. **No JVM heap limit (confirmed in the build):** `ravilo-desktop/build.gradle.kts` sets only `-Dapple.laf.useScreenMenuBar`
   and `-Dapple.awt.application.name` in `jvmArgs` — no `-Xmx`. The JVM's default max heap is a quarter of RAM, so caches grow until the GC is pressed; artwork
   bitmaps decoded at full size and kept (album covers, backdrops, the player's thumbnails) would explain gigabytes.
3. **Polling:** progress reports, session/queue polling, the cast heartbeat, mDNS discovery and the events socket on
   short timers; each wake-up costs a core.
4. **The native player or ffmpeg/mpv pieces left running** after a play (a decoder thread, a paused AVPlayer item).
5. **A leak:** something added per song (listeners, coroutines, flows) and never removed — the queue hands off a song
   every few minutes while casting.

## Requirements

### FR-R383-1 — Measure first, on the owner's Mac

A measurement run with the release build, recorded in this spec's build notes: idle in the window, window hidden,
casting music to a speaker for 60 min, a film playing in the window, after the film stops. For each: CPU %, memory
footprint, threads, idle wakeups (`top -stats … idlew`, Activity Monitor), plus a JFR recording / heap histogram
(`jcmd <pid> GC.class_histogram`) and the Skiko frame rate. The run names the top consumers by evidence.

### FR-R383-2 — Budgets

| State | CPU | Memory footprint | Idle wakeups/s |
|---|---|---|---|
| Window open, nothing playing | < 2 % | < 600 MB | < 50 |
| Window hidden or minimised | ≈ 0 % | < 600 MB | < 20 |
| Casting music (nothing in the window) | < 5 % | < 700 MB | < 50 |
| Music playing on the Mac itself | < 10 % | < 800 MB | < 100 |
| A 4K film in the window | the decoder's own cost + < 15 % for the app | < 1.5 GB | — |

Memory must not grow over hours: a 4-hour music cast ends within 10 % of where it was after 15 minutes.

### FR-R383-3 — No frames when nothing changes

The app draws only when something on screen changes; a hidden or minimised window draws nothing; progress lines update
at most once a second; animations stop when finished.

### FR-R383-4 — Bounded memory

An explicit max heap for the JVM; artwork decoded at the size it is shown and kept in a bounded cache; caches released
when the window hides; no per-song growth.

### FR-R383-5 — Quiet timers

Timers and polling coalesced and backed off when idle (no sub-second timers while nothing plays); push over the events
socket instead of polling where it exists.

### FR-R383-6 — Visible to us

The app's own diagnostics (Settings ▸ About, and the device's QoE/health report) carry CPU %, memory and wakeups, so the
admin sees a heavy client without someone opening Activity Monitor.

### FR-R383-7 — Tests

A long-running desktop test (JVM, headless where possible) that plays a fake 100-song queue through the cast engine and
asserts memory stays flat; a test that a hidden window requests no frames; the budgets of FR-R383-2 checked by a script
run on the Mac (`scripts/check-mac-resources.sh`) as part of the Mac release checklist.

## Acceptance

1. The FR-R383-1 states, re-measured on the owner's Mac after the fixes, meet FR-R383-2.
2. Activity Monitor no longer lists Ravilo far above other apps while it casts music.

## Open questions (dev)

1. Whether the same happens on Linux (the Flatpak, R333–R335) — the JVM/Compose parts are shared.
2. Whether the 1.50.0 release behaves differently from `main` (the test builds) — measure both.
