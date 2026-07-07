# Ravilo session / user / device management — investigation (2026-07-07)

**Date:** 2026-07-07

**Question:** the operator asked for (a) a clear overview of which Jellyfin users have which
tokens/sessions/devices and when each was last/ever used; (b) how to handle Jellyfin users with
**restricted** (folder-limited) access in jellystructure/Ravilo; (c) why device pairing only ever binds
to the admin user no matter which user is picked — *"does our setup even work?"*; (d) whether the custom
pairing should be replaced by (or extended with) Jellyfin QuickConnect; and (e) why the backend spews a
flood of ugly session logs (`Jellyfin session bridge dropped … 403`, `paired user token rejected (401)`).

**Status: investigation only — nothing implemented here.** Everything below was verified against the live
stack (Jellyfin @ jellyfin.example.net, read-only with the admin token; the live `config/jellystructure.db`)
and the working tree on 2026-07-07. The durable recommendation has graduated into four `Planned` phase
specs — **141 / 142 / 143** (`specs/requirements/`) and **R175** (`specs/ravilo/requirements/`) — this
report is the record of the analysis behind them.

---

## 1. Live findings (verified)

### Devices & sessions (from `config/jellystructure.db`)
- **`ravilo_device`: 24 rows, every one bound to `jogvan`** (id `7450a8c6…`, `is_admin=1`). Zero other
  users have ever successfully paired.
- **Only 4 distinct `jellyfin_user_token` values across those 24 devices** (device_tokens are all
  distinct). So devices are not getting their own per-user Jellyfin token — they share a handful of the
  admin's tokens, minted across ~4 admin logins over the period.
- `created_at` / `last_seen` are stored as **epoch millis** and *are* maintained (debounced to once/min
  in `RaviloDeviceService.validateDeviceToken`) — so "device last used" already exists, just isn't
  surfaced anywhere.
- **`session` table (admin web logins): 15 rows, all `jogvan`.** Columns: `token, jellyfin_user_id,
  jellyfin_username, jellyfin_user_token, expires_at` — **no `created_at`, no `last_used`** (only
  expiry). `Session.getAll` exists but has no caller.
- `ravilo_pairing` (ephemeral challenge table): empty at inspection.
- `api_key` (remote/HA keys) is the **only** table with `created_at` + a real `last_used_at` + `revoked`,
  and the only one with a working "list + last-used + revoke" UI (the Settings API-keys card).

### Jellyfin users & access (live `/Users`)
| user | admin | EnableAllFolders | enabled folders |
|------|-------|------------------|-----------------|
| jogvan | ✔ | ✔ | (all) |
| charlotte | ✗ | ✗ | **5** |
| danjal | ✗ | ✗ | **2** |
| Test Stream | ✗ | ✗ | **3** |
| Olivar / olsen / Test Føroyskt | ✗ | ✔ | (all) |

So there are **genuinely restricted users** (charlotte / danjal / Test Stream). **QuickConnect is
enabled** on this server (`GET /QuickConnect/Enabled` → `true`), and there are **zero** references to
QuickConnect anywhere in the codebase — pairing is 100% custom.

## 2. Root cause — one broken token, three symptoms

**(a) Pairing always resolves to the admin user.** The admin "Pair a TV" modal renders a "Sign in as"
dropdown (`RaviloConfig.kt:575`) populated from real Jellyfin users, but the click handler
(`RaviloConfig.kt:627`) only reads the 6-digit code — `RaviloApi.approvePairing(code)` posts `{code}`
with **no user id on the wire at all**. Server-side, `/tv/pair/approve` (`TvRoutes.kt:137-196`) then, when
a `js_session` cookie is present (always, for the logged-in admin), copies the **approving admin's own
identity + token** into the pairing row and hard-sets `isAdmin=true` (`TvRoutes.kt:149-153`). The
`username`+`password` branch — the only path that authenticates as a *chosen* user — is never reached
from the config UI. The pick is discarded twice over. And Jellyfin has no admin API to mint another
user's token without their password, so "just send the dropdown value" cannot fully fix it.

