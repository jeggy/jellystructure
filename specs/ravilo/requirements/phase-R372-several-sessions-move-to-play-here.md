# Phase R372 — Several sessions at once, *Move to…*, *Play here*, and how sessions end

## Scope

Written 2026-10-03 (design-authored), **dev-reviewed 2026-10-04** (see the end: moves are Ravilo's own LOAD + stop, no stream transfer; the watchdog leaves a session paused and offline, amending FR-R368-2; the TV bullets need fixing). **Phase 5 of 6** (see R368). Depends on
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

**FR-R372-4 — How a session ends.** **The two windows below are superseded by FR-R372-8 (amendment
2026-10-12): 4 h for a pause on a place that is still there, 10 min for a place that has gone offline.**

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

**Fix 2026-10-05 (Pixel 9, orchestrator):** removing the first room of a group (a move onto the other room) crashed the
app: `CastController.moveLoad` called `route.select()` on `Dispatchers.Default` after its hand-off request, and
MediaRouter throws off the main thread (*"The media router service must only be accessed on the application's main
thread"*). `selectRoute` (Android) now re-posts itself to the main looper, and `moveLoad`/`startLoad` leave and select
on `Dispatchers.Main`. Before the fix the group kept playing with no controller and the session row stayed "playing".

## Amendment 2026-10-05 — moves found with two sessions

- **A move to an app that is casting another session:** the app lets go of that other speaker and leaves it playing
  (as a relay does), then plays the moved queue itself. Before, *Play on this Mac* for Stue while the Mac drove
  Gæsteværelse loaded the songs paused and sent the play to Gæsteværelse; nothing was heard, Stue played on, and
  10 s later the remote said *Couldn't move*.
- **`session.move_failed` is said for 8 s** (`MOVE_FAILED_SHOWN_MS`), then the place line names the place again. It
  had stayed on the remote until the next move.
- **The place line says it opens *Move to…*:** a dim *· Move to…* follows *Playing on {place}* while the list is
  closed and the session can move.
- **The remote's *Up next*** lists the song playing and what follows it, not the songs already played.

## Amendment 2026-10-05 (night) — films moved between the phone and a TV

Found moving *Copper Static* between the Pixel and the Ravilo app on Stue TV:

- **From the player, a place moves the film:** with no play context, a tap on a Ravilo app in the players *Play on a TV*
  sheet did nothing. The player now passes its film and position, and the film already playing here moves (R372) rather
  than a second session starting.
- **The film starts where it was sent:** a `session_load` / `play_item` start position reaches the player
  (`Dest.Player.startAtMs`); a moved film began at 0:00 before. The Chromecast remote's *Play on this phone* keeps the
  cast's place too (2 s back).
- **The same film elsewhere:** the same-kind ask's *Play on {place} instead* resumes that session, and *Play on this
  phone* moves it here. A different film keeps R370's behaviour. Films only: an episode's session title is the series'.
- **One per lane on a device:** a move landing on a device ends anything else live in that lane there (the moved session
  stayed *starting* while reports went to an older copy on the same phone).

## Found live 2026-10-09 (evening, the Mac re-test) — fixed

From the server's session timeline and the Mac's log of the same evening:

- **A play that failed stayed in *Playing everywhere* as paused at 0:00 for hours.** An HDR film failed on the Mac in the
  afternoon (`started` → `paused` at 0 → `offline` a minute later from the 110 watchdog) and, under FR-R372-4, was kept
  24 h as *paused, offline* — until a new play on the Mac replaced it six and a half hours later. In the evening the same
  film failed again: the error screen stood for four and a half minutes with the session *paused · 0:00*, and it ended
  only when the player was left. *Fix, two halves:* (1) **the app stops the session the moment a play ends on R237's
  error** (a failed or never-moving stream after its one restream) — the row goes 15 s later, and *Retry* starts a
  new one; (2) **the server ends, rather than keeps, a session the watchdog reaps at 0:00** (`failed`): nothing was
  played, so there is no place to resume (FR-R372-4 keeps a session that has a place).
- **A late progress report undid a stop.** The app cancels its heartbeat before posting the stop, but a report already
  on the wire can land after it; it cleared the 15 s hold and left the film *paused* until the watchdog made it
  *offline* (24 h). *Fix:* while a stop holds the session, a **paused** report of the same item keeps the hold (a start
  or a playing report still joins, as a song boundary or an auto-advance needs).
- Tests: `PlaybackSessionsTest` — a reap at 0:00 ends the session `failed`; a paused report inside the hold leaves it to
  end `stopped`; a playing one still keeps it; `OurStreamStuckTest` covers the recovery decision the stop hangs on.

## Amendment 2026-10-12 — a dead session does not live for hours (owner decision; DEB-757)

From the owner's *Playing now* screenshot: a header reading **4 playing** over one real play and three rows that had
been *offline · paused* for hours. The investigations (DEB-758, the server; DEB-759, the clients) found that none of
the three is a crash, and that the 24 h of FR-R372-4 is not what prod actually does.

**What was measured on prod** (7-day window, 205 sessions, 46 of which went offline at least once):

- The keep is **24 h since the last change**, and a re-reap of an already-offline place *is* a change — so **27 of the
  46 were re-armed** and the row's life is unbounded. First `offline` → end: median 23 m, p90 12 h 52 m, **max 122 h
  36 m**. Exactly **one** session in the window ever ended through the keep; `idle` has never fired at all.
- Three re-arm mechanisms: a server restart re-registers a tracker entry for an already-offline row and reaps it again
  ~90 s later; a late progress heartbeat re-creates a tracked entry that is socket-less and is reaped on the next
  30 s tick; a queue report bumps the row without even writing a timeline event.
- The admin's *End* **cannot end exactly these rows** — the command route answers 409 `unreachable` before it reaches
  the end, so the button the page offers does nothing. There is currently no way to clear one by hand.
- On the client side there is one *commonMain* gap that produces this row shape on every platform at once: at the true
  end of a film or a last episode there is nothing to advance to, the credits card simply waits for input, and the
  10 s heartbeat keeps reporting **paused at the end position** for as long as the app lives. A closed browser tab
  reports nothing at all (no `pagehide`, no `sendBeacon`, and a plain `fetch` dies with the page).

**FR-R372-8 — how long a session outlives its place** (replaces the two 24 h windows in FR-R372-4 and owner
decision 1 of 2026-10-04):

- **Paused, and the place is still there** (it still reports, or still holds its events socket): ends **4 h** after
  the last change, `end_reason = idle`.
- **The place has gone away** (`offline = true`): ends **10 min** after the **first** `offline` of that offline spell,
  `end_reason = offline`. Detection is unchanged (socket close, or no report for 90 s), so a quit app's row is gone
  within roughly 11½ minutes and a closed browser tab's within about 10.
- **A re-reap does not restart the clock.** While a session is already `offline`, a further reap writes no new
  `offline` event, makes no second force-stop, and does not move the 10 min. The window is measured from the start of
  the offline spell, never from `updated_at` — so a queue report, a revision bump or a restart cannot extend it.
- **A restart does not revive a dead row.** A restored session that is already `offline` gets no tracker entry: it
  keeps its original clock and ends on it. FR-R368-4's restored-and-silent case (owner, 2026-10-04: *paused, offline*)
  keeps that state and takes the 10 min.
- **A report from the place still clears `offline`** (existing behaviour) and the session becomes paused-with-a-place
  on the 4 h window. A genuine later loss starts a new offline spell; FR-R372-12 keeps a flapping socket from
  manufacturing them.
- No wire change: `offline`, `idle` and the `end_reason` values already exist, and an installed client needs no new
  build. An offline row keeps counting in R368's glyph badge and the bar's *+N* — it simply stops doing so within
  ~11 min instead of days.

**What the shorter window costs, and why it is accepted.** Ending a session writes nothing to Jellyfin: the resume
point was already written when the watchdog force-stopped the place, and prod's `playback_outbox` is empty, so no such
write is outstanding. What goes with the row at 10 min is R372's own affordance — *Play here* and *Move to…* from that
row — and, for an item Jellyfin keeps no resume point for (audio, a position under Jellyfin's own minimum, or an item
Jellyfin has already marked played), the row's `position_ms` is the only copy of that position. For music R322's *last
played, paused* carries it. The owner weighed this on 2026-10-12 and chose the shorter window.

**FR-R372-9 — *End* ends a row whose place cannot be reached.** *End* on a session is an instruction about **our own
row**, not a command to the place. When the place is unreachable (`offline`, no socket, no sender control), *End* ends
the session (`stopped`) and answers success; it does not need to deliver anything, because the place has already been
force-stopped and its position written. Only the commands that genuinely need the place (play, pause, seek, volume,
*Move to…*) may answer 409 `unreachable`. The admin's wording for *End* (phase 304) stays true for both cases.

**FR-R372-10 — a play that runs out ends its session.** When the engine reaches the end of the item and there is
nothing to advance to (a film, a last episode, the end of a queue), the app ends the session at that moment — the
*played out* line of FR-R372-4 — and stops its heartbeat. The credits or *Skip credits* card may stay on screen; what
must not happen is the app reporting **paused at the end position** every 10 s until someone presses Back. An episode
with a next one is unchanged (the outgoing store already stops at its own playhead).

**FR-R372-11 — a browser tab that goes away ends its session.** Two halves, and the server half is the one that
closes the class:

> **Amended 2026-10-30 (DEB-2998).** On a computer a hidden page has not gone away: the client half's stop on
> `visibilitychange → hidden` no longer applies there, and `pagehide` alone carries it (R376, FR-R376-4f, FR-R376-4g). A
> phone or tablet keeps both events. The server half is unchanged.

> **Amended 2026-10-30 (DEB-3036, measured on DEB-3093).** An abrupt end is no longer read at once, because a bare close
> frame does not say whose it is. A browser tab's own 1001 and the 1001 every socket gets when Caddy reloads look the
> same, and Caddy reloads whenever a labelled container on the host starts or stops. One reload force-stopped a film
> on a TV that was still playing it: the socket was back 1.1 s later, but the film's encode was already gone, and 52 s
> later the TV had to restream.
>
> - A socket that ended abruptly counts as gone only once it has **stayed gone for 15 s**. The longest reload gap on
>   record is 9.9 s, and the reconnects seen after a reload took 1.1 s and 2 s. This holds on every tick, the close's
>   own and the periodic 30 s one, so neither reaps inside the 15 s, however old the play is.
> - The close arranges one more read at 15 s, so a place that really went is read then and not at some later tick. A new
>   socket from the device inside the 15 s ends the matter: no `offline`, no stop to Jellyfin, no encode released.
> - The client half is untouched, so a tab closed normally still ends its session at once through its own stop. A tab
>   that dies without sending one reads as gone at 15 s rather than at once.
> - A live tune takes the same 15 s. `LiveTvService.stopWatchdogTick` runs off the same close with no grace at all. The
>   server half below left it out on purpose; it is the same fault, read from the code and not yet seen on a live tune.
> - Unchanged: the 90 s heartbeat test, a clean `background` close, a `replaced` socket, the `cast` and `screen` kinds'
>   exemption, and FR-R372-12's two holds, which the abrupt path still goes past once the 15 s are up.

> **Amended again 2026-10-30 (DEB-3093, trouble-never-reaches-the-viewer.md FR-1).** Those 15 s decide only when the place reads as gone. A reap no
> longer ends the stream, and a device that reported progress in the last 30 s is not reaped on the socket test at all.

- **The server:** for a place that holds its events socket for as long as it exists — a browser tab, the Cast
  receiver, the screen app — an **abrupt** socket end with a session still open is read as *the place is gone*: the
  session goes `offline` at once and takes FR-R372-8's 10 min. A **clean** close carrying R293's `background` reason is
  **not** evidence that playback ended (R293 drops the socket on purpose when an app goes off screen, and a minimised
  desktop keeps its film), and is read exactly as today.
- **The client:** the browser sends its stop on `pagehide` and on `visibilitychange → hidden` through a transport that
  survives the page. **`fetch(…, { keepalive: true })` is the one to use**, because it still carries the device token
  in its header exactly as every other call does. `navigator.sendBeacon` is the fallback only if `keepalive` proves
  unreliable in a browser we support; it cannot set headers, so the token would have to ride the query string — the
  precedent FR-R293-7 already set for this same client's socket handshake (`WS_TOKEN_IN_QUERY`), and it is never
  logged. Either way this is an addition to the wire, so no installed client is affected.

**FR-R372-12 — the watchdog does not force-stop a play that is alive.** Two cases found in the same prod window, both
of which stop a viewer's film under them:

- **A just-started session has no socket grace.** The heartbeat test waits 90 s; the socket test does not, so a play
  started in the second before its socket is up is force-stopped on the next tick — one prod row was reaped **1.2 s**
  after it started. The socket test gets the same 90 s grace from the session's start.
- **A flapping socket must not reap a reporting place.** One prod session logged **112 `offline` and 89 `back_online`
  events in 6 h 23 m**, minimum gap 30 s: each late heartbeat re-created a socket-less tracked entry that the next tick
  reaped, force-stopping Jellyfin under someone who was watching. A progress report for a session the watchdog has
  reaped brings that session **back online** (and back under tracking) instead of creating an entry that is reaped
  immediately. While reports keep arriving, there is no force-stop.

### Acceptance (this amendment)

A1. A browser tab playing something is closed. Within ~10 s the row reads *offline · paused*; **10 minutes later it is
gone** from *Playing now* and from *Playing everywhere*, with `end_reason = offline`.
A2. A film is paused on a TV that is still on and still reporting. The row survives a restart and the next 3 h, and is
gone after **4 h** (`end_reason = idle`). Jellyfin's resume point is unchanged.
A3. A row that has been *offline · paused* for 5 minutes survives a backend restart with its clock intact: it ends
~5 minutes later, not 10, and no new `offline` event is written.
A4. *End* on an offline row in the admin's *Playing now* removes it at once. No *Can't reach {place}*.
A5. A film is watched to its very end on the phone and left on the credits card. The session ends at the end of the
film; the row does not sit at *paused* at the end position.
A6. A play started on a device whose socket comes up a second later keeps playing; it is not force-stopped.

### Tests

- `PlaybackSessionsTest` — the 10 min offline sweep; the 4 h idle sweep; a second reap of an offline session writes no
  event and does not move the clock; a restart leaves an offline row's clock alone and registers no tracker entry; a
  queue report on an offline row does not extend it; a report clears `offline` and puts the session on the 4 h window.
- `SessionCommandsTest` — *End* on an unreachable, offline session ends it (`stopped`); play/pause/seek/volume/move
  still answer 409 `unreachable`.
- `PlaybackWatchdogTest` — no force-stop inside 90 s of a start when the socket is not yet up; a heartbeat for a reaped
  session brings it back online and is not reaped on the next tick.
- Client: the player ends its session when the engine runs out with nothing to advance to (and the credits card stays);
  the browser posts its stop on `pagehide` with a page-surviving transport.
- `WireCompatTest` stays green: nothing is removed or retyped.

### On the QA queue

The four device claims from DEB-759's plan (film end on phone / Mac / browser / TV; a web tab closed versus switched
away; a Mac ⌘Q versus Force Quit; a phone swipe-away for video and then for music), each watched in the admin's
*Playing now*, plus A1–A4 timed against a clock. Low or muted volume throughout, restored afterwards.

### Build notes 2026-10-12 — the server half of the amendment

Built in two commits because the three server requirements share one file each way; the order was forced, not chosen.

**FR-R372-8 / FR-R372-9, and FR-R372-12 riding along** (DEB-768, DEB-771). `SESSION_OFFLINE_KEEP_MS` = 10 min measured
from `SessionRec.offlineSince` (the first `offline` of a spell, read back after a restart from the timeline's one
`offline` event), `SESSION_PAUSED_KEEP_MS` = 4 h, as two sweep branches rather than one; a re-reap of an already-offline
row returns at once, writing no event and moving no clock; `restore()` brings an offline row back offline rather than
*reconnecting* and leaves it out of the list it returns, so no tracker entry is registered for it (prod's commonest
re-arm); `stop` on an offline place routes to `CommandRoute.EndOurRow`, which delivers nothing and ends the row
`stopped`, while every other op still answers 409 `unreachable`. FR-R372-12 could not be a separate commit: it shares
`shouldForceStop` and the tracker with the above. The socket test now takes the same 90 s grace from a start that the
heartbeat test has (`TrackedPlayback.startedMs`), an entry a progress report created is judged by heartbeat alone
(`fromReport`), and the watchdog's own force-stop is marked as such (`stopped(reaped = true)`) so the place's next
report comes straight back under tracking instead of being swallowed by the 60 s post-stop grace and then re-creating
an entry the following tick reaps.

**FR-R372-11, the server half** (DEB-771). `EventsCloseCause.isAbrupt(closeReason, error, replaced)` is the
discriminator, beside the classifier that already had the same three inputs: a clean close carrying R293's
`background` is not abrupt (nor is a `replaced` slot, where a newer socket of the same device holds it); an error, an
EOF, a ping timeout, a bare close frame and a clean close with any other word are. `Server.kt`'s close path passes the
closing device through `stopWatchdogTick(…, socketEndedAbruptlyFor = …)`, and for that one device `shouldReap` goes
past FR-R372-12's grace.

That override is not tidiness — **FR-R372-12 on its own regresses A1.** Today's read-at-once on a socket close is an
accident of the socket test having no grace at all, so with the grace in and nothing else, a browser tab closed two
seconds into a film sat at *playing* for 90 s, and a `fromReport` entry never read as gone at all. A tab closed well
into a film was still read at once (`startedMs` an hour old), which is why the regression is narrow and worth writing
down rather than assuming.

`isDeviceConnected` is still consulted on the abrupt path, so a reconnect or a replaced socket stops the reap; a
session still *reconnecting* after a restart stays judged by heartbeat alone (R368 review item 12 a), unchanged.

**Deliberately not touched.** `LiveTvService.stopWatchdogTick` mirrors the old test for the tuner-stream lifecycle and
is outside this amendment. `cast` and `screen` kinds stay exempt from the socket test entirely (FR-218-11, FR-236-8),
even though FR-R372-11's prose names the Cast receiver among the places that hold their socket: the exemption exists
because the socket test once read as *disconnected* 11 s into every cast and force-stopped it. Pulling the receiver
into the socket test is a separate decision, not this one.

**Tests.** `PlaybackWatchdogTest` (8 — the 1.2 s row, every tick inside the 90 s, a closed socket still ending the play
past the grace, a silent play still reaped by its heartbeat, the flap coming back under tracking and surviving 20
further ticks, the viewer's own stop still swallowed, a new start judged by its socket again, reconnecting unchanged,
and the clean-`background`-versus-abrupt close both ways round); `EventsSocketHealthTest` (`isAbrupt` over every close
shape, including an error beating a `background` close frame); `PlaybackSessionsTest`, `SessionCommandRuleTest` and
`SessionCommandIntegrationTest` from the first commit. No wire change in either commit, so `WireCompatTest` is
unaffected and no installed client needs a build.

**Not built here:** FR-R372-10 and FR-R372-11's client half (the browser's `pagehide` stop) are the clients' — DEB-774.
A1–A6 need a clock, a real place and the admin page, so none of them is proved by these commits; they are DEB-775 on
the QA queue. Nothing is deployed: prod runs neither commit.

