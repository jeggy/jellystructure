# Phase 141 — Ravilo proxied username/password login (retire code pairing) (FR-AUTH1)

> Replaces the custom code+poll+admin-approve TV pairing with a **direct username/password login on
> the Ravilo client, proxied through jellystructure to Jellyfin's `AuthenticateByName`**. Jellystructure
> becomes the session middleman: it mints and stores a **real per-user Jellyfin token** behind its own
> device token and owns the session lifecycle. This fixes the "every device is the admin user" bug at
> its root and eliminates the stale-token 401/403 log flood. Foundation for **Phase 142** (restricted-
> user filtering — needs a real per-user identity) and **Phase 143** (users/devices overview). Ravilo
> client half is **R175** (the login screen). Related: R56 (PlaybackInfo token usage), Phase 110 (the
> per-device Jellyfin identity + session bridge this corrects).

## Problem

Device pairing is broken for every non-admin user, verified against the live system: **all 24
`ravilo_device` rows are the admin user `jogvan`** (`is_admin=1`), across only **4 distinct Jellyfin
tokens**. Two compounding bugs:

1. The admin "Pair a TV" modal renders a "Sign in as" dropdown (`RaviloConfig.kt:575`,
   `#pair-user-sel`), but the "Pair TV" click handler (`RaviloConfig.kt:627`) only reads the 6-digit
   code — `RaviloApi.approvePairing(code)` (`RaviloApi.kt:118-124`) posts `{code}` with **no user id
   on the wire at all**. The selection is discarded client-side.
2. `POST /tv/pair/approve` (`TvRoutes.kt:137-196`) then, whenever a `js_session` cookie is present
   (always, for the logged-in admin), copies the **approving admin's own identity + token** into the
   pairing row and hard-sets `isAdmin = true` (`TvRoutes.kt:149-153`). The `username`+`password` branch
   (`TvRoutes.kt:154-177`), the only path that authenticates as a *chosen* user, is never reached from
   the config UI.

Because each device is handed the admin's cookie-session token (which decays), downstream calls fail:
`PlaybackService.tvToken()` (`PlaybackService.kt:387-416`) catches the REST **401** (negative-cache +
server-token fallback, logged once via `tokenRejectionLogged`), but `JellyfinSessionBridge`
(`JellyfinSessionBridge.kt:82-83`) opens `…/socket?api_key=<that same dead token>` and gets **403 on the
WS handshake**, retrying forever and re-logging on every TV events-socket flap → the
"Jellyfin session bridge dropped … 403" flood the operator sees.

Fundamentally, jellystructure cannot mint another user's token without their password (Jellyfin has no
admin-impersonation token API), so "just send the dropdown value" can never fully work — a real
credential proxy is required.

## Current state (verified)

- `jellyfinClient.authenticateByName(url, username, password, identity)` (`JellyfinClient.kt:41-65`)
  already exists and already accepts a `JellyfinDeviceIdentity` — the proxy primitive is in place.
- The pairing tables/services: `ravilo_pairing` (`RaviloPairing.sq`), `ravilo_device`
  (`RaviloDevice.sq`, PK `(device_id, jellyfin_user_id)` — already multi-user-per-device), and
  `RaviloDeviceService` (`startPairing`/`pollPairing`/`approvePairing`/`validateDeviceToken`/`unpair`).
- `AuthPlugin.kt:15-35` gates `/api/tv/**` behind a device token; `OPEN_API_PATHS` whitelists the
  pairing endpoints for the pre-token handshake.
