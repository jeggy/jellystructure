# Phase R369 — Any Ravilo app controls a session

## Status

`Planned` — written 2026-10-03 (design-authored), **dev-reviewed 2026-10-04** (see the end: the backend reaches a loaded receiver over its own events socket, not a Cast channel; the revision check runs at the target with `expect_index`; routes move to `/api/tv/playback/sessions`; 304b ships with it). **Phase 2 of 6** (see R368). Depends on
R368. Designs: `Playback Sessions - Directions.html` §B2 (the bar as a remote) and §B5 (the remote).

## The rule

A command goes **to the server**, the server sends it to the session's target, and every app shows what the target
then reports. No app tells another app directly. The phone that sent a cast keeps its direct link as a faster path
(R356), but the server stays the record.

## Requirements

**FR-R369-1 — Commands.** `POST /api/tv/sessions/{id}/command { revision, op, args }` where `op` ∈ `play` · `pause` ·
`seek {position_ms}` · `next` · `previous` · `jump {index}` · `set_shuffle {on}` · `set_repeat {mode}` ·
`set_audio {stream}` · `set_subtitle {stream}` · `stop`. Queue edits (`add`, `move`, `remove`) reuse the R322 queue
ops on the session. A command whose `revision` is out of date is still applied when it can't conflict (play, pause,
stop). A seek or queue edit based on an old revision gets `409 { session }`; the app redraws and does nothing more.

**FR-R369-2 — How a command reaches the target.** An **app** target gets it as a `session_command` event on its own
events socket (the TV, phone or desktop player already acts on R354's dashboard commands; this reuses that path). A
**cast** target gets it through the receiver's custom channel from the backend (report §4.3). If the backend can't
reach the receiver, the command goes to the sender phone when one is attached, and otherwise fails with
`session.cant_reach`.

**FR-R369-3 — Who can control.** Anyone whose own session it is. Another member's session can be controlled only
when 304's household switch is on. An **admin** controls every session fully from the admin (304), whatever the switch
says. `SessionView.controllable` carries the answer, and the app never works it out itself.

**FR-R369-4 — Controllers.** Opening a session's remote (or controlling it from the bar) adds this device to
`playback_session_controller`, and closing the remote removes it. There's no limit. Two apps that press at the same
moment are handled by FR-R369-1's revision rule. The latest press wins and both apps show the outcome.

**FR-R369-5 — The bar is a remote** (canvas §B2). The FR-R368-8 bar has ⏯ and next for any session where
`controllable` is true. Tapping the bar opens the remote. The **+N chip** opens *Playing everywhere*.

**FR-R369-6 — The remote** (canvas §B5) is today's Now playing (phone), Playing page (desktop) and book player,
pointed at a session:

- A **place line** under the title, in accent, with the place's icon: *Playing on Stue + Gæsteværelse*. Tapping it
  opens R372's *Move to…*.
- The transport, the seek bar, the queue, lyrics, shuffle and repeat all act on the session.
- Volume is R371's.
- ⋯ adds **Play here** and **Stop** (R372 defines them). **Stop** ends the session wherever it plays, after an inline
  confirm the first time on someone else's session.
- While a command is in flight the control dims for up to 400 ms. There's no spinner unless the wait passes 1 s, then
  the R218 small spinner shows by the timestamp.

**FR-R369-7 — The position stays smooth.** Between reports, an app moves the bar forward from
`position_ms + (now − position_at)` while the state is `playing`, and snaps to the next report. A target reports at
least every 5 s while playing, and right away after any command.

**FR-R369-8 — Strings** × en · da · fo (drafts):

| Key | en | da | fo |
|---|---|---|---|
| `session.cant_reach` | Can't reach {place} | Kan ikke nå {place} | Fái ikki samband við {place} |
| `session.stop_everywhere` | Stop on {place} | Stop på {place} | Steðga á {place} |
| `session.stop_person` | Stop {person}'s {title}? | Stop {person}s {title}? | Steðga {title} hjá {person}? |

## Acceptance

1. *Cannery Lights* plays on Stue from Eyð's Pixel. On her MacBook she presses pause in the capsule. Stue pauses
   within a second, and the Pixel's Now playing shows *Paused*.
2. The Pixel is switched off. The MacBook still pauses, skips and seeks Stue (the backend reaches the receiver itself).
3. The Pixel and the MacBook press next at the same moment. Stue skips once, not twice (the second command is stale for
   `next`, so it gets a 409 and is dropped).
4. Olivar's session shows no ⏯ on Eyð's phone while 304's switch is off.

## Taken as leans

- A stale `next` is dropped (409) rather than applied twice.
- Stopping someone else's session asks once.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against R368's own dev review (same day), `TvEventBus`, `RemoteRoutes` (236), the events socket in `RaviloApp`,
`TvApiClient.connectEvents`, `RemoteCommand`/`RemoteControl` (R354), `MusicPlayback`/`MusicCast`, `ScreenSender`,
`ActiveCastSender`, `CastMessages.kt`, the receiver (`ravilo-cast` `Receiver.kt`) and `i18n/*.json`. The rule holds and
acceptance 2 works without any new Cast plumbing (item 1). The revision check has to move to the target (item 4), and
the remote needs a detail payload R368 doesn't carry (item 5). Fifteen items; one is for the owner.

1. **Correction to FR-R369-2: the backend has no Cast channel.** The custom namespace (`CAST_NAMESPACE`,
   `CastMessages.kt:14`) runs between a sender app and the receiver only; the backend is not a Cast sender. Report §4.3
   says the right thing: *via the receiver's events socket*. That socket already exists. The receiver opens
   `/api/tv/events` with its own device token while an item is loaded (`syncEvents`/`eventLoop`, `Receiver.kt:300-340`,
   R354 FR-R354-7) and obeys `playstate_command`/`player_command` through `onRemote` (`:349`). So the backend reaches
   every **loaded** receiver today, with the phone switched off (acceptance 2). It can't reach an idle one (the socket
   closes with `1000 idle`), but an idle receiver has no live session. The fallback to the sender phone matters only in
   the receiver's reconnect gap (2 → 30 s backoff) or after a token failure. The server sends the same
   `session_command` to that phone, and the phone relays it on the Cast namespace.

