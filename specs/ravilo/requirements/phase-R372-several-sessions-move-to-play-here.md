# Phase R372 — Several sessions at once, *Move to…*, *Play here*, and how sessions end

## Status

`Planned` — written 2026-10-03 (design-authored), **dev-reviewed 2026-10-04** (see the end: moves are Ravilo's own LOAD + stop, no stream transfer; the watchdog leaves a session paused and offline, amending FR-R368-2; the TV bullets need fixing). **Phase 5 of 6** (see R368). Depends on
R368–R371. Designs: `Playback Sessions - Directions.html` §B5 and §B7, and `… - Desktop, TV & Admin.html` §B9
(the TV as a target) and §B11 (the 16-state strip).

## Requirements

**FR-R372-1 — Several sessions per viewer, with no limit.** Each session is separate: its own queue, place, position
and options. R368's bar rule picks which one the bar shows, and *Playing everywhere* lists them all.

**FR-R372-2 — *Move to…*** (canvas §B7). Opened from the remote's place line or ⋯. It lists R370's places with the
current one ticked:

- **Move** (pick another single place). The new place loads the session's queue, index and position (minus 2 s for
  speech, books and episodes), and the old one stops once the new one reports `playing`. During the hand-off the place
  line reads *Moving to {place}…*. If the new place fails, the old one keeps playing and the line says *Couldn't move to
  {place}*.
- **Add or remove places** (tick more than one; music and books only) is R371's group path.
- A film or episode moves only to a `video` place.
- The session keeps its `id`, so every controller stays attached.

**FR-R372-3 — *Play here*.** On a session not playing on this device, *Play here* (⋯, or the session's row in
*Playing everywhere*) is a *Move to…* to **this device**. On the phone it's the existing R299 `cast_play_here`.

**FR-R372-4 — How a session ends.**

- **Paused:** ends **24 h** after the last change. The resume point is the one Jellyfin already has.
- **Stopped** by anyone: ends right away everywhere, and its rows fade out after 60 s (FR-R368-7).
- **Played out:** the queue ends, and the row reads *Finished* for 60 s.
- **The place went away** (switched off, unplugged, no report for 90 s): the session is **paused where it was** and
  marked *{place} is offline · paused at 48:10*. It offers *Play here* and *Move to…* and ends after the 24 h.