### Build notes 2026-10-13 — the client half of the amendment (DEB-774)

Written and built through the evening of 2026-10-12; the commit crosses midnight, so this half is dated 2026-10-13
and the server half above 2026-10-12.

One commit, `commonMain` first: FR-R372-10 is one decision shared by all five clients, and the three platform pieces
hang off it.

**FR-R372-10 — a play that runs out ends its session.** The gap was where DEB-759 said it was, and it was a guard:
the natural-end branch of the poll loop (`PlayerScreen.kt`) is `!nextUpVisible && !nextUpDismissed`, so with the
credits card already up from the early trigger the end of the file was **never acted on at all** — and
`startHeartbeat`'s loop went on posting `progress(position = the end, isPaused = true)` every 10 s for the life of the
app. `EndOfPlayWatch` (beside `StuckWatch` in `PlayerStore.kt`, over the pure `endsPlayNow`) takes the decision once
per item, deliberately **not** guarded on the card: loaded for this item · the engine ended · nothing to advance to ·
no advance already asked for. "Nothing to advance to" is `advanceNext`'s own test — a next episode *and* a host that
can navigate to it — read off the live values (`currentNextEpisodeId`, a new `currentNavigateToEpisode`), never the
ones the poll loop captured when it started: that is R381's prefetch bug in a new place (the Pixel, 2026-10-08).