2. **Routes and socket follow R368's review.** `GET /api/tv/sessions` is taken (R191's signed-in profiles,
   `TvRoutes.kt:421`/`:442`), so the command route is **`POST /api/tv/playback/sessions/{id}/command`**. The events
   socket opts in with `features=sessions` (R368 item 2). A target that will obey `session_command` adds a second
   feature, `session_control`. The server sends `session_command` only to sockets that declared it. For a target that
   didn't (an older app or receiver), the server falls back to today's `playstate_command`/`player_command` for the
   ops those cover (play, pause, seek, next, previous, stop, volume). The other ops are absent from that session's
   `SessionDetail.ops` (item 5), so the remote shows those controls absent rather than broken.

3. **Where a command lands.**
   - **Server:** `TvEventBus.notifySessionCommand(userId, deviceId, json)` beside `notifyPlayerCommand`
     (`TvEventBus.kt:238`), with the same `targetFor` device fallback (`:176`), because the target's socket is
     registered under the session's owner, not the caller.
   - **App:** `connectEvents` gains `onSessionCommand`. `RaviloApp` gates it exactly as player commands
     (`acceptsPlayerCommand`, `RaviloApp.kt:547-555`) and hands it to `RemoteControl.dispatch`. `RemoteCommand`
     (`:shared`, `RemoteCommand.kt:17`) and `RemotePlayer` (`:75`) gain `Jump(index)`, `SetShuffle`, `SetRepeat`,
     `SelectAudio(index)`, `SelectSubtitle(index)` and the four queue edits. Only the exhaustive `when`s recompile; the
     wire doesn't change.
   - **Receiver:** the new ops map onto the handlers that already serve the sender's `CastCommand` (`play_at`,
     `shuffle`, `repeat`, `audio`, `subtitle`, `queue_*`, `CastMessages.kt:160-180`). One handler then serves both
     paths, so a command can't mean two things.
   - Track picks use the target-neutral `index` (`CastTrack.index`). The receiver maps it to its Cast track id, the way
     236's `set_audio {index}` already reads.

