# Phase R370 — One *Play on…* list: start on any place, join or start new

## Status

`Planned` — written 2026-10-03 (design-authored), **dev-reviewed 2026-10-04** (see the end: the backend can't load a Cast device, so only the Android and desktop apps start on one, and the app's own discovery is half the list; books never cast). **Phase 3 of 6** (see R368). Depends on
R368 and R369. Designs: `Playback Sessions - Directions.html` §B3 (one *Play on…* list) and §B6 (join-or-new moments).

## Requirements

**FR-R370-1 — One list of places, from the server.** `GET /api/tv/targets` → `[Target { id, kind, name, icon,
capabilities { video, audio, display }, busy: SessionRef?, reachable }]`. It merges Ravilo devices with an events
socket open and Cast receivers (each scanning app reports what it finds, and so does the backend; report §4.4).
**Single places only** (owner, 2026-10-03): no group, *whole house* or *everywhere* entry — a speaker group the
household made in the speakers' own app is left out too. More rooms are added after starting (R371). The server removes duplicates. Every *Play on…* sheet and popover shows this list. Today's phone-only
discovery list becomes a fallback for when the server can't be reached.

**FR-R370-2 — Four tiers, in this order** (canvas §B3):

1. **This device** (*This phone* / *This Mac* / *This computer*).
2. **Playing now**: places with a session, each showing what it plays.
3. **Free places**: TVs and displays first, then speakers, by name.
4. **Unreachable**: last seen within 24 h, dimmed, with *Not reachable*. They can't be picked.

The list depends on the kind: film and episode list only `video` places, music and books list all of them. A place that
can't play this kind is absent, not greyed. The iPhone keeps R324's footnote and its own rules.

**FR-R370-3 — Starting on a place.** `POST /api/tv/sessions { target_id, items, index, start_ms, options }` creates
the session (FR-R368-1) and tells the target to load it. An app target gets a `session_load` event; a cast target is
loaded by the backend through its channel. If the backend can't load a receiver itself, the sending app loads it and
attaches. The session starts as `starting`, and the app opens the remote straight away.

**FR-R370-4 — A place that is busy always asks (owner, 2026-10-04)** (canvas §B6). Choosing a place that already
has a session opens an inline choice on that row, **every time** (never remembered, no *Remember* switch):

- **Play {title} here instead.** The current session there ends at its position (a paused film keeps its resume point
  as today), and a new one starts.
- **Cancel.**

There is **no *Add to what's playing*** — adding to a queue stays in the song menu (*Play next*, R322). The same
always-ask applies when the viewer presses Play on something of the same kind already playing for them elsewhere
(music while their music plays on Stue; an episode of a series playing on a TV): *Play there instead* or *Play here*
(the other keeps playing). On someone else's session, *Play here instead* is offered only when 304's switch is on, and
reads *Stop {person}'s {title} and play here?*. A place busy outside Ravilo keeps R324's *Stop Spotify and play here?*.

**FR-R370-5 — Starting a second session is just starting one.** Picking a free place while something plays elsewhere
creates a **new** session. The other one keeps playing, and the bar now shows +1 (FR-R368-8). There's no "you already
have one" warning.

**FR-R370-6 — Strings** × en · da · fo (drafts):

| Key | en | da | fo |
|---|---|---|---|
| `target.playing_now` | Playing now | Spiller nu | Spælir nú |
| `target.free` | Free | Ledige | Leys |
| `target.unreachable` | Not reachable | Kan ikke nås | Fæst ikki samband við |
| `target.replace` | Play {title} here instead | Afspil {title} her i stedet | Spæl {title} her í staðin |

## Acceptance

1. With nothing playing, Eyð's Pixel *Play on…* lists This phone · Stue · Gæsteværelse · Kontor · Soveværelse TV, and
   nothing that plays on several places at once.
2. *Cannery Lights* plays on Stue, and she picks Stue for another album. The row asks *Play … here instead / Cancel*
   — no *Add*. She does it again a minute later and is asked again.
3. She starts *Sommeren ’92* on Soveværelse TV from the MacBook. Stue keeps playing, and the capsule shows **+1**.
4. Kontor is switched off. It shows *Not reachable* at the bottom and can't be picked.

## Taken as leans

- Four tiers in this order. Unreachable places listed for 24 h.

## Decided by the owner (2026-10-03)

- **No cast-everywhere button.** *Play on…* lists single places only; you pick one, then add another (R371).
- **2026-10-04: a busy place always asks, never offers *Add*** (FR-R370-4).

## Dev review (2026-10-04, against `main` `5210045a`)

