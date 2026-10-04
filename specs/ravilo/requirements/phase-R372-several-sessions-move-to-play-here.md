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

1. *Cannery Lights* on Stue. Eyð picks *Move to… → Kontor*. Kontor starts at the same second, Stue stops, and the
   MacBook's capsule follows without a blink.
2. *Sommeren ’92* paused on Soveværelse TV is gone from *Playing everywhere* after 24 h. Jellyfin's resume point is
   48:10.
3. Soveværelse TV is unplugged mid-film. The Pixel shows *Soveværelse TV is offline · paused at 48:10* with *Play
   here*. Play here resumes on the phone at 48:08.

## Decided by the owner (2026-10-04)

- Paused sessions end after 24 h.
- A place that goes offline pauses its session and keeps it 24 h, instead of ending it.

## Taken as leans

- Moving speech, books and episodes rewinds 2 s.

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
