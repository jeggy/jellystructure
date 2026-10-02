# Ravilo playback sessions and casting — what we learned, how it works today, and where it should go

**Date:** 2026-10-02 · **Against:** `main` `f13e547c` · **Status:** research report (not a spec). Companion design brief:
`specs/ravilo/design-brief-playback-sessions-2026-10-02.md`.

> Owner, 2026-10-02: *"Next step is to be able to do the same, but from the app instead, making it possible to do it
> from desktop as well. And we would also like to be able to start a session from the mobile to a casting session and
> then see directly on the macbook client as well. And then on top of this, we would like to be able to have multiple
> of these sessions. So basically, every session is controlled in the cloud (jellystructure) and each session can be on
> a device or in a google cast group etc. And multiple Ravilo clients can control a single session, but also have
> support to create a new session, so having multiple sessions."*

This report has three jobs:

1. **Record what one long day of real-device testing taught us** (§1) — so the next round does not relearn it.
2. **Map how playback, casting and remote control work today** (§2–§3) — every piece, where it lives, which phase owns it.
3. **Describe the target**: one server-owned *playback session* that any Ravilo client can see, join and control,
   playing on any target (§4–§7), with the open questions only the owner or a device can answer (§8).

---

## 1. What we learned on 2026-10-01/02

Everything below was found on real devices — the Pixel 9, the MacBook, the living-room Android TV, the kitchen Nest Hub
and two Nest Wifi point speakers (*Stue*, *Gæsteværelse*) — against production. Each line names the phase that fixed it.

### 1.1 Google Cast on Android (MediaRouter2, Android 16)

- **An idle Cast route's description is its model name** ("Nest Wifi point", "Google Nest Hub"), not empty. Ravilo read
  it as "the app it is playing" and called every device *Busy*. A busy device's description is the running app's
  status text. Fix: compare against `CastDevice.modelName`/friendly name (R353).
- **Selecting a speaker can silently do nothing.** While Play services connects to a speaker that is also a member of
  a speaker group, it republishes every route; if the new session reaches the app in that gap, androidx logs
  *"Selected routes are empty"* and drops it — no Cast session ever starts. It is a race, roughly one in fifteen.
  Fix: re-select after 3 s, at most twice (R353). Seen firing in production afterwards — it works.
- **ExoPlayer may only be touched on the main thread.** The music hand-off read the position from a background
  coroutine and crashed the app (R353). The Mac's player has no such rule, so the Mac never showed it.
- **A volume slider must start at the device's real level.** It opened at 50 %, so one touch could take a room to half
  volume (R353).
- **A dynamic group is the same receiver, moved.** ⊕ in Android's output panel makes a virtual Cast device
  ("Gæsteværelse + 1") on the first speaker's address at its own port (32xxx). The *same* Ravilo receiver page moves
  onto it and keeps playing — no LOAD, no relaunch, no new enrolment, no new Jellyfin session. The member speakers run
  Google's own multizone apps (leader `531A4F84`, follower `705D30C6`), never a second Ravilo receiver. The sender's
  session id stays; only its device name changes. Removing the added speaker moves the receiver back (R355).
- **Moving music *to* another speaker (stream transfer) is not available** until the receiver declares
  `STREAM_TRANSFER` and handles `RESUME_SESSION` — Android's panel greys the option out (R355 open question 1).
- **An app update or reinstall drops the phone's Cast session; the group plays on.** Rejoining is unreliable
  (R355, found, not fixed).

### 1.2 The Cast receiver as its own Ravilo device

- **A receiver borrowed its first sender's Jellyfin token for good.** Jellyfin replaces a device's token when that
  device signs in again, so one fresh sign-in on the Mac silently broke Stue, Gæsteværelse and the bedroom TV at once
  (`HTTP 401 … re-pair the device`). Nothing ever re-enrolled a receiver after its first cast. Fix: every cast refreshes
  the receiver's token from the casting device; a new sign-in carries its receivers along; a rejected token heals from
  the user's live devices (phase 300). Lesson: **a credential copied from another device inherits that device's
  lifetime.** A receiver's own Jellyfin identity (Quick Connect) is the recorded follow-up.
- **A small display needs to be asked what it decodes.** The receiver declared 1080p H.264 with 6 channels to a
  720p-class Nest Hub → Shaka 4032 (no playable variant), then 3016 (audio renderer). Fix: probe H.264 level/size,
  bitrate and channels through `cast.__platform__.canDisplayType` and send them in the ticket (R351).