**(b) The 401/403 log flood is the same broken token.** Each device is handed the admin's cookie-session
token, which decays. `PlaybackService.tvToken()` (`PlaybackService.kt:387-416`) handles the REST **401**
(negative-cache → server-token fallback, logged once via the `tokenRejectionLogged` HashSet). But
`JellyfinSessionBridge` (`JellyfinSessionBridge.kt:82-83`) opens `…/socket?api_key=<that same dead
token>&deviceId=ravilo-<id>` and gets **403 on the WebSocket handshake** (403 is how Jellyfin's WS
upgrade middleware surfaces a rejected token vs REST's 401). It **never** calls `tvToken()` /
`isTokenNegativeCached()` — the one Jellyfin call path that bypasses the fix already built for exactly
this — and its exponential backoff is reset to 2 s every time the TV's `/api/tv/events` socket flaps
(`Server.kt` disconnect→connect), so a flapping client produces the "many per second" flood. The read
loop *is* crash-safe (wrapped in try/catch, cancellation rethrown) — it's noise, not a process risk.
A secondary factor: pairing mints the token under a throwaway identity `"ravilo-pair-<code>"`
(`TvRoutes.kt:166`) while all later calls use `"ravilo-<deviceId>"` — Jellyfin prunes older tokens per
DeviceId on each new login under that id, so the mismatch itself can invalidate the token.

**(c) Restricted users see everything.** `JellyfinPolicy` (`auth/Models.kt:34-38`) captures **only**
`IsAdministrator` + `MaxParentalRating`. `MediaStore` serves jellystructure's own scanned catalog with
**no per-user ACL**; `HomeFeedService` / `BrowseService` / `DetailService` use `device.jellyfinUserId`
only for per-user config/watch-state, never to gate the catalog. `MediaItem` has **no library
association** at all. So a restricted user would see the whole library and 403 at playback on blocked
titles.

**Does the setup work?** Partially — only ever as the admin (that's why playback works at all; it falls
back to the admin/server token), and the admin tokens decay, producing the flood. It has never worked as
any non-admin user.

## 3. Decisions taken (operator)

1. **Auth:** retire the code+poll+admin-approve pairing entirely. Ravilo logs in with **username +
   password on the client, proxied through jellystructure to Jellyfin `AuthenticateByName`** —
   jellystructure is the session middleman, minting/storing a real per-user token behind its own device
   token. **QuickConnect explicitly not chosen** (even though it's enabled and would also work).
2. **Restricted users:** stay **first-class** — they log in and use Ravilo normally; jellystructure just
   learns their Jellyfin library access and **filters the catalog** to it. Never block/disable anyone (a
   disabled Jellyfin account already can't authenticate at Jellyfin, so no jellystructure handling is
   needed).
3. **Also build:** a Users & Devices overview + admin web-session tracking.

## 4. Recommended design (→ graduated into specs)

Feasibility was pre-verified: `jellyfinClient.authenticateByName(...)` already accepts a
`JellyfinDeviceIdentity`; `Scanner.scanItem` (`Scanner.kt:59-85`) already resolves each item's owning
`LibraryMapping` (whose `jellyfinId` **is** the Jellyfin library ItemId that `EnabledFolders` lists) by
path prefix — so item→library is one captured field away.

- **Phase 141 (FR-AUTH1) — proxied login + retire pairing + bridge hygiene.** New `POST /api/tv/login`
  (`{username, password, deviceId, deviceName}`) authenticating under the device's **real**
  `ravilo-<deviceId>` identity; `RaviloDeviceService.loginDevice` upserts the device row directly (skips
  `ravilo_pairing`); password proxied over HTTPS, never stored/logged. Remove `/tv/pair/{start,poll,
  approve}` + the admin Pair-a-TV modal. Harden `JellyfinSessionBridge` to skip/relax on a known-dead
  token (reuse `tvToken`/negative-cache) and log once per transition.
- **Phase 142 (FR-AUTH2) — restricted-user catalog filtering.** Extend `JellyfinPolicy` with
  `EnableAllFolders`/`EnabledFolders`; add `MediaItem.libraryId` captured at scan (+ a one-time
  path-prefix backfill, no re-scan); thread an allowed-library predicate through
  `HomeFeedService`/`BrowseService`/`DetailService`/playstate. Filtering, never blocking.
- **Phase 143 (FR-AUTH3) — Users & Devices overview + admin-session tracking.** Add
  `created_at`/`last_used_at` to `session`; a cookie-gated `GET /tv/admin/overview` aggregating all users
  → devices + web sessions; a new "Users & devices" Settings tab mirroring the API-keys card, with
  per-item revoke + sign-out-everywhere.
- **R175 (FR-RV-AUTH1) — Ravilo login screen.** Replace the code `PairingScreen` with a username/password
  `LoginScreen` wired into the existing `MultiTokenStore`/`ProfilePicker`/`ProfileMenu` "Add user" flow.
  (R174 was already taken by grid-columns, hence R175.)

**Sequence:** 141 first (real per-user tokens; kills the flood) → 142 (needs the per-user identity +
policy) → 143 (reads best once devices carry genuine per-user rows). R175 is the client half of 141.

## 5. Notes / open considerations
- The 24 stale admin device rows keep working as admin until each is re-logged-in; a one-time reset may
  be wanted when 141 ships.
- The `libraryId` backfill must run **before** 142's filtering activates, or already-scanned items (no
  `libraryId`) would be wrongly hidden.
- QuickConnect remains a viable alternative if the password-proxy UX proves clunky on a TV remote — it's
  enabled server-side and would yield the same valid per-user tokens without password entry, at the cost
  of the user approving on their own phone in Jellyfin's web UI.
- Parental-rating item filtering is out of scope for 142 (only library-level access); a later phase could
  add it.

*(A fuller implementation-level breakdown lives in the session plan file that seeded these specs; the
phase specs 141–143 + R175 are the durable, maintained version.)*
