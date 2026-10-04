# Phase 304 — The admin sees every playback, and the household chooses who can control whose

## Status

`Planned` — written 2026-10-03 (design-authored), **dev-reviewed 2026-10-04** against `main` `5210045a` (see *Dev review*: a section on the page, not a tab; admin routes and `/ws`, not `/api/tv/events`; the remote ships with R369; FR-304-5 cannot fire as written). **Phase 6 of 6** of playback sessions (see
Ravilo R368). Depends on R368 (it can ship any time after it). Designs: `design/ravilo/Playback Sessions - Desktop, TV
& Admin.html` §B10 (the Sessions tab and the Activity timeline).

## Requirements

**FR-304-1 — A Sessions tab in *Ravilo users & devices*.** It sits beside Users and Devices and lists every live
`playback_session` (Ravilo R368). Each row shows the person, what plays (artwork, title, `S01E05`), the place with its
icon, the state, the position, how long it has run, and the apps controlling it (*Pixel 9 · MacBook*). The list comes
from R368's `session_list` with the admin's own view (all owners). An empty list is one sentence: *Nothing is playing
anywhere.*

**FR-304-2 — The admin is a full remote for any session (owner, 2026-10-04).** Expanding a row shows a remote: ⏯,
previous / next, a seek bar, the queue (jump, reorder, remove), shuffle / repeat, audio and subtitle tracks for a film
or episode, volume (R371: *Volume* + each room), *Move to…* (R372's single places, and *Add a speaker…* for music),
and **Stop** with an inline confirm. Every control sends R369's commands with the admin's session as the controller,
so viewers see the change at once and the timeline records *from the admin*. The household switch (FR-304-4) never
limits the admin.

**FR-304-3 — Each session's timeline.** Expanding a row shows an Activity-style timeline: started (from which app,
on which place), moved, places added or removed, paused or resumed, *reconnected after restart*, *{place} went
offline*, ended (why). The entries come from a `playback_session_event` table kept for **7 days**. Ended sessions stay
in the tab under *Ended today* until midnight.

**FR-304-4 — The household switch.** The Sessions card's header has one switch (drawn there 2026-10-04, beside the
sessions it governs, rather than in Settings → Ravilo): **Household members can control each
other's playing**, off by default. With it off, viewers see each other's sessions without controls (R368 FR-R368-6).
With it on, R369 marks every session controllable for every member of the household. The admin always has full
control from the admin, whatever the switch says.

