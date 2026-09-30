# Phase 289 — a load that replaces what is playing is not a failure

**Status:** Built and deployed 2026-09-30; seen on the guest-room speaker and the bedroom TV (see *Seen on the devices*). Dev-authored 2026-09-30, from a device finding.
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

- **FR-289-5 — on a speaker the receiver removes its own screens, never the framework's player.** 286 emptied the
  whole page on a device with no display (FR-286-3). That removed `<cast-media-player>`, and the framework then
  failed **every load after the first** with error 905, and **every converted song** (HLS) with Shaka's *Cannot read
  property 'insertRule' of null* — a first song that was a plain file played, which is why it looked like it worked.
  The receiver removes `body > section, body > div` — its own — and nothing else.
- **FR-289-6 — the receiver says what its player did, on a channel of its own.** A speaker has no screen and no
  DevTools; FR-R297-3's "record why a stream failed" went to a console nobody can open. One line of text per event —
  a load taken, the stream handed to the player, an error's code and the framework's own error object, why an item
  ended — on `urn:x-cast:dev.jellystructure.ravilo.log`. No sender builds state from it; the desktop app writes the
  lines to its own log (`cast: {device} · …`), and a second Cast connection can read them. FR-289-5 and FR-289-7
  were both found by reading it.
- **FR-289-7 — only a sender's LOAD can enrol the receiver.** The receiver's own loads repeat the sender's data
  with no hand-off code. A sender that had come from another device sent that device's receiver id along; the
  receiver read the mismatch as "enrol again", tried with an empty code, and the first *Next* on the TV showed the
  no-server screen. Enrolment is asked only when the LOAD carries a code; and a sender forgets what the device
  before said when it opens a session to another one.

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
- `isHeadless()` removes the receiver's own screens only (FR-289-5); `note()` and the log namespace
  (`CAST_LOG_NAMESPACE` in `CastMessages.kt`, `CastSession.notes` in `ravilo-castv2`, printed by
  `CastSenderDesktop`); `mustEnrol()` (FR-289-7); `CastSenderDesktop.open` clears the status, the volume and the
  receiver's last message of the device before.
- e2e: *on a speaker the receiver removes its own screens and leaves the framework's player* — the fake framework
  answers `display_supported: false`; after a LOAD the player element is there, the receiver's sections are not,
  the log channel carries the load, and the phone's channel carries only `noserver`. The fake keeps the two
  channels apart (`__castSent`, `__castNotes`).

## 6. Seen on the devices (2026-09-30, evening)

Deployed with the owner's permission: the backend at `v1.47-46` (a restart), then the receiver's script alone
copied into the running container three times while the log channel was read (no restart; the image was rebuilt
from the same commit afterwards, so a recreated container serves the same script).

With the first deploy — FR-289-1 to -4 only — *Next* and an album over a song were still silent on the speaker, now
without a `failed`. The log channel said why: `error code=905 … loading=1` at every load over a playing item, and
`shakaErrorCode 7999 … Cannot read property 'insertRule' of null` on every converted song. With FR-289-5:

| Guest-room speaker (Nest Wifi point), from the Mac | |
|---|---|
| a song picked over a playing one, plain file → converted, converted → converted | plays, 1.5 s after the click |
| *Play* on another album | plays its first song |
| *Next*; *Previous* twice (restart, then the song before) | plays |
| a song's own end | the next one follows |
| the speaker's `MEDIA_STATUS` | carries the song's length (`216.746`) — it was `null` |

| Bedroom TV (BRAVIA, Chromecast built-in), from the Mac | |
|---|---|
| the music moved there from the speaker (R324's note) | continues at 46.1 s; the speaker's app has closed |
| *Next* — before FR-289-7 | the no-server screen (`noserver`, the receiver asking to enrol with no code) |
| *Next*, *Previous* twice — with FR-289-7 | plays |

Not seen: a film's audio or burned-in subtitle change on a Chromecast (R285) — the same code path, not tried.

## 7. Open questions

1. **A song that cannot be played on the speaker says nothing on the sender.** `MusicCast.linked` goes false on
   `failed`, and the bar shows the device's own last song. R299 gave films *{device} couldn't play this · Play on
   this phone*; music has no such line. Lean: the same sentence on the music bar, with *Skip* beside it when the
   queue has more. Not built.
2. **Should a failed song skip to the next?** R297 made a film's failure stop the queue (an error used to walk it,
   two seconds an episode). For music a single unplayable file ends the album. Lean: skip once, stop on the second
   failure in a row.
3. **Whose session is it?** A receiver that kept its token plays as the viewer who enrolled it, whoever casts next:
   a second viewer's phone sends no receiver id on its first LOAD, so nothing asks for a new enrolment (218
   FR-218-9 as built). Seen while reading FR-289-7; not changed here.
