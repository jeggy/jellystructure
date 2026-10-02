# Phase 299 — The Jellyfin dashboard controls every Ravilo playback

> Owner, 2026-10-02: the Jellyfin dashboard must be able to pause, play, seek, skip, stop and set the volume of
> anything Ravilo plays — music and films, on the phone, the Mac/Linux app, the web app, a TV, and on a Chromecast,
> Nest Hub or speaker, whichever sender started the cast. Old sessions must stop lingering. Tested in CI.

## Status

`✓ Built` 2026-10-02 (build notes at the end), not deployed, not device-tested. Written 2026-10-02 (dev-authored), against `main` `3127421c`. Number given by the coordinator (admin **Renumbered 296 → 298 → 299 on 2026-10-02:** another session took 296 (*the loudness finding is about music only*), 297 and 298 (*a library renamed in Jellyfin is picked up*) the same day.
specs top at 295). The client and receiver half is **R354**; both ship together.

**Amends** phase 110 (FR C.3's routing, FR C.5's capabilities), phase 218 (its claim that phase 110 "gives dashboard
pause/stop/seek for free" for a Chromecast — it never did), and phase 256 (FR-256-4's grace is now also what ends a
Jellyfin session, and a sweep ends the ones no socket ever held).

## What was seen (production `v1.48-66-ga00b813a`, Jellyfin 12.1, 2026-10-02)

1. A music cast from the Pixel 9 to the Stue speaker shows in `/Sessions` as `DeviceName "Chromecast via Ravilo ·
   Stue"`, `Client "Ravilo"`, with the right song and pause state, but `SupportsRemoteControl: false` and
   `SupportedCommands: []`. The dashboard offers no controls.
2. `POST /Sessions/{id}/Playing/Unpause` and `/PlayPause` against it answer 204 and nothing reaches the speaker.
3. Sessions linger for days: *Chromecast via Ravilo · Stue TV*, *Soveværelse TV* and *Gæsteværelse* were last active
   2026-09-30; a *Ravilo Web* and an older *fedora* session too. A read-only `/Sessions` probe the same morning: the
   Mac and Linux apps (events socket open) read `SupportsRemoteControl: true`, `SupportedCommands ["DisplayMessage",
   "Play", "PlayState"]`; every receiver and every client without an open socket reads `false`, `[]`.

## Why (root causes)

- **A remote command reaches a device only through phase 110's bridge**, and the bridge is opened only while the
  device holds `/api/tv/events` open (`Server.kt` → `sessionBridge.connect`). The Cast receiver never opens that
  socket (`ravilo-cast` has no `connectEvents`, no WebSockets plugin), so no Jellyfin `/socket` is ever opened under a
  receiver's identity and no capabilities are posted. Jellyfin only knows the receiver through its REST reports
  (`/Sessions/Playing*`). Jellyfin computes `SupportsRemoteControl` as *capabilities say media control* **and** *a
  session controller is live* (`SessionInfo.SupportsRemoteControl`, `WebSocketController.SupportsMediaControl =>
  HasOpenSockets`), so a REST-only session can never be controlled, and `SendPlaystateCommand` to it is a silent 204.
  Phase 218's spec assumed the receiver would ride phase 110 "for free" (lines 157, 208); nothing ever wired it.
- **Even an open bridge forwarded too little.** `postCapabilities` registers `["DisplayMessage","Play","Playstate"]`
  only — no volume commands. `handleIncoming` drops every `GeneralCommand` except `DisplayMessage`, so
  SetVolume/VolumeUp/VolumeDown/Mute/Unmute/ToggleMute die at the bridge.
- **Sessions linger because nothing ends them.** Jellyfin ends a session only when its last WebSocket closes
  (`WebSocketController.OnConnectionClosed` → `SessionManager.CloseIfNeededAsync` → `OnSessionEnded`) or on a logout.
  A session created by REST calls alone has no controller, so it is never closed: it stays in `/Sessions` until
  Jellyfin restarts. Every receiver session, and every client session that never held a bridge (a phone in the
  background, a late stop report after the bridge closed), is one of those.

## Requirements

**FR-299-1 — A device says what it obeys.** The events socket takes one optional query parameter,
`remote=<comma list>`, of Jellyfin `GeneralCommandType` names the device acts on. The server keeps only these
(case-insensitive, de-duplicated, in this order): `DisplayMessage`, `Play`, `PlayState`, `SetVolume`, `VolumeUp`,
`VolumeDown`, `Mute`, `Unmute`, `ToggleMute`. A query parameter, not a header, because a browser (the web app, the Cast
receiver) cannot set a WebSocket handshake header. **Absent ⇒ today's registration byte for byte** (`["Video"]`,
`["DisplayMessage","Play","Playstate"]`), so an old app keeps exactly what it has.