- The current pairing mints the token under a **throwaway** identity `"ravilo-pair-<code>"`
  (`TvRoutes.kt:166`), while all later calls use `JellyfinDeviceIdentity.forDevice(device)` =
  `"ravilo-<deviceId>"` — an identity mismatch that itself risks token invalidation (Jellyfin prunes a
  DeviceId's older tokens on each new login under that id, `JellyfinClient.kt:411-416`).

## Design

### A. Proxied login endpoint
1. New **`POST /api/tv/login`** (add to `OPEN_API_PATHS`, `AuthPlugin.kt:15-35`; no device token exists
   yet). Body: `{username, password, deviceId, deviceName}`.
2. Authenticate via `jellyfinClient.authenticateByName(url, username, password, identity)` where
   **`identity = JellyfinDeviceIdentity.forDevice(deviceId/deviceName)` = `"ravilo-<deviceId>"`** — the
   *same* identity every later call (`PlaybackService`, `JellyfinSessionBridge`, `postCapabilities`)
   uses. This retires the throwaway `"ravilo-pair-<code>"` mint and its invalidation risk.
3. Derive `isAdmin`/`isKids` from the returned `policy` (as `TvRoutes.kt:176-177` already does).
   Restricted (folder-limited) users authenticate **normally** — no blocking. A disabled Jellyfin
   account simply fails `authenticateByName` (Jellyfin returns 401), surfaced as a normal "invalid
   credentials" login error; jellystructure adds no disable logic of its own.
4. Persist via a new `RaviloDeviceService.loginDevice(deviceId, deviceName, jellyfinUserId, username,
   jellyfinUserToken, isAdmin, isKids)` that **directly upserts the `ravilo_device` row** (mints/reuses
   a `device_token`, stamps `created_at`/`last_seen`) — bypassing `ravilo_pairing` entirely. Return the
   existing `PairResult` shape (`{deviceToken, TvSession}`) so the client barely changes.
5. **The password is proxied over HTTPS and never persisted or logged** — only the resulting Jellyfin
   access token is stored (identical to today's token handling).

### B. Retire code pairing
6. Remove `POST /tv/pair/{start,poll,approve}` (`TvRoutes.kt:103-196`), the admin **Pair-a-TV modal**
   (`RaviloConfig.kt:548-647`) and `RaviloApi.approvePairing` (`RaviloApi.kt:118-124`). The admin config
   editor no longer approves TVs — devices self-serve via login (R175).
7. Leave the `ravilo_pairing` table **dormant** (not dropped) — avoids a destructive migration now; a
   later cleanup phase can drop it. `unpair` / `listSessions` / `removeSession` are unchanged.

### C. Harden the session bridge (log hygiene)
8. `JellyfinSessionBridge` (`JellyfinSessionBridge.kt:69-119`) must stop hammering a dead token: gate
   the WS attempt on token validity — reuse `PlaybackService.isTokenNegativeCached(device)` /
   `tvToken()` and **skip** the connect when the token is known-rejected (and/or fall back to the server
   token like `tvToken` does). Log the "dropped" warn **once per transition** using the same
   `tokenRejectionLogged`-style `HashSet` gate (`PlaybackService.kt:38-43`), and do not reset the
   exponential backoff on an events-socket flap. With valid per-user tokens from §A the 403 disappears;
   this keeps the bridge quiet if a token later expires.

## Non-goals
- **Jellyfin QuickConnect** — considered and explicitly not chosen; the credential-proxy model is the
  decided approach.
- Storing or caching the user's password anywhere.
- Dropping the `ravilo_pairing` table or resetting the 24 stale admin device rows (they keep working as
  admin until each is re-logged-in; a later cleanup can reset them).
- Any change to how playback tokens are used at stream time (R56) beyond the identity fix in §A.
- The client login UI itself — that is **R175**.

## Acceptance
- A Ravilo client posting `{username, password, deviceId, deviceName}` to `/api/tv/login` for a
  non-admin user creates a `ravilo_device` row bound to **that** user's id + a freshly-minted Jellyfin
  token (not the admin's), and returns a working device token.
- After migrating a household, `ravilo_device` contains **distinct** `jellyfin_user_id` values.
- The password never appears in logs or the database.
- Backend logs show no repeating "session bridge dropped … 403" or repeating 401 for a freshly logged-in
  device; a later-invalidated token logs at most once per transition.
- The `/tv/pair/*` routes and the admin Pair-a-TV modal are gone; `check-phases.sh` passes.
- Verified via `compileKotlinLinuxX64` + admin `compileKotlinWasmJs`; a paired token authorizes as the
  real user against the live Jellyfin API (not the server fallback).