`PlayerStore.endOfPlay` is the existing `stopSession` — the bounded 3-attempt stop retry, the final QoE post, R347's
≥90 %/credits mark-played — with **the store's state put back afterwards instead of left `Idle`**. The spec says the
card may stay on screen, so the picture and its chrome have to stay with it, and `Idle` would disable the transport
(`enabled = … sessionState is Ready`) and change `VideoShutter` under a viewer still looking at the card. No second
stop comes free rather than by a new guard: `stopSession` clears `currentItemId` up front, so `close()` on the way out
of the card finds nothing to post. An episode with a next one is untouched (`hasNext`), and so is the `SkipMode.OFF`
path, which already exits through `skipCredits()`.

**A scrub back out of the end is not covered, and cannot be from here.** After *Watch credits* the chrome is up and
the viewer can scrub backwards into a real play — and with the session ended, that play reports nothing. Restarting
the client's heartbeat would be **inert**, which is worth writing down: the route drops a progress tick for an item
that already reported a stop (`PlaybackService.reportProgress` → `PlaybackTracker.heartbeat`), and
`PlaybackSessions.onProgress` refuses to create a session for a (device, item) that has an ended row. A genuine
resume therefore needs a fresh `startPlayback` — a re-negotiation and a visible reload — which is past this
amendment's text and is a decision for the owner, not a few lines smuggled in here (**DEB-808**). Jellyfin's position
and watched state are already written by the stop at the end of the film, so what is lost is the report of a
re-watched tail.

