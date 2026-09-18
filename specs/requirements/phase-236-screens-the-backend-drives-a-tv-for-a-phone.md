# Phase 236 — Screens: the backend drives a TV for a phone

> Owner direction 2026-09-18: the most important use case is an iPhone that has installed the Ravilo
> web app streaming to a Samsung TV in a house where jellystructure is not on the LAN. No browser on an
> iPhone can launch a Chromecast, and a stream that lives in the phone's own `<video>` dies with the
> app. So the TV becomes the player, the phone becomes a remote, and **the backend is where they meet**:
> a receiver-only TV app (R264) holds its own device identity, plays what the backend pushes and reports
> back; the phone (R265) lists its TVs, starts a title, and drives it — entirely through the server. The
> phone can be closed, die, or be a different phone; the picture continues. This is the Chromecast
> receiver model (218 / R245) with Google removed from the middle, and most of it already exists.

## Status

`Planned` — written 2026-09-18 from the research report `ravilo-web-pwa-player-cast-2026-09-18.md`
(§5, §12) and a trace of `TvEventBus.kt`, `RemoteRoutes.kt`, `CastService.kt`, `PlaybackService.kt`
and `RaviloDeviceService.kt`. Not dev-reviewed, not built. Backend + shared DTOs. Pair: **R264** (the
receiver app) and **R265** (the phone remote). Nothing here changes the Android TV client, the Chromecast
receiver's Cast-namespace path, or phase 111's API-key routes.

**Numbering:** verified against `STATUS.md` and the spec directories 2026-09-18 — admin taken through
**235**, Ravilo through **R263**.

## Owner decisions this phase encodes (2026-09-18)

1. **Receiver-only TV app**: no navigation on the TV; one more media player with everything a player has
   (transport, seek, audio/subtitles, subtitle size, next episode, skip intro/credits), fully controllable
   from the phone.
2. **Every TV of that Jellyfin user is listed**, in two sections: TVs **on the same network as the phone**
   first, then a collapsible **all your TVs**. "Same network" is *only if possible* — it is (FR-236-6).
3. **AirPlay stays**, one tier further down, with a notice that the phone must stay on (R265).
4. **The entry point is the Cast glyph** on the phone, even though the TV is not a Chromecast (R265).
5. **One API for everyone (added later the same day).** jellystructure already lets API-key callers
   control Ravilo devices (phase 111, `/api/remote/**`). That surface becomes *the* device-control API:
   API users get in-depth management of every Ravilo device, and the Ravilo PWA and Android app use
   **the exact same routes** with their ordinary device-token authentication instead of an API key.
   No parallel `/api/tv/screens` API exists.

## Current state (traced against `main`, 2026-09-18)

- **Push to a device already exists.** `TvEventBus` sends over each device's `/api/tv/events` socket:
  `play_item {jellyfin_id, kind, start_position_ms}`, `playstate_command {command: Stop|Pause|Unpause,
  seek}`, `navigate`, `server_message`, `config_changed`, `playstate_changed`. Phase 111's
  `RemoteRoutes.kt` exposes `GET /remote/devices`, `POST /remote/play`, `POST /remote/command`
  (`stop|pause|unpause|home`) to **API-key** callers only.
- **Reporting exists.** Every playing device heartbeats `PlaybackProgressRequest {position_ms,
  is_paused}` every 10 s (`PROGRESS_INTERVAL_MS`); `PlaybackTracker` keys sessions by `(deviceId,
  jellyfinId)` and the stop watchdog reaps after `STOP_WATCHDOG_MS = 90_000`. A Chromecast receiver is
  judged by heartbeat alone (218 amendment 2026-09-18). Start samples, QoE and the decode ceiling (185 /
  R216) are per device.
- **Enrolment exists.** `CastService.mint(phone)` issues a 6-character code bound to the phone's
  `(deviceId, jellyfinUserId)`; `redeem(code, …)` creates the receiver's `ravilo_device` row **with a
  session for that user** (the phone's Jellyfin token, ACL, kids flags copied — `loginDevice`). Phase 141
  makes device identity per `(device, user)`: `listSessions(deviceId)` returns one session per paired
  user, so one TV can be paired by several household members.
- **The client address is already read.** `TvRoutes.kt:246/416/572` take `X-Forwarded-For`'s first hop
  (Caddy) or `origin.remoteHost`.
- **What does not exist:** phone-facing routes under the device token; a status report richer than the
  heartbeat (R245's `CastRemoteStatus` today travels phone↔dongle over the Cast namespace, invisible to
  the backend); a status stream to the phone; the "which user is this play for" binding when a device has
  several sessions; the same-network grouping.

## Requirements

**FR-236-1 · A screen is a device kind, not a name prefix.** `ravilo_device` gains `kind`
(`tv | phone | web | cast | screen`), migrated from today's `DEVICE_PREFIX` naming and R252's `platform`;
a receiver app enrols as `screen` with `platform` `tizen-screen` / `webos-screen`. Everything below
applies to `screen` and, unchanged in behaviour, to `cast` (a Chromecast receiver is a screen that Google
launched).

**FR-236-2 · Pairing is the hand-off code, once per user per TV.** The receiver's idle screen shows its
code whenever it is not playing; a phone enters it (R265) and `redeem` **adds a session for that user to
the existing device** (`receiverId` reuse) instead of minting a new device — the TV keeps one identity
and one name across users. A user un-pairs from Settings → Users & devices (existing revoke). The 6-char
code, TTL and sweep are 218's; nothing new is invented.

**FR-236-3 · One device-control API, two credentials.** Phase 111's `/api/remote/**` is extended, not
duplicated, and `AuthPlugin` accepts **either** an API key **or** a device token on it; both resolve to
the same principal — a `jellyfinUserId` (an API key is bound to one user, `ApiKeyData.jellyfinUserId`;
a device session is per `(device, user)`). Every route below scopes to that user's devices and behaves
identically for Home Assistant and for a phone. Existing phase 111 shapes stay valid (a Home Assistant
integration written against them keeps working); new fields and commands are additive.
- `GET /api/remote/devices` → the existing list, each device extended with `kind` (`tv | phone | web |
  cast | screen`), `platform`, `online`, `paired_users` (names, for screens), `now_playing: ScreenStatus?`
  (was a title string; the string stays as `now_playing_title` for compatibility), and `nearby: Boolean` —
  FR-236-6's judgement relative to **the caller** (an API caller from the LAN gets the same grouping a
  phone would). The phone renders `nearby` as its first tier; it never computes it.