4. **The revision rule needs three fixes.**
   - **(a) Which ops are checked.** Acceptance 3 needs `next`/`previous`/`jump` checked, but FR-R369-1 lists only seek
     and queue edits. Checked: `next`, `previous`, `jump`, `seek` and the queue edits. Never checked: `play`, `pause`,
     `stop`, volume and mute.
   - **(b) What bumps the revision.** Only a change bumps it (R368 item 9: queue, index, options, target, state). A
     position heartbeat doesn't, or every seek would be stale within 10 s.
   - **(c) Where the check runs.** The check must run **at the target**, not only on the server. The sender keeps its
     direct Cast link (R356), so two paths reach one receiver. The Pixel's `next` over the Cast namespace hasn't been
     reported when the Mac's arrives through the server, so the server alone would apply both: Stue skips twice and
     acceptance 3 fails. Each index-moving command therefore carries `expect_index`, and each queue edit carries
     `queue_rev`, which the receiver already keeps (R356 FR-R356-8). The target drops a mismatch and reports where it
     is. The server returns `409 { session }` early when it already knows. `CastCommand` gains an optional
     `expect_index` (additive: an older receiver ignores it and behaves as today). The phone's own direct commands then
     obey the same rule.

5. **The remote needs a payload R368 doesn't have.** `SessionView` (FR-R368-5) has no queue, index, shuffle/repeat,
   tracks, volume or lyrics.
   - Add **`GET /api/tv/playback/sessions/{id}` → `SessionDetail`**: the view plus `queue` (ids and titles) with
     `queue_rev`, `queue_index`, `shuffle`, `repeat`, `audio_tracks`/`subtitle_tracks` with the picks, `volume`,
     `lyrics_on` and `ops`.
   - Push a `session_detail` event **only to the session's controllers**. This is what
     `playback_session_controller` is for. R368's review moved the table here (its item 11).
   - The queue goes out only when `queue_rev` changes, windowed the way R359 windows a LOAD (`CastQueueParts.kt`). A
     487-song queue must not ride every event.
   - **Queue reporting** comes with this phase (R368 item 7). The receiver adds its `queue`/`queue_rev` (already in
     `CastReceiverMessage`) to a server report, and an app reports its `MusicQueue` the same way, both on change only.
     The receiver's tracks list (`audio_tracks`/`subtitle_tracks`) goes the same way.

6. **"Reuse the R322 queue ops" means the receiver's.** The server has no queue ops (`MusicTvRoutes.kt` has none).
   R322's queue lives in the client's `MusicQueue`/`MusicEngine`, and a cast's queue lives in the receiver (286
   FR-286-4). Use the receiver's names and meanings as the session ops: `queue_add {track}`,
   `queue_play_next {track}`, `queue_move {index, to}` and `queue_remove {index}`. The server never edits `queue_json`
   itself. It forwards the command and stores what the target reports (constitution: server-pushed state only).

7. **Attach and detach go over the socket.** Use WS messages `attach_session {id}` / `detach_session {id}` on
   `/api/tv/events`, handled like `subscribe_device` (`handleSubscribeMessage`, `RemoteRoutes.kt:280`). The socket's
   `finally` detaches everything it held. With REST, a killed app would leave a controller row behind. Rows are live
   state, so the table is cleared on boot. 304 shows controllers, so it reads the table. The admin is not a device:
   its rows use a `controller_kind` of `admin` with the admin user's id.