- **Jellyfin reads a film's audio limits from `VideoAudio` codec profiles, never `Audio`.** Phase 177 put the channel
  limit in an `Audio` profile, so no film negotiation ever honoured it, for any client. Fix in R351.
- **A credits marker can sit on the final fade to black** (1–2 s before the end). The receiver trusted it, so the
  next-up card showed for 2 s. The countdown now always fits (R351); the scan heuristic still writes such markers.

### 1.3 Jellyfin's dashboard and sessions (Jellyfin 12.1)

- **Remote control needs capabilities *and* a live socket.** A Ravilo session only posted capabilities while its
  device had our events socket open; a receiver never did. Jellyfin answers `204` to `/Sessions/{id}/Playing/Pause` even
  when nothing will receive it — a 204 proves nothing (phase 299).
- **`IsActive: true` means "no socket controller", not "recently active".** Every stale Ravilo receiver session read
  active; the first stale-session sweep skipped exactly them (299, fixed the same day).
- **A session created by REST reports alone never ends** until Jellyfin restarts — sessions from 30 September were
  still listed on 2 October. Ending one means opening and closing `/socket` under the device's own token; never
  `Logout` (it deletes the token, and a receiver's token was its phone's) (299).
- **A dashboard Stop on a cast keeps the Cast session connected.** The receiver stops, shows its idle view and stays
  loaded. Ravilo's hand-back only ran on a session *end*, so the phone fell back to the song from before the cast. And
  right after `ended` the receiver sends one more report with nothing loaded at position 0 — taken naively, it
  overwrites the last real position (R353 FR-R353-5).
- **Song changes made by other controllers** (Google Home's *next*, the dashboard's *next*) must reach the sender's
  queue, or a later hand-back resumes the wrong song (R353).

### 1.4 Process

- **Parallel sessions race for spec numbers.** Another session took 296, 297 and 298 the same day; this round's phase
  was renumbered twice. Check `ls specs/*/requirements` on the latest `main` immediately before numbering.
- **Deploy from a clean worktree** when another session has uncommitted work in the shared checkout
  (`docker build -t jellystructure-backend … <worktree>` + `compose up --no-build`).