**FR-R372-11, the client half — the browser.** `wasmJsMain`'s `PlayerLifecycleEffect` listened to `visibilitychange`
only, and its stop was a Ktor `Js` fetch the browser aborts with the page. It now listens to `pagehide` too — the one
event a closing tab is guaranteed to fire — and both events run the stop inside `UnloadStop.whileUnloading`, so
`PlayerStore.stopSession` posts it **synchronously, in the event**, through `fetch(…, { keepalive: true })`:
`TvApiClient.stopPlaybackPost` builds the same route, the same body and `auth()`'s own headers as plain data
(`PlainPost`), so the device token stays in `Authorization` and nothing rides the query string. `sendBeacon` was not
needed and is not built. One stop either way — the first event latches `away`, and `stopSession` clears its item
before anything else can post. The PiP/AirPlay exemption stays on `visibilitychange` and is not applied to
`pagehide`: a page that is going away takes its AirPlay stream with it.

Both events going through the keepalive transport is what the spec asks for, and it has one cost worth stating: a
keepalive post is fire-and-forget (there is no answer to wait for once the page is gone), so a plain tab-switch stop
no longer gets the 3-attempt retry it had. A stop lost that way is exactly the case FR-R372-11's server half closes
from the socket. The transport is null on every other platform, where `UnloadStop.post` answers false and the stop is
byte-for-byte the one it always was.

