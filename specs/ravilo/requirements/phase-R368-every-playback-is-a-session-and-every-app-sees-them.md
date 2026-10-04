# Phase R368 — Every playback is a session on the server, and every Ravilo app sees them

> Owner, 2026-10-02: *"every session is controlled in the cloud (jellystructure) and each session can be on a device
> or in a google cast group etc. And multiple Ravilo clients can control a single session, but also have support to
> create a new session, so having multiple sessions."* 2026-10-03, after round 1: *"Yes, lets write specs."*

## Status

`Planned` — written 2026-10-03 (design-authored), **dev-reviewed 2026-10-04** against `main` `5210045a` (see *Dev review* at the end: the route name collides, session events need an opt-in, the receiver's queue reporting moves to R369, and two owner questions). Source: the brief
`specs/ravilo/design-brief-playback-sessions-2026-10-02.md`, the report
`specs/research-reports/ravilo-playback-sessions-and-casting-2026-10-02.md`, and the canvases
`design/ravilo/Playback Sessions - Directions.html` (part 1) and `… - Desktop, TV & Admin.html` (part 2).

**Phase 1 of 6, in the report's order:** **R368** sessions on the server, seen everywhere → **R369** any app
controls a session → **R370** one *Play on…* list, start anywhere → **R371** groups and volume → **R372** several
sessions, Move to…, Play here → **304** the admin. Each one can be shipped and used without the ones after it.

**Numbers:** written as R360–R364 + 303 on `main` tree `3a17405` (admin up to 302, Ravilo up to R359). **Renumbered
twice on 2026-10-04:** first to R361–R365 + 304 when `main` (`76f351b`) took **R360** (*no cast icon when there is
nothing to cast to*, dev-authored with the owner) and **303** (*a title's Checks card lists only the steps done to it*);
then to **R368–R372 + 304** when `main` (`fdbc126`) took **R361–R367** (the dev team's TV D-pad sweep, R366 Discover
order, R367 Discover rows). Every FR id moved with its phase (now FR-R368-n … FR-R372-n, FR-304-n).

**Relation to R360 (on `main`).** R360 makes the cast glyph present only when the sheet would list at least one
device (one rule, `CastController.hasDevices` → `rememberCastIconShown()`), and removes *Add a TV*. This phase puts
*Playing everywhere* in that same sheet, so it **amends FR-R360-1**: a row in *Playing everywhere* counts as part of
"the list". A session elsewhere (another member on the Køkken hub, Eyð's book on the MacBook) shows the glyph — with
its count, FR-R368-7 — even in a household with no Chromecast and no paired TV. It joins the same function, so the
icon and the sheet still cannot disagree; R360's 10 s grace applies when the last session ends. Once R370 lands,
R360's device half reads R370's `/api/tv/targets` list instead of the sheet's own discovery. Nothing here brings
*Add a TV* back.

**Leans taken as decisions.** The owner asked for specs without answering the canvas's §C questions. So every lean
drawn there is written here as the decision, and each one is listed under *Taken as leans* so the owner can reverse it.

**Applies to** the backend, the wire, every Ravilo app (phone, desktop, web, TV) and the Cast receiver. The wire
change only adds things: older apps and receivers keep working and just don't see sessions.

## Today (report §2)

No object stands for a playback. Pieces of it live in the client (`PlayerStore`, `MusicEngine`), in the server's
in-memory `PlaybackTracker` keyed `(device, item)` (one per **song** for music), in `SessionPlan`, `ScreenShuffles` and
`ScreenStatusTracker`, and in the Cast receiver, which owns its queue (286). Another app can see at most "device X plays
item Y". A cast's full state reaches only the phone that sent it. A backend restart loses all of it.

## Requirements

### A — The session

**FR-R368-1 — One session per playback, in a table.** `playback_session`:

| Field | Meaning |
|---|---|
| `id` | stable; survives a restart |
| `owner_user_id` | the Jellyfin user whose account plays it (library rules, watched state) |
| `target_kind` · `target_id` | `app` (a Ravilo device row: phone, desktop, web, TV) · `cast` (a receiver row) · `cast_group` (R371) |
| `target_name` | the place as the household named it (*Stue*, *Soveværelse TV*, *Pixel 9*) |
| `kind` | `film` · `episode` · `music` · `audiobook` |
| `queue_json` · `index` | the items in play order and the current one. A film is a queue of one; an audiobook is one item with chapters |
| `position_ms` · `position_at` | the last reported position and the server time of that report |
| `state` | `starting` · `playing` · `paused` · `buffering` · `failed` · `ended` |
| `options_json` | shuffle, repeat, R343's start-over latch, audio/subtitle picks, speed + sleep (books), volume (R371) |
| `revision` | increased on every change |
| `created_at` · `updated_at` · `ended_at` | |

`playback_session_controller(session_id, device_id, attached_at)` lists the apps currently showing a session. This
phase only fills it; R369 uses it. It is a new migration and drops nothing. `PlaybackTracker`, `SessionPlan`,
`ScreenShuffles` and `ScreenStatusTracker` keep working, either fed by the session or feeding it. Whether to fold them
into it is for the dev review.

**FR-R368-2 — It starts and ends with the playback.** A session is created by the first start (`playback/start`,
`music/play`, or a receiver's first report) for a target that has no live session for that owner. It ends when the
target stops, when the watchdog force-stops it (110 FR B.2), when the queue plays out, or under R372's 24-hour rule for
paused sessions. **Music is one session per queue, not one tracker entry per song.** Jellyfin still gets its per-song
reports (FR-279-6).

**FR-R368-3 — The target reports and the server stores.** Targets report through the routes they already use. Those
requests gain an optional `session_id`; a report without it is matched by `(device, item)` as today. **The Cast
receiver now reports its full state to the server** (queue, index, position, state, shuffle/repeat). Today it tells
only its sender.

**FR-R368-4 — A restart brings sessions back.** After a backend restart, every session that wasn't `ended` is
reloaded in its last state and marked *reconnecting* until its target reports again. If the target hasn't reported
within **2 minutes**, the session ends at its stored position. Owner, 2026-10-04: every playback comes back, not
only paused ones.

### B — Every app sees them

**FR-R368-5 — Wire** (all additive):

- `GET /api/tv/sessions` → `SessionList { sessions: [SessionView], revision }`.
- On `/api/tv/events`: `session_list` (the whole list, sent on connect and whenever a session starts or ends) and
  `session_state { SessionView }` (one session changed). Clients render what they receive and never build a session
  from their own player.
- `SessionView`: `id` · `revision` · `owner { id, name }` · `mine` · `kind` · `title` · `subtitle` (artist · album |
  `S01E05` | chapter) · `artwork` · `target { kind, id, name, icon }` with `icon` ∈ `phone · computer · tv · display ·
  speaker · group` · `state` · `position_ms` · `position_at` · `duration_ms` · `here` (this device is the target) ·
  `controllable` (R369).

**FR-R368-6 — Whose sessions a viewer sees.** Their own, plus every other household member's, shown with that
person's first name. Someone else's session is listed but can't be controlled. Admin 304 adds a household switch
that lets members control each other's sessions.

**FR-R368-7 — *Playing everywhere* opens from the cast glyph (direction A).** On every page that has the cast glyph
(the phone's top bar, the desktop toolbar), tapping it opens a **sheet** on the phone or a **popover** on the desktop
with two parts: **Playing everywhere** on top, today's *Play on…* places below (canvas §B1 A, §B8).

- A row shows: artwork (16:9 for a film or episode, square otherwise), the title (*Title · S01E05* for an episode), the
  place with its icon in the accent colour, the state (a still three-bar mark while playing, *Paused*, *Loading…*), and
  a 3 dp progress line. Someone else's row adds *{person} is listening / watching*. On the phone a row is 70 dp tall
  with 46 dp touch targets.
- Order: this device first, then the viewer's other sessions by most recent change, then other people's.
- This device's own row is highlighted. An ended row fades out after 60 s.
- The whole section is **absent** when nothing plays anywhere.
- The glyph shows a **count** of sessions that are **not** on this device. The count is absent at 0.
- **The glyph itself follows R360**, with one addition (see *Status*): it is present when the places list is not
  empty **or** *Playing everywhere* has a row. A household with nothing to cast to and nothing playing elsewhere
  sees no glyph, as R360 decided.
- Until R369, a row has ⏯ and ⋯ only where control already works today (FR-R368-9). Elsewhere those buttons are
  absent, not greyed out.

**FR-R368-8 — The bar shows what plays elsewhere** (canvas §B2). When nothing plays on this device, the phone's mini
bar, the mac capsule and the GNOME docked bar show a session:

- The second line is the place with its icon, in accent (*Stue + Gæsteværelse*).
- **Which session the bar picks (owner, 2026-10-04): always the one this app touched last.** Starting it, pausing,
  skipping, seeking or opening its remote counts as touching it; it stays on the bar while it lives, even if paused or
  if another session starts elsewhere. Only before this app has touched any session does the bar show the most
  recently started one that is `playing`. When the touched one ends, the bar falls back to that same rule.
- When there are more, the bar shows a **+N chip** that opens FR-R368-7's sheet or popover.
- In films mode, the bar shows a session only if it is music or an audiobook (R337's rule). Films and episodes playing
  elsewhere appear only in the sheet.
- The TV has no bar.

**FR-R368-9 — Controls in this phase are the ones that exist today.** ⏯ and next work where they already do: on this
device's own playback, and from the phone or computer that sent a cast (R245 · R324 · R356). Everything else comes in
R369.

**FR-R368-10 — Ravilo on the TV** receives `session_list` but shows nothing new in this phase. Its Home row *Playing
in other rooms* is round 2 and isn't specified here. The receiver-only app and older apps see nothing new.

### C — Words

**FR-R368-11 — Strings** × en · da · fo (da/fo are drafts; where a key already ships, the shipped table wins). A
viewer never sees the word *session*, and never the name of a product or protocol (R180, FR-R245-16).

| Key | en | da | fo |
|---|---|---|---|
| `session.everywhere` | Playing everywhere | Spiller overalt | Spælir allastaðni |
| `session.playing_on` | Playing on {place} | Spiller på {place} | Spælir á {place} |
| `session.person_listening` | {person} is listening | {person} lytter | {person} lurtar |
| `session.person_watching` | {person} is watching | {person} ser | {person} hyggur |
| `session.reconnecting` | Reconnecting… | Forbinder igen… | Bindur í aftur… |

*This phone* / *This Mac* / *This computer* reuse R265's keys.

## Invariants

- Playback itself doesn't change: what plays, how it starts, Jellyfin's reports and the watched state are all as before.
- No client keeps its own list of sessions. It shows the last `session_list` / `session_state` it received.
- An app with no events socket open receives nothing and isn't listed as a controller.

## Acceptance

1. Eyð casts *Cannery Lights* from the Pixel 9 to Stue. Within a second the MacBook's capsule shows it with *Stue* in
   accent, and the toolbar glyph reads **1**. (Tests: `PlaybackSessionsTest`, `playback-sessions.spec.ts`,
   `MusicMiniBarElsewhereTest`; the timing only on devices.)
2. Eyð plays *Vinterfærgen* on the Pixel too, and Olivar watches *Lundin og vinir* on the Køkken hub. The Pixel's sheet
   lists her book first (highlighted), *Cannery Lights* · Stue, and Olivar's row with his name and no buttons. The glyph
   reads **2**. (Tests: `PlaybackSessionRulesTest`, `PlaybackSessionsTest` (the lanes), `PlayingEverywhereSheetTest`,
   `CastGlyphCountTest`.)
3. The backend restarts while *Cannery Lights* plays. Each app's row reads *Reconnecting…* until Stue's next report,
   then carries on at the right position. (Tests: `PlaybackSessionsRestartTest`; on a live server.)
4. An older phone build sees no sessions and plays exactly as before. (Tests: `TvEventBusSessionsIntegrationTest`,
   `WireCompatTest`, `SessionWireTest`, `playback-sessions.spec.ts`.)
5. If Olivar's profile may not see what Eyð plays, his sheet shows *Eyð is watching · Stue* with no title, no artwork
   and no progress line. A member whose device is on another network sees none of these sessions. (Tests:
   `PlaybackSessionRulesTest`, `PlayingEverywhereSheetTest`.)

## Taken as leans

- Direction **A**: *Playing everywhere* lives in the cast glyph's sheet/popover. B (the bar stack) and C (a Rooms page)
  were drawn and set aside.
- The bar's second line names only the place, never *Started on …*.
- An ended row fades out after 60 s.

## Decided by the owner (2026-10-04)

- The bar always shows the session this app touched last (FR-R368-8).
- Other people's sessions: visible with their name, no controls; 304's household switch can allow control.
- After a restart **every** session comes back (FR-R368-4). The dev review may only narrow this if re-attaching
  proves unreliable, and must say so.

## Tests

These follow the reviewed and decided design below. Where the FR text and the *Dev review* / *Owner decisions*
differ, the tests follow the review: `/api/tv/playback/sessions`, `features=sessions`, one live session per (target,
lane), the 15 s hold, `queue_index`, `reconnecting` as a flag, the household as phase 236's `nearby` rule, and a hidden
title showing person, place and state. The rules are pure functions in `tv/PlaybackSessions.kt` (server) and
`ui/sessions/PlaybackSessions.kt` (client) so they can be tested without a socket. Then come the service on a temporary
database with a fake clock, the wire, the routes end to end, and Robolectric for the sheet and the bar. Test names are
Kotlin backtick names. Functions named here that don't exist yet are the ones the build adds.

**Server, pure rules.** `src/linuxX64Test/kotlin/dev/jellystructure/tv/PlaybackSessionRulesTest.kt` (`linuxX64Test`):

- FR-R368-2, review item 5: `sessionLane(kind)` and `startDecision(live, ownerUserId)` (`Create` · `Join` ·
  `Replace`). Tests: `a film and a song on one device are two lanes`, `the same owner joins the live session`,
  `another owner on the same receiver replaces the first`.
- Review item 9: `isSessionChange(stored, report, nowMs)`.
  - `a heartbeat is not a change`: within 3 s of `position_ms` plus the time elapsed while `playing`.
  - `a jump of more than three seconds is a seek`.
  - `a pause an item and an option are changes`.
- Review item 15: `targetIcon(platform, kind, smallScreen)`. One table test, `every platform maps to its icon`: phone →
  phone, mac/linux/web → computer, tv → tv, cast-audio → speaker, and cast → tv (display when R351 reported a small
  screen).
- FR-R368-6, review items 10 and 11, owner decisions 1 and 2:
  `sessionViewFor(viewer, viewerAddress, session, targetAddress, canSee, castMintedBy)`, the per-socket projection.
  - `a viewers own session is listed in full and is mine`.
  - `another member on the same network is listed by first name and is not controllable`.
  - `another member on another network is not listed`. This goes through `isNearby`; `ScreenNetworkTest` already
    covers the address rule itself.
  - `a title the viewer may not see shows person place and state only`: `title`, `subtitle`, `artwork`,
    `position_ms` and `duration_ms` are null; `owner`, `target` and `state` are set.
  - `here is true only on the target device`.
  - `controllable is here or the live cast this device minted`: the phone whose `CastService.mint` code the receiver
    redeemed gets true while that cast lives. The same user's other phone gets false, and so does that phone once the
    cast ends.

  Reading taken: owner decision 2 filters *other people's* sessions. A viewer's own session on another network is still
  listed ("their own", FR-R368-6). Flip this test if the owner reads it otherwise.
- Review item 12 (a): `shouldForceStop(device, heartbeatStale, socketOpen, reconnecting)`, taken out of the watchdog's
  socket test (`PlaybackService.kt:980`). Test: `a reconnecting session is judged by heartbeat alone`. A fresh
  heartbeat with no socket while reconnecting keeps the session. A stale heartbeat stops it. Outside reconnecting, a
  socket-holding kind with its socket gone stops as today.
- Review item 2: `parseEventFeatures(raw)`, next to `parseRemoteDeclaration` (`JellyfinRemote.kt`). Test:
  `only the sessions feature is recognised`. A missing, blank or unknown value gives the empty set.

**Server, the service on a database.** `tv/PlaybackSessionsTest.kt` (`linuxX64Test`) uses a real `PlaybackSessions`,
`RaviloDeviceService` and `CastService` on a temporary DB with a fake clock and a recording notifier (the `CastServiceTest`
setup).

- FR-R368-1/2: `the first start creates one row with owner target lane and kind`. Check `queue_index = 0`,
  `started_by_device_id`, `target_name` = the device's `display_name`, and the current item's
  `jellyfin_play_session_id`. A music start with a `bookId` gives `kind = audiobook`.
- Review item 6:
  - `a song boundary inside fifteen seconds keeps the session`: stop, +14 s, start → same id, one revision bump, no
    `session_list`.
  - `a book's next part stays one session`.
  - `an episode auto-advance stays one session`.
  - `a stop with no start for fifteen seconds ends it` (`end_reason = stopped`, one `session_list`).
  - `the last song paused at zero stays paused while it heartbeats`.
- Review item 5: `another owner on the same receiver ends the first as replaced`.
- FR-R368-3, review item 8: `a missing or unknown session id matches by device and item`.
- Review item 9: `heartbeats store the position and push nothing`. Ten reports update `position_ms` and push nothing; a
  pause pushes one `session_state` carrying `server_now_ms`.
- FR-R368-2: `the watchdog reap ends the session`.
- Review item 13: `an ended session stays listed for sixty seconds as ended`. It is in the list at +59 s; at +61 s a list
  without it goes out.
- Owner decision 2: `an address change re-sends the list`. When the viewer device's `last_public_address` moves to
  another network, the next list has no other-household rows.

**Server, restart.** `tv/PlaybackSessionsRestartTest.kt` (`linuxX64Test`) covers FR-R368-4, review item 12 and owner
decision 3. Building a new service on the same DB file is the restart.

- `every live session comes back reconnecting in its last state`: playing and paused both come back; ended ones don't.
- `the tracker is rebuilt with its play session id`: `PlaybackTracker` gets the stored `jellyfin_play_session_id`, so
  phase 180 can release the transcode (shipped bug 2).
- `shuffle and start over survive in options`: `SessionPlan` is restored from `options_json` (R343).
- `a report clears reconnecting and pushes one state`.
- `two minutes of silence ends it at the stored position`: still reconnecting at +119 s; at +121 s it is `ended`
  (`end_reason = no_return_after_restart`) with its position unchanged. When R372 ships, this test changes to R372's
  *paused, offline* rule.

**Server, migration.** `db/PlaybackSessionMigrationTest.kt` (`linuxX64Test`) covers review item 4. It is the suite's
first migration test.

- `a fresh database has playback_session with queue_index and lane`: also checks `started_by_device_id`, `end_reason`
  and `jellyfin_play_session_id`, and that there is no `index` column.
- `an existing database upgrades and keeps its rows`:
  1. Create a DB and insert a `ravilo_device` row.
  2. Drop `playback_session` and set `PRAGMA user_version` back to the version before this phase's `.sqm`.
  3. Reopen it with `createDatabase`.
  4. The table is back, the device row is intact, and nothing was dropped.

  `playback_session_controller` isn't here; it comes with R369 (item 11).

**Server, fan-out (backwards compatibility).** `tv/TvEventBusSessionsIntegrationTest.kt` (`linuxX64Test`) covers review
items 2 and 9. It runs an in-process CIO server over loopback with `WebSockets` and a real `TvEventBus`, like
`ReceiverTokenRefreshIntegrationTest`.

- `only a socket that asked for sessions receives them`. Three sockets connect: A with `features=sessions`, B without
  it (today's app), and C as the TV. A gets `session_list` and then `session_state`. B and C get no frame at all, so an
  installed app never reaches its `else → refreshConfig()`.
- `an opted-in socket gets the whole list on connect`.
- `the list is built per socket`: two viewers get different `mine`, `here` and `controllable`.

**Wire.**

- Backwards compatibility: add `SessionList`, `SessionView`, `SessionListEnvelope` and `SessionStateEnvelope` to
  `scripts/wire_roots.py`'s RESPONSES. `WireCompatTest.todaysModelsKeepEveryAppInUseWorking`
  (`shared/src/linuxX64Test/…/wire/`) must stay green. `StreamTicket.session_id` and the `session_id` on
  `PlaybackProgressRequest`, `PlaybackStopRequest` and `AudiobookProgressRequest` are optional, and no field is removed.
- `shared/src/commonTest/kotlin/dev/jellystructure/shared/tv/SessionWireTest.kt` (`commonTest`):
  - `a stream ticket from an older server decodes without a session id`.
  - `a request with a session id decodes where unknown keys are ignored` (the `VolumeReportTest` pattern).
  - `session events round-trip with server_now_ms and reconnecting`.
  - Take `dispatchTvEvent(text, handlers)` out of `connectEvents` (`TvApiClient.kt:893`). Test:
    `session events reach their handlers and never onEvent`. A garbled frame is dropped.
  - With `wsUrl` made internal: `the events url is unchanged without features`. With no features the URL is
    byte-identical to today's (with and without `remote`, header and query-token builds); with them it ends in
    `features=sessions`.

**Routes, end to end.** `tests/e2e/playback-sessions.spec.ts` (Playwright on the e2e stack: mock Jellyfin and the scan
fixture, with both parts played by hand as in `screens.spec.ts`):

- `a started playback is listed`. A phone opens `/api/tv/events?…&features=sessions` and starts an item through
  `/api/tv/playback/start`. `GET /api/tv/playback/sessions` and the socket both show it with `here = true`. After a stop,
  it ends 15 s later.
- `an old socket gets no session frames`: a second login's socket without `features` receives nothing.
- `the profile routes are untouched` (item 1): `GET /api/tv/sessions` still lists profiles, and
  `DELETE /api/tv/sessions/{userId}` still signs one out.

**Client, pure.** `ravilo-ui/src/commonTest/kotlin/dev/jellystructure/ravilo/ui/sessions/PlaybackSessionsTest.kt`
(`commonTest`):

- Invariant 2, the `PlaybackSessions` store:
  - `the store holds exactly the last list`.
  - `a state replaces its row only with a higher revision`.
  - `a state for an id not in the list is ignored`.
- FR-R368-7:
  - `orderSessionRows`: `this device first then mine by latest change then others`.
  - `sessionsElsewhereCount`: `the count leaves out this device and is absent at zero`.
  - `castIconShown(hasDevices, rows)` (R360's rule, amended): `the glyph shows for a device or a row and not for neither`.
- FR-R368-8, `pickBarSession(sessions, touchedId, filmsMode)`:
  - `the bar keeps the session this app touched last`, even when it is paused or another one starts.
  - `before any touch it shows the latest playing one`.
  - `when the touched one ends it falls back`.
  - `films mode shows only music and audiobooks from elsewhere`.
  - The `+N` count.
- Review item 9, `drawnPositionMs(view, serverNowMs, receivedAtMs, nowMs)`:
  - `a playing row advances on the server clock`.
  - `a paused row is frozen`.
  - `a device clock ten minutes off still draws the right time`.
  - The result is clamped to `duration_ms`.
- FR-R368-10, review item 2: `eventsFeaturesFor(platform)`. Test: `phone desktop and web ask for sessions and the TV
  does not`.
- `SessionStringsTest` (next to `LoadErrorStringsTest`) covers FR-R368-11 and review item 14:
  - The four `session.*` keys exist in en, da and fo and aren't the English fallback.
  - Faroese `session.reconnecting` is *Sambindur aftur…*.
  - `session.playing_on` doesn't exist; `cast.playing_on` is reused.
  - No value contains *session*, *Cast*, *Chromecast* or *Jellyfin*.

  `scripts/check-ravilo-strings.sh` and `check-i18n-spelling.sh` stay green.

**Client, Robolectric.** These go in `ravilo-ui/src/androidUnitTest/…` (`@RunWith(RobolectricTestRunner::class)`,
`sdk = [34]`, phone qualifiers) and use a fixed `SessionList`, plus `fakeTvApiClient` where a client is needed.

- `components/PlayingEverywhereSheetTest.kt` (FR-R368-7, owner decision 1):
  - `the section is absent when nothing plays`.
  - `rows are ordered and this device is highlighted`.
  - `another persons row says who is watching and has no buttons`.
  - `a hidden title shows person place and state with no artwork or progress`.
  - `play pause exists only on a controllable row`: `assertDoesNotExist`, not disabled.
  - `a row is 70 dp with 46 dp targets`.
  - `reconnecting shows over the paused or playing state`.
- `music/MusicMiniBarElsewhereTest.kt` (FR-R368-8):
  - With nothing local playing, the bar shows the picked session with the place in its second line.
  - `+N` opens the sheet.
  - In films mode, an episode playing elsewhere stays off the bar.
  - A local playback always wins.
- `components/CastGlyphCountTest.kt` (FR-R368-7, review item 13):
  - With `LocalCast` null and a session elsewhere, the glyph shows with its count.
  - With no devices and no rows, it is absent.

**Only a device or a live server can confirm:**

- **Acceptance 1's "within a second":** cast from the Pixel to a receiver the owner has cleared for testing, and time
  the MacBook's capsule.
- **Acceptance 3, with the owner's go for a dev-backend restart:** restart during a cast and during a direct-played
  film. Each row should read *Reconnecting…* until the next report. A film on R291's composed master may end `failed`
  instead (item 12); record which.
- **The household rule behind the real proxy:** put one phone on mobile data. The other member's row should leave its
  sheet, then come back on Wi-Fi. Only the live proxy gives real forwarded addresses.
- **Acceptance 4 on an installed older build:** while sessions start and change, the backend log should show no rise in
  `/api/tv/config` requests from that build.
- **The desktop popover** (new chrome, item 13): check it on the Mac and on the Linux build. `desktopTest` has no Compose
  UI harness.

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `PlaybackService` (`PlaybackTracker`, `startPlayback`, `startMusicPlayback`, `reportProgress`,
`stopPlayback`, the watchdog), `SeriesReplay.kt`, `ScreenStatusTracker`, `TvEventBus`, the events socket in `Server.kt`,
`TvRoutes`, `MusicTvRoutes`, `AudiobooksTvService`, `CastService`, the receiver (`ravilo-cast` `Receiver.kt`), the
client's `TvApiClient.connectEvents`, `RaviloApp`, `Cast.kt`, `ScreensSheet.kt`, `AppBar.kt`, the music engines,
`i18n/*.json` and the canvases. The model holds and the restart rule needs no narrowing. Two things must change before
anything is built (items 1 and 2), the receiver's queue reporting moves to R369 (item 7), and two questions are for the
owner. Seventeen items.

1. **`GET /api/tv/sessions` is taken.** It lists the profiles signed in on this device (`TvRoutes.kt:421`, `TvSession`,
   ravilo `plan.md:117`), and `DELETE /api/tv/sessions/{userId}` is R191's single sign-out (`:442`, called by
   `TvApiClient.signOutSession`). Neither can change: installed apps call the DELETE. **Use
   `/api/tv/playback/sessions`** (the playback family already lives there): `GET /api/tv/playback/sessions` here, and
   R369's `…/{id}/command`, R370's `POST` and R372's routes under the same prefix. The event names (`session_list`,
   `session_state`) collide with nothing and stay. This also affects R369, R370 and R372.

2. **Installed apps read any unknown event as "the config changed".** `connectEvents` routes every type it doesn't
   know to `onEvent` (`TvApiClient.kt:919`), and `RaviloApp.kt:539` turns that into `liveConfig.emit` → `refreshConfig()`
   (`:508`). So pushing `session_state` to an older phone makes it re-fetch `/api/tv/config` on every pause, every song
   and every start anywhere in the house. "Older apps just don't see sessions" is only true if they never receive the
   events. **The socket opts in:** `/api/tv/events?…&features=sessions`, beside R354's `remote=` (`wsUrl`,
   `TvApiClient.kt:936`). The server sends `session_list`/`session_state` only to sockets that asked. The receiver
   (`Receiver.kt:321`) and the screen ignore unknown events anyway, but they don't ask either. The TV doesn't ask in
   this phase (FR-R368-10), which also saves its traffic. The new client handles both types explicitly, never in `else`.

3. **Corrections to *Today*.**
   - The receiver **does** report to the server: it negotiates through `/api/tv/playback/start`, reports progress
     (with `is_paused` and R357's volume) and stop per item like any device (`Receiver.kt:71`, `:710`), and holds its own
     events socket while something is loaded (R354, `:302`). Only the queue, the index and shuffle/repeat stay between
     receiver and sender.
   - An audiobook is not one item. It is one book with parts, each its own Jellyfin item and session (281 FR-281-10,
     `MusicTvRoutes.kt:66`). The phone's heartbeat goes to `PUT /api/tv/music/audiobook/{id}/progress`, which mirrors it to
     the part's session (`AudiobooksTvService.kt:120`).
   - `SessionPlan` lives in `PlaybackService.plans`, keyed like the tracker (`PlaybackService.kt:375`). `ScreenShuffles`
     is in `SeriesReplay.kt:61`. `ScreenStatusTracker` is written only by `POST /api/tv/playback/status`, which no
     Ravilo app posts (FR-236-11 is half built).

4. **Where it lands (server).**
   - Table `playback_session` in a new `PlaybackSession.sq` plus migration `66.sqm` (the next one at HEAD; check again
     before building, parallel work takes numbers). Drops nothing. **`index` is a reserved word in SQLite**: call it
     `queue_index`. Add three columns the FRs imply: `lane` (`video` · `audio`, item 5), `started_by_device_id` (item 11)
     and `end_reason`. The current item's `jellyfin_play_session_id` goes in too (item 12).
   - A new `tv/PlaybackSessions.kt` (service plus store), called from `startPlayback` after `playbackTracker.started`,
     from `startMusicPlayback` (which gains a `bookId` from `MusicTvRoutes` so it knows `kind = audiobook`), and from
     `reportProgress`, `stopPlayback` and the watchdog reap.
   - **Fold nothing in this phase.** `PlaybackTracker` is per item on purpose: it is what Jellyfin, phase 180's encode
     release and 178's deferral need. The session sits above it. `ScreenShuffles` and `ScreenStatusTracker` belong to
     the paused Tizen work and stay as they are.

5. **One live session per target *and lane*, not per owner.** The rule "no live session for that owner" breaks twice:
   - A device can hold a paused song under a playing film. The desktop pauses music for a film (R337), and the engine
     stays loaded and heartbeats every 10 s (`MusicEngineAndroid.kt:384`), so the tracker holds both.
   - A receiver plays one thing. When Olivar casts to a speaker Eyð is playing on, Eyð's session ends with
     `end_reason = replaced`. Receiver rows are per (receiver, user) (phase 300) but share the `device_id`, so
     `target_id` is the device id.

   So: a start joins the live session on (target device, lane) when the owner matches. Otherwise it ends that session
   and creates a new one. `lane` is `video` for film and episode, and `audio` for music and audiobook.

6. **A song boundary must not end the session.** Every song is a stop then a start, on the phone and on the receiver
   alike (FR-279-6, `Receiver.kt:710`). So a stop holds the session for **15 s**, and only ends it if no start for the
   same (target, lane) arrives in that time. An episode's auto-advance falls inside the window too. At the end of the
   queue the engine stays on the last song, paused at 0:00, and keeps heartbeating (FR-R322-5). The session is therefore
   `paused` until the app closes (watchdog) or R372's 24-hour rule. No new request field is needed for this.

7. **The queue: what this phase needs, and what moves to R369.** Nothing in R368 shows a queue, and the apps and
   receivers keep their own queues through a restart. So in this phase `queue_json` holds the current item, with a
   count only when one is known. Full-queue reporting by apps and the receiver (FR-R368-3's second half) **moves to
   R369**, where `next`, `jump` and queue edits need it. When it comes, it sends ids only and reports on change, not
   every tick: R359 casts 487-song queues and tests with 5 000.

8. **`session_id` on the wire (all additive).** `StreamTicket` gains `session_id`, and `PlaybackProgressRequest`,
   `PlaybackStopRequest` and `AudiobookProgressRequest` gain an optional `session_id`. A missing or unknown id falls
   back to (device, item) as the FR says. An older server ignores the field: its decoder ignores unknown keys, the same
   reason R357's volume was safe.

9. **A position heartbeat is not a change.** `position_ms`/`position_at` are stored on every report (one `UPDATE` per
   10 s per session is nothing). But a heartbeat doesn't bump `revision` and pushes nothing. `session_state` goes out on a
   state change, an item change, a seek (a jump of more than 3 s from the expected position), an options change, and
   when reconnecting starts or ends. Apps draw the moving position from `position_ms` plus the time since `position_at`,
   frozen while not `playing`. That is the server's state drawn with a clock, not derived state. Both envelopes carry
   `server_now_ms` so a device whose clock is wrong still draws the right time.
   - Fan-out: `TvEventBus` gains `notifySessions(build: (userId, deviceId) -> String?)`. The list is built per socket,
     because `mine`, `here`, `controllable` and the visibility filter (item 10) differ per viewer. It sends the whole
     list each time: it is a few rows.

10. **Visibility (owner question 1).** A kids profile or a library-limited user would receive the title and artwork of
    whatever anyone else plays. Playback itself is gated per viewer (`requireVisible` via `MediaItem.visibleTo`,
    `PlaybackService.kt:483`; music via `MusicTvService.visible`; books via `visibleBook`). So the list must apply the
    same checks to other people's rows. What such a row shows is the owner's call.

11. **`controllable` is the server's answer from day one.** In this phase it is true when `here`, or when this device
    minted the cast's hand-off code (`CastService.mint(device)`, `TvRoutes.kt:662`) and that cast is live. That is exactly
    FR-R368-9's "where control already works". R369 widens the rule, and its FR-R369-3 already says the app never works
    it out itself. `playback_session_controller` should be created in R369, where attach exists: in this phase nothing
    attaches, so the table would stay empty. R368 records `started_by_device_id` instead, which 304's *started from
    which app* needs anyway.

12. **Restart (FR-R368-4) is feasible for every target, so it is not narrowed.** The player streams from Jellyfin and
    keeps going. The next progress report (≤ 10 s) or the events socket's reconnect brings the row back. Three things
    the build must do:
    - **(a)** While a session is reconnecting, the watchdog judges it by heartbeat only. Its socket test
      (`PlaybackService.kt:980`) would otherwise end a session whose progress arrived before its events socket came
      back.
    - **(b)** On boot, rebuild the tracker entries from the rows, including the current item's
      `jellyfin_play_session_id`, so phase 180 can still release a transcode started before the restart (shipped
      gap 2).
    - **(c)** Store `SessionPlan`'s fields in `options_json` and restore `plans` from it. Otherwise R343's shuffle and
      Start over are forgotten across a restart (shipped gap 2).

    *Reconnecting* is a boolean `reconnecting` on `SessionView`, not a seventh `state`, so the row keeps showing
    paused or playing underneath it. One real limit: a film on R291's composed master
    (`/api/tv/stream/{id}/master.m3u8`, served by this backend) loses its audio playlist while the backend is down. It
    may fail rather than carry on, and its session then ends as `failed`. That is honest, and no different from today.

13. **The glyph and the sheet.**
    - R360 is `Planned`, not built. **Build R360 first.** This phase adds "any row in *Playing everywhere*" to its
      `CastController.hasDevices` → `rememberCastIconShown()`.
    - `CastButton` returns early when `LocalCast` is null (`Cast.kt:291`), and that is the case whenever no cast
      capability exists. A household with sessions but no cast target needs the controller provided, or the gate moved.
    - The sheet is a `HandsetSheet` on every platform, the desktop included (`ScreensSheet.kt:130`, opened from
      `DeskCastButton`, `AppBar.kt:402`). So **the desktop popover is new chrome**.
    - The section is a new composable at the top of `ScreensSheet`'s body. The bar half goes into `MusicMiniBar`
      (`MusicPlayerScreens.kt:684`) and `DesktopMusicBar` (`DesktopMusicBar.kt:73`). The "touched last" choice is a local
      selection of a session id, which is fine.
    - **The 60 s fade must come from the server** (invariant 2): an ended session stays in `session_list` with
      `state = ended` for 60 s, then a new list drops it. The client animates the change.

14. **Strings.**
    - `cast.playing_on` and `cast.paused_on` already ship in all three languages (placeholder `{device}`). Reuse them,
      and drop `session.playing_on`.
    - R265 has no *This phone* / *This Mac* keys. What exists is `mode.label` (*This phone*) and `mode.label_desk`
      (*This computer*, R337). There is no *This Mac*: use *This computer*, or add `mode.label_mac`.
    - The Faroese draft *Bindur í aftur…* differs from the shipped `cast.reconnecting` (*Sambindur aftur við {device}…*).
      Use *Sambindur aftur…*.
    - `loading` exists.
    - New keys: `session.everywhere`, `session.person_listening`, `session.person_watching`, `session.reconnecting`.
      Regenerate the lexicon.

15. **`target.icon` from what the device row knows (`platform`).**
    - `phone` → phone · `mac`/`linux` → computer · `web` → computer (the server can't tell a phone browser apart)
    - `tv` → tv · `cast-audio` → speaker
    - `cast` → tv, or display when the receiver's R351 probe reported a small screen
    - `group` comes with R371.

    `target_name` is the device row's `display_name` (the receiver sends its name at redeem). A dynamic group's
    *Stue + Gæsteværelse* needs R371, because the receiver moves without redeeming again (R355).

16. **Not sessions in this phase** (a lean, not asked): Live TV (`LiveTvService` keeps its own tracking, and `kind`
    has no `live`), playback from other Jellyfin apps (report §8 Q4's lean: sessions are Ravilo's), and trailers.

17. **Build order.**
    1. R360.
    2. Server: table, service, hooks, items 5, 6 and 12, the list route, the opt-in push. Unit tests on a fake clock:
       the song boundary, lane replacement, restart restore with reconnecting expiring at 2 min, visibility per viewer.
    3. Client: `TvApiClient` handlers, one `PlaybackSessions` `StateFlow`, the sheet section, the count, the bar.
    4. The desktop popover.

    The receiver needs nothing in this phase (item 7).

**For the owner**

1. **Someone else plays something this viewer isn't allowed to see** (a kids profile, a library-limited user). What
   does their row show? **Lean: the person, the place and the state (*Eyð is watching · Stue*), with no title, no
   artwork and no progress line.** It still counts on the glyph.
2. **Who counts as the household?** Every Jellyfin user on this server who signs in to Ravilo, or fewer? Since 167, a
   friend outside the house can have an account. **Lean: every user, as written.** A per-user "hide my playing" can
   come later if anyone asks.

**Shipped bugs found (not fixed)**

1. **The admin's Users & devices shows a raw id for a song.** The *▶ playing …* line (`TvRoutes.kt:960`, `:1042`) uses
   `MediaStore.titleForJellyfinId`, which knows only films and episodes (`MediaStore.kt:723`). A song or audiobook part
   therefore shows its Jellyfin id.
2. **A backend restart forgets what a playback needs at its stop.** `TrackedPlayback.jellyfinPlaySessionId` and
   `SessionPlan` are memory only. After a restart the first heartbeat re-creates the entry with no play-session id
   (`PlaybackService.kt:203`). So phase 180's teardown can't release a transcode started before the restart (Jellyfin's
   own idle timeout ends it eventually). A shuffled episode stopped after a restart also writes its real position as
   the resume point (R343 FR-R343-5), and a Start over's clear is lost. Item 12 (b) and (c) fix both.
3. **A heartbeat drops `directPlay`.** `PlaybackTracker.heartbeat` rebuilds the entry without it (`PlaybackService.kt:204`,
   default `false`). From the first progress report (≤ 10 s) on, every direct-played song on a speaker counts against
   218's cast ceiling, which 286 FR-286-8 says it never should (`CastService.checkCeiling`, `CastService.kt:163`). With
   the default ceiling of 2, two speakers playing MP3s block a third cast with a 503. This is the same shape as the
   phase-180 fix documented just above it.
4. **Possible, from the code and not observed:** after a restart, a playback whose progress report arrives before its
   events socket reconnects can be force-stopped by the next watchdog tick (≤ 30 s, socket test at
   `PlaybackService.kt:980`). Jellyfin is then told it stopped while it plays, and its heartbeats are ignored for 60 s
   (`STOP_GRACE_MS`). Item 12 (a) covers this for sessions; the tracker has the same gap today.


## Owner decisions (2026-10-04, after the dev review)

1. **A session the viewer may not see** (kids profile, library limits): the row shows the person, the place and the
   state only (*Eyð is watching · Stue*) — no title, no artwork, no progress line. It still counts on the glyph.
2. **The household is the users on the same internet network**, not every account on the server. A session is
   listed to a viewer only when the playing device and the viewer's device share a public address — the same
   `nearby` rule phase 236 already applies to screens (`ScreenStatus`), computed server-side from the requests'
   source addresses (behind Caddy: the forwarded address). A friend with an account at their own house never sees
   this household's playback and is never seen. Per-session, re-evaluated when a device's address changes; no
   setting. The admin (304) still sees every session.
3. **Restart rule:** until R372 ships, R368-4's 2-minute end stands; once R372 ships, a place silent after a restart
   becomes *paused, offline* and is kept 24 h like any other silent place (R372-4 wins).