**FR-299-2 — Capabilities follow the declaration.** The bridge posts `/Sessions/Capabilities/Full` with
`SupportsMediaControl: true`, `SupportedCommands` = the declared list, and `PlayableMediaTypes: ["Video"]` when the
list holds `Play` (a device that takes the dashboard's *Play on*), else `[]` (a receiver, which only obeys). When a
device reconnects inside the bridge's grace with a different declaration (an app update), the capabilities are posted
again on the open bridge.

**FR-299-3 — Every command is routed.**
- `Playstate`: all nine commands (`Stop`, `Pause`, `Unpause`, `PlayPause`, `Seek`, `NextTrack`, `PreviousTrack`,
  `Rewind`, `FastForward`) go out as today's `playstate_command`, the command name unchanged, `SeekPositionTicks` as
  `seek_position_ms`. (They always did; old apps ignored the five they did not know, and still do.)
- `GeneralCommand` `SetVolume` (`Arguments.Volume`, a string 0–100) → `player_command set_volume {"volume": n}`
  (phase 236's own shape); `VolumeUp`/`VolumeDown` → `player_command volume_up`/`volume_down`; `Mute`/`Unmute` →
  `player_command mute {"muted": true|false}`; `ToggleMute` → `player_command mute` with no arguments (toggle).
  `DisplayMessage` and `Play` are unchanged. Anything else is ignored.
- `player_command` is the right carrier: an app older than R354 drops it (`RaviloApp` only logs it), where an unknown
  event `type` would have triggered a config re-pull.

**FR-299-4 — Receivers get the same bridge.** A Cast receiver that opens `/api/tv/events` with its own device token
(R354) gets a bridge under its own identity (`ravilo-cast-…-{user}`, `Chromecast via Ravilo · {name}`), the identity
its REST reports already use, so the dashboard's session is the receiver's and it becomes controllable. Nothing in the
bridge is cast-specific. Phase 110's stop watchdog keeps judging a receiver by heartbeat only
(`needsEventsSocket`), so a receiver closing its socket never stops its playback.

**FR-299-5 — A bridge's end ends the Jellyfin session.** When a device's events socket closes, the bridge stays for
phase 256's 90 s grace and then closes its Jellyfin socket, which ends the session on Jellyfin's side
(`CloseIfNeededAsync`). That was already so for TVs; it is now stated, tested, and true for receivers too. The grace is
a constructor parameter (default 90 s) so the test can shorten it.

**FR-299-6 — A sweep ends the sessions no socket ever held.** Every 60 s the server reads `/Sessions` (server token)
and ends each one that is all of: `Client "Ravilo"`; its `DeviceId` is the Jellyfin identity of a device row we hold;
no `NowPlayingItem`; not `IsActive` (no socket); `LastActivityDate` at least 90 s ago; and that device's bridge is
neither open nor in its grace. It ends it by opening `/socket` under that device's own identity and token, waiting for
Jellyfin's first frame (the controller is attached), and closing — the same thing a TV's bridge closing does.
**Never `POST /Sessions/Logout`**: it deletes the access token, and a receiver's token is its phone's. A session with
something playing is never touched (Jellyfin's own idle check clears a dead `NowPlayingItem` after 5 minutes; the next
sweep then ends it). Sessions of devices we no longer hold are left alone.

**FR-299-7 — Health says what each device declared.** Each `session_bridges` entry on `/api/health/full` gains
`commands` (the declared list; absent for an old app). Additive; `/api/health` keeps numbers only.

## Out of scope

- The dashboard's *Play on* for a song, and *Play on* a receiver (a receiver starts only from a sender).
- `ravilo-screen` (Tizen and the web screen): Tizen work is paused; it keeps today's registration.
- Reporting volume back to the dashboard (`VolumeLevel`/`IsMuted` on progress reports) — a later phase.
- Jellyfin's own *Inactive session threshold*: once sessions are controllable, a household that sets it will see
  Jellyfin stop a long-paused Ravilo session by sending `Stop`. That is Jellyfin's setting doing what it says.

## Acceptance

1. A Pixel music cast to a speaker: `/Sessions` shows the receiver's session with `SupportsRemoteControl: true` and
   the volume commands; the dashboard's pause/next/previous/seek/stop/volume act on the speaker.
2. The same for a film cast to a TV, a Nest Hub, and a cast started from the Mac.
3. A phone, Mac, Linux, web and TV session: same, for music and films (R354).
4. A receiver that goes idle leaves `/Sessions` within the grace plus one sweep; a leftover REST-only Ravilo session
   with nothing playing is gone within ~3 minutes.
5. An app older than R354 registers exactly what it did before.

## Dev review (2026-10-02, against `main` `3127421c`)

1. **The receiver has every piece but the socket.** It holds a device token (`ravilo.cast.token`) after its first
   enrolment, its `TvApiClient` already speaks `connectEvents` (shared), and the browser build puts the token in the
   query (`WS_TOKEN_IN_QUERY`, allowed by `check-events-query-token.sh`). It needs `ktor-client-websockets` and the
   loop. CORS for `/api/tv/events` already admits the receiver's origin (it is ours).
2. **Jellyfin's message shapes, from its source** (`MediaBrowser.Model/Session`): `Playstate` data is
   `PlaystateRequest {Command, SeekPositionTicks?, ControllingUserId}` with `Command` one of the nine names above;
   `GeneralCommand` data is `{Name, ControllingUserId, Arguments: Dictionary<string,string>}` — **the arguments are
   strings**, so `Volume` arrives as `"35"`. `SupportedCommands` parses case-insensitively (the bridge's `"Playstate"`
   comes back as `"PlayState"` on the live server). The capability values are `GeneralCommandType` names.
3. **Why a declaration, not a version check.** The server cannot know from a version string what a build obeys, and
   R252's version header is not trustworthy for that (debug builds, dev builds). A device saying so is exact and
   additive.
4. **Ending a REST-only session without harm.** Jellyfin binds a socket to the session named by the handshake's
   `Authorization` header (`RequestHelpers.GetSession` → `Client` + `DeviceId` + user). Opening and closing one under
   the device's identity attaches a `WebSocketController` and then closes it, which is `CloseIfNeededAsync`. A later
   REST call recreates the session; nothing is lost. `Logout` is ruled out (token deletion). Waiting for the first
   frame avoids closing before `EnsureController` runs (a socket closed before it is added would leave nothing to
   close).
5. **The 90 s heartbeat applies to a paused receiver.** A receiver reports progress from `TIME_UPDATE` and once on
   pause; a dashboard *Pause* longer than 90 s would be force-stopped by the watchdog (`isHeartbeatStale`). R354 adds a
   paused heartbeat on the receiver. The phone's music engine already ticks every 10 s while paused.
6. **CI.** The Docker e2e mock's `/socket` accepts and never pushes, and `/api/remote/command` rejects `kind=cast`, so
   neither covers this. The test is a K/N integration test in `linuxX64Test`: a fake Jellyfin (Ktor CIO server on a
   free local port: `/socket`, `/Sessions/Capabilities/Full`, `/Sessions`, token check), the real bridge and
   `TvEventBus`, and five real device sockets (phone, desktop, web, TV, receiver) connected over the loopback. It runs
   in CI's `unit` job (`./gradlew linuxX64Test`) with no new task.

## Build notes (2026-10-02)

**Built 2026-10-02, not deployed, not device-tested.** Commits `e6718448`, `d25ce6ad` (on `main` `3127421c`).

1. **FR-299-1/-2** — `parseRemoteDeclaration` and `capabilitiesBody` (`tv/JellyfinRemote.kt`); `Server.kt` reads
   `remote=` on `/api/tv/events` and passes it to `JellyfinSessionBridge.connect(device, commands)`; the bridge keeps
   each device's declaration and the token it registered under, posts `capabilitiesBody(...)` on connect, and
   re-posts on the open bridge when a reconnect inside the grace declares something different.
   `JellyfinClient.postCapabilities` takes the body (default: phase 110's bytes, `LEGACY_CAPABILITIES`).
2. **FR-299-3** — `parseJellyfinMessage` reads every frame into a `BridgeCommand`; `handleIncoming` only delivers it
   (`notifyServerMessage` / `notifyPlayItem` / `notifyPlaystateCommand` / `notifyPlayerCommand`). Unknown Playstate
   names are no longer forwarded.
3. **FR-299-4** — nothing receiver-specific on the server: R354's receiver opens the events socket and gets the same
   bridge.
4. **FR-299-5** — the grace is a constructor parameter (`graceMs`, default 90 s); `isBridged(deviceId)` = open or in
   its grace.
5. **FR-299-6** — `JellyfinSessionReaper` (`tv/JellyfinSessionReaper.kt`), run every 60 s from `Main.kt` on its own
   loop: `GET /Sessions` (server token, `JellyfinClient.getSessionsBody`), `parseJellyfinSessions`,
   `staleRaviloSessions` (Ravilo client, a device row we hold, nothing playing, not `IsActive`, idle ≥ 90 s, not
   bridged), then `JellyfinSessionBridge.endJellyfinSession(device)`: a socket under the device's identity and **own**
   token (`tvTokenForClient` — never the server fallback, which would bind another user's session), wait up to 5 s for
   Jellyfin's first frame, close. Logged per device (`Jellyfin session ended: device=… had no socket and nothing
   playing`).
6. **FR-299-7** — `BridgeHealth.commands` (additive) on `/api/health/full`.

**Deviation:** the spec's `PlayableMediaTypes` rule is as written (`Video` only with `Play`); no `Audio` — the
dashboard's *Play on* for a song is out of scope, and advertising `Audio` would invite a `Play` the apps would treat as
a film.

**Tests (CI `unit` job, `./gradlew linuxX64Test`, no new task):**
- `JellyfinRemoteTest` (7): declarations, the legacy capability bytes, app and receiver capabilities, all nine
  Playstate commands, every volume command (Jellyfin's string arguments), message/play/keepalive/junk, the sweep's
  rule (playing, active, bridged, foreign and unknown sessions untouched; nothing inside the grace).
- `JellyfinSessionBridgeIntegrationTest` (1, ~3 s): a fake Jellyfin on a Ktor CIO server on a free loopback port
  (`/socket` that demands the header credential, `/Sessions/Capabilities/Full`, `/Sessions`, `/Users/{id}`), the real
  bridge and `TvEventBus`, and six real device sockets — phone, desktop, web, TV, Cast receiver and an app older than
  R354. It asserts each device's registered capabilities (the old app's are phase 110's bytes), sends all 15 commands
  on each device's own Jellyfin socket and checks that device received exactly those 15 and no other device's, that
  the receiver's Jellyfin socket closes after the grace, and that the sweep ends the receiver's idle session (a new
  open + close under its identity) and never touches the playing one.
- The whole suite: 846 tests, 0 failures.

**Owed:** the device checks in R354's build notes; the first deploy should show the lingering 2026-09-30 sessions
(*Chromecast via Ravilo · Stue TV*, *Soveværelse TV*, *Gæsteværelse*, the old *Ravilo Web* / *fedora*) leave
`/Sessions` within ~2 minutes, each with one `Jellyfin session ended` log line. A session whose device row is gone is
left alone by design (Jellyfin's restart clears it).

**Found on production right after the deploy (2026-10-02) and fixed:** the sweep ended nothing — all five stale
*Chromecast via Ravilo* sessions (two days old) stayed. Jellyfin 12.1 reports a session with no socket controller as
`IsActive: true`, and `staleRaviloSessions` skipped every `IsActive` session, i.e. exactly the stale ones. The check is
gone (a Ravilo device never holds its own Jellyfin socket; the bridged check already protects a live one), and
`JellyfinRemoteTest` now uses production's shape.

## Amendment (2026-10-02 evening) — *Send message* reaches the device it was sent to, or says why not

With R354's amendment (FR-R354-9; the owner's *Send message* to the phone did nothing).

**FR-299-8 — A message is delivered like a command.** `notifyServerMessage` finds the device's events socket the way
`notifyPlaystateCommand` does (`targetFor`: the user's own socket for that device, else that device's socket under any
user), so a message to a shared screen is not lost to whose token opened it.

**FR-299-9 — A message that finds no socket says so.** The server logs, once per message, whether it was sent and to
which device, or dropped because the device holds no live events socket (the app is off screen: R293) — the byte length
of the text, never the text itself. Before, a dropped message left no trace, and *does nothing* could not be told apart
from *never arrived*.

**FR-299-10 — A receiver with a screen takes messages.** The receiver declares `DisplayMessage` on a device with a
screen (R354 FR-R354-9e); `capabilitiesBody` already registers what is declared, so the dashboard offers *Send message*
for it. No change for a speaker. Additive: the declaration is a list of existing names.

### Build notes (amendment, 2026-10-02 evening) — not deployed

`TvEventBus.notifyServerMessage` uses `targetFor` and logs `TV events: message (N chars) sent to device …` or
`… dropped: no live events socket (off screen)`. The receiver's declaration is R354's (`receiverRemoteDeclaration`);
`capabilitiesBody` registers it unchanged, so a TV, a hub or a Chromecast playing a Ravilo cast reads `DisplayMessage`
in its `SupportedCommands`. Backend `linuxX64Test` green. Ships with the next backend deploy (the receiver too).
