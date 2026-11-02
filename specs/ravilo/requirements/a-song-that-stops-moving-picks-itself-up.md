# A song that stops moving picks itself up

Task: DEB-3400

> Owner, 2026-11-02 (DEB-3400), listening on the Mac from the office: *"it stopped playing right now 13.42. Please
> take a look and help make sure this wont happen again"*

DEB-3093 (*trouble never reaches the viewer*) is the same wish for films on Android. This is its music half, on the
desktop.

## What happened

Read off the server the same day (times CET):

- **13:39:50** a 2:43 FLAC song starts on the Mac (direct play, `Static=true`). The Mac had every byte of the file,
  acknowledged, within seconds.
- **≈13:42:30** the song stops at 2:40.4, 2.7 s before its end, and nothing moves on. The app keeps reporting progress
  every 10 s with the same position and `paused = false`: the session got no `paused` event and was never reaped
  `offline` (that takes 90 s without a heartbeat).
- **13:44:37** the owner presses *Next* and the next song plays.
- **13:47:04** a second stop is a `paused` event sent by the Mac: the owner, or macOS pausing because the headphones
  moved to another device. It is not this bug.

Ruled out: the server (no Jellyfin or Caddy error in the window, nothing in flight on the stream's connection), the
home uplink (DEB-3022's probe clean from 13:38 to 13:46), the file (it decodes cleanly to its full length) and the path
between them (the heartbeats kept arriving).

## Why the Mac never noticed

- `MusicEngineDesktop.startMonitor` (4 Hz) acts on *ended*, *failed* and a change of *playing* or *buffering*. A song
  that wants to play, has not failed and has not ended, but whose position stops, is none of these. Nothing happens.
- `Player.swift` sets `ended` only from `AVPlayerItemDidPlayToEndTime`. `AVPlayerItemPlaybackStalled` bumps a counter
  that music never reads. With `automaticallyWaitsToMinimizeStalling`, AVPlayer waits for as long as it takes.
- A real failure shows R322's sheet (*Couldn't play this · Try again · Skip*, FR-R322-5) and waits for a click.
  `retry()`, which starts the song again at its position, already exists. Only a click calls it.
- The music engine posts no QoE: the Mac's `playback_qoe` rows are all films. A freeze leaves no trace on the server.
- The same engine drives mpv on Linux (R335), so the gap is the desktop's, not only the Mac's. Android's ExoPlayer
  retries a broken load by itself and has shown nothing like this. This spec does not touch it.

Why AVPlayer froze is not known. The whole file had been delivered, so the cause lies on the Mac: the audio output, or
AVPlayer itself near the end of a FLAC. The requirements below do not depend on the cause. FR-6 records it next time.

## Requirements

Scope: songs and audiobook parts in `MusicEngineDesktop`, on the Mac and on Linux. Radio keeps its own tuner
(DEB-2659). Films keep R220 and R329.

**FR-1 — A song that stops moving is frozen.** A song is *frozen* when, for 8 s in a row, the viewer wants it to play
(nobody has paused it), it is not seeking, has not ended and has not failed, and its position has moved less than 1 s.
Buffering counts: a pulse that lasts 8 s without the position moving is a freeze too. The 8 s count only time the
engine's monitor actually ran. A gap of more than 2 s between two monitor ticks (the Mac asleep, the process suspended)
starts the count again, so waking the Mac is never a freeze.

**FR-2 — Near its end, a frozen song has ended.** A song that freezes within 5 s of its end (position ≥ duration − 5 s)
is treated as ended. R322's next-song rule runs: the next song, or the queue end of FR-R322-5. For a book part, the next
part.

**FR-3 — Otherwise it starts again where it froze, once, without a word.** The engine loads the song afresh at the
frozen position (what `retry()` does today). No sheet and no toast. The bar shows R218's pulse while it loads. Each
freeze point gets one restart.

**FR-4 — When the restart does not help.** The restart has failed when the song freezes again before it has played
30 s past the point where it first froze. *Open: the owner's choice on DEB-3400's card. Until he answers, the lean
stands.*

- **Lean:** the engine skips to the next song, exactly as FR-2 does. The frozen song stays in the queue. Music keeps
  going.
- **Alternative:** R322's failure sheet (*Try again · Skip*).

A book part always gets the sheet, because skipping a part loses some of the book.

**FR-5 — A network failure gets the same one restart.** When the player fails a song with a network error, FR-3's
restart runs before R322's sheet. On the Mac a network error is an error in `NSURLErrorDomain`. On Linux it is mpv's
load or network failure. Any other failure, such as a decode error or an HTTP 4xx, goes straight to the sheet, as R237
requires: never retry what cannot succeed. The native library reports the error's domain and code next to its text.

**FR-6 — Every freeze leaves a trace.**

- **The client log** (`DesktopLog`, `~/Library/Logs/Ravilo/ravilo.log`) gets one line when a freeze is found and one
  line for its outcome: *ended*, *restarted*, *skipped* or *sheet*. The first line carries the position, the duration
  and the player's own `debug()` line: on the Mac that is the item status, the time control and its reason for waiting,
  and the last error-log and access-log events.
- **The server** gets the song's QoE on the films' existing `PlaybackQoeReport` (`frozen_count`, `frozen_ms`,
  `audio_decoder`, `direct_play`), posted when a song that froze closes. **No new field and no new route.** If the
  existing fields cannot carry the outcome, the outcome stays in the log. If a music item's report would move
  `device_stream_record` and lower the device's film quality (DEB-3093), the server trace waits and only the log ships.

## Out of scope

- Android, TV, phone and web music players.
- Radio (DEB-2659's tuner).
- A pause sent by macOS, for example when headphones move to another device. Ravilo obeys it, as it should.
- Finding out why AVPlayer froze. FR-6 is how the next freeze tells us.

## Acceptance

Unit tests carry FR-1 to FR-5: a pure watch over (tick time, position, wants-play, seeking, ended, failed) samples, and
the engine's response to each verdict. On the Mac (QA), and on Linux for items 2 to 4:

1. A whole album plays through on the office or home network. No freeze lines in `ravilo.log`, and every song advances
   on its own.
2. Pause a song for 30 s, then play. It resumes and logs no freeze.
3. Sleep the Mac in the middle of a song for 1 min, then wake it. It logs no restart.
4. Start a song while the server is blocked on the Mac (a `pf` rule), and lift the block after 15 s. The song starts
   without a click, and the log shows one *restarted* outcome.

## Dev notes

- Files: `MusicEngineDesktop.kt` (`startMonitor`, `retry`, `onSongEnded`, `skip`). `MacPlayer.kt`, `MacNative.kt` and
  `ravilo-desktop/native/Player.swift` for FR-5's error domain and code, which is an ABI addition.
  `MpvPlayer.kt` for its failure class. `DesktopLog.kt`.
- The watch is best kept as a small pure class beside the engine, so FR-1's timing is tested without a player:
  `:ravilo-ui:desktopTest --tests '*FreezeWatch*'`.
- FR-3 and FR-4 count per freeze point, not per song. A long song may freeze, recover and, minutes later, freeze once
  more somewhere else. That second freeze gets its own restart.
