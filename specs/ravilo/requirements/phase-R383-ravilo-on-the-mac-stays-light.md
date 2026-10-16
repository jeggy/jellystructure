# Phase R383 — Ravilo on the Mac stays light

> Owner, 2026-10-09: *"I opened the Activity Monitor on the Mac and can see that Ravilo uses 13 GB memory and 400 % CPU
> and has 19 000 idle wakeups, which I don't really know what it means, but it's way above every other application in
> the list. Let's not fix this now, but let's create a spec for it."*

## Scope

Written 2026-10-09 (dev-authored). The Mac app (R328–R331, R337): the Compose Desktop/JVM app, its native player
(`ravilo-desktop/native/Player.swift`) and the music/cast engines it runs. The owner's *"not now"* above was lifted on
2026-10-16 (below).

## Decided by the owner (2026-10-16): build it next

> Owner, 2026-10-16 (card `c8500823` on DEB-1406), asked whether to lift 10-09's *"Let's not fix this now"* and queue
> R383 after the four small fixes: *"Yes — queue it next."*

This supersedes the 2026-10-09 *"Let's not fix this now"*. The phase is built on DEB-659.

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

## Build review (2026-10-16, read against `main` `1fb3186b`, before the build)

Nobody reviewed this spec against the code before it was queued, so this section does that. It is written for the
engineer, and it flags where FR-R383-1…7 **cannot be built as written** or lands somewhere the spec does not say. None
of it changes what the phase promises the viewer. Where a flag needs a number or a choice, it says which, and who makes
it.

**One fact first: the heap did not just grow, it ran out.** The installed 1.50.0's last log line on 2026-10-09 was
`OutOfMemoryError: Java heap space` (R329's 10-09 Mac notes). With no `-Xmx`, that heap was a quarter of the Mac's RAM.
So something grows without bound, and **FR-R383-4's max heap on its own only brings the out-of-memory forward.** The cap
ships together with the fix for whatever grows, never before it.

### What cannot be built as written

1. **FR-R383-1 is not a build step, and part of it cannot run on the release.**
   - The measurement needs a Mac, and the engineer's seat has none. It is a QA row on the board's `Mac` type.
   - The released app's jlink runtime has **no `jdk.jfr`**: the modules are `java.instrument`, `java.management`,
     `java.naming`, `java.net.http`, `java.prefs`, `java.sql`, `jdk.crypto.ec`, `jdk.unsupported` and
     `jdk.accessibility` (`ravilo-desktop/build.gradle.kts`). A JFR recording of the release build is therefore
     impossible. `jcmd <pid> GC.class_histogram` from a separate JDK 21 attaches through the VM itself and does work.
   - Nothing in the app reports a frame rate, so "the Skiko frame rate" needs a hook first.
   - **Resolution:** the first slice builds the instruments: an opt-in frame counter
     (`-Dravilo.debug.frames`, frames per minute to the log) and `scripts/check-mac-resources.sh` (FR-R383-7). Add
     `jdk.jfr` to the modules only if the measurement asks for it, because it grows the `.dmg`. QA then runs one Mac
     session against **the current public release and `main`** (open question 2; the public app is 1.52 now, not
     1.50). The fixes follow from that evidence. Bárður files that QA row when the first slice lands.
2. **FR-R383-2's 4K row, *"the decoder's own cost + < 15 % for the app"*, cannot be measured.**
   - `Player.swift` runs inside the app's own process (`libravilo-mac.dylib` over JNA), so Activity Monitor cannot
     split decoder from app.
   - **Read it as:** the same file in QuickTime Player on the same Mac is the baseline, and Ravilo may use at most 15
     points of CPU above it. That is measurable and keeps the intent.
   - The other rows stand. If the measurement shows a 600 MB footprint cannot be reached by a JVM plus Skia at all,
     changing that number is the owner's decision, made after the evidence.
3. **FR-R383-6 has neither of the surfaces it names.**
   - There is **no Settings ▸ About** on the desktop. Settings has Appearance, Language, Playback, Listening and
     Account (`SettingsScreen.kt`). About is a menu-bar window (`AboutWindow.kt`, opened from `RaviloMenuBar.kt`).
     **Read *Settings ▸ About* as that window.**
   - There is **no device health report**, only `PlaybackQoeReport`. It is sent per play (`POST /api/tv/playback/qoe`)
     and only while something plays in the app.
   - So the state that was seen, casting music with nothing in the window, would reach the admin by no road that
     exists. Making it visible means a new periodic post, a store and an admin column: a wire addition across both
     trees, so `jellystructure-admin` too.
   - **Recommendation:** build the About-window half and the native reading now. The reading is a new `@_cdecl` in
     `MacNative` over `proc_pid_rusage(RUSAGE_INFO_V4)` for `ri_phys_footprint` and `ri_pkg_idle_wkups`; the JNA
     bridge already exists, and `ci / macos` is its only compile check. Leave the admin half for after the fixes,
     when there is something to watch for. That does not narrow what the phase promises. If the admin half is
     wanted sooner, it is a separate task.
4. **FR-R383-7's queue test needs a seam that does not exist yet.**
   - `MusicCast` is an `object` that holds a concrete `CastController`, and the desktop sender `CastSenderDesktop` is
     an `object` that opens real sockets. A fake 100-song queue cannot go through them without a refactor.
   - The seam that does exist is `ravilo-castv2`'s `CastSession` over a `CastTransport` interface, driven by
     `CastSessionTest`'s `FakeDevice`. The hand-off is tested today only as pure functions (`CastHandOverTest`,
     `CastHandBackTest`).
   - Either inject the sender into `MusicCast`, or drive the test at the `CastSession` level and accept that it does
     not cover `MusicCast`'s own state. Both are fine. The refactor is part of the cost, so expect M, not S.
   - *A hidden window requests no frames* needs the frame counter from item 1.
   - *"Part of the Mac release checklist"*: no such checklist exists in the repository. The nearest is R330's
     acceptance checklist. Releases are Tróndur's, so the build ships the script and Tróndur decides where the
     release flow runs it.

