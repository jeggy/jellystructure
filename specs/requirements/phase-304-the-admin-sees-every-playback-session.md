# Phase 304 — The admin sees every playback, and the household chooses who can control whose

## Status

`Planned` — written 2026-10-03 (design-authored), **not dev-reviewed**. **Phase 6 of 6** of playback sessions (see
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
