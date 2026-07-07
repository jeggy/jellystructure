# Phase 143 — Users & Devices admin overview + web-session tracking (FR-AUTH3)

> One admin surface that shows **every Jellyfin user → all their Ravilo devices and admin web sessions**,
> with when each was created / last used / currently connected, and per-item **revoke** + **sign out
> everywhere**. Also fills the gap that admin web sessions track only expiry today. Completes the
> auth arc (**Phase 141** proxied login, **Phase 142** restricted filtering) with the management view.
> Related: Phase 55 (URL-addressable Settings tabs), Phase 110/111 (`/tv/admin/devices`, `TvEventBus`
> connected-state), the existing API-keys card (the working "list + last used + revoke" pattern to
> mirror).

## Problem

There is no way to see, across all users, who has which sessions/devices/tokens and when they were last
used — the exact question the operator asked. Concretely:

- **Three separate token systems, no unified view.** Admin web sessions (`session` table,
  `SessionService`), TV device tokens (`ravilo_device`, `RaviloDeviceService`), and remote API keys
  (`api_key`) never appear together. Only the **API-keys card** (`Settings.kt:1582-1649`) surfaces a
  real "list + last used + revoke" — the other two have no such screen.
- **Admin sessions track only `expires_at`** (`Session.sq`): no `created_at`, no `last_used`, and
  although `getAll` exists it has no caller. So "when did this admin session last act" is unknowable.
- **The device-list API is orphaned.** `GET /tv/admin/devices?userId=` + `DELETE
  /tv/admin/devices/{deviceId}` (`TvRoutes.kt:455-480`) exist but have **zero frontend callers**, and
  are per-user only (require an explicit `userId`) — there is no all-users overview.
- No "sign out everywhere" action anywhere in the product.

`ravilo_device` does keep `created_at` + `last_seen` (debounced to once/min in
`RaviloDeviceService.validateDeviceToken`), so device last-used already exists — it just isn't surfaced.

## Current state (verified)

- `RaviloDeviceService` already has `listByUser(jellyfinUserId)`, `listSessions(deviceId)`,
  `removeSession(deviceId, userId)`, `unpair(deviceToken)`.
- `TvEventBus.isConnected(deviceId)` gives live connected state (already used by `/tv/admin/devices`).
- `SessionService` has `create`/`validate`/`revoke` + a `Session.getAll` query; `AuthRoutes` sets/reads
  the `js_session` cookie. Admin routes are cookie-gated under `/api/tv/admin/**` (`AuthPlugin.kt:75-95`).

## Design

### A. Admin web-session timestamps
1. Add `created_at` + `last_used_at` to the `session` table (`Session.sq`), populated on create and
   bumped in `SessionService.validate` (debounced to once/min, mirroring
   `RaviloDeviceService.updateLastSeen`). Add list + revoke-by-token queries/methods.

### B. Aggregation API
2. New cookie-gated `GET /api/tv/admin/overview` returning, grouped by Jellyfin user:
   - the user's `ravilo_device` rows — device name, `created_at`, `last_seen`, `connected`
     (`TvEventBus.isConnected`), `is_admin`, `is_kids`;
   - the user's **admin web sessions** — created, last used, expiry.
   Reuse `listByUser`; add a `RaviloDevice.allDevices()` query (all rows, grouped by user in the service)
   and use `Session.getAll`. Supersedes the orphaned per-user `/tv/admin/devices`.
3. New revoke routes: reuse `DELETE /tv/admin/devices/{deviceId}` for a device; add
   `DELETE /tv/admin/sessions/{token}` (or by id) for a web session; add
   `POST /tv/admin/users/{userId}/signout-all` that revokes **all** of a user's device tokens + web
   sessions.

### C. Admin frontend
4. A new **"Users & devices"** Settings tab (Phase-55 URL-addressable tabs, `Settings.kt`) listing each
   Jellyfin user with their devices + sessions: name, created, last-seen/last-used, a **connected**
   badge, a per-row **Revoke**, and a **Sign out everywhere (this user)** button. Mirror the API-keys
   card's markup/interaction (`Settings.kt:1582-1649`). New `RaviloApi` calls for overview + the revoke/
   signout routes. Read-only where it should be; destructive actions confirm before firing.

## Non-goals
- Editing Jellyfin users, passwords, or policies (jellystructure never mutates Jellyfin accounts).
- Surfacing remote **API keys** in this tab — they already have their own card; this tab is
  users/devices/web-sessions. (A later pass could unify all three.)
- Real-time push of the overview — a normal fetch/refresh is fine (connected-state is a point-in-time
  read of `TvEventBus`).
- Historical/audit logging of past sessions beyond created/last-used timestamps.

## Acceptance
- The Users & devices tab lists **every** Jellyfin user with all their devices and web sessions, each
  showing created + last-used/last-seen + a live connected badge.
- Revoking a device removes its `ravilo_device` row (that TV must re-login); revoking a web session
  invalidates that cookie; "sign out everywhere" clears all of a user's device tokens **and** web
  sessions in one action.
- Admin web sessions now record and display `created_at` + `last_used_at` (previously only expiry).
- `check-phases.sh` passes; verified via `compileKotlinLinuxX64` + admin `compileKotlinWasmJs`.