**Desktop (Mac, Linux).** `DesktopShutdown.installShutdownHook()`, installed in `main()`, runs the same two steps ⌘Q
takes — two seconds for `TeardownWork`, then every registered step — on a log-out, a session shutdown, `SIGTERM` or
`SIGINT`. `runAll` got a run-once latch in the same change, without which the hook would re-post every step after an
ordinary ⌘Q had already sent them. A Force Quit (`SIGKILL`) has no hook and stays the server's.

**Android music.** `closeSong()`'s stop moves off the engine's `Dispatchers.Main.immediate` scope onto its own
`exitScope`, with the video stop's bounded 1 s/2 s/4 s retry: `RaviloMusicService.onTaskRemoved` calls `stopSelf()` on
the next line, so it was one roll of the dice against process death.

**Cast receiver and Tizen: untouched**, as the amendment says.

**Tests.** `EndOfPlayTest` (**U**, new — the film that runs out, the episode with a next one, an advance already
asked for, one stop however many ticks see the end, the next item arming it again); `UnloadStopTest` (**U**, new —
no transport means the ordinary stop runs, the browser's takes the request whole, a transport that refuses or throws
falls back, `unloading` set only inside the handler and cleared on a throw); `UnloadStopWireTest` (`shared`
`commonTest`, new — the unload stop's headers are `auth()`'s, the token is in a header and never in the URL, and an
unpaired device sends no `Authorization`); `DesktopShutdownOnceTest` (**desktopTest**, new — the steps run once
however many ends ask for them).