8. **FR-R369-3: authorise against the session, not the device list.** Today `/api/remote/command` checks the caller's
   own devices (`listByUser`, `RemoteRoutes.kt:166`) and answers 403 to another user's play (`:176`). The new route
   checks `owner == caller`, or 304's switch. R368 sets `controllable` per viewer (`here`, or *minted this cast's
   code*). This phase widens it to *own session*, and to *any household member's* when 304's switch is on. 304 is
   split, and its **304b** (the admin's remote and the household switch) ships **with** this phase. Flipping the
   switch pushes a fresh `session_list` to every socket so `controllable` changes at once (304 acceptance 3).

9. **The remote reuses two seams.**
   - **Music:** `MusicPlayback` (`MusicCast.kt:315`) already chooses between the engine and `MusicCast`. Add a third
     backend: a session on another place that this app holds no link to.
   - **Film:** `CastRemoteScreen` reads a `CastSender`, and `ScreenSender` (`seams/ScreenSender.kt:35`) is already one
     over server routes. A `SessionSender` built the same way maps `SessionDetail` → `CastRemoteStatus`, so the remote
     isn't redrawn.
   - `ActiveCastSender`'s "at most one linked" rule (`ActiveCastSender.kt:16-20`) is about this app's own links. A
     session remote is not a link and doesn't touch it.

10. **FR-R369-7: keep the 10 s report.** Today the player and the receiver both report every 10 s
    (`PlayerStore.kt:63`, `Receiver.kt:81`), and every report goes on to Jellyfin through `PlaybackWriter`.
    - With `server_now_ms` on every envelope (R368 item 9), the bar draws smoothly from a 10 s report, as
      `MusicPlayback.currentPositionMs` already does for a cast (`MusicCast.kt:372-379`).
    - What matters is an **immediate** report after each command and state change. Most of that exists: the receiver
      calls `sendStatus()` after `onRemote`, and R357 reports volume. Add an immediate server report on every seek,
      skip and pause.
    - **Drop "at least every 5 s".** It would double Jellyfin's progress writes and buy nothing.

11. **"Can't reach" needs a definition.** `TvEventBus`'s `notify*` calls are fire-and-forget: `runCatching { send }`,
    and `?: return@launch` when there is no socket (`TvEventBus.kt:193`, `:218`). So 236's `202` means "sent", not
    "done".
    - The command route returns `409 { reason: "unreachable" }` at once when the target has no socket and no attached
      sender can relay.
    - Otherwise it returns `202`. The app shows `session.cant_reach` when no `session_state` or `session_detail`
      reflects the command within 3 s.
    - The 400 ms dim and the 1 s spinner are fine with the constitution. They show a press in flight, never a
      predicted state.

12. **Stop, and R353's hand-back.** A server `stop` on a music cast reaches `musicEnded()` (`Receiver.kt:366`). A phone
    still linked then takes the song back paused (R353 FR-R353-5). That creates no session, because R368 starts one
    only on a start. The server sends `session_state` with `state = ended` and `end_reason = stopped` and the
    stopper's name, so R372's *{person} stopped it* has its source.

13. **Off screen.** A phone that is a controller off screen has no socket (R293, R354 FR-R354-5 holds it only for its
    own music). It resynchronises on the socket's open: `GET …/playback/sessions` in the same `onOpen` that already
    checks the config rev (`RaviloApp.kt:527`).

14. **Strings.** The three keys are new and correct. `session.stop_everywhere` sits beside the shipped `cast.stop`
    (*Stop casting*) and `cast.stop_room` (*Stop*). Use `cast.stop_room` for the button, and the new key only where the
    place must be named. Regenerate the lexicon (R279/R288).

15. **Build order.** R360 → R368 (+ 304's read-only list) → **R369 + 304b** → R370 / R371 → R372. Inside R369:
    1. the server: route, revision rule, socket attach, detail and fallback;
    2. the receiver: `session_command`, `expect_index`, queue reporting;
    3. the client: `SessionSender`, the third `MusicPlayback` backend, the bar's ⏯.

    Tests: two controllers pressing `next` against one fake receiver skip once (direct plus server path). A stale
    `seek` answers 409. A heartbeat doesn't bump the revision.

**For the owner**

1. **Stopping someone else's playing asks first — every time, or only the first time?** FR-R369-6 says *the first
   time*; the lean below it says *asks once*. **Lean: every time, one inline confirm.** It is rare, and a stopped film
   in another room is hard to undo.

**Shipped bugs found (not fixed)**

None new. One stale comment: `listedToRemote()` (end of `RemoteRoutes.kt`) says a receiver "never opens the events
socket". That was true until R354. The filter itself is still right.
