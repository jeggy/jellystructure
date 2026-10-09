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
   within a second, and the Pixel's Now playing shows *Paused*. *(Tests: `SessionCommandIntegrationTest`; on the speaker, the manual check.)*
2. The Pixel is switched off. The MacBook still pauses, skips and seeks Stue (the backend reaches the receiver itself). *(Tests: `SessionCommandIntegrationTest`, `playback-sessions.spec.ts`; manual check.)*
3. The Pixel and the MacBook press next at the same moment. Stue skips once, not twice (the second command is stale for
   `next`, so it gets a 409 and is dropped; at the target, a mismatched `expect_index` is dropped). *(Tests: `SessionCommandRuleTest`, `ReceiverRemoteTest`, `SessionCommandIntegrationTest`, `playback-sessions.spec.ts`.)*
4. Olivar's session shows no ⏯ on Eyð's phone while 304's switch is off. *(Tests: `SessionControlRuleTest`, `SessionRemoteTest`.)*

## Taken as leans

- A stale `next` is dropped (409) rather than applied twice.
- Stopping someone else's session asks once.

## Tests

Written against the reviewed design (dev review + owner decisions), not the first draft: routes under
`/api/tv/playback/sessions`, `session_command` only to sockets that declared `session_control`, the revision check at
the target (`expect_index` / `queue_rev`), stopping someone else's playback asks **every** time, and a speaker whose
receiver isn't running is reached through R370's relay. R368's `PlaybackSessions` (service + store, fake clock as in
`PlaybackTrackerTest`) is the base every server test builds on.

Paths: **B** = `src/linuxX64Test/kotlin/dev/jellystructure/tv/` (backend, `linuxX64Test`) · **S** =
`shared/src/commonTest/kotlin/dev/jellystructure/shared/tv/` (`:shared` `commonTest`, also covers the receiver's logic,
which lives in `ReceiverRemote.kt`) · **U** = `ravilo-ui/src/commonTest/kotlin/dev/jellystructure/ravilo/ui/` · **R** =
`ravilo-ui/src/androidUnitTest/kotlin/dev/jellystructure/ravilo/ui/` (Robolectric) · **E** = `tests/e2e/` (Playwright,
real backend + mock Jellyfin + the real `ravilo-cast.js` on the fake CAF in `helpers/cast-receiver.ts`).

**1. Pure decisions (extract these; every later test leans on them)**

- `SessionCommandRuleTest` (**B**, new) — on `revisionChecked(op)` and `sessionCommandVerdict(op, revision, session)`
  in a new `tv/SessionCommands.kt` (FR-R369-1, review item 4a/4b):
  - `` `next, previous, jump, seek and every queue edit are checked` `` and `` `play, pause, stop, volume and mute never are` ``.
  - `` `a stale seek is 409 with the session the server holds` `` · `` `a stale next is 409, not a second skip` ``.
  - `` `play, pause and stop on an old revision are applied` ``.
  - `` `an index-moving command carries expect_index and a queue edit carries queue_rev` `` (the forwarded body).
- `SessionCommandRouteTest` (**B**, new) — on `commandRoute(target, socket, attachedSender, relayApps, op)` (FR-R369-2,
  review items 1, 2, 11; R369 owner decision 2). Outcomes `SessionCommand` · `LegacyPlaystate` · `LegacyPlayerCommand` ·
  `ViaSender` · `RelayLoad` · `Unreachable`:
  - `` `a target that declared session_control gets session_command` ``.
  - `` `a target without it gets today's playstate_command or player_command for play, pause, seek, next, previous, stop and volume` ``.
  - `` `without session_control jump, shuffle, repeat, tracks and queue edits are not offered` `` (they are absent from
    `SessionDetail.ops`).
  - `` `a receiver in its reconnect gap goes through the attached sender phone` ``.
  - `` `play on a cast session whose receiver closed is a relay load at the stored position` `` (owner decision 2).
  - `` `no socket, no sender and no relay app is unreachable` ``.
- `SessionControlRuleTest` (**B**, new) — on `controllableBy(viewer, session, householdControl)` (FR-R369-3, review
  item 8): `` `your own session is controllable` `` · `` `another member's is not while 304's switch is off` `` ·
  `` `another member's is with the switch on` `` · `` `the admin controls every session whatever the switch says` `` ·
  `` `a session the viewer may not see is never controllable` `` (R368 owner decision 1).
