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

## Design addendum (2026-07-09, design-side — mockup shipped, not yet dev-reviewed)

Mockup: **`design/app/settings.html?tab=users`** — a "Users & devices" tab between Download tools and
Notifications (Phase-55 tab machinery; header carries summary chips `N users · N devices · N connected
now` + a Refresh button). Per §C it mirrors the API-keys card interaction model. Per-user group card:
avatar + name + short Jellyfin id, badges (`admin` / `restricted` / `kids`), then:

1. **Access line (extends §B/§C).** A read-only chip row mirroring the user's **full Jellyfin policy**:
   library access (`3 of 5 libraries · Movies · Series · Dansk TV`), **allowed / blocked tags**
   (`blocked tags horror · true-crime`, `allowed tag kids-safe only`) and **max rating**. Needs
   `AllowedTags` / `BlockedTags` / `MaxParentalRating` deserialized on `JellyfinPolicy` (display-only
   here; Phase 142 *enforces* libraries only — see the 142 addendum for the enforcement follow-up).
2. **Now watching.** An accent strip under the header when the user has a live session: title, device,
   play method, progress bar + position. Source: Jellyfin `GET /Sessions` (`NowPlayingItem` +
   `PlayState`, admin token), point-in-time on load/refresh — no push.
3. **Recently watched.** A history section in the same table: title/episode · when · device · a
   `✓ finished` / `stopped at N%` badge. Source: per-user played items sorted by `DatePlayed`
   (`UserData.LastPlayedDate` / `PlayedPercentage`). Two rules keep binge-scale history scannable:
   **consecutive episodes of one series group into a single row** (`Havets Hjarta · S01 · E01–E08 ·
   29 Jun – 6 Jul · ✓ 8 episodes`), and the table shows ~4 recent entries with an inline **"Show N
   more ▾"** expander (footer notes the month's play count and that full history lives in Jellyfin).
4. **Destructive actions.** Two-step confirm: the button itself morphs (`Revoke` → `Revoke — sure?`,
   auto-reverts after ~3.5 s); the admin's *current* web session warns `Revoke — signs YOU out?`.
   **Sign out everywhere** empties the whole group into an explanatory empty state.
5. **Entry point.** The Ravilo config editor's pagebar links here (its Pair-a-TV button/modal is
   removed per Phase 141 §B6).

API delta vs §B: the overview response additionally needs the per-user **policy summary**, **now
playing**, and **recent plays** (suggest: fold `access` + `nowPlaying` + the first history page into
`GET /api/tv/admin/overview`; lazy `GET /api/tv/admin/users/{userId}/history?offset=` behind the
expander). All Jellyfin reads are read-only; jellystructure still never mutates accounts or policies.

## Dev-review addenda (2026-07-09) — reconciled with live code + DB

Verified against the working tree and the live `config/jellystructure.db`. This section **supersedes** the
spec body/addendum where they differ.

### Verified (accurate as written)
- Live `session` table is exactly `token, jellyfin_user_id, jellyfin_username, jellyfin_user_token,
  expires_at` — **no `created_at`, no `last_used`** (15 rows, all `jogvan`). §Problem is accurate.
- `RaviloDeviceService` has `listByUser/listSessions/removeSession/unpair`; **`allDevices()` does not exist**
  and `RaviloDevice.sq` has no all-rows query — §B's new query is genuinely new. `created_at`/`last_seen` on
  `ravilo_device` are real (debounced once/min).
- `TvEventBus.isConnected(deviceId)` gives live connected state (it is `suspend`); already used by
  `/tv/admin/devices`. Phase-55 tab machinery (`data-tab` + `?tab=`) slots a new `users` tab in cleanly.

### Corrections to apply
- **§A1 double-counts existing work.** `session.getAll` **and** `session.deleteByToken` already exist, and
  `SessionService.revoke(token)` already wraps the delete — do **not** "add revoke-by-token". What is
  genuinely new: the two columns, a `SessionService.list()` wrapper over the existing `getAll`, and the
  `last_used_at` bump. The debounce reference is mis-named: `RaviloDeviceService.updateLastSeen` is a SQL
  query; the real analog is the **inline once/min guard inside `validateDeviceToken`**. `SessionService`
  has **no in-memory cache**, and `validate()` is called on **every** cookie request (`AuthPlugin.kt:99`)
  without touching the row — so the bump needs a **new in-memory `lastWritten` map**, or it becomes a SQLite
  UPDATE per request.
