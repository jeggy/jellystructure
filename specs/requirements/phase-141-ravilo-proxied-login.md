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

## Design addendum (2026-07-09, design-side)

§B6 is reflected in the mockups: the config editor's Pair-a-TV button, modal, code inputs and their
CSS/JS are removed from `design/app/ravilo-config.html`; the pagebar slot now holds a **"Users &
devices"** link to `settings.html?tab=users` (Phase 143) — the operator's replacement surface for
"which TVs are signed in". The TV-side login screen this phase enables is mocked per **R175** (see its
addendum).

## Dev-review addenda (2026-07-09) — reconciled with live code + DB

Verified against the working tree, the live `config/jellystructure.db`, and the Jellyfin behaviour the
codebase already documents. This section **supersedes** the spec body where they differ.

### Verified (accurate as written)
- **The problem is real and current.** Live `ravilo_device` = **24 rows, every one `jogvan` / `is_admin=1`,
  across only 4 distinct Jellyfin tokens, 24 distinct `device_id`s** — matches §Problem exactly.
  `ravilo_pairing` is empty (dormant already). §Problem's two-bug root cause is line-accurate.
- `authenticateByName(baseUrl, username, password, identity)` exists and already sends the DeviceId in the
  `MediaBrowser … DeviceId="…"` auth header — the proxy primitive is in place.
- `RaviloDeviceService` has `startPairing/pollPairing/approvePairing/validateDeviceToken/unpair`;
  `loginDevice` does **not** exist yet (correct). `insertDevice` is `INSERT OR REPLACE` on PK
  `(device_id, jellyfin_user_id)`, so a direct upsert works with no PK-collision crash.
- `JellyfinSessionBridge`'s read loop is **already crash-safe** (incoming wrapped in `runCatching`, outer
  `catch(Throwable)` rethrows only `CancellationException`, keepalive send `runCatching`). §C must
  **preserve** this, not regress it.

### ⚠ Headline design gap — multi-user Add-user collides with Jellyfin's per-DeviceId token pruning
This is the one substantive hole and it is **not** noted anywhere in the spec/research report. `forDevice`
mints the Jellyfin identity as **`"ravilo-<deviceId>"` — one DeviceId per *physical device*** (see
`JellyfinClient.kt:422-428`). But the Phase-110 KDoc there states, from live observation: *"Jellyfin prunes
a DeviceId's older tokens on every new login under that same id."* So on a shared TV, when a second person
does **Add user** (R175) under the same physical `deviceId`, Jellyfin **prunes the first profile's token** →
switching back to profile 1 → `tvToken()` 401 → falls back to the admin/server token → the exact class of
bug this arc exists to kill, re-created for multi-user.
- **Fix:** the Jellyfin login identity must be **unique per (device, user)**, not per physical device — e.g.
  `"ravilo-<deviceId>-<username>"` (the username is known at login time, before we learn the userId, so it
  can seed the DeviceId; a client-generated per-profile slot id also works). Keep the **jellystructure**
  `ravilo_device.device_id` as-is (physical-device grouping / the composite PK) — only the *Jellyfin*
  DeviceId needs the per-user suffix. Phase 143 groups by user, not by physical device, so it is unaffected.
- **Bridge implication:** `JellyfinSessionBridge` opens `…/socket?deviceId=ravilo-<id>` — it must use the
  **active profile's** per-user identity (the DeviceId the active token was minted under), or the 403 returns.
- This needs a decision from the dev team; **recommend per-(device,user) identity.** Update §A2 accordingly.

### Corrections to apply
- **Stale anchors:** `authenticateByName` is **`JellyfinClient.kt:59-83`** (not 41-65); the "prunes older
  tokens per DeviceId" statement is the **`JellyfinDeviceIdentity` KDoc at 422-428** (not 411-416, which is
  `getNextUp`); `tvToken()` is **`PlaybackService.kt:388-417`** (not 387-416); `isTokenNegativeCached` is at
  421. `tokenRejectionLogged` is at **line 43** and is **file-`private`**, and `tvToken`/`isTokenNegativeCached`
  are **package-level functions**, not members of `class PlaybackService` — so §C8's bridge **cannot reuse
  the same `HashSet`** (it needs its own once-per-transition gate) and `tvToken()` needs a server token the
  bridge must source from `configStore` (it has none today).