**FR-R372-5 — Every state the remote and bar can show** (canvas §B11, the 16-state strip): starting · playing ·
paused · buffering · reconnecting (after a restart) · moving · move failed · adding a place · place offline · can't
reach · stopped by someone else · finished · ended · someone else's (view-only) · someone else's (controllable) ·
older receiver (not in sessions, R356's direct path only). Each state's words are in the strings table. No state uses
the word *session*.

**FR-R372-6 — The TV as a place** (canvas §B9). A Ravilo TV app can be a target like any other:

- *Play on…* lists it (it has an events socket). The TV opens the player with what was sent, whatever page it was on,
  except during R274's sign-in, where it waits.
- A **volume panel** on the D-pad (▲ while the chrome is up, as drawn) shows the TV's volume for sessions sent to it.
- The TV's Home row *Playing in other rooms* is round 2 and not specified here.

**FR-R372-7 — Strings** × en · da · fo (drafts):

| Key | en | da | fo |
|---|---|---|---|
| `session.move_to` | Move to… | Flyt til… | Flyt til… |
| `session.moving` | Moving to {place}… | Flytter til {place}… | Flytur til {place}… |
| `session.move_failed` | Couldn't move to {place} | Kunne ikke flytte til {place} | Fekk ikki flutt til {place} |
| `session.place_offline` | {place} is offline · paused at {time} | {place} er offline · sat på pause ved {time} | {place} er ikki tøkt · steðgað við {time} |
| `session.finished` | Finished | Færdig | Liðugt |
| `session.stopped_by` | {person} stopped it | {person} stoppede den | {person} steðgaði |

## Acceptance

1. *Cannery Lights* on Stue. Eyð picks *Move to… → Kontor*. Kontor starts 2 s back, Stue stops once Kontor plays, and the
   MacBook's capsule follows without a blink. *(Tests: `MovePlanTest`, `SessionMoveIntegrationTest`; manual check.)*
2. *Sommeren ’92* paused on Soveværelse TV is gone from *Playing everywhere* after 24 h. Jellyfin's resume point is
   48:10. *(Test: `SessionEndingTest`.)*
3. Soveværelse TV is unplugged mid-film. The Pixel shows *Soveværelse TV is offline · paused at 48:10* with *Play
   here*. Play here resumes on the phone at 48:08. *(Tests: `MovePlanTest`, `SessionMoveIntegrationTest`, `MoveToSheetTest`; manual check.)*

## Decided by the owner (2026-10-04)

- Paused sessions end after 24 h.
- A place that goes offline pauses its session and keeps it 24 h, instead of ending it.

## Taken as leans

- Moving speech, books and episodes rewinds 2 s.

## Tests

Written against the reviewed design and the owner's decisions: *Move to…* is Ravilo's own load-then-stop, keeping the
session's `id`; **every** move rewinds 2 s, music included; a silent place, whether after a restart or not, leaves its
session *paused, offline* for 24 h (the watchdog no longer ends it); a move onto a speaker from the web app, iPhone or
TV uses R370's relay; the TV's group *Speakers* panel is built in this phase. All server tests run on a fake clock
(R368's `PlaybackSessions`, as in `PlaybackTrackerTest`).

Paths as in R369: **B** backend `linuxX64Test` (`…/dev/jellystructure/tv/`), **U** `ravilo-ui` `commonTest`, **R**
`ravilo-ui` `androidUnitTest` (Robolectric), **E** `tests/e2e/`.

**1. Pure decisions**

- `MovePlanTest` (**B**, new) — on `movePlan(session, target, movingApp, relayApps)` and `moveStartMs(positionMs)` in
  `tv/SessionMoves.kt` (FR-R372-2/-3, review item 2, owner decisions 2 and 4):
  - `` `a move onto a Ravilo app is a server session_load` ``.
  - `` `a move onto a Cast place from Android or the desktop is that app's own LOAD` ``.
  - `` `a move onto a Cast place from the web app, iPhone or TV is a relay load` ``.
  - `` `with no relay app a Cast place is not reachable` ``.
  - `` `a film or episode moves only to a video place` ``.
  - `` `every kind starts 2 s back, music too, never below 0` `` — acceptance 3 (48:10 → 48:08).
- `SessionEndingTest` (**B**, new) — on `PlaybackSessions` and the 110 watchdog (FR-R372-4, review items 5, 6 and 8,
  owner decision 1; amends FR-R368-2/-4):
  - `` `a paused session ends 24 h after its last change` `` — acceptance 2.
  - `` `a stopped session ends at once and leaves the list 60 s later` ``.
  - `` `a played-out queue reads finished for 60 s` ``.
  - `` `a watchdog reap stops Jellyfin's playback but leaves the session paused and offline` ``.
  - `` `the 24 h sweep ends a paused, offline session` ``.
  - `` `a place silent after a restart is paused and offline for 24 h, not ended at 2 min` ``.
  - `` `a report from an offline place clears offline` ``.
  - `` `a music session keeps its queue, index and position while it lives` ``.
- `SessionStateLineTest` (**U**, `seams/`, new) — on `sessionStateLine(view, move, nowMs)` (FR-R372-5, review item 10):
  - `` `each of the 16 states maps to its key` `` (starting `loading` · paused `cast.paused_on` · finished `ab.finished` ·
    moving `session.moving` · move failed `session.move_failed` · offline `session.place_offline` · stopped by someone
    `session.stopped_by` · …).
  - `` `ended and adding a place have no words` ``.
  - `` `an older receiver is today's cast remote unchanged` ``.
  - `` `no state's words in en, da or fo contain the word session` ``.
- `PlayHereTest` (**U**, `music/`, new) — on `playHere(session, device)` (FR-R372-3, review item 3):
  - `` `music on the phone uses the hand-back at the session's place, 2 s back` ``.
  - `` `a film opens the player 2 s before the session's position` ``.
  - `` `the label is cast.play_here, cast.play_here_mac or cast.play_here_desk by platform` ``.
- `OfflineCastPlayTest` (**U**, `seams/`, new) — review item 7, owner decision 4: on a paused, offline cast session:
  - `` `the linked phone's Play is a new LOAD` ``.
  - `` `the web app's Play is a relay load while a relay app is online` ``.
  - `` `with no relay app Play is absent and Play here is offered` ``.
- `SessionControlPathTest` (**U**, `seams/`, new) — review item 1: `` `the newest cast holds the link and an older one is
  controlled through the server` `` · `` `the bar's touched-last pick ignores which one holds the link` ``.
- `TvSpeakersPanelModelTest` (**U**, `screens/`, new) — on `tvSpeakersPanel(session)` (FR-R372-6, owner decision 3):
  - `` `the panel exists for a group session sent to the TV` ``.
  - `` `the master goes to the receiver and a room to R371's road` ``.
  - `` `a room with no road is disabled with its reason` ``.

**2. Route and socket integration**

`SessionMoveIntegrationTest` (**B**, new; R369's loopback harness, fake clock):

- `` `move keeps the session id, stops the old place after the new one reports playing, and every controller follows` ``
  — acceptance 1.
- `` `the place line reads moving until the new place plays` ``.
- `` `a move with no playing report in 10 s fails and the old place carries on` ``.
- `` `the session keeps its id while its Jellyfin play session changes, inside the 15 s hold` ``.
- `` `a move from the web app onto a speaker relays cast_relay_load` `` (owner decision 4).
- `` `a stale move revision answers 409` ``.
- `` `an unplugged place's session turns paused and offline with Play here offered` `` — acceptance 3.
- `` `a play_item reaching a TV at sign-in is dropped and the load fails at 10 s` `` (R155 kept, review item 9).
- `` `a TV with Ravilo closed is not an app place` ``.

`playback-sessions.spec.ts` (**E**, from R369, extend) — `"a receiver whose socket closes leaves the session paused and
offline"`: close the fake receiver's page mid-song; the backend's session list shows it paused and offline at its last
position.

`WireCompatTest` (existing) stays green: the move route, `offline`, `end_reason` values and the new state keys are
additive.

**3. UI (Robolectric)**

- `MoveToSheetTest` (**R**, `components/`, new):
  - `` `the current place is ticked and a film lists only video places` ``.
  - `` `picking another place shows Moving to, then the new place` ``.
  - `` `a failed move shows Couldn't move to and the old place stays` ``.
  - `` `an offline session offers Play here and Move to` `` — acceptance 3.
- `TvSpeakersPanelTest` (**R**, `screens/`, new; the TV player on D-pad key events):
  - `` `up from the transport opens the Speakers panel on a group session` ``.
  - `` `left and right move the focused slider and send set_volume through the server` ``.
  - `` `the TV remote's volume keys are not consumed` ``.
  - `` `no panel on a session that isn't a group` ``.

Strings: `RaviloStringsTest` `the_move_keys_resolve_in_every_language` (`session.move_to`, `session.moving`,
`session.move_failed`, `session.place_offline`, `session.stopped_by`). `session.finished` is not added; `ab.finished` is
used.

**Only the real speaker can confirm** (Gæsteværelse; no other room)

- *CAF's paused timeout.* Pause a song on Gæsteværelse and time how long until the receiver closes and the session turns
  *offline*. Record it in the build notes.
- *Move and back.* Move a song from the Pixel to Gæsteværelse and back. Each move starts 2 s back, and the old place
  stops only once the new one plays.
- *Unplugged.* Unplug Gæsteværelse mid-song. Within 90 s the Pixel shows *Gæsteværelse is offline · paused at …* with
  *Play here*, and Play here resumes 2 s back.
- *Relay move.* From the web app, move a session that plays on the computer onto Gæsteværelse, with the Pixel online as
  the relay.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against R368's and R369–R371's reviews, `PlaybackService`'s watchdog, `RaviloApp`'s `play_item` path,
`MusicCast` (R353 hand-back, R358), `CastSenderAndroid`, the receiver, R299's *Play on this phone*, the TV player and
the canvases (`sessions-rest.js` §B9/§B11, `sessions-phone.js` §B7). *Several sessions*, *Move to…* and *Play here*
can be built on R368–R370 without Cast's stream transfer. The ending rules contradict R368 in two places (items 5
and 6). The TV bullets need fixing (item 9). Eleven items; three are for the owner.

1. **Several sessions.** The server side is free: R368's rule is one live session per (target, lane), with no per-user
   limit. The limit is Android's Cast SDK, which keeps **one** Cast session per app (R370 item 9). A phone running two
   casts holds the link to the newest one only, and controls the older one through the server (R369's receiver socket
   path). Nothing visible changes for the viewer. The bar's *touched last* rule (FR-R368-8) is a local pick of a session
   id, so it doesn't care which one holds the link.

2. **Move without stream transfer.** *Move to…* is Ravilo's own: start on the new place from the session's queue,
   index and position, then stop the old place when the new one reports `playing`.
   - **Route:** `POST /api/tv/playback/sessions/{id}/move { target_id, revision }` (R368 item 1 moves the family).
   - **Onto a Ravilo app:** the server sends R370's `session_load`.
   - **Onto a Cast device:** the *moving app* does the LOAD, because the backend can't (R370 item 1). So a move onto a
     speaker or a Chromecast is offered from the Android and desktop apps only. The web app, the iPhone and the TV app
     move only between Ravilo apps and screens; a Cast place is absent from their *Move to…*.
   - Cast's own `STREAM_TRANSFER` (R355 open question 1) is not needed for any of this.
   - **The session keeps its `id`; its Jellyfin side changes.** The old target stops and the new one starts, as at a
     song boundary. R368 item 6's 15 s hold covers the gap, so watched state and resume points come out as today.
   - **Failure:** no `playing` report within 10 s → `session.move_failed`, and the old place carries on. A Cast LOAD that
     never played gives the song back where it was (R358's path).

3. **Play here.**
   - **Music on the phone:** R353's hand-back (`castHandBack`, `MusicCast.kt:400`) already resumes a cast song on the
     phone at its place. *Play here* becomes a move to this device that uses it.
   - **Film:** the player opens at the session's position.
   - **Wrong key name:** the shipped keys are `cast.play_here` (*Play on this phone*), `cast.play_here_mac` and
     `cast.play_here_desk` (`en.json:398`, `:914`, `:941`), not `cast_play_here`.
   - R299's use is the failure sheet's. The new use is any session's ⋯ and its *Playing everywhere* row, with the same
     words.

4. **Rewind on a move.** FR-R372-2 says *speech, books and episodes*. Acceptance 3 rewinds a film (48:10 → 48:08), and
   "speech" is not a kind the server knows. Owner question 2.

5. **Correction: the watchdog doesn't end a session once this ships.** FR-R368-2 ends a session when the 110
   watchdog force-stops it. The watchdog fires after 90 s with no heartbeat, or at once for a device whose events socket
   closed (`stopWatchdogTick`, `PlaybackService.kt:972`).
   - FR-R372-4 says the same silence leaves the session **paused and offline for 24 h**.
   - So from this phase on, the watchdog still stops the **Jellyfin** playback (that saves the position, and the
     dashboard stays honest), but moves the session to `paused` with `offline = true` (a flag beside R368's
     `reconnecting`), not to `ended`.
   - FR-R368-2 is amended when this ships.

6. **Restart versus offline.** FR-R368-4 (owner) ends a session whose target hasn't reported within 2 minutes after a
   restart. FR-R372-4 (owner) keeps a silent place's session paused for 24 h. The server can't tell the two silences
   apart. Owner question 1.

7. **A paused speaker doesn't wait 24 h.** The receiver runs with `disableIdleTimeout = false` (`Receiver.kt:292`), so
   CAF closes the app once it has sat idle or paused for its timeout. When it closes, its socket goes and the session
   becomes paused and offline, as item 5 describes.
   - *Play* then needs a new LOAD. The linked phone already does that (R353's *Play* after a stop).
   - From the web app, the iPhone and the TV app, *Play* on such a session is absent, and *Play here* is offered.
   - CAF's paused timeout should be measured on the speakers once, and noted in the build notes.

8. **24 h for music.** A song has no Jellyfin resume point, so the session's own queue, index and position **are**
   the resume point while the session lives. When it ends at 24 h, R322's *last played, paused*
   (`/api/tv/music/last-played`) still gives the Playing tab its song. For a film or an episode, Jellyfin's resume
   point is the one kept, as written.

9. **The TV (FR-R372-6).**
   - **Wrong reference:** R274 is the phone's bottom bar. The TV's sign-in is R175.
   - **"Waits" would reverse R155.** Today a `play_item` that arrives during sign-in or at the profile picker is
     **dropped, never queued** (FR-R155-1.2/.3, `RaviloApp.kt:855`). Keep that: the load gets no report, ends `failed`
     after R370's 10 s, and the sender says so. A TV at sign-in has no socket anyway (the socket is per active user),
     so it isn't listed.
   - **Listed only while Ravilo is open on it** (R293). Otherwise its Cast route stands in (R370 item 4).
   - **The volume panel doesn't match the mockup.** The spec puts it on ▲ in the player chrome, for the TV's volume.
     The mockup (`sessions-rest.js:11-27`) draws a different thing: a *Speakers* panel for a **group** session, opened
     from a *Speakers* button, with each room's slider, and the note *the TV remote's own volume keys stay the TV's*.
     Ravilo can't set the TV set's volume in any case, only its own player's gain (R354 FR-R354-6). And ▲ already moves
     focus in the player chrome. Owner question 3.

10. **The 16 states and their words.** FR-R372-5 says every state's words are in the table. Most come from existing
    keys:
    - starting: `loading`
    - paused: `cast.paused_on`
    - reconnecting: R368's key
    - can't reach: R369's
    - someone else's: R368's person strings
    - finished: **`ab.finished`** (*Finished* · *Færdig* · *Liðugt*, already shipped), so drop `session.finished`

    *Adding a place* is R371's sheet with no state line. *Ended* is the 60 s fade with no words. *Older receiver* is
    today's cast remote unchanged; receivers are served by the backend, so it only lasts until that receiver page
    reloads after a deploy. New keys: `session.move_to`, `session.moving`, `session.move_failed`,
    `session.place_offline` and `session.stopped_by`. Regenerate the lexicon.

11. **Build order and tests.** Last of the Ravilo five, after R370 (loads, targets) and R371 (rooms).
    1. The server: the move route, offline-paused from the watchdog, the 24 h sweep.
    2. The client: *Move to…*, *Play here*, the states.

    Tests on a fake clock: a move fails at 10 s and the old place keeps playing; a watchdog reap leaves the session
    paused and offline, and the 24 h sweep ends it; a move keeps the session id while the Jellyfin play session changes.

**For the owner**

1. **After a server restart, a place that hasn't come back in 2 minutes: end its session (R368) or keep it paused and
   offline for 24 hours (this phase)?** The server can't tell a restart's silence from a switched-off speaker.
   **Lean: once this phase ships, both become *paused, offline* for 24 h. The 2-minute end applies only until then.**
2. **Which moves rewind 2 seconds?** **Lean: every kind except music** (a film, an episode and an audiobook all
   rewind), which also matches acceptance 3.
3. **The TV's volume panel.** **Lean: drop it from this phase.** The TV remote keeps its own volume. The group
   *Speakers* panel the mockup draws moves to round 2 with *Playing in other rooms*.

**Shipped bugs found (not fixed)**

None. (R368's review lists the watchdog-versus-restart race and the lost `directPlay` flag. Both touch item 5's
watchdog path, so build on its fixes.)


## Owner decisions (2026-10-04, after the dev review)

1. **Restart:** once this phase ships, a place silent after a restart is *paused, offline* for 24 h (replaces R368-4's
   2-minute end, as the dev review proposed).
2. **Every move rewinds 2 s, music included.**
3. **The TV's volume panel is built in this phase** — the group *Speakers* panel the mockup draws (▲ from the
   transport), with master + one slider per room, driven through the server (R371's relay for rooms, the
   receiver path for the master). The TV remote's own volume keys stay the TV's.
4. Moving onto a speaker from the web app, iPhone or TV uses R370's relay.

## Build notes (2026-10-05)

Built against the review and the owner decisions (every move rewinds 2 s, music too; a place silent after a restart is
*paused, offline* for 24 h; moving onto a speaker from the web app, iPhone or TV uses R370's relay).

**Server** (`PlaybackSessions`, `PlaybackTargets.kt`): `POST /api/tv/playback/sessions/{id}/move {target_id, revision}`
(`target_id = here` is *Play here*: the calling device). `move()` checks control and the revision (409 stale), a film or
an episode only to a video place, a book never to a Cast place; an app place gets `session_load` with the same session
id from `moveStartMs` (2 s back); a Cast device the caller sees answers `load_here`; any other Cast device is a relay
launch. `beginMove` sets *Moving to {place}…*; the new place's first start joins the session (its id kept; the
Jellyfin play session changes underneath, covered by the 15 s hold), the old place gets `Stop` once the new one reports,
and a late report from the old place starts nothing; no report in 10 s ⇒ `move_failed` and the old place carries on.
**FR-R368-2 amended:** the watchdog's reap leaves the session *paused* and `offline` (a report from the place clears
it); paused or offline sessions end 24 h after their last change (`idle` / `offline`); **FR-R368-4 amended:** a
restored session silent for 2 min becomes paused + offline, not ended.

**Apps:** the remote's place line opens *Move to…* (the server's places for the kind, the current one ticked);
*Play here* (⋯) hands music this app casts back through R353's path, and otherwise moves to this device
(`cast.play_here` / `_mac` / `_desk`). A move onto a Cast device this app sees is its own LOAD (`CastController.moveLoad`:
a hand-off naming the session, then LOAD on connection; the app keeps the link). The state line covers starting,
reconnecting, offline (*{place} is offline · paused at …*), moving / move failed, stopped by someone, finished
(`ab.finished`), someone else's. Strings `session.move_to`, `session.moving`, `session.move_failed`,
`session.place_offline`, `session.stopped_by` (`session.finished` not added).

**Completed 2026-10-05:** **the TV's *Speakers* panel** (owner decision 3). The TV app now asks for the session list
too (`features=sessions,session_control`; an installed TV without this build asks for neither and is unaffected). While
one of the viewer's own music sessions plays on a group of speakers (live, steerable, not here), the TV player's
transport row ends with a **Speakers** button (`PlFocus.SPEAKERS`, read from `TvSpeakers` snapshot state so the
player composable gains no locals — R258's register limit); Select opens the panel over the player: the title, the
rooms, *Volume* (the receiver's master) and one row per room (its level or *Muted*, *—* when it doesn't report, disabled
with its reason when no app can reach the rooms), then *Add a speaker…* (opens the free speakers; OK adds one). ▲▼ move
between rows, ◀▶ change the level by 5 through the server (`set_volume`, master or `cast_device_id`), OK mutes, Back
closes and the player takes focus back; the TV remote's own volume keys are never consumed. The focused row is lit and
ringed (R350). Strings `tv.speakers`, `tv.speakers_muted`, `tv.speakers_keys`. **The admin's *Move to…*:** the open
row lists the owner's places that can play it (`GET /api/tv/admin/playback/sessions/{id}/targets`, the owner device's
view) and moves it (`POST /api/tv/admin/playback/sessions/{id}/move`, made as the owner's device; a Cast place always
by relay, since the admin page has no Cast; `here` refused). The remote's *Move to…* now ticks a receiver's place by
its Cast device id. Not written: `MoveToSheetTest`, the Robolectric D-pad `TvSpeakersPanelTest` (the rules are covered
by the model test), the Playwright case.

**Tests:** `PlaybackSessionsTest` (+ reap → paused offline, the 24 h sweep, a move keeping its id and stopping the old
place, a failed move, the restart → offline), `MoveToTest` (3), `TvSpeakersPanelModelTest` (8), the loopback
`SessionMoveIntegrationTest` (4: the id kept, `session_load` 2 s back, *moving to*, the old place stopped once the new
one plays; a stale revision 409; a speaker move from the web app relayed; the admin's targets and move).

**Fixed after the Pixel 9 Pro test (2026-10-05):** the remote did not follow its session's end — once the row left the
list it fell back to the detail as it was (*Playing on …*, a pause button, the bar pinned at the end, *Can't reach …*).
A session gone from the list is shown ended (`remoteSessionView`): *Stopped on {place}* (`session.ended`), no transport,
volume, *Move to…* or *Play here*, nothing in flight. Test: `SpeakerRoundTwoTest`.

**Only devices can confirm:** CAF's paused timeout on a speaker; a move Pixel → speaker → Pixel (each 2 s back); an
unplugged speaker turning *offline · paused* within 90 s with *Play here*; a relay move from the web app.