- **Stale anchors:** API-keys card is **`Settings.kt:1580-1655`** (not 1582-1649) and its revoke is
  **one-click** — the addendum's two-step "Revoke — sure?" morph is **net-new** behaviour, not "mirror the
  card". Admin cookie gate is **`AuthPlugin.kt:97-111`** (not 75-95; 75-95 is the device-token branch).
- **`DELETE /tv/admin/devices/{deviceId}` also requires `?userId=`** and revokes **one `(device_id,
  jellyfin_user_id)` row** via `deleteByDeviceAndUser` — it is a per-(device,user) revoke, not a whole-device
  delete. That is actually correct for the user-grouped overview (each row is a pairing), but the spec's
  route signature and "for a device" wording must say so.
- **"Sign out everywhere" needs new queries that don't exist.** Neither `session` nor `ravilo_device` has a
  `deleteByUser`. Either add both, or loop (`getAll`→filter→`deleteByToken`; `listByUser`→`removeSession`).
  Call this out in §B3.
- **Web sessions are admin-only.** `/auth/login` 403s non-admins (`AuthRoutes.kt:56-62`), so **every**
  `session` row is an admin. In the user-grouped view, only admin users ever have a web-sessions sub-list;
  restricted users show **devices only**. The mockup/§C must not render a web-sessions group for non-admins.

### Architecture — the addendum over-reaches on live Jellyfin reads
The design addendum sources "Now watching" from Jellyfin `GET /Sessions` and folds per-user history into the
base overview. Both cut against the project's standing line (*"Ravilo/admin uses jellystructure's own store;
Jellyfin = streaming only"*) and the outbound-FD ceiling (all Jellyfin calls pass `OutboundHttp.withPermit`,
Semaphore 64; CIO dies at FD ≥ 1024). Reconcile as follows:
- **Now watching (Ravilo devices): needs ZERO new outbound.** jellystructure already tracks live Ravilo
  playback locally — `PlaybackService.activePlayback` / `nowPlayingItem(deviceId)`, already surfaced by
  `/tv/admin/devices` and `/api/remote/devices`. Source the strip from there (extend the accessor for title
  via `MediaStore.resolveByJellyfinId`, position, and — new — play method, which `startPlayback` knows as
  `needsTranscode` but doesn't store). Reserve `GET /Sessions` as an **explicit opt-in** only if catching
  **non-Ravilo** playback (phone/web) is truly wanted — and note it is then the sole new outbound call.
- **Access line (policy summary): FREE.** `getUsers()` → `GET /Users` already returns each user's full
  `Policy` inline, so the summary adds **no** call — just more deserialized fields.
- **Recently watched: the only section that must read Jellyfin live** (there is no local play-history store —
  playstate is read live per fetch). Keep it **strictly lazy per expanded user** (`GET
  /api/tv/admin/users/{userId}/history?offset=`); do **not** fan it out across all users on the base
  overview (N-users × a call each against the 64-gate). Needs `LastPlayedDate` added to `JellyfinUserData`
  (only `PlayedPercentage`/`Played`/position today) and a new `Filters=IsPlayed&SortBy=DatePlayed` call. The
  "group consecutive episodes" rule can't fully group across a pagination boundary (minor caveat); the
  "month play count" footer needs an extra count query or an approximation.

### JellyfinPolicy field coordination (with Phase 142)
The addendum's "add `AllowedTags`/`BlockedTags`/`MaxParentalRating`" is partly wrong: **`MaxParentalRating`
already exists** on `JellyfinPolicy` — only `AllowedTags`/`BlockedTags` are missing (both real; `AllowedTags`
is Jellyfin **10.9+**, nullable-safe). `MaxParentalRating` is an **Int score**, not a label — the "max
rating" chip needs a score→label map jellystructure does not have. And 142 is **also** adding
`EnableAllFolders`/`EnabledFolders` to the same 2-field model — the two specs must describe **one** consistent
`JellyfinPolicy` (142 owns the folder fields; 143 owns the tag fields; `MaxParentalRating` already present).

### Design gaps
- **Self-revoke.** Revoking your own `js_session` (or "sign out everywhere" on your own admin user) kills your
  current cookie → next request 401 → bounced to login. `AuthPlugin` already puts the live `SessionData`
  (incl. its `token`) into `SessionKey`, so the backend can mark **"(this session)"** and the FE can warn
  before firing (the addendum's "Revoke — signs YOU out?" is implementable). Require both.
- **Plan.md:** `specs/plan.md` §"API Routes › Auth" should gain `GET /api/tv/admin/overview`, the revoke
  routes, and `POST /api/tv/admin/users/{userId}/signout-all`; note the two new `session` columns.