### What is buildable, and where the evidence already points

- **FR-R383-3 (frames):** the window's state is already tracked. `rememberAppOnScreen()` is `shown && !minimised`
  (`DesktopWindowSeams.kt`), but it gates only network work. Composition, the infinite animations (`Shimmer.kt`, the
  music `Pulse`, several in `PlayerScreen.kt`) and the 200–500 ms position loops keep running in a minimised window.
  Gate them on it.
  - **Exception to *"progress lines at most once a second"*:** synced lyrics need sub-second timing, and so does a
    seek bar while it is dragged. The once-a-second rule is for a progress line nobody is touching.
- **FR-R383-4 (memory):**
  - The heap flag goes in `compose.desktop { application { jvmArgs } }`. That block is shared with the Linux build,
    which is wanted (open question 1). The value is the engineer's to set from the measurement, inside FR-R383-2's
    footprint.
  - Coil's memory cache is `maxSizePercent(0.20)` of the max heap, so today it has no fixed ceiling. The `-Xmx` bounds
    it.
  - **"Decoded at the size it is shown" is client work.** The server already resizes every proxied image and honours
    `?w=` (`RaviloArtworkService.resizeInto`: posters 320 px by default, backdrops 1920, stills 640; music art 480 in
    `MusicTvRoutes`). What is left is on the client: absolute TMDB/https URLs skip the proxy and arrive full size, and
    Coil's decode size is not tied to the slot.
  - Nothing clears the cache when the window hides.
- **FR-R383-5 (timers):** the ones that run with nothing playing, by cost:
  - `MacPlayer`'s **50 ms** tick runs for as long as a player handle exists, and stops only on `release()`.
  - `DesktopAppearance` makes two JNA calls every second, forever. The Mac posts
    `AppleInterfaceThemeChangedNotification`, so this can be push.
  - `Main.kt` re-reads the menu-bar language file every 2 s, forever.
  - The Bonjour snapshot runs every 1 s while discovery is held.
  - The events socket (`/api/tv/events`) already pushes, so the 15 s config poll is the one to look at.

### Build order (one engineer, on DEB-659)

1. **Instruments:** the frame counter and `check-mac-resources.sh`. S. Then the QA measurement on a Mac.
2. **The leak, the heap cap and the image sizes** (FR-R383-4), in one release, for the out-of-memory reason above. M.
3. **Frames and timers** (FR-R383-3, FR-R383-5). M.
4. **The About-window figures** (FR-R383-6, its client half) and the remaining FR-R383-7 tests. S–M.
5. QA re-measures against FR-R383-2 (acceptance 1–2).
