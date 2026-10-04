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
   Olivar, places, positions and controllers.
2. The admin pauses Olivar's session, skips to the next episode, then stops it. The hub follows each, and the timeline
   reads *Paused from the admin · Next from the admin · Stopped from the admin*.
3. Turning the household switch on gives Eyð's Pixel ⏯ on Olivar's row within a second.

## Decided by the owner (2026-10-04)

- The admin has full remote control of every session.
- Other people's sessions: visible with names, no controls unless the household switch is on.

## Taken as leans

- The household switch is off by default.
- Timelines are kept for 7 days.

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
