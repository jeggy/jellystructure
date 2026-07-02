# Phase 110 — Jellyfin session bridge: one named session per Ravilo TV (FR-JS1)

## Goal
Make each Ravilo device appear in Jellyfin's dashboard as **its own, correctly-named session**
(`Ravilo — Stue TV`, attributed to the right Jellyfin user), with playback that **starts, progresses and
stops** in the dashboard exactly when it does on the TV — and give jellystructure a **per-device Jellyfin
WebSocket** so dashboard **messages** (→ R152) and **remote-control commands** (→ Phase 111 / R155) reach
the right TV. Fix the paired-user-token 401 storm at its root.

## Problem
- The dashboard shows one anonymous **"Jellystructure"** device no matter which TV plays, and the session
  **lingers long after playback stops**.
- **Dashboard → Send message** goes nowhere (nothing holds a Jellyfin socket to receive it).
- The backend log spams `TV: paired user token rejected by Jellyfin (401) … using server token` — every
  TV request re-probes a dead token (an extra Jellyfin round-trip per request, forever) and silently
  degrades to the server token.

## Current state (verified in code)
- **One global Jellyfin identity for everything:** `JellyfinClient.kt:25-27` —
  `DEVICE_ID = "jellystructure-server-v01"`, header `MediaBrowser Client="Jellystructure",
  Device="Server", DeviceId="jellystructure-server-v01", Version="0.1.0"`; `jellyfinAuth()` (`:359-361`)
  only appends the token. Every call — admin login, pairing `authenticateByName`, PlaybackInfo, playback
  reporting — presents the same DeviceId.
- **Streaming is direct:** the TV loads `"$jellyfinBase/Videos/$id/stream?…&api_key=$token"`
  (`PlaybackService.kt:89-94`) — no jellystructure proxy, **no `DeviceId` on the stream URL**.
- **Reporting:** `/api/tv/playback/start|progress|stop` → `POST /Sessions/Playing[/Progress|/Stopped]`
  (`JellyfinClient.kt:180-241`) with the paired token + the global header. **Stopped is only sent when
  the app calls stop** (`PlayerStore.stopSession`); an app kill / network drop / HDMI-off leaves the
  session playing until Jellyfin's own 5-minute check-in timeout.
- **Tokens:** minted once at pair-approve via `authenticateByName` (`TvRoutes.kt:168,184`) and stored per
  `(device, user)` row (`RaviloDevice.sq`). `tvToken()` (`PlaybackService.kt:324-341`) probes validity;
  an invalid token is **removed from the cache instead of negative-cached**, so every subsequent request
  re-probes (401) and falls back to the server token — the log storm.
- **No Jellyfin WebSocket exists anywhere.** `ravilo_device.display_name` exists but is always `""`
  (`RaviloDeviceService.kt:96`) — no TV name is ever captured.
- `TvEventBus` registers connections **per user only** (`TvEventBus.kt:19-33`) — a message cannot be
  addressed to one device.

## Jellyfin facts this design relies on (confirmed against 10.10 source)
- Sessions are keyed **`Client + DeviceId`**; `Device` (name) is taken from each request's auth header.
  Same user token fanned out across several DeviceIds ⇒ separate sessions. **But multiple logins that
  REUSE one DeviceId prune that device's older tokens** — with every pairing using the shared
  `jellystructure-server-v01`, pairing a user on a second TV (or re-pairing) can invalidate the first
  TV's token. **This is the likely root cause of the 401 storm.**
- Dashboard "Send message" = `POST /Sessions/{id}/Message {Header?, Text, TimeoutMs?}` → delivered over
  **that session's WebSocket** as `GeneralCommand { Name: "DisplayMessage", Arguments: {Header, Text,
  TimeoutMs} }`. The button only appears when the session's capabilities include `DisplayMessage`.
- WS protocol: connect `wss://…/socket?api_key=<token>&deviceId=<deviceId>`; envelope
  `{MessageType, Data}`; server sends `ForceKeepAlive` (60 s) — client must send `KeepAlive` (~every
  30 s) or be dropped. **Closing the WS removes the session immediately** when it has no other sockets —
  the lever for prompt dashboard cleanup. HTTP-only idle sessions age off the dashboard after ~16 min.
- Capabilities: `POST /Sessions/Capabilities/Full {PlayableMediaTypes, SupportedCommands,
  SupportsMediaControl, …}`. `SupportsMediaControl=true` **+ an open WS** makes the session a
  remote-control target (dashboard cast menu **and** Home Assistant's Jellyfin integration, which builds
  one `media_player` per session and can `play_media` to it).
- Reporting payloads: `PlaybackStartInfo/ProgressInfo/StopInfo` with `ItemId`, `MediaSourceId`,
  `PositionTicks`, `PlaySessionId`, `IsPaused`, `PlayMethod`, `CanSeek`. NowPlaying auto-clears if no
  check-in for 5 min (progress ~every 10 s keeps it live).
- **API keys are unsuitable for these sessions** (`UserId` empty ⇒ no per-user watch-state recording, no
  user attribution) — the bridge must run on **per-user tokens**.

## Requirements

### A. Per-device Jellyfin identity
1. **Capture a device name.** The pairing start request gains a `device_name` (the TV sends its
   Android device name / model; web sends a browser label); store it in the existing
   `ravilo_device.display_name`. Existing paired devices backfill `"Ravilo TV <short-id>"` until re-paired.
2. **Per-device auth identity.** A `jellyfinAuth(token, device)` variant builds
   `MediaBrowser Client="Ravilo", Device="<display_name>", DeviceId="ravilo-<device_id>",
   Version="<app/server version>"`. Used for **everything done on behalf of that device**: pairing
   `authenticateByName` (token minting), PlaybackInfo, playback reporting, the session WS. Server-side
   admin/scan traffic keeps the existing `jellystructure-server-v01` identity.