- `POST /api/remote/play { device_id, jellyfin_item_id, start_position_ms }` → 202 as today; now carries
  the caller's user into `play_item` (FR-236-4); 404 if the device holds no session for the caller; 409
  `device_offline` as today, and **409 `busy`** with the current `ScreenStatus` when another user's play is
  running (the phone shows *"{name} is playing something for {user}"*; never silently hijacks).
- `POST /api/remote/command { device_id, command, … }` — the existing `stop | pause | unpause | home`
  plus `seek {position_ms}`, `skip {delta_ms}`, `next`, `previous`, `set_audio {index}`, `set_subtitle
  {index | null}`, `set_subtitle_size {S|M|L}`, `cancel_next_up`, `skip_segment` (the current intro/credits
  segment), `set_volume {0–100}` / `mute` (screens and TVs that can — a phone or web device answers 409
  `unsupported`). Pushed as the existing `playstate_command`/`navigate` where they fit, else the new
  `player_command {command, args}` event. Only the user whose play is running may command it.
- `GET /api/remote/devices/{device_id}` → one device with its full `ScreenStatus` snapshot.
- **Status stream.** `GET /api/remote/events` (WebSocket, either credential) pushes
  `{"type":"device_status", device_id, status}` on every change for **all** of the caller's devices — this
  is what an API user subscribes to. A Ravilo client may instead send `{"type":"subscribe_device",
  device_id}` on its **own** `/api/tv/events` socket (one authenticated, reconnecting socket per client)
  and receives the same `device_status` messages; `unsubscribe_device` or the socket closing ends it.
  Constitution: server-pushed state only — a remote never advances a position by a local clock.
- `DELETE /api/remote/devices/{device_id}/sessions/me` → un-pair the caller's user from a shared screen
  (the Settings revoke, exposed).
- Rate limits and logging as phase 111 (`"remote"` logger names the key *or* the device).

**FR-236-4 · The play carries the user; the TV never holds a credential.** `play_item` gains
`session_user_id`. The receiver starts playback with its device token as today; the backend resolves the
Jellyfin identity for `/playback/start`, progress, stop, QoE and Continue Watching from **the session
named by the last `play_item`** for that device, not from "the device's user". A device with one session
behaves exactly as today. A `stop` or a reaped session clears the binding.

**FR-236-5 · Status is reported by the receiver and fanned out by the backend.** New
`POST /api/tv/playback/status` (device token), body = R245's `CastRemoteStatus` fields as a
`ScreenStatus` DTO in `shared`: `item_id, title, kicker, art_url, position_ms, duration_ms, playing,
buffering, loaded, ended, has_next, next_up_secs, next_title, busy_retry_after, no_server, audio_tracks,
subtitle_tracks, selected_audio, selected_sub, sub_size, transcoding, session_user_id`. Sent **on every
change** and at least every 5 s while loaded (position ticks). The backend keeps the last status per
device in memory beside `PlaybackTracker`, stamps `reported_at`, and pushes it to subscribers. The 10 s
progress heartbeat stays as the watchdog's liveness signal and the playstate write path; status is
display state, never written to Jellyfin.