- **A shared scratch directory needs unique file names** — a builder overwrote the main session's phone helper.
- **Device etiquette:** check for an active phone call before every input batch (we once drove the phone during the
  owner's call); keep speakers ≤ 10 %; foreground-check every app.

---

## 2. How it works today (map)

### 2.1 Where "what is playing" lives

There is **no server-side playback session object**. "A session" today is several things in several places:

| Case | Client | Server | Receiver | Jellyfin |
|---|---|---|---|---|
| Film, local | `Dest.Player` + `PlayerStore` (per item) + resume record (`PlayerResume.kt`, 12 h) | `PlaybackTracker` entry keyed `(deviceId, itemId)` + `SessionPlan` (R343/R347), in memory | — | one session per (device, user) |
| Music, local | `MusicEngine` queue (`MusicQueue.kt`), device-local snapshot `MusicQueueStore` | one tracker entry **per song** (FR-279-6); `lastPlayed` derived from Jellyfin history | — | one playback per song |
| Film on a Chromecast | sender mirror over the Cast namespace | tracker entry under the receiver's device row (item id only) | receiver owns the episode list, advance, Skip Intro, next-up | receiver's own session |
| Music on a speaker | sender mirror (`MusicCast`), engine's parked queue for hand-back | per-song entries under the receiver row | **receiver owns the queue** (286 FR-286-4) | receiver's session |
| Film on a "screen" (Tizen, paused) | phone mirror via `device_status` | `ScreenStatusTracker` + `ScreenShuffles` (the only server-held queue), in memory | screen app | screen's session |

Consequences: other clients see at most "device X plays item Y" (`nowPlaying`); a Chromecast's rich state reaches only
its sender; a restart of the backend loses every in-memory playback fact; the same playback appears as two Jellyfin
sessions while a phone casts.

### 2.2 The pieces (with owners)

- **Playback routes** (`TvRoutes.kt`): `/api/tv/playback/start|progress|stop|qoe|restream|status`,
  `/api/tv/music/play`. Writes to Jellyfin go through `PlaybackWriter` (219: queued, retried, last-write-wins).
  Watchdog force-stops after 90 s without a heartbeat (110 FR B.2).
- **Events channel** `WS /api/tv/events` (`Server.kt`) + `TvEventBus` (user → device → socket, cap 128). Events:
  `play_item`, `playstate_command`, `player_command`, `navigate`, `device_status`, `playstate_changed`,
  `home_changed`, `config_changed`, … Apps hold it while on screen (R293) or while music plays/paused ≤ 10 min (R354);
  a receiver while something is loaded (R354). A TV app off screen has none (R293 non-goal).
- **Jellyfin bridge** (`JellyfinSessionBridge`, 110/238/256/299): one Jellyfin `/socket` per device with an open events
  socket, **capped at 16**, 90 s grace; capabilities from each device's `remote=` declaration; stale-session sweep
  every 60 s.
- **Casting:** Android sender (`CastSenderAndroid`, `CastRoutesAndroid`, `MusicCast`); Mac/Linux Cast v2 client
  (`ravilo-castv2`, R330) sending the same LOAD; the CAF receiver (`ravilo-cast` `Receiver.kt`) as its own Ravilo device,
  enrolled by a 6-character hand-off code (218), token per (receiver, user) (300).
- **Screens** (236): `/api/remote/**` (`devices`, `play`, `command`, `pair`, `WS /api/remote/events`), 409 when another
  user is watching (R270 names them), `nearby` = same public address. Built for the Tizen screen; the Tizen work is
  paused. **FR-236-11 (apps post `ScreenStatus` and obey `player_command`) is only half built** — apps obey (R354), none
  report.
- **UI:** `components/Cast.kt` (`CastController`, `CastSheetHost`, `CastMiniBar`), `ScreensSheet.kt` (R265 tiers),
  `CastRemoteScreen.kt` (R245 remote), music mini bar and desktop capsule (`DesktopMusicBar.kt`, R337).

### 2.3 What already works (verified 2026-10-02)

- Phone and Mac cast music to a speaker; the phone and the Mac follow pause/next/seek made elsewhere.
- A phone-made dynamic speaker group plays and is named "Gæsteværelse + Stue" on the phone (R355).
- The Jellyfin dashboard pauses, seeks, skips, stops and sets volume on: the phone (music), the Mac (music, film,
  window closed), the TV (film), and a cast receiver (speaker) (299/R354).
- A dashboard Stop on a cast keeps the speaker's song paused on the phone; Play resumes it on the speaker (R353).

### 2.4 What does not exist

- A session another Ravilo client can **see** (the Mac does not know the phone is casting to Stue).
- A session another Ravilo client can **control** except through the Jellyfin dashboard.
- Building a speaker group **from Ravilo** (only Android's system panel can), and anything group-related on the
  desktop.
- **Moving** a session from one target to another (stream transfer), or from a device to a cast and back, except the
  phone's own hand-off/hand-back.
- **More than one session per user** in any UI.
- Music *Play on* from Jellyfin's dashboard (`PlayableMediaTypes` never includes `Audio`).
- Volume/mute reported back to Jellyfin (the dashboard's volume indicator stays empty).

---

## 3. Known limits that constrain the design

| Limit | Source | Why it matters |
|---|---|---|
| Android may kill a background app's socket; a TV app off screen has no socket at all | R293, R354 | A *controller* that is asleep cannot receive updates; a *target* that is an app must be on screen or hold a media session |
| 16 Jellyfin bridges | `JellyfinSessionBridge.kt` | Every session target and controller with a socket takes one |
| Cast SDK only on Android; Mac uses our own Cast v2 client; iPhone/web cannot reach speakers | R324 FR-R324-10, R330 | Group building and speaker control differ per platform |
| Dynamic groups are made by Google's multizone apps; the public sender API to create one is Android's MediaRouter (dynamic group route controller) | R355 | The desktop cannot build a group the same way; needs research (§8) |
| Stream transfer needs `STREAM_TRANSFER` + `RESUME_SESSION` in the receiver | R355 OQ1 | "Move this to the kitchen" is not possible until then |
| Receivers borrow a user's Jellyfin token | 300 | A session's target is tied to a user; two users casting to one speaker get separate receiver rows |
| Everything playback-related on the server is in memory | §2.1 | A backend restart forgets every session |
| Speakers resolve the public hostname through public DNS | 286 | Receivers depend on the router's hairpin rule |

---

## 4. Target: the server owns every playback session

### 4.1 The model

A **playback session** is a server object:

```
PlaybackSession
  id                 stable, survives restarts (a table, not memory)
  owner              the Jellyfin user whose account plays (library rules, watched state)
  target             one of: an app device (phone, Mac, Linux, web, TV) · a Cast device · a Cast group
                     (dynamic or Google Home) · later: a Tizen screen
  queue              items + index (films/episodes and songs alike; a book is one item with chapters)
  position, state    playing / paused / buffering / ended / failed, with the server time it was measured
  options            shuffle, repeat, start-over latch (R343), subtitle/audio picks, volume
  controllers        the Ravilo clients currently attached (can be many), and the Jellyfin dashboard
  revision           increments on every change; commands carry the revision they were made against
```

- **Every playback is a session**, local ones included. Playing on the phone creates a session whose target is the
  phone. That is what lets the Mac *see* it and *take it over*.
- **The target executes; the server decides.** Commands (play, pause, seek, next, queue edits, volume, move) go to the
  server; the server applies them to the session and forwards them to the target (app over `/api/tv/events`, receiver
  over its events socket, or Cast for the parts only Cast can do). The target reports position and state back. Clients
  render what the server pushes (the constitution's *server-pushed state only*).
- **Many controllers, one session.** Any client of the same user (and, by an owner decision, other household members)
  can attach to a session: it gets the full state and can send commands. Conflicting commands resolve by revision —
  last command against the current revision wins; a stale one is answered with the new state, not applied.
- **Many sessions.** A user can run several at once (a film on the TV, music in the kitchen, an audiobook on the
  phone). Starting playback asks — or decides by rule — whether to start a new session or replace one.
- **Moving a session** (from the phone to the kitchen speaker, from the Mac to the TV) is a first-class command, not a
  cast-specific special case: the server stops the old target at a position and starts the new one there.

### 4.2 How today's pieces map onto it

| Today | In the target model |
|---|---|
| `PlaybackTracker` `(device, item)` entries, `SessionPlan`, `ScreenShuffles`, `ScreenStatusTracker` | folded into `PlaybackSession` (persisted) |
| Music queue on the client (R322), queue on the receiver (286) | the session's queue on the server; client and receiver hold a *copy* that follows revisions |
| Cast hand-off code + LOAD (218) | "start session on target X": the server mints the code; any client can ask for it, so the Mac can cast what the phone started |
| `play_item`, `playstate_command`, `player_command`, `device_status` | session events on the same socket: `session_state`, `session_command`, `session_list` |
| `/api/remote/**` (236) | generalised from "screens" to "any target"; the 409 *busy with {name}* rule stays |
| Jellyfin bridge per device (299) | per *target*; the dashboard controls sessions through the same command path |
| `MusicCast` hand-back (R353) | becomes "move session to this device" |

### 4.3 Targets and what each can do

| Target | Start | Control | Report | Notes |
|---|---|---|---|---|
| Ravilo app on a phone | ✓ (it is the device) | ✓ via events socket while on screen or holding a media session | ✓ | Android background limits (R354) |
| Ravilo desktop (Mac/Linux) | ✓ | ✓ (window may be closed) | ✓ | |
| Ravilo web | ✓ (films; no music engine yet) | ✓ while the tab is open | ✓ | |
| Ravilo TV app | ✓ | only while on screen (R293) | ✓ | a TV off screen cannot be a target |
| Chromecast / Nest Hub (CAF receiver) | ✓ via hand-off code, from any client that can reach Cast | ✓ via receiver's events socket | ✓ | receiver must report rich state to the **server**, not just to senders |
| Speaker | as above, audio only | ✓ | ✓ | |
| Cast dynamic group | Android: MediaRouter dynamic group; desktop: research (§8) | ✓ through the leader's receiver | ✓ | members run Google's apps |
| Google Home static group | appears as a Cast device ("_googlecast" group) | ✓ | ✓ | not listed in Ravilo today (R355 OQ2) |

---

## 5. Suggested phasing (to be numbered when written; next free at time of writing: 303 / R356)

1. **Sessions on the server, read-only.** Persist a `PlaybackSession` for every playback (local and cast); every
   client gets `session_list` and shows "playing elsewhere" (the Mac sees the phone's cast to Stue). Receivers report
   state to the server. No new controls yet. *Design: the sessions list, the "playing elsewhere" bar.*
2. **Control any session from any client.** Commands through the server with revisions; the Mac's capsule/bar drives
   the phone's speaker session; the phone drives the Mac. Jellyfin's dashboard uses the same path. *Design: attaching
   to a session, who is controlling, conflicts.*
3. **Start on any target from any client.** The Mac casts to speakers and the TV (it already has a Cast v2 client);
   the server mints hand-off codes for whichever client asks; the TV app becomes a target while on screen. *Design: the
   unified "Play on…" sheet on desktop.*
4. **Groups from Ravilo.** Android builds dynamic groups through MediaRouter; desktop through the method §8 settles;
   Google Home groups listed. *Design: picking several speakers, naming, per-speaker volume.*
5. **Multiple sessions and moving.** New-vs-replace rules, session switcher, move a session between targets, stream
   transfer in the receiver. *Design: the switcher, move, merge.*
6. **Admin.** A sessions view in jellystructure (who plays what where, end a session), and household rules.

Each phase keeps today's behaviour working for old apps (additive wire only; old apps simply do not see sessions).

---

## 6. Data and protocol sketch (for the dev review, not decided)

- Table `playback_session(id, owner_user_id, target_kind, target_id, state, position_ms, position_at, queue_json,
  index, options_json, revision, created_at, updated_at, ended_at)` and `playback_session_controller(session_id,
  device_id, attached_at)`.
- REST: `GET /api/tv/sessions` · `POST /api/tv/sessions` (start: target + queue) ·
  `POST /api/tv/sessions/{id}/command` (`{revision, command, args}`) · `POST /api/tv/sessions/{id}/move` ·
  `POST /api/tv/sessions/{id}/attach|detach` · `DELETE /api/tv/sessions/{id}`.
- Events on `/api/tv/events`: `session_list`, `session_state {id, revision, …}`, `session_command` (to the target).
- Targets report with `POST /api/tv/sessions/{id}/report` (replacing the per-item progress calls over time; the old
  routes keep working).
- Jellyfin: one Jellyfin session per *target*; controllers never create their own Jellyfin playback.

---

## 7. Risks

- **Two sources of truth during migration** (client queue vs server queue). Mitigation: phase 1 is read-only.
- **Android background limits** make the phone a weak *target* when off screen; it is fine as a *controller* only if
  it re-syncs on resume (state is on the server).
- **Latency of server-routed commands** (pause should feel instant). Mitigation: the controlling client applies
  optimistic UI only for the gesture itself, then renders the server's state (no derived state kept).
- **Cast group creation on desktop** may need undocumented multizone APIs (§8).
- **Bridge cap 16** and event-bus cap 128 with many targets and controllers.
- **Household privacy**: seeing and controlling another person's session is an owner decision.

---

## 8. Open questions

**For the owner**
1. Can household members see each other's sessions? Control them? (Lean: see, yes, with the person's name — as R270
   does for a busy TV; control only your own unless "allow household control" is on.)
2. Pressing Play while a session is already running on another target: start a new session, or take it over? (Lean:
   on the same device → replace that device's own session; elsewhere → new session, with "take over" one tap away.)
3. How many sessions at once is useful? (Lean: no limit, the list shows the active ones; ended ones fade after a
   minute.)
4. Is the Jellyfin dashboard a full controller of Ravilo sessions (it is today, per target), or should it see sessions
   too? (Lean: keep per-target control; sessions are Ravilo's concept.)

**For research / a device**
5. Can a desktop (our Cast v2 client) create or extend a dynamic group? Candidate: the multizone namespace
   (`urn:x-cast:com.google.cast.multizone`) against a group leader — undocumented; must be tested on the speakers.
   Fallback: list Google Home static groups and let Android build dynamic ones.
6. Stream transfer: receiver `STREAM_TRANSFER` + `RESUME_SESSION` → a fresh hand-off code minted by the receiver
   (R355 OQ1). Does the moved receiver keep our events socket and Jellyfin session?
7. Can the server keep a session alive across a backend restart well enough (targets re-attach, positions resume)?
8. iPhone / web: no Cast SDK; can they be *controllers* of a cast session through the server? (Yes, by design — they
   never talk to Cast.) Can they *start* one? (Through the server minting the code and a capable device or the
   backend launching it — R286's road C, the backend as a Cast sender, becomes relevant.)