- **§A2 signature:** `JellyfinDeviceIdentity.forDevice(...)` takes a **`DeviceData`**, not
  `(deviceId, deviceName)` strings — at login no `DeviceData` exists yet. Construct
  `JellyfinDeviceIdentity("ravilo-<deviceId>-<user>", deviceName)` directly (or add a
  `forDevice(deviceId, user, deviceName)` overload). As written §A2 will not compile.
- **§A1 / OPEN_API_PATHS:** add `/api/tv/login` **and remove** the now-dead `/api/tv/pair/{start,poll,approve}`
  entries (current list: `/api/auth/login`, `/api/setup`, the three pair paths, `/api/tv/events`,
  `/api/tv/channel-logos/`, `/api/tv/image/`, `/api/webhooks/`).
- **§A4 return type:** `loginDevice` should return the **domain `Pair<DeviceData, String>`** (device + token)
  the way `pollPairing` does; the route builds the `PairResult` DTO. It should `getByDeviceAndUser` first to
  reuse an existing `device_token`/`created_at` (token stability), else mint a new one.
- **§B6 removal boundary:** `post("/unpair")` lives **inside the same `route("/tv/pair"){ … }` block
  (spanning 103-207)**; §B7 keeps unpair, so the removal is **start/poll/approve only — the `route` wrapper
  and `/unpair` stay**. "Remove `TvRoutes.kt:103-196`" is not a clean wholesale delete.
- **Base URL:** state explicitly that the credential proxy is **server-side** — `authenticateByName` runs
  against `configStore.current.apiKeys.jellyfinUrl` (existing blank-URL → 503 guard reused); the **client
  sends only `{username, password, deviceId, deviceName}`** and never sees the Jellyfin URL. (Note: whether
  that hop is HTTPS depends on the configured Jellyfin URL — the spec's "over HTTPS" is only true if the
  operator configured an https URL; on a LAN it is often plain http. Reword to "proxied server-side, never
  persisted or logged" rather than asserting HTTPS.)
- **Error taxonomy (for R175's two error states):** `authenticateByName` throws `IllegalArgumentException`
  on Jellyfin **401**, `IllegalStateException` on other non-2xx, and the blank-URL guard returns **503**. Map
  401 → "invalid credentials", 503/throw → "server unreachable / not configured". Disabled Jellyfin accounts
  surface as 401 (matches §A3).

### Non-goals / stale-rows clarification
The 24 stale admin rows are **not** superseded by re-login. A migrating device logs in with a *new*
`(deviceId, realUserId)` row; the old `(oldDeviceId, jogvan)` row is never matched by the upsert and
**lingers as an orphan** until explicitly removed (Phase 143's revoke, or a one-time reset). Reword the
Non-goal ("they keep working as admin until each is re-logged-in") to say they **persist as orphans**.

### Docs to reconcile when this ships (spec wins, but these must not silently contradict it)
- **`specs/ravilo/constitution.md` §"Authentication & device pairing" (161-177)** — the invariant currently
  reads *"Sign-in uses a **pairing-code flow** … no password is typed on the TV"* (pt 2), *"the TV returns
  to pairing"* (pt 5), *"the TV never performs Jellyfin sign-in itself"* (pt 6). Points **2, 5, 6 are
  directly contradicted** by this arc and must be rewritten to the proxied username/password login
  (password still never stored/sent-onward; token still minted server-side — those halves survive).
- **`specs/ravilo/plan.md`** — route table lines **114-116** (`/api/tv/pair/{start,poll,approve}`) → replace
  with `POST /api/tv/login`; the `PairingChallenge` DTO (line 75) orphans; "Auth header = the device token
  from pairing" (103) → "…from login"; the `ravilo_device` description (147) drops its "Pairing" note; the
  Screens list (163) `Pairing` → `Login`.
- **Cross-spec seam with Phase 142:** 142 §A2 assumes "the policy is already fetched at login (Phase 141)"
  and persists the allowed-library set on the device row — but **this spec does not currently fetch or
  persist `policy.enabledFolders`**. Either 141's `loginDevice` must also capture the folder access (add it
  here), or 142 owns that fetch. Make the hand-off explicit in one of the two specs.