- `SessionRevisionTest` (**B**, new, on R368's `PlaybackSessions` with a fake clock) — review item 4b:
  `` `a position heartbeat does not bump the revision` `` · `` `a seek beyond 3 s, a skip, a pause and a queue change do` ``.
- `RemoteCommandTest` (**S**, extend) — FR-R369-1, review item 3:
  - `sessionCommandsReadAsThePlayersOwnControls`: every `op` → `RemoteCommand`, incl. `Jump`, `SetShuffle`,
    `SetRepeat`, `SelectAudio(index)`, `SelectSubtitle(index)`, `QueueAdd/PlayNext/Move/Remove`.
  - `eachSessionCommandDrivesThePlayerOnce`: `applyTo` on a fake `RemotePlayer`.
  - `anUnknownOpReadsAsNothing`: a newer server's op is dropped, not a crash.
- `ReceiverRemoteTest` (**S**, extend) — the receiver's reading (`receiverRemoteAction`, review items 3 and 4c):
  - `aSessionCommandMapsOntoTheSendersCastCommandHandlers`: `jump` → `play_at`, `set_shuffle`, `set_repeat`,
    `set_audio {index}`, `set_subtitle {index}`, `queue_*`.
  - `aMismatchedExpectIndexIsDroppedAndReported` (a new `ReceiverRemoteAction.Stale`, followed by an immediate status).
  - `aMismatchedQueueRevIsDropped`.
  - `noExpectIndexBehavesAsToday`.
- `CastMessagesWireTest` (**S**, new) — review item 4c, backwards compatibility:
  - `castCommandWithoutExpectIndexDecodesAsToday`.
  - `castCommandWithExpectIndexStillDecodesOnAnOlderReader` (`ignoreUnknownKeys`).
- `SessionRemoteStateTest` (**U**, `seams/`, new) — the client half:
  - `` `sessionDetail maps to the remote's CastRemoteStatus` `` (`sessionRemoteStatus`, review item 9; the remote isn't
    redrawn).
  - `` `the position moves from position_ms plus server time while playing and freezes otherwise` ``
    (`sessionPositionMs(view, nowMs, serverNowMs)`, FR-R369-7).
  - `` `a press dims for 400 ms, a spinner shows after 1 s, and can't reach after 3 s with no reflecting report` ``
    (`commandFeedback(sentAtMs, nowMs, reflected)`, FR-R369-6, review item 11).
  - `` `stopping someone else's playback asks every time and your own never` `` (`stopNeedsConfirm`, owner decision 1).
  - `` `pause, next and volume never ask` ``.
  - `` `the bar's play-pause and next are present only when controllable` `` (FR-R369-5).
- `RemoteControlTest` (**U**, extend) — FR-R369-2, review item 3:
  - `aSessionCommandIsGatedLikeAPlayerCommand` (`acceptsPlayerCommand`).
  - `aSessionCommandWithAStaleExpectIndexDoesNothingAndReports` (the app target obeys the same rule as the receiver).
  - `queueEditsReachTheMusicQueue`.
- `EventsCatchUpTest` (**U**, extend) — review item 13: `` `an open with features=sessions refetches the playback sessions` ``.
- `CastHandBackTest` (**U**, extend) — review item 12: `aServerStopOnAMusicCastHandsBackPausedAndStartsNoSession`.

**2. Route and socket integration (fake sockets on the loopback)**

`SessionCommandIntegrationTest` (**B**, new), built like `JellyfinSessionBridgeIntegrationTest`: an embedded CIO server
on `127.0.0.1:0` with the real `TvEventBus`, R368's `PlaybackSessions` on a temp database, the new
`playbackSessionRoutes(…)`, and one `DeviceSocket` per fake device: a phone (`features=sessions,session_control`), a
computer (`features=sessions`), a receiver (`session_control`), a receiver and a phone that declare nothing (older
builds), and a relay-capable phone. The fake receiver answers commands with the real `receiverRemoteAction`.

- `` `a pause from the computer reaches the receiver as session_command and both apps see the paused report` `` —
  acceptance 1.
- `` `with the sender phone's socket closed the receiver still pauses, skips and seeks` `` — acceptance 2.
- `` `two nexts on one revision, one direct and one through the server, skip once` `` — acceptance 3 (the direct one is
  sent as the phone's `CastCommand` straight to the fake receiver).
- `` `a stale seek answers 409 with the session` ``.
- `` `an older receiver gets playstate_command and player_command and its detail lists only those ops` `` (backwards
  compatibility).
- `` `a socket that did not declare sessions gets no session event at all` `` (R368 item 2, an older phone).
- `` `a socket with sessions but not session_control is never sent session_command` ``.
- `` `in the receiver's reconnect gap the command reaches the sender phone, and with no phone it is 409 unreachable` ``.
- `` `play on a session whose receiver closed sends cast_relay_load to the relay app` `` (owner decision 2).
- `` `another member's session is 403 with the switch off, and flipping it pushes a fresh session_list` `` — acceptance 4,
  304b.
- `` `the admin's command is accepted with the switch off` ``.
- `` `attach_session adds a controller row, closing the socket removes it, and boot clears the table` `` (review item 7).
- `` `session_detail goes only to controllers, and the queue rides it only when queue_rev changes, windowed` `` (review
  item 5; a 487-song queue on the R359 window).
- `` `the target reports at once after a command and the 10 s heartbeat stays` `` (review item 10).
- `` `a stop on a music cast ends the session with end_reason stopped and the stopper's name` `` (review item 12).

`WireCompatTest` (`shared/src/linuxX64Test/…/shared/wire/`, existing) stays green: `SessionDetail`, the command body,
the new `CastCommand.expect_index` and every new event are additive.

`playback-sessions.spec.ts` (**E**, new) — `"the receiver obeys a server pause and drops a stale next sent on both paths"`:
the real receiver on the fake CAF enrols and loads a three-song queue, a second device's `POST
…/playback/sessions/{id}/command` pauses it, and a `next` sent through `__castCommand` plus one through the server with
the same `expect_index` moves the queue by one.

**3. UI (Robolectric)**

`SessionRemoteTest` (**R**, `screens/`, new; `fakeTvApiClient` answers `GET …/playback/sessions/{id}`):

- `` `the place line names the place and opens Move to` `` (FR-R369-6).
- `` `ops the target doesn't support are absent, not greyed` ``.
- `` `Stop on someone else's playback asks inline every time` `` · `` `Stop on your own doesn't ask` ``.
- `` `the bar shows play-pause and next for a controllable session and none for someone else's with the switch off` `` —
  acceptance 4.

`CastRemoteFitTest` (**R**, existing) stays green with a `SessionSender` behind the remote.

Strings: `RaviloStringsTest` (`ravilo-i18n/src/commonTest/…`, extend) `the_session_control_keys_resolve_in_every_language`
for `session.cant_reach`, `session.stop_everywhere`, `session.stop_person`; `scripts/check-ravilo-strings.sh` green.

**Only the real speaker can confirm** (Gæsteværelse; no other room)

- *The receiver's socket path with the phone gone.* Cast a song from the Pixel to Gæsteværelse, put the Pixel in
  airplane mode, then pause, skip and seek from the computer. Each happens within a second.
- *Two paths at once.* Press next on the Pixel and the computer together. Gæsteværelse skips exactly once.

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


## Owner decisions (2026-10-04, after the dev review)

1. **Stopping someone else's playback asks every time** — one inline confirm, no "don't ask again". Pausing, skipping
   and volume never ask.
2. Commands from any app reach a speaker that isn't running the receiver yet through **R370's relay** (owner: the
   server is a server; being on the speakers' network is an accident of this household).

## Build notes (2026-10-04)

Built against the dev review and the owner decisions: routes under `/api/tv/playback/sessions`, `session_command` only
to sockets that declared `session_control`, legacy `playstate_command`/`player_command` for older targets, the check
at the target, stopping someone else's playback asks every time.

**Server** (`tv/SessionCommands.kt`): pure `revisionChecked`, `sessionCommandVerdict` (next/previous/jump/seek/queue
edits checked; play/pause/stop/volume never), `commandRoute` (SessionCommand · LegacyPlaystate · LegacyPlayerCommand ·
ViaSender · RelayLoad · NotOffered · Unreachable), `opsFor`, `controllableBy`; `SessionControl` (authorises against the
session, 409 stale / unreachable / not_offered, 403, timeline *command … from the admin*, a `stop` ends the session at
once with the stopper's name). Routes: `GET /api/tv/playback/sessions/{id}` → `SessionDetail` (queue window of 60 around
the current entry, titles resolved server-side, modes, tracks, volume, `ops`), `POST …/{id}/command`,
`POST /api/tv/playback/sessions/queue` (a target's queue report, on change only, ids). `attach_session`/`detach_session`
on the events socket; a closed socket detaches all; the controller table (**68.sqm**) is cleared on boot.
`session_detail` goes to attached controllers only. `controllable` widened: own sessions anywhere; another member's with
304's switch on (and nearby + may see). `TvEventBus` keeps per-socket features (`hasFeature`, `notifySessionCommand`).

**Deviation (recorded):** besides `expect_index` (sent when the target has reported its queue) the envelope carries
`expect_item` — the item the server last knew was playing — so the stale check works before any queue report and on
a film. `CastCommand` gains optional `expect_index`/`expect_item` (additive); the receiver checks both on
`next`/`prev`/`play_at`. The phone's own direct Cast commands do not set them yet.

**Receiver** (`ravilo-cast`): opens its socket with `features=session_control`; `session_command` → stale check →
`castCommandForSession` (the senders' own `CastCommand` handlers) or `sessionRemoteCommand` → `onRemote`; reports its
queue (ids, `queue_rev`, index, modes) to the server when it or the index changes.

**Apps:** `features=sessions,session_control` (the TV: `session_control` only); `RemoteCommand` gains Jump · SetShuffle ·
SetRepeat · SelectAudio · SelectSubtitle · Queue* (RemotePlayer gets default no-op methods; `MusicRemotePlayer`
implements the music ones); `session_command` is read by `sessionCommandAction` (local · over this app's Cast link ·
stale · ignore). The phone/desktop music engine reports its queue on change. `SessionRemote` (attach/detach frames on
the socket, detail, commands with the dim/spinner/*Can't reach* rule, re-attach on socket open — item 13) and
`SessionRemoteScreen` (Dest `SessionRemote`): place line, transport, seek, shuffle/repeat, a film's tracks, the queue
(tap to jump), *Stop* with the inline confirm on someone else's. The bar's and the sheet's ⏯/next go through the server
unless the session plays here. **Deviation:** the remote is its own screen rather than Now playing / the Playing page
re-pointed (review item 9's third `MusicPlayback` backend and `SessionSender`); it draws the same parts.
Strings `session.cant_reach`, `session.stop_everywhere`, `session.stop_person` (+ `session.audio`/`subtitles`/
`subtitles_off` for the remote's track rows).

**Tests:** `SessionCommandRuleTest` (17 incl. the command service on a DB and the household config round-trip),
`SessionWireTest` (`:shared`, 12), `SessionRemoteStateTest` (9), `WireCompatTest` green (new roots).
Not written: the Playwright receiver spec. **2026-10-05:** the loopback `SessionCommandIntegrationTest` (5; harness
`tv/SessionLoopback.kt`) is written — and found a shipped bug: the command service encoded `session_command` with
`encodeDefaults = false`, so the envelope's `type` (a default) was never sent and an app read the frame as an unknown
event, i.e. a config change. Fixed (`encodeDefaults = true`).

**Only a speaker can confirm:** the receiver's socket path with the phone in airplane mode (pause/skip/seek from the
computer); two `next`s pressed together skip once.

## Found live 2026-10-09

- **A film on the TV app reached through the server had no *Audio & Subs*** (R266's live run, Soveværelse TV debug
  1.50-119 + Pixel 9 Pro): with the Ravilo app already open on the TV, *Play on…* went through the server (R372's move)
  and the phone showed this phase's session remote — play/pause and seek reached the TV, but no track rows, while the
  Cast road's remote (R245) had them (R380 owner decision 1: the same remote against anything). Two gaps: the TV never
  reported a film's tracks to the session (only a music queue was reported), so `SessionDetail.audio_tracks` was empty
  and the remote hides the rows; and a `set_audio` / `set_subtitle` reaching the TV's film player picked nothing (its
  `RemotePlayer` kept the interface's empty defaults). **Fixed 2026-10-09:** while the film player is up, the TV reports
  its picker's flat lists (the same `castVideoLists` the Cast channel sends, R380 FR-R380-7) as a `SessionQueueReport`
  with **no queue** — on change, checked every 2 s, re-sent every 30 s in case a first report came before the session
  existed; the server treats an empty queue as a tracks-only report (the session's queue, index and revision stand),
  a first track index is detail (not a move), and a report that changes nothing pushes nothing. The film player's
  remote picks through the picker's own door (`selectAudioAt` / `selectSubtitleAt`, `-1` = Off), so a pick from the
  phone persists, restreams and burns in as an OK on the TV does. No wire change (`audio_tracks` etc. existed).
  Tests: `PlaybackSessionsTest` (tracks-only report keeps the place; an unchanged one pushes nothing; a pick is a state),
  `SessionVideoTracksTest` (the report; `set_audio`/`set_subtitle` incl. Off through `applyTo`). **TV re-test owed:**
  Ravilo open on the TV, *Play on…* a film from the phone → the session remote lists the TV's tracks; a pick applies
  on the TV and the remote's ✓ follows. Not covered: a film playing on a phone or computer as a session's place (the
  picker lists are built for the TV only).
