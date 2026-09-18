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

**FR-236-3 · Phone-facing routes under the device token.**
- `GET /api/tv/screens` → `{ nearby: [Screen], others: [Screen] }` for the calling user (only devices
  holding a session for that `jellyfinUserId`). `Screen = { device_id, name, platform, online, last_seen,
  now_playing: ScreenStatus? }`. `online` = heartbeat or events socket seen within 90 s.
- `POST /api/tv/screens/{device_id}/play { jellyfin_id, start_position_ms }` → 202, pushes `play_item`
  (FR-236-4). 404 if the screen holds no session for the caller; 409 with the current `ScreenStatus` if
  another user's play is running on it (the phone shows *"{name} is playing something for {user}"*, R245's
  busy shape — never silently hijacks).
- `POST /api/tv/screens/{device_id}/command { command, … }` with the full set R245 needs: `pause`,
  `unpause`, `seek {position_ms}`, `skip {delta_ms}`, `next`, `stop`, `set_audio {index}`, `set_subtitle
  {index | null}`, `set_subtitle_size {S|M|L}`, `cancel_next_up`. Pushed as `playstate_command` (existing
  ones) or the new `player_command {command, args}` event. Only the user whose play is running may
  command it; 409 otherwise.
- `GET /api/tv/screens/{device_id}/status` (one snapshot) and a subscription on the phone's **own**
  events socket: `{"type":"subscribe_screen","device_id":…}` → the backend pushes `{"type":"screen_status",
  device_id, status}` on every change until `unsubscribe_screen` or the socket closes. Constitution:
  server-pushed state only — the remote never advances a position by a local clock.

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
else remote host). `GET /api/tv/screens` puts a screen in `nearby` when its address matches the caller's:
IPv4 exact, IPv6 by `/64`. Stated limits, in the spec and in the admin help text: carrier-grade NAT can
group two houses (harmless — the list is already filtered to the user's own TVs), a phone on a VPN or
mobile data groups nothing (the TV is then simply in *others*). No LAN probing from the phone (an `https`
page cannot reach a TV's local HTTP anyway) and no location permission, ever.

**FR-236-7 · Reconnect is one question.** A phone rebuilds its remote from `GET /api/tv/screens`
(`now_playing` present ⇒ mini bar; absent ⇒ nothing), exactly R245 FR-R245-5's two outcomes, with no
SDK session resumption involved.

**FR-236-8 · The receiver is judged by its heartbeat, the phone by nothing.** No watchdog looks at the
phone. A screen whose heartbeat stops for 90 s is reaped as today (phase 180 teardown: Jellyfin stop,
encode released) and its status cleared; subscribers get a final `screen_status` with `loaded=false`.
The events socket closing does **not** end a play (the 218 amendment's rule, extended to screens).

**FR-236-9 · Ceilings and limits.** A screen counts under 218's `max_sessions` receiver ceiling only when
`platform` is a cast receiver; a Tizen/webOS screen counts like a TV (one transcode per playing device).
Phase 182's 503 + `Retry-After` reaches the phone as `busy_retry_after` in status (R245 FR-R245-9's
*Server busy* state). Rate-limit `/screens/*` per device like the other TV routes.

**FR-236-10 · Admin.** Settings → Users & devices lists screens with `kind`, platform, paired users and
*now playing*; revoke per user. The Chromecast card (218 / 226 / 227) is untouched.

## Acceptance

- Unit: `screens` grouping (IPv4 exact, IPv6 /64, CGNAT case documented), the multi-session binding
  (`play_item` from user B on a TV paired by A and B plays under B; user C gets 404), the 409 on a busy
  screen, the status fan-out to two subscribed phones, the reap clearing status.
- e2e (mock stack): a fake screen device enrols with a code, opens the events socket, receives `play_item`
  with `session_user_id`, posts status; a phone device lists it under `nearby` (same test-runner
  address), commands `seek`, and its events socket receives `screen_status` with the new position.
- Live (with R264/R265): Pixel 9 Chrome → Stue TV running the receiver → play, pause, seek, subtitle
  change, close the app, reopen → mini bar with the live position.
- `scripts/check-phases.sh` green.

## Non-goals

- No launching or waking a TV (nothing on the LAN); the viewer starts the receiver with the TV remote.
  A SmartThings-cloud launch is a separate investigation.
- No Chromecast protocol in the backend (the launcher, 237/R268 in the report) — not needed for this
  route.
- No change to phase 111's API-key routes (Home Assistant keeps working); they may later be re-expressed
  over these.
- No AirPlay code (R265; it needs nothing from the backend).

## Open questions

1. **Status transport for the phone:** the phone's own events socket with subscribe/unsubscribe (lean —
   one socket, already authenticated, already reconnecting) vs a second socket per screen.
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