**Wire:** additive only — a new non-suspending builder beside `stopPlayback`, posting the existing route with the
existing body. No field added, retyped or removed, so an installed APK is unaffected and `WireCompatTest` is too.

**Not proved here.** A5 is a timed watch of the admin's *Playing now* on a real device, and so are the browser-tab,
⌘Q-versus-SIGTERM and swipe-away claims: all of them are DEB-775 on the QA queue, with no device touched from this
seat. Nothing is deployed.

## Amendment 2026-10-14 — two ways a session ends wrongly, and one way it never ends at all (DEB-1054)

Found while QA proved R380 acceptance 4 on the Stue TV (DEB-1005 → DEB-1054) and measured against a read-only copy of
prod's SQLite at prod's own commit `v1.51-174-g5b13710e`. The book half of that bug — a part change minting a nameless
`film` session — is R368's, and is written up in its *Amendment 2026-10-14* (FR-R368-12). **Neither rule below is
book-specific, and neither is fixed by that amendment.** Both are this phase's: they are ending rules.

**FR-R372-13 — `failed` means a play that never reported, not a position that reads 0.** Today `onReaped`
(`tv/PlaybackSessions.kt:816`, *found live 2026-10-09, evening*) ends the row `failed` whenever the reaped session's
`position_ms <= 0`, instead of keeping it *paused · offline*. The rule was written for a load that never played, and
for that it is right. But the stop watchdog's own forced stop writes the **tracker's** position into the session first
(`PlaybackService.stopPlayback` → `onStop`, `:803`), so a row can reach the reap holding `0` for reasons that are not a
failure at all. Measured on prod for `ps-9b84dae0c5257a6b`: `started` 07:57:38.446, `paused` 07:57:55.600, then
`ended … failed` at 07:59:54.510 with `position_ms = 0` written at 07:59:54.509 — the two writes 1 ms apart in one
tick, `revision 7`, and **no `offline` event**. That is `STOP_WATCHDOG_MS` (90 000 ms, `PlaybackService.kt:132`) to the
millisecond after the last heartbeat for the part that was left behind; the load watchdog is eliminated on timing
(its arm fires 10 s after a `starting`, and this death is 72 s after the mint and 119 s after the pause).