**FR-304-5 — The Dashboard.** No new row. A session stuck in *reconnecting* for longer than 10 min shows as an
information row under Services (*Stue hasn't reported since the restart*), with the 285 grammar (*for information*).

**FR-304-6 — Words.** Admin is English only.

## Acceptance

1. With *Cannery Lights* on Stue and *Lundin og vinir* on the Køkken hub, the Sessions tab lists both, with Eyð and
   Olivar, places, positions and controllers. (304a; per the review it is a *Playing now* section, and the row shows
   *started from {app}* until R369 adds controllers. Tests: `AdminSessionListTest`, `ravilo-users-sessions.spec.ts`.)
2. The admin pauses Olivar's session, skips to the next episode, then stops it. The hub follows each, and the timeline
   reads *Paused from the admin · Next from the admin · Stopped from the admin*. (304b. Tests: `AdminSessionCommandTest`,
   `PlaybackSessionEventsTest`, `ravilo-users-sessions.spec.ts`; the hub itself only on the device.)
3. Turning the household switch on gives Eyð's Pixel ⏯ on Olivar's row within a second. (304b. Tests:
   `HouseholdControlConfigTest`, `PlaybackSessionRulesTest`, `TvEventBusSessionsIntegrationTest`,
   `ravilo-users-sessions.spec.ts`.)
4. A session from another network, or one hidden from a kids profile, is listed in full for the admin. (304a. Test:
   `AdminSessionListTest`.)

## Decided by the owner (2026-10-04)

- The admin has full remote control of every session.
- Other people's sessions: visible with names, no controls unless the household switch is on.

## Taken as leans

- The household switch is off by default.
- Timelines are kept for 7 days.

## Tests

These follow the reviewed and decided design below:

- a *Playing now* section above *People*, not a tab;
- the admin's own routes and `/ws`, with no visibility or network filter (owner decision 2);
- the split into **304a** (list and timeline) and **304b** (remote and switch);
- *End*, not *Stop*;
- FR-304-5 dropped (owner decision 1), so there is nothing to test for it.

R368's tests carry the session itself. Test names are Kotlin backtick names. Functions named here that don't exist yet
are the ones the build adds. The admin frontend (wasmJs) has no unit-test harness, so the page is checked in
Playwright.

**304a: the list and the timeline (ships after R368)**

- `src/linuxX64Test/kotlin/dev/jellystructure/tv/PlaybackSessionEventsTest.kt` (`linuxX64Test`). Covers FR-304-3 and
  review item 5. It uses `PlaybackSessions` on a temporary DB with a fake clock (the `CastServiceTest` setup).
  - `a start writes started with the starting device`: `source` is `started_by_device_id`.
  - `heartbeats write nothing and a pause writes one`: paused and resumed are written only when `is_paused` changes.
  - `a restart writes reconnected and the watchdog writes went offline`.
  - `an end writes ended with its reason`: stopped, replaced, and no return after a restart.
  - `the hourly tick drops events and ended sessions older than seven days`: 6 d 23 h is kept, 7 d + 1 ms is gone, and
    live sessions are never touched.
  - Pure function `endedTodaySince(nowMs, zone)`. Test: `ended today starts at local midnight`. A session that ended at
    23:59 yesterday is out, 00:01 today is in, and a daylight-saving change day is handled.
- `tv/AdminSessionListTest.kt` (`linuxX64Test`). Covers FR-304-1, review items 1, 2 and 9, and owner decision 2. Tests
  `adminSessionList(nowMs)`:
  - `the admin sees every owner on every network`: a session on another public address and a title hidden from a kids
    profile both appear in full.
  - `ended today is listed and nothing older`.
  - `a row says started from its app until controllers exist`: the device's `display_name` comes from
    `started_by_device_id`.
  - `now watching names a song by its title`: the per-user line reads the session table, which fixes R368's shipped
    bug 1 (a raw Jellyfin id).
- `jobs/JobEventSessionsTest.kt` (`linuxX64Test`). Test: `the sessions event round-trips with its type and the whole
  list`. `JobEvent.PlaybackSessions` keeps the shape the admin page decodes.
- Migration (review item 5).
  - If 304a ships with R368, R368's `PlaybackSessionMigrationTest` also asserts `playback_session_event`.
  - Otherwise, add `the event table migrates and drops nothing` to that class for 304a's own `.sqm`.
- `tests/e2e/ravilo-users-sessions.spec.ts` (Playwright on the e2e stack, with devices played by hand as in
  `screens.spec.ts`):
  - `the admin list is cookie gated`: `GET /api/tv/admin/playback/sessions` answers 401 without the admin cookie, 401 to
    a device bearer token, and 200 with the cookie.
  - `a playback started by a device shows on the page and on the socket`:
    - a phone starts an item;
    - *Playing now* sits above *People* and shows the person, the place, the state and *started from {app}*;
    - a `PlaybackSessions` frame arrives on `/ws`;
    - after a reload the page seeds from the GET.
  - `nothing playing reads one sentence`: *Nothing is playing anywhere.*
  - `no remote before R369`: the row has no ⏯, no seek and no *End*. They are absent, not greyed out.

**304b: the remote and the switch (with or after R369)**

- `config/HouseholdControlConfigTest.kt` (`linuxX64Test`, the `ThemeConfigTest`/`PublicUrlTest` pattern). Covers
  FR-304-4 and review item 6.
  - `a config without the key reads household control off`.
  - `writing it through ConfigStore round-trips and keeps every other field`.
- Extend R368's `PlaybackSessionRulesTest`:
  - `with household control on another nearby members session is controllable`. With the switch off, it is not.
  - `the switch never widens the household`: a member on another network is still not listed.
- Extend R368's `TvEventBusSessionsIntegrationTest` with `flipping the switch re-sends the list to every opted-in
  socket`. `controllable` flips in the next `session_list`, and a socket without `features=sessions` still gets nothing.
- `tv/AdminSessionCommandTest.kt` (`linuxX64Test`). Covers FR-304-2 and review items 4 and 8, on R369's command
  service.
  - `an admin pause is recorded from the admin`: the timeline entry has `source = admin`.
  - `the admin is never a controller row`.
  - `a stale revision from the admin gets a conflict` (409).
  - `the household switch never limits the admin`.
  - `end goes through stopPlayback and keeps the resume point`.
- Extend `ravilo-users-sessions.spec.ts`:
  - `the admin command route is cookie gated`.
  - Acceptance 2, with a hand-played device on its events socket:
    - pause, next and *End* each reach the device;
    - the timeline has the three entries, each *from the admin*.
  - Acceptance 3:
    - the switch is written through its route;
    - a member's opted-in socket gets `controllable = true` within a second;
    - there is no global Save.
  - Controls whose phase hasn't shipped are absent: R371's volume and *Add a speaker…*, and R372's *Move to…*.

**Only a live server or a device can confirm**

- The page against real sessions across a real backend restart (with the owner's go). The timeline should read
  *reconnected after restart*, or *ended* with the reason that it didn't come back. *Ended today* should reset at the
  server's midnight.
- Acceptance 2 on a real display, cleared by the owner for testing: the display follows each admin command (304b).

## Dev review (2026-10-04, against `main` `5210045a`)

Read against `RaviloUsers.kt` (the admin page), `GET /api/tv/admin/overview` (`TvRoutes.kt:980`), the admin's `/ws`
socket (`Server.kt:684`, `WsBroadcaster`, `JobEvent`), `DashboardRoutes`' Services group, `design/app/ravilo-users.html`,
and R368's own review. The design holds. Where it lands changes in three places, and one FR contradicts R368. Nine
items; one is for the owner.

1. **The page has no tabs.** *Users & devices* is one page: a pagebar, then one card per user with their devices
   nested (`renderRaviloUsers`, `RaviloUsers.kt:40`). The mockup doesn't draw a tab either. It draws a **Playing now**
   section above **People**, with the switch in that section's header and *Ended today* under the live rows
   (`ravilo-users.html:182-186`). Build that. The per-user *Now watching* line stays, but reads the session table, which
   also fixes R368's shipped bug 1 (a raw id for a song).

2. **The admin has no device token, so it doesn't use R368's wire.** `/api/tv/events` and R368's list route take a
   device token. The admin page holds a cookie session and listens on `/ws`. So:
   - `GET /api/tv/admin/playback/sessions` (cookie-gated like `/tv/admin/overview`) answers every live session plus
     today's ended ones, for all owners, with **no** visibility filter: the admin sees everything.
   - A new `JobEvent.PlaybackSessions` (the whole list) goes out on `/ws` through `WsBroadcaster` whenever R368 would
     push.
   - The page seeds from the GET and then follows the events (constitution: "seeded from GET, not accumulated purely
     from WS events").

3. **"Can ship any time after R368" is true only for the list and the timeline.**
   - FR-304-2's controls need R369's commands, R371's volume and *Add a speaker…*, and R372's *Move to…*.
   - FR-304-4 means nothing before R369, because nothing is controllable yet.
   - **Split: 304a** (the list, the timeline, *Ended today*) after R368, and **304b** (the remote and the switch) with
     or after R369. Each control appears when its phase ships, and is absent before (not greyed), R368's own rule.

4. **Admin commands get their own route.** `POST /api/tv/admin/playback/sessions/{id}/command` (cookie) calls the
   same command service as R369's route, with `source = admin`. The admin isn't a device, so it is never a row in
   `playback_session_controller`. The timeline writes *from the admin*. The revision rule applies to the admin as to
   anyone, so a stale seek gets the 409 and the row redraws.

5. **The timeline table.** `playback_session_event(session_id, at, what, source, detail)`, where `source` is a
   device id or `admin`. It goes in R368's migration if 304a ships together with it, and in its own otherwise. The
   session service writes it on its own transitions:
   - started (with `started_by_device_id`) · paused/resumed (a change in `is_paused`, not every heartbeat) ·
     reconnected after restart · target went offline (watchdog) · ended + `end_reason`;
   - *moved* and *places added/removed* come with R372 and R371.

   An hourly tick deletes events and ended session rows older than 7 days. *Ended today* means since midnight in the
   server's time zone.

6. **The switch is one setting.** A boolean in `AppConfig` (for example `ravilo.household_control`, default `false`),
   written through `ConfigStore`, never by editing `config.toml` by hand. A small admin route writes it immediately,
   with no global Save (this is not the Settings page). On a change, the server re-sends `session_list` to every
   opted-in socket, because `controllable` flips. That is what makes acceptance 3's "within a second" true. R369's
   rule reads it.

7. **FR-304-5 cannot fire as written.** R368 FR-R368-4 ends a session that hasn't reported within **2 minutes** of a
   restart. So nothing is ever *reconnecting* for 10 minutes. See the owner question.

8. **Words.** The mockup's button says *End* (with *End — sure?* as the inline confirm). The FR says *Stop*. Either
   way it is R369's `stop`, which goes through `stopPlayback`, so the resume point is kept as the mockup's note says.
   Use the mockup's *End*: *Stop* is already the player's own word.

9. **Rows and sizes.** A row shows how long the session has run (from `created_at`) and the controlling apps (R369's
   controller rows, with names from the device rows). Until R369 there are no controllers, so the row shows
   *started from {app}* (from `started_by_device_id`). The table stays small: one row per live session, plus today's
   ended ones.

**For the owner**

1. **FR-304-5 — what should the Dashboard say about a restart?** R368 ends a session that hasn't reported within 2 min,
   so "stuck for more than 10 min" never happens. **Lean: drop the row.** The session's timeline already says *ended —
   didn't come back after the restart*, and 285's rule is that nothing shows when nothing is wrong. The alternative is
   an information row, *N playbacks didn't come back after the restart*, shown until midnight.

**Shipped bugs found:** none new here. R368's review lists four. Bug 1 (a raw id on this page) is fixed by item 1.


## Owner decisions (2026-10-04, after the dev review)

1. **FR-304-5 (the Dashboard row about playbacks stuck after a restart) is dropped.** Under R368/R372's rules it can't
   fire; the session's timeline already says what happened, and the Dashboard shows nothing when nothing is wrong
   (285).
2. The admin sees **every** session, across networks (R368's household rule is for viewers only).
