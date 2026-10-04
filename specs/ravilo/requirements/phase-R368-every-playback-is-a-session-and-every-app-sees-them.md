# Phase R368 — Every playback is a session on the server, and every Ravilo app sees them

> Owner, 2026-10-02: *"every session is controlled in the cloud (jellystructure) and each session can be on a device
> or in a google cast group etc. And multiple Ravilo clients can control a single session, but also have support to
> create a new session, so having multiple sessions."* 2026-10-03, after round 1: *"Yes, lets write specs."*

## Status

`Planned` — written 2026-10-03 (design-authored), **not dev-reviewed**. Source: the brief
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
   accent, and the toolbar glyph reads **1**.
2. Eyð plays *Vinterfærgen* on the Pixel too, and Olivar watches *Lundin og vinir* on the Køkken hub. The Pixel's sheet
   lists her book first (highlighted), *Cannery Lights* · Stue, and Olivar's row with his name and no buttons. The glyph
   reads **2**.
3. The backend restarts while *Cannery Lights* plays. Each app's row reads *Reconnecting…* until Stue's next report,
   then carries on at the right position.
4. An older phone build sees no sessions and plays exactly as before.

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