Read against R368's dev review, `RemoteRoutes` (236), `TvEventBus`, the `play_item` path in `RaviloApp`, `CastSender`
/ `CastRoute` / `rememberCastRoutes`, `CastSenderAndroid`, `ActiveCastSender`, `ScreenSender`, `MusicCast`, the music
engines, `RaviloDeviceService`, `CastService`'s hand-off, `ravilo-castv2` and the receiver. The four tiers and the
always-ask choice can be built. **The backend cannot load a Cast device** (item 1), so starting on a speaker stays
with the apps that have a Cast sender, and the phone's own discovery is half of the list, not a fallback (item 2).
Fourteen items; four are for the owner.

1. **Correction to FR-R370-1/-3: the backend neither scans nor loads.** There is no report §4.4 (the report ends at
   §4.3).
   - The backend runs in Docker on a bridge network (`docker-compose.yml`), with no mDNS. It is not a Cast sender.
   - An idle receiver has no events socket (it opens only while an item is loaded, `Receiver.kt:300`), and the receiver
     page doesn't run at all until a sender has launched the app on the device. So "a cast target is loaded by the
     backend through its channel" is not possible today.
   - That is R286's *road C* (the backend as a Cast sender). `ravilo-castv2`'s protocol is already commonMain; TLS to
     :8009 and mDNS are jvmMain (`ravilo-castv2/build.gradle.kts`). It would need a `linuxX64` target, native TLS that
     accepts the devices' self-signed certificates, and device addresses supplied by the apps. That is its own phase
     (owner question 1).
   - **As built here:** `POST /api/tv/playback/sessions` (R368 item 1 moves the family off `/api/tv/sessions`) creates
     the session in `starting`. **The starting app** does the Cast LOAD as today (`castHandoff` → LOAD → `castRedeem`).
     `CastLoadData` gains `session_id`, so the receiver's first report joins that row.

2. **The app's own discovery is half the list, not a fallback.** A Cast row starts only through
   `CastRoute.select` (`CastSender.kt:150`), and that closure exists only where this app's discovery found the route:
   Android through MediaRouter, the desktop through `ravilo-castv2` with Bonjour or jmdns.
   - The web app, the iPhone and the TV app can't start anything on a Cast device. Their *Play on…* lists Ravilo apps
     and paired screens. A Cast place reaches them only as a busy row through sessions, and can't be picked there.
   - The server's list adds the Ravilo apps, the screens, *busy with what* and *whose*. The client merges the two by a
     stable Cast device id (item 3).
   - R368's status note ("R360's device half reads R370's list instead of the sheet's own discovery") should not
     happen. R360's `hasDevices` keeps local routes for Cast places, and adds the server's app targets.

3. **A Cast route and its server record need a shared key.** Receiver rows share the receiver's `device_id` across
   users (phase 300, R368 item 5), but nothing on them names the Cast device. So the server can't tell the phone that
   the *Stue* route is the one playing Olivar's session.
   - `castHandoff()` (`TvApiClient.kt:477`, no body today) gains an optional body `{ cast_device_id, session_id }`:
     Android's `CastDevice.deviceId`, the desktop's mDNS `id`. The server stores `cast_device_id` on the receiver's
     device row. An older app sends no body, and that works as today.
   - `Target` carries `cast_device_id` for Cast places, and busy is keyed on it.

4. **One physical TV can be two places.** A Google TV running Ravilo is a Ravilo app (`kind tv`) while Ravilo is on
   screen, and a Cast route always. They share no id.
   - Show one row when the names match (the app's display name against the route's friendly name, ignoring case), and
     prefer the app while its socket is open: the native player, no hand-off.
   - When Ravilo is closed on it, the TV is still reachable as its Cast route. That is the honest answer to R293 (a TV
     app off screen holds no socket).
   - When the names differ, both rows show.

5. **Group rows go.** R324's `CastRoute.kind == "group"` rows (Google Home groups, when the platform lists them) and
   R355's dynamic-group route of another session (*Stue + 1*, which `ScreensSheet` lists as a *Speaker group* today)
   leave *Play on…*. That is the owner's single-places rule. A session on a group shows once in *Playing now*, named
   by its rooms (R371).

6. **Capabilities must come from the app, not from `kind`.**
   - **`kind` can't tell.** The desktop registers as `kind = tv` (`RaviloDeviceService.kt:151`, default `"tv"`), and
     `deviceKindOf` maps anything unknown to TV (`Models.kt:1551`).
   - **Not every app plays music.** The web app has no music engine (`MusicEngineWasm.kt`, `supported = false`). The
     TV app has no music mode (`!isTvPlatform`, `RaviloApp.kt:621`).
   - **Books never cast** (`MusicCast.kt:382`, R323).
   - So the events socket declares what the app plays, beside `remote=` and `features=`: `plays=video,music,book`.
     Receivers derive theirs from `platform` (`cast` = display: video and audio; `cast-audio` = speaker: audio).
   - **Correction to FR-R370-2:** "music and books list all of them" is wrong for books. Books list Ravilo apps that
     declare `book`, and no Cast place (owner question 4).