The rule narrows to what it was written for: **a reap ends `failed` only when the session never had a report of its
own — it never left `starting`.** A session that has been `playing` or `paused` goes `offline` and keeps its place on
FR-R372-8's 10 min clock, whatever its position reads. For a book the old rule is doubly wrong: a part-relative `0` is
a legitimate place (the head of a part), and the book's real place is `book_ms` / `audiobook_progress`, which the row
never consulted. The cost of narrowing it is that a genuinely failed load which did manage one `playing` report at 0
now waits out the 10 min offline window instead of ending at once — a row that lingers, against a row that lies. The
lie is worse: `failed` is the one signal DEB-919 added for diagnosing this area, and it is useless if a session that
played can carry it.

**Open, and for the fix to confirm rather than assume:** why the tracker held `0` rather than the paused 26.038 s for
the part being left. Position updates are not events, so prod has no row to read; it is a question about what the app
reports for the old part as it crosses a boundary (DEB-1054's repro B shows the app does report `0` at a roll:
`starting 0`, then `playing 0`). The rule above makes the answer harmless either way, which is why it is not a blocker.

**FR-R372-14 — no live session may outlive every clock.** Each ending arm in `tick()` (`:980`) requires one of:
`offline` (→ 10 min), `paused` with no stop-hold (→ 4 h), `starting` **with** a `load_deadline` (→ 10 s), or a
`stop_hold_until`. A session that is **`playing`, not offline, with no `load_deadline` and no stop-hold matches no arm
at all** — there is no stale-`playing` deadline anywhere, so nothing inside `PlaybackSessions` can ever end it. It ends
only if something outside it reaps its tracker entry or drops its socket. DEB-1054's minted ghost sat `playing` at a
position that never moved, was still there minutes later, and went only on an explicit `stop`.

