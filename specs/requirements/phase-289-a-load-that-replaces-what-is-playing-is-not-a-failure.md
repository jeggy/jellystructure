# Phase 289 — a load that replaces what is playing is not a failure

**Status:** Planned → built the same day (see *Build notes*). Dev-authored 2026-09-30, from a device finding.
**Depends on:** 218 / R245 (the Cast receiver), 286 (the receiver owns the music queue), R285 (a track change is a
reload), 180 (a stop that arrives before its start ends the start).

## 1. What happened

2026-09-30, the owner's Mac casting music to the guest-room speaker (a Nest Wifi point), the first day a speaker
could start the app at all. With a second Cast connection watching the speaker's `MEDIA_STATUS` and the
receiver's own messages:

| What was done while a song was playing | Result on the speaker |
|---|---|
| *Play* on another album | `IDLE`; the receiver says `failed`, naming the **new** song; silence |
| *Next* in the player bar | the same |
| nothing — the song reached its end | the next song starts (`IDLE` → `BUFFERING` → `PLAYING`) |
| *Play* on an album with the speaker connected and idle | plays |

So every load that arrives **while something is playing** dies; a load into an idle player works. The server's log
for the first row:

```
playback stop: device=cast-… item=<the NEW song> at 16036ms     ← the OLD song's position
PlaybackInfo (music): item=<the NEW song>                        ← 38 ms later
```

## 2. Why

When a load replaces a playing item, the framework ends the item before with `MEDIA_FINISHED`, reason
`INTERRUPTED`. It arrives after the receiver's LOAD interceptor has taken the new item as `current`. The receiver
read every reason but `END_OF_STREAM` as a failure, so it:

1. sent **stop** for `current` — the new item — with the old item's position. The server has no session for the new
   item yet, so it keeps the stop as "the viewer left before the start answered" (180 FR-180-3) and ends the
   session the load opens a moment later: the stream the player is handed is already gone;
2. said `failed` to the phone and emptied itself (`current = null`, the idle screen);
3. never stopped the old item (the server's watchdog did, a minute and a half later).

The same path is taken by the receiver's own loads while something plays: *Next*, *Previous*, a song picked from
the queue (286), and **a film's audio or burned-in subtitle change** (R285's restream is a reload at the current
position). None of those had been tried on a real device: R324 was verified on an emulator against a fake
framework that never sends `INTERRUPTED`, and R285's receivers are marked unverified.

## 3. Requirements

- **FR-289-1 — `INTERRUPTED` is neither an end nor a failure.** The receiver does nothing on it: no stop, no
  message, no screen change. The reason is compared as text (`"INTERRUPTED"`), because a constant this framework
  build does not define reads as `undefined` and would match a missing reason. The same holds for any
  `MEDIA_FINISHED`, and for an `ERROR` with the player idle, that arrives **while a LOAD is being prepared**
  (enrolment, the ticket): whatever ended then is the item before, and `current` is already the new one.
- **FR-289-2 — the item a sender's LOAD replaces is stopped once, under its own id.** On a sender's LOAD (it
  carries a hand-off code; the receiver's own loads carry none and have dealt with the item before themselves) the
  receiver reports the stop of what is playing — its id, its position — before it takes the new item. Only when the
  server still holds that item's session (a ticket was negotiated and no stop was sent since): an item that ended
  or failed is not stopped a second time, since a stop with no session to end would end the next start of that
  item. When the new item **is** the same item, the stop is awaited before the new start is asked for.
- **FR-289-3 — a song's length is said with the load.** A speaker's player reports no duration for a FLAC
  (`MEDIA_STATUS` has `duration: null`), so every sender's bar read 0:00 with no progress — Google's Home app
  included. The receiver puts the queue's own length for the song on `media.duration`, and its own Now-playing
  screen uses the same number when the player gives none.
- **FR-289-4 — no wire change.** `CastLoadData`, `CastReceiverMessage` and `CastCommand` are as they were; an app
  of any version works with this receiver, and this receiver with any app.

Not in this phase: what the phone or the computer shows when a song really cannot be played on a speaker (today
the bar goes back to what the device itself last had, with no word said) — see *Open questions*.

## 4. Acceptance

- With a song playing on a speaker: *Next*, *Previous*, a song picked from the queue and *Play* on another album
  each start the new song there; the receiver says no `failed`; the server's log shows one stop, for the old song.
- A film playing on a Chromecast: changing the audio track or a burned-in subtitle restarts the picture at the
  same place, and the phone's remote does not say *couldn't play this*.
- The same song started again while it plays starts again.
- A load that really fails is still said once as `failed` (R299's test is unchanged and passes).
- On a speaker, the Home app's card for a FLAC shows the song's length and a moving bar.

## 5. Build notes (2026-09-30)

- `ravilo-cast/…/Receiver.kt`: `onFinished` returns on `"INTERRUPTED"` or while `loading > 0`; the `ERROR`
  listener's idle-player branch waits for `loading == 0`; `loading` is counted around the LOAD interceptor;
  `sessionOpen` is true from a negotiated ticket until its stop is sent; `replaced()` is called from `intercept`
  for a LOAD with a code; `media.duration` is set from the queue's track; `onTime` keeps the track's length when the
  player reports none.
- `tests/e2e/cast-receiver.spec.ts`: *a load that replaces what is playing is neither an end nor a failure (289)* —
  the real bundle in front of the fake framework, `INTERRUPTED` fired while the second load is being prepared and
  again once it is handed over: no `failed`, no `ended`, the screen is not the idle one, exactly one stop reaches
  Jellyfin, and the new item's own end is still said as `ended`. Run against the receiver as it was, the test fails
  with `["status", "failed", "failed"]` — what the speaker did.
- `tests/mock-jellyfin/server.js`: a 204 was sent with `Content-Length: 2` and no body; the backend's client failed
  each stop write on it and retried, and the mock counted every retry as a stop. A 204 has no body now.
- **Not seen on a device yet.** The receiver is served by the backend; it reaches the speakers with the next
  deploy. Until then *Next* and a new album over a playing one fail on a speaker or a Chromecast as described.

## 6. Open questions

1. **A song that cannot be played on the speaker says nothing on the sender.** `MusicCast.linked` goes false on
   `failed`, and the bar shows the device's own last song. R299 gave films *{device} couldn't play this · Play on
   this phone*; music has no such line. Lean: the same sentence on the music bar, with *Skip* beside it when the
   queue has more. Not built.
2. **Should a failed song skip to the next?** R297 made a film's failure stop the queue (an error used to walk it,
   two seconds an episode). For music a single unplayable file ends the album. Lean: skip once, stop on the second
   failure in a row.
