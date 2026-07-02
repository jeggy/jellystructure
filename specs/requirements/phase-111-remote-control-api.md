# Phase 111 — Jellystructure API keys + Ravilo remote-control API (FR-RC1)

> Builds on **[Phase 110](phase-110-jellyfin-session-bridge.md)** (per-device event addressing on
> `/api/tv/events`). The app-side command handling is **[R155](../ravilo/requirements/phase-R155-remote-commands.md)**.

## Goal
Let external tools — first target **Home Assistant** — control Ravilo devices through jellystructure's
own API: create a **jellystructure API key** bound to a Jellyfin user, **list that user's Ravilo
devices**, and tell one of them to **start streaming a given Jellyfin item** ("stream Polly Piglet in Stue
TV"). Commands ride the existing per-device events socket; the TV does the playing.

## Current state (verified in code)
- **No API-key auth exists.** `AuthPlugin.kt` knows exactly two callers: the `js_session` admin cookie
  and the per-device Ravilo bearer token. `config.api_keys` holds *outbound* credentials (TMDB/Jellyfin),
  not inbound keys.
- **No device-list surface exists.** `RaviloDevice.sq` has `getByToken/getByDevice/getByDeviceAndUser` —
  no per-user listing; the Pair-a-TV modal only approves codes.
- A device is addressable the moment Phase 110 lands (`TvEventBus` keyed by `(userId, deviceId)`), and
  the app can already open the player for a Jellyfin id (`Dest.Player`, `RaviloApp.kt:118-128`).

## Requirements

### A. API keys (create · list · revoke)
1. New SQLite table `api_key`: `id`, `name`, `jellyfin_user_id` (the user the key acts as),
   `token_hash` (store a hash, never the plaintext), `created_at`, `last_used_at`, `revoked` flag.
   The plaintext token is shown **once** at creation.
2. **Settings ▸ Connections ▸ "API keys"** card: create (name + Jellyfin-user picker), list (name, user,
   created, last used), revoke. Admin-cookie-gated like the rest of Settings.
3. Auth: requests carry `Authorization: Bearer <key>` or `X-JS-Api-Key: <key>`. `AuthPlugin` accepts a
   valid key **only for the `/api/remote/**` route family** (least privilege; widening the surface later
   is a deliberate decision, not a default). Key validation is O(1)-cached like device tokens.

### B. Remote-control endpoints (`/api/remote/**`)
1. `GET /api/remote/devices` — the key's user's paired devices:
   `[{ device_id, name, connected, last_seen, now_playing? }]`. `connected` = has a live
   `/api/tv/events` socket; needs the missing `RaviloDevice.getByUser` query + the Phase 110 device
   names.
2. `POST /api/remote/play` — body `{ device_id, jellyfin_item_id, start_position_ms? }`:
   validates the device belongs to the key's user **and is connected** (else `409 device_offline`),
   then pushes a device-addressed `play_item { jellyfin_id, start_position_ms }` event. Responds `202`.
   For a **series** id, the TV resolves it to detail-then-resume (R155) — callers can pass a series,
   season-episode, or movie id and get the sensible thing.
3. `POST /api/remote/command` — body `{ device_id, command: "stop" | "pause" | "unpause" | "home" }` →
   device-addressed `playstate_command` / `navigate` event. Minimal set; extend later.
4. All remote actions are **Activity-logged** (`remote` category: key name, device, command, item).

### C. Home Assistant recipe (docs, not code)
1. Document two integration paths in the spec/README:
   - **Native (via Phase 110):** each connected Ravilo TV already appears in HA's Jellyfin integration
     as a controllable `media_player` (sessions with `SupportsMediaControl` + open WS) — `play_media`
     with a Jellyfin item id works with **zero** jellystructure-specific setup.
   - **Jellystructure REST (this phase):** HA `rest_command` examples for `/api/remote/devices` +
     `/api/remote/play` with the API key in a header — the path that also works for non-Jellyfin
     automations ("stream X in Stue TV" scripts, voice assistants, cron).
2. The two paths are complementary; the REST path is authoritative for Ravilo-specific behaviour
   (profile choice, app navigation), the native path is convenient for media-player UX in HA.

### D. Ravilo config editor: device list (admin visibility)
1. The Pair-a-TV area in `/#/ravilo` gains a **paired-devices list** per user (name, last seen,
   connected dot, **unpair**, and the Phase 110 "Jellyfin login expired — re-pair" chip). Backed by the
   same new device-list query (admin-cookie route `GET /api/tv/admin/devices?userId=`).

## Scope
- Backend: `api_key` table + store + `AuthPlugin` branch; `/api/remote/**` routes; `RaviloDevice.getByUser`;
  `TvEventBus` device-addressed sends (Phase 110); Activity logging.
- FE: Settings ▸ Connections API-keys card; Ravilo config editor device list.
- Shared: `play_item` / `playstate_command` / `navigate` event envelopes (with Phase 110).
- Docs: HA recipe.

## Non-goals
- No full remote-navigation protocol (arbitrary D-pad injection, screenshots, key events) — `play` +
  basic playstate + `home` only; extend on demand.
- No per-key scopes/permissions beyond the user binding + `/api/remote/**` fence (later if needed).
- No admin-API key access to `/api/media/**` etc. — admin automation stays cookie-gated for now.
- No queueing/casting semantics (PlayNext/PlayLast) — `play now` only.

## Acceptance
- Creating a key in Settings shows the token once; the key lists and revokes; a revoked key gets 401.
- `GET /api/remote/devices` with a valid key returns the bound user's TVs with live `connected` state.
- `POST /api/remote/play` on a connected TV starts playback of the item on that TV within ~2 s
  (R155); on a disconnected TV it returns `409 device_offline` and plays nothing.
- A HA `rest_command` following the recipe plays a movie on the named TV; the HA Jellyfin integration
  independently shows the TV as a media player (Phase 110).
- Every remote action appears in Activity with the key name.