So: **a live session whose `position_at` has not moved for `SESSION_PLAYING_SILENT_MS` ends `idle`**, the same end as
the paused arm. **4 h** — deliberately the same keep as FR-R372-8's paused window, and deliberately far longer than any
reporting cadence this product has (10 s heartbeats, the 90 s stop watchdog, a receiver that reports on change). This
is a floor against an immortal row, not a second watchdog, and nothing is exempt from it: `cast` and `screen` sessions
are exempt from the **socket** test (FR-218-11, FR-236-8) because a quiet socket is not evidence there, but four hours
without a single position report is not a cadence for anything. If a kind ever legitimately plays for 4 h in silence,
that is a dev-review objection to raise against this number, not a reason to leave the gap.

This requirement **outlives DEB-1054**. Fixing the mint (FR-R368-12) removes today's way of making such a row; it does
not give `tick()` the ability to end one.

**Also for the engineer, stated and not measured:** why the ghost outlived the 90 s stop watchdog at all is not known.
A plausible mechanism is that `onReaped` and `findLocked` both pick with `lastOrNull { … it.itemId == itemId }`, so
with two live sessions on one place and one item id a reap can land on the wrong row — which would also explain how
the *book's* session took the `failed` that was due to the abandoned part. Confirm it from the code; FR-R368-12's
match precedence must not make the ambiguity worse.

### Acceptance (this amendment)

Both are provable without a device, and that is the point of writing them this way:

1. A session that reported `playing` and then `paused`, reaped by the watchdog with position 0, reads
   `offline = 1`, `state = paused`, `end_reason` NULL — and ends `offline` 10 min later. A session that never left
   `starting`, reaped the same way, still ends `failed` at once.
2. A live `playing` session with no report for 4 h ends `idle`; at 3 h 59 m it is still live.
3. On prod after the deploy, `select count(*) from playback_session where end_reason = 'failed'` gains no row for a
   session whose events include a `paused` or a `resumed`.

### Tests (this amendment)

- `tv/SessionEndingTest` — the two reap cases of acceptance 1, and the 4 h stale-`playing` arm with its boundary, on
  the injected clock. No sleeping.
- `tv/PlaybackSessionsTest` — the `failed` arm still fires for a load that never reported, so DEB-919's signal is kept.
- The device half of DEB-1054 (◀ back across a part boundary on the Stue TV, where the `failed` was seen) stays on the
  QA queue and is **not** a condition of the build: the three cases above decide whether the fix is right.

## Amendment 2026-10-16 — a film watched to the end is over (owner decision; DEB-808)

> Owner, 2026-10-16 (card `c8500823` on DEB-1406), asked whether a scrub back out of the end of a film is a new
> session or the film is simply over: *"Over — one spec line, nothing to build."*

**FR-R372-15 — after FR-R372-10 ends a session at the end of an item, a scrub back into that item is not a session.**
It reports nothing, starts no `startPlayback`, and *Playing now* does not show it; Jellyfin keeps the position and
watched state the end-of-play stop already wrote. This answers the gap the 2026-10-13 build notes named (*A scrub back
out of the end is not covered*) and closes DEB-808 with nothing to build. Pressing Play on the item again is a new play
and a new session, exactly as today.
