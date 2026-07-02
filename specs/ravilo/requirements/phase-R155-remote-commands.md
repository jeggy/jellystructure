# Phase R155 — Ravilo handles remote commands (play / playstate / home)

> Client side of **[Phase 111](../../requirements/phase-111-remote-control-api.md)** (jellystructure
> remote-control API) and of the Jellyfin remote-control path opened by
> **[Phase 110](../../requirements/phase-110-jellyfin-session-bridge.md)** (dashboard cast menu / Home
> Assistant `Play` commands, forwarded by the bridge). One handler serves both sources.

## Goal
A connected Ravilo device reacts to server-pushed commands on its existing `/api/tv/events` socket:
**start playing a given item**, **stop/pause/unpause**, and **go home** — so "stream Polly Piglet in Stue
TV" from Home Assistant (or the Jellyfin dashboard's cast menu) just works.

## Current state (verified in code)
- The events socket parses `TvEvent{type, rev}` and routes `acquisition_changed` via a dedicated
  envelope; **unknown types harmlessly fall through** to the config-refresh handler
  (`TvApiClient.kt:294-316`) — so new payload-bearing types are backward-safe.
- Programmatic navigation exists: `push(Dest.Player(itemId, …))` starts playback of a Jellyfin id
  (`RaviloApp.kt:118-128, 513, 539-551`); Home's Play routes series through detail context
  (`buildEpisodeContext`, `SeriesDetailScreen.kt:128-146`).
- The player exposes `togglePlay` / stop via `PlayerStore` (`stopSession`, `PlayerStore.kt:65-79`).

## Requirements

### FR-R155-1 — `play_item`
1. Handle a device-addressed `play_item { jellyfin_id, kind, title?, start_position_ms? }` event
   (the backend resolves and includes `kind` = `movie | episode | series`, so the app never looks it up):
   - `movie` / `episode` → `push(Dest.Player(itemId = jellyfin_id, startPositionMs))` — if a player is
     already open it is replaced (stop old session first).
   - `series` → open `Dest.SeriesDetail(jellyfin_id)` and auto-trigger its Play/Resume action (the
     existing resume-pointer logic picks the right episode).
2. **Profile guard:** commands are addressed to a `(user, device)`; if the TV's **active profile** is a
   different user, ignore the command and log it (no profile auto-switching). The "Who's watching?"
   picker (no active profile yet) also ignores.
3. Commands received mid-pairing/loading are dropped, never queued.

### FR-R155-2 — `playstate_command`
Handle `playstate_command { command, seek_position_ms? }` when the player is open:
`stop` → stop session + pop back to the originating screen; `pause`/`unpause` → set (not toggle) the
play state; `seek` → seek to `seek_position_ms`. When no player is open, ignore. (`pause`/`unpause` from
Jellyfin's dashboard buttons and HA arrive through the Phase 110 bridge as these events.)

### FR-R155-3 — `navigate`
Handle `navigate { target: "home" }` → `resetTo(Dest.Home)` (same reset semantics as the app-bar Home
tab). Only `home` this phase.

### FR-R155-4 — Wiring
| Layer | File | Change |
|---|---|---|
| Events client | `shared/…/tv/TvApiClient.kt` (`connectEvents`) | Decode the three payload envelopes; new callbacks beside `onAcquisition`. |
| App | `ravilo-ui/…/RaviloApp.kt` (events loop `:184-193`) | SharedFlows → a `RemoteCommandHandler` that owns the profile guard + nav calls; player commands reach `PlayerStore` via the existing store references. |

## Non-goals
- No profile switching, no wake-from-standby / HDMI-CEC power-on (a TV with the app not running is
  simply `disconnected`; Phase 111 returns `409 device_offline`).
- No arbitrary D-pad/key injection, no screenshots, no text input.
- No play-queue semantics (PlayNext/PlayLast collapse to play-now; noted for later).

## Acceptance
- `POST /api/remote/play` (movie id) on a connected TV opens the player at the right position within
  ~2 s; a series id lands on its detail and starts the resume episode.
- Jellyfin dashboard ⏸/⏹ on the TV's session (via the Phase 110 bridge) pauses/stops the running player.
- Commands for a non-active profile do nothing (logged), and the UI never flickers or loses focus state
  when a command is ignored.
