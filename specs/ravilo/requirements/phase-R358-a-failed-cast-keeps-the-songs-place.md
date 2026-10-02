# Phase R358 — A cast that never started gives the song back where it was

> Found 2026-10-02 in the Mac cast test (R330/R353 sender on the MacBook, music to a speaker whose Jellyfin token
> was dead): a song playing at about 0:30 was cast; the speaker launched Ravilo's receiver but never got a stream
> (the backend logged the receiver's token as rejected, HTTP 401); the bar showed 0:00; after *Stop casting* the song
> came back on the Mac at **0:00**. Owner, 2026-10-02: *"Also this part — a failed cast resets the song to 0:00."*

## Status

`✓ Built` 2026-10-02 — deployed with the Mac test build `v1.48-117-gf0fb5fe0`, **device-tested on the Mac**: a cast that never played (the 487-song LOAD of R359) held the bar at 1:17 and *Stop casting* gave the song back at 1:17, paused (it came back at 0:00 before); the normal path gave back the speaker's place (1:01). Android not re-tested (see Build notes). Written 2026-10-02 (dev-authored,
owner-approved the same day) against `main` `91a12ee8`. Number checked
free (Ravilo specs top at R357). **Amends** R353 FR-R353-5 (the hand-back keeps the speaker's place — but only a
place the speaker actually reported) and R330 (the Mac's cast sender). Applies to every sender that hands music to a
speaker and back: the Mac and Linux desktop sender and the Android sender. No wire change, no string, no UI change.

## What is wrong

When a song moves to a speaker, the sender's bar follows the speaker. A speaker that never plays reports no media
status, so the bar sits at 0:00. Ending the cast hands back "where the speaker is", and with no report that is
read as 0:00, so the song restarts.

## Requirements

- **FR-R358-1 — the place handed back.** Ending a cast (Stop casting, *Play on this phone/this computer*, the cast
  dropping, a Cast error) hands the music back:
  - at the speaker's last **reported** song and position, if the speaker ever reported playing (R353 FR-R353-5,
    unchanged);
  - otherwise at the song and position the sender had **when it handed the music over**. A position of 0 that nobody
    reported is never a place.
- **FR-R358-2 — while waiting.** Until the speaker's first report, the sender's bar shows the hand-over song and
  position (not 0:00). It stays paused-looking until the speaker reports playing.
- **FR-R358-3 — the hand-back is paused** where it lands, as R353 already does; Play resumes from there.
- **FR-R358-4 — one rule for every sender.** The rule lives in common code (the shared hand-back/merge both senders
  use), with a unit test: hand over at 30 s, no report, stop ⇒ the same song at 30 s; hand over at 30 s, a report at
  45 s on the next song, stop ⇒ that song at 45 s.

## Verification

- Unit: the cases above, plus a report of position 0 while the receiver is still loading (no playing state) is not
  a place.
- Live, on the Mac: play a song, seek to ~0:30, cast to a speaker that cannot play (or kill the cast before the first
  report), Stop casting ⇒ the song is back at ~0:30, paused. Only the Gæsteværelse speaker or the Køkken hub; never
  the Stue speaker, the Stue TV or the Soveværelse TV.

## Build notes (2026-10-02) — not deployed, not device-tested

**Root cause.** Both senders, the moment they send a music queue, put their own status in place before the device has
said anything: `CastSenderDesktop.castMusic` (and `CastSenderAndroid`'s) sets `CastRemoteStatus(loaded = true,
buffering = true, music = true, queue, queueIndex)` with `positionMs` left at 0. `castMusicLive` reads that as a live
report, so `MusicCast` was *linked*: the bar was `MusicCast.state(st)` at **0:00**, and that status was kept as
`lastMusic`. A receiver whose token is rejected says only `noserver` (`failLoad`), which leaves the item loaded and
the position at 0, so nothing ever replaced it. *Stop casting* → `MusicCast.stop()` → `takeBack()` (still linked) →
`MusicEngine.loadPaused(queue, index, MusicPlayback.currentPositionMs() = 0)` — the song came back at 0:00. A session
that dropped instead would have handed back the same 0 through `castHandBack(lastMusic)`.

**Built** (common code, `MusicCast.kt`; both senders reach it):
- `CastHandOver(queue, index, positionMs)` — recorded by `handOff()` (the engine's queue and position, which also
  covers R353's `resumeOnDevice`) and by `playQueue()` (an album started while casting: its first song at 0).
- `castReportIsAPlace(st, handOver)` — a report is the device's own place when it says *playing*, or names another
  song than the one handed over (the speaker moved on). The senders' loading status, a receiver still loading at 0,
  and `noserver` are not. With nothing handed over (a plain join) every report counts, as before.
- `castShownStatus(st, handOver, heard)` — until a report was a place, the status `MusicCast` publishes, follows
  (R352's last-played record) and keeps as `lastMusic` carries the hand-over's song and position, `playing = false`
  (FR-R358-2). So the bar, `takeBack()` (*Stop casting*, *Play on…* another device) and `playHere()` all use 0:30.
- `castHandBackFrom(lastLive, handOver)` — a session that ends, or stops on the device, with no live report at all
  hands back the hand-over itself (FR-R358-1). The hand-back stays paused (FR-R358-3, unchanged).
- The Android media card (`CastSessionRemote`) reads `MusicCast.shown(link, st)`, the same rule, so the lock screen
  does not say 0:00 either.

**Deviation / note.** While waiting, the bar keeps the device's *buffering* flag (the spinner while the receiver
loads), so "paused-looking" is: not playing, clock still at the hand-over place. A receiver stuck in `noserver` keeps
the spinner as before. The senders' own optimistic status is unchanged (no sender edit; R357 works there).

**Tested:** `CastHandOverTest` (6: hand over at 30 s + no report + stop ⇒ the song at 30 s, also with no status at
all; a report at 45 s on the next song ⇒ that song at 45 s; position 0 while loading and `noserver` are not a place;
the bar shows 30 s, not playing, until the first report; another song before it plays is a place; a plain join takes
the device's word). `CastHandBackTest` (8) and `CastStatusMergeTest` (10) unchanged and green.
`:ravilo-ui:desktopTest` and `:ravilo-ui:testDebugUnitTest` (filtered to these classes), `:ravilo-ui:compileKotlinWasmJs`,
`:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-android:assembleRelease`; fences deanonymization · phases · Ravilo
strings · player dex green.

**Owed:** the live check above on the Mac (no device access this round), and the same on the Pixel 9.