7. **An app is a place only while it holds its socket.** The TV and a phone's film player hold one only on screen
   (R293). A phone or computer playing music holds one off screen, and for 10 minutes after a pause (R354 FR-R354-5).
   - So the TV is a place only while Ravilo is open on it, and a phone in a pocket is not one.
   - A film `session_load` follows `play_item`'s on-screen gate (`RaviloApp.kt:545`, `:855`). A music or book load is
     accepted while the socket is held for music.
   - The `Target` row says so: `video = false` while the socket is held only for music.

8. **Loading an app target reuses `play_item`.** `notifyPlayItem` (`TvEventBus.kt:183`) and `/api/remote/play`
   (`RemoteRoutes.kt:126`) already open the player whatever page the app is on.
   - `session_load` is the `play_item` envelope plus `session_id`, and for music the queue (ids, index, position,
     shuffle/repeat). Music into `MusicEngine.playQueue` is new client work.
   - A load that brings no `starting` or `playing` report within 10 s ends the session as `failed`. The starting app
     then shows R299's copy (`cast.failed`).
   - The profile-picker drop rule stays (FR-R155-1.2/.3, `RaviloApp.kt:855`).

9. **Android keeps one Cast session per app.** Starting a cast to Soveværelse TV while the same phone casts to Stue
   ends the phone's first Cast session.
   - `setStopReceiverApplicationWhenEndingSession(false)` (`CastSenderAndroid.kt:103`) keeps Stue playing, so
     FR-R370-5 works. From then on the phone controls Stue only through the server (R369's receiver socket path).
   - Two things the build must respect. R353's hand-back on a session ended from outside must not fire when the phone
     leaves on purpose: it would pull Stue's song back onto the phone. And `ActiveCastSender`'s one-link rule stays,
     because it governs links, not sessions.
   - The desktop's own client could hold several links. Keep it to one, so both behave alike.

10. **Replacing a busy place.** `POST …/playback/sessions` takes an optional `replace { session_id, revision }`. The
    server ends that session (a stop to its target, `end_reason = replaced`, the resume point kept as today) and then
    loads the new one.
    - For a Cast place, the new LOAD replaces whatever the receiver plays anyway. R324's take-over stays for an app
      that isn't Ravilo.
    - Another user's session: 236's `/play` refuses with `409 ScreenBusy` (`RemoteRoutes.kt:138-147`). The new route
      allows `replace` across users only while 304's switch is on, and otherwise answers 403. The client offers the
      choice only when `controllable` is true, so it never decides this itself.

11. **The list is live.** The app fetches `GET /api/tv/playback/targets` when the sheet opens, plus a
    `targets_changed` signal (sent to sockets with `features=sessions`) while it is open. The same pattern R360
    settled for the sheet's own fetch.

12. **The Unreachable tier comes from records the server has.**
    - Paired screens.
    - Receiver rows whose last report is within 24 h but which this app's discovery doesn't find.
    - Ravilo apps with no socket: only if the owner wants them (owner question 3).

    No discovery reporting by apps is needed for this.

13. **Strings.**
    - `target.playing_now`, `target.free`, `target.unreachable` and `target.replace` are new.
    - Unreachable is not the same as R265's `screens.offline` (*Offline · last seen {when}*), which stays on screen
      rows. Use one or the other per row, never both.
    - *This phone* / *This computer* are `mode.label` / `mode.label_desk` (R368 item 14).

14. **Build order and tests.** After R368 and R369 (the remote opens straight away, FR-R370-3), and parallel with
    R371.
    1. The server: targets, the start route, `replace`, the 10 s load timeout, `cast_device_id`.
    2. The apps: `plays=`, `session_load`, the merged four-tier sheet.

    Tests on fakes: a busy place across users with the switch off and on; a load with no report fails at 10 s; the
    merge keeps one row per Cast device id; books never list a Cast place.

**For the owner**

1. **Should the server learn to start music on a speaker by itself?** Without it, only the Android and desktop apps
   can start or move anything onto a Cast device. The web app, the iPhone and the TV app can start on other Ravilo
   apps and screens, and control a speaker only once it plays. **Lean: not in this phase; a later phase if the iPhone
   should cast.**
2. **While this phone is already the speaker's remote, does pressing Play on another album still ask?** Today
   (R324) it plays on the speaker straight away. FR-R370-4's always-ask would add a question to every press while
   casting. **Lean: no question when this app holds the session's Cast link (as today). Ask only when the other
   session was started from another app.**
3. **Which places go in *Not reachable*?** Every phone in a pocket and every TV with Ravilo closed would sit there all
   day. **Lean: only TVs, displays and speakers (screens and Cast devices seen in the last 24 h). A Ravilo app with no
   connection is left out.**
4. **Audiobooks on a speaker.** Books have never cast (R323), and the receiver can't play them (parts, chapters,
   speed, sleep). **Lean: in this round books list only Ravilo apps; casting books is its own phase.**

**Shipped bugs found (not fixed)**

None.