**FR-236-6 · "On this network" is decided by the server from public addresses.** Each heartbeat, status
report and events-socket open stamps the device's `last_public_address` (first `X-Forwarded-For` hop,
else remote host). `GET /api/remote/devices` marks a device `nearby` when its address matches the caller's:
IPv4 exact, IPv6 by `/64`. Stated limits, in the spec and in the admin help text: carrier-grade NAT can
group two houses (harmless — the list is already filtered to the user's own TVs), a phone on a VPN or
mobile data groups nothing (the TV is then simply in *others*). No LAN probing from the phone (an `https`
page cannot reach a TV's local HTTP anyway) and no location permission, ever.

**FR-236-7 · Reconnect is one question.** A phone rebuilds its remote from `GET /api/remote/devices`
(`now_playing` present ⇒ mini bar; absent ⇒ nothing), exactly R245 FR-R245-5's two outcomes, with no
SDK session resumption involved.

**FR-236-8 · The receiver is judged by its heartbeat, the phone by nothing.** No watchdog looks at the
phone. A screen whose heartbeat stops for 90 s is reaped as today (phase 180 teardown: Jellyfin stop,
encode released) and its status cleared; subscribers get a final `screen_status` with `loaded=false`.
The events socket closing does **not** end a play (the 218 amendment's rule, extended to screens).

**FR-236-9 · Ceilings and limits.** A screen counts under 218's `max_sessions` receiver ceiling only when
`platform` is a cast receiver; a Tizen/webOS screen counts like a TV (one transcode per playing device).
Phase 182's 503 + `Retry-After` reaches the phone as `busy_retry_after` in status (R245 FR-R245-9's
*Server busy* state). Rate-limit `/api/remote/*` per credential as phase 111 does.

**FR-236-10 · Admin.** Settings → Users & devices lists screens with `kind`, platform, paired users and
*now playing*; revoke per user. The Chromecast card (218 / 226 / 227) is untouched.

**FR-236-11 · Every Ravilo device honours the whole command set and reports status.** The API's promise
is only true if the *targets* keep it. The Android TV client, the phone app and the web build already
act on `play_item`, `playstate_command` and `navigate` (`RaviloApp.kt`); they gain handlers for
`player_command` (next/previous, audio and subtitle selection, subtitle size, cancel next-up, skip
segment, volume where the platform allows) and **post `ScreenStatus`** from the player exactly as the
receiver does (FR-236-5) — so an API user, or a phone acting as a remote, sees the same buffering / track
list / next-up countdown for a living-room Android TV as for a Tizen screen. Client-side this lands in
`ravilo-ui` commonMain (`PlayerStore`, one status reporter shared by every platform); it is listed here
because it is the API's contract, and it is the last thing that makes phase 111 "in-depth" rather than
play/pause/stop. A device that cannot honour a command answers via status, never silently.

**FR-236-12 · The API is documented as a product.** `specs/plan.md`'s API routes section and the phase
111 documentation gain the full `/api/remote/**` contract (both credentials, every command, the status
DTO, the WebSocket), plus one worked Home Assistant example (`media_player` play / pause / seek / select
subtitle from `device_status`). Admin → Settings → Advanced's API-key card links to it.

## Acceptance

- Unit: `screens` grouping (IPv4 exact, IPv6 /64, CGNAT case documented), the multi-session binding
  (`play_item` from user B on a TV paired by A and B plays under B; user C gets 404), the 409 on a busy
  screen, the status fan-out to two subscribed phones, the reap clearing status.
- e2e (mock stack): a fake screen device enrols with a code, opens the events socket, receives `play_item`
  with `session_user_id`, posts status; a phone device (device token) lists it with `nearby=true` (same
  test-runner address), commands `seek`, and its events socket receives `device_status` with the new
  position; **the same three calls with an API key** succeed with identical bodies; an API key of another
  user gets 404; phase 111's original `command: "pause"` body still works.
- Live (with R264/R265): Pixel 9 Chrome → Stue TV running the receiver → play, pause, seek, subtitle
  change, close the app, reopen → mini bar with the live position.
- `scripts/check-phases.sh` green.

## Non-goals

- No launching or waking a TV (nothing on the LAN); the viewer starts the receiver with the TV remote.
  A SmartThings-cloud launch is a separate investigation.
- No Chromecast protocol in the backend (the launcher, 237/R268 in the report) — not needed for this
  route.
- No breaking change to phase 111's shapes (Home Assistant keeps working); they are extended in place.
- No AirPlay code (R265; it needs nothing from the backend).

## Open questions

1. **Status transport for the phone:** its own events socket with subscribe/unsubscribe (lean — one
   socket, already authenticated, already reconnecting) vs opening `/api/remote/events` like an API
   user. Both must exist for API users anyway; the question is only what the phone does.
2. **Should a screen be visible to users who have never paired it** (household mode)? Owner said
   filtered by user; pairing-per-user keeps it explicit. Revisit if pairing three phones per TV proves
   tedious.
3. **IPv6 prefix width** — `/64` is the common home delegation; a `/56` household would still match per
   subnet, which is fine.

## Dev notes

- `redeem`'s `receiverId` reuse already exists for "storage survived between casts"; FR-236-2 widens it
  to "any user pairs the same device". Guard: a code redeemed from a device id whose name/platform differ
  from the existing row is a new device, not a hijack of someone's TV.
- `TvEventBus` messages are hand-built JSON strings; add `player_command` and `screen_status` the same
  way, and a `session_user_id` field to `play_item` (absent ⇒ today's single-session behaviour).
- Keep `ScreenStatus` in `shared` so the receiver (Kotlin/JS), the phone (Compose) and the backend share
  one serializer — the R245 lesson where the receiver declared fields the backend never read.