3. **Stream URL identity:** append `&DeviceId=ravilo-<device_id>` to the direct-play/transcode URLs in
   `StreamTicket` so the media requests land on the same session.
4. Because tokens are now minted under **unique per-device DeviceIds**, Jellyfin's per-device token
   pruning can no longer cannibalise other TVs' tokens (root-cause fix for the 401 storm).

### B. Playback lifecycle that matches the TV
1. Start/progress/stop keep flowing through the existing `/api/tv/playback/*` routes but are forwarded
   with the per-device identity + per-device token, including a stable `PlaySessionId` per playback.
2. **Server-side stop watchdog:** a playback with no progress heartbeat for **90 s** (client reports
   ~10 s) is force-stopped: jellystructure sends `POST /Sessions/Playing/Stopped` at the last known
   position. The TV-events WS disconnect for that device triggers the same immediately when a playback
   is active. No more lingering "Now Playing".

### C. Per-device Jellyfin session WebSocket
1. While a device is **connected** (its `/api/tv/events` socket is open), jellystructure holds **one
   outbound WS to Jellyfin** for it: `wss://…/socket?api_key=<device's user token>&deviceId=ravilo-<id>`,
   answering `ForceKeepAlive` with `KeepAlive` (30 s), reconnecting with capped backoff. On TV
   disconnect, the WS closes → the Jellyfin session disappears promptly.
2. After connect, post `Sessions/Capabilities/Full`: `PlayableMediaTypes=["Video"]`,
   `SupportedCommands=["DisplayMessage","Play","Playstate"]`, `SupportsMediaControl=true` — enabling the
   dashboard message button, the cast/remote-control menu, and Home Assistant control **per TV**.
3. Inbound command routing (server → that device over the existing `/api/tv/events` socket):
   - `GeneralCommand DisplayMessage` → `server_message { text, header?, timeout_ms? }` (→ R152 toast).
   - `Play { ItemIds, StartPositionTicks, PlayCommand }` → `play_item { jellyfin_id,
     start_position_ms }` (→ R155).
   - `Playstate { Command: Stop|Pause|Unpause|Seek, SeekPositionTicks }` → `playstate_command` (→ R155).
   - Anything else: ignored, debug-logged.
4. **WS handler hardening rules apply** (Kotlin/Native: an escaping exception in a WS loop kills the
   process): read loops catch everything, sends are `runCatching`, reconnect is supervised — same
   pattern as `/api/tv/events` (`Server.kt:266-288`).
5. **FD budget:** these are long-lived sockets — one per connected TV, hard-capped (16) and **outside**
   `OutboundHttp`'s 24-permit pool (a permanent permit would starve it). Documented in the Phase 118 FD
   budget.

### D. Per-device event addressing
1. `TvEventBus` registry keys become `(userId, deviceId)` (the events route already authenticates the
   device token, so the deviceId is known at register time). Existing broadcasts (`config_changed`,
   `acquisition_changed`) keep user-level fan-out; new bridge events (`server_message`, `play_item`,
   `playstate_command`) are **device-addressed**.
2. The `TvEvent` envelope stays `{type, rev}` for signals; payload-bearing events follow the
   `acquisition_changed` precedent (a type-specific envelope decoded by type). Old clients that don't
   know a type fall through to the generic handler harmlessly (verified: unknown types just trigger a
   silent config re-pull today, `TvApiClient.kt:304-314`).

### E. Token health (no more silent degradation)
1. **Negative-cache** a rejected paired token (~10 min) so the double Jellyfin round-trip per request
   stops; log the rejection **once per transition**, not per call.
2. Surface it: the device row (Phase 111's device list + the Ravilo config editor's Pair-a-TV area) shows
   **"Jellyfin login expired — re-pair this user"**; the System health panel counts affected devices. The
   server-token fallback keeps the TV working meanwhile (unchanged behaviour, now visible).
3. A successful re-pair (existing flow) mints a fresh token under the per-device identity and clears the
   flag.

## Scope
- Backend: `JellyfinClient` (per-device auth header + WS client + capabilities + stop watchdog),
  `PlaybackService` (identity on ticket URLs + reporting), `RaviloDeviceService`/`TvRoutes` (device-name
  capture, negative cache), `TvEventBus` (device keying + new envelopes), `RaviloDevice.sq`
  (name backfill only — column exists).
- Shared: pairing DTO gains `device_name`; new event envelopes.
- Ravilo app: sends its device name at pairing (Settings/pairing screen); **no other app change here**
  (message/command handling is R152/R155).
- Admin FE: health-panel chip for expired tokens (device list itself is Phase 111).

## Non-goals
- No media-byte proxying — streams stay direct TV→Jellyfin (by design; only identity is added).
- No remote-control **sending** from jellystructure's own UI (Phase 111 owns the control API).
- No Jellyfin library-event listening (`LibraryChanged` ingest is Phase 114's separate, single WS).
- No transcode-decision changes (R56 negotiation untouched).

## Acceptance
- Playing on two different TVs shows **two** dashboard sessions — `Ravilo — <TV name>` each, correct
  user, correct Now Playing — and **no** "Jellystructure/Server" session for TV traffic.
- Stopping playback (or killing the app / dropping the network) clears Now Playing within ~90 s; a TV
  going offline removes its session from the dashboard promptly.
- Dashboard → Send message on a Ravilo session delivers to **that** TV (visible once R152 lands).
- Home Assistant's Jellyfin integration lists each connected Ravilo TV as a controllable media player.
- Re-pairing a user on a second TV does **not** break the first TV; a genuinely dead token produces one
  log line + a visible "re-pair" chip instead of a per-request 401 storm.
