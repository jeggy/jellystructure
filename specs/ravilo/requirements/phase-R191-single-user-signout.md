# Phase R191 — Sign out a single profile without unpairing the whole TV

> A Ravilo TV can already have **several Jellyfin profiles signed in at once** (a genuine
> multi-profile "who's watching" model, not a single active session) — the data model, backend
> tokens, and client-side session store all already support it correctly. What's missing is a
> **real per-profile sign-out**: today's "Sign out" button silently does nothing but navigate to
> the login screen, and the only action that actually revokes anything ("Unpair this TV") always
> signs out **every** profile on the device. This phase wires up the sign-out affordance that
> already *should* exist, using backend plumbing that mostly already does too.

## Status
Implemented (2026-08-08).

### Implementation notes (2026-08-08)
- **FR-R191-1/2 (real single-profile Sign out):** added `signOutSession(userId)` to the shared
  `TvApiClient` (calls `DELETE /api/tv/sessions/{userId}` under the caller's own active token) and
  `signOutActiveSession(apiClient)` in `ravilo-ui` (`SettingsScreen.kt`) — best-effort revoke
  (`runCatching`, mirrors `unpairAllSessions`'s own shape) then `MultiTokenStore.remove(userId)` +
  `PlaybackPrefsStore.clearProfile(userId)` for the active profile only, returning whether any
  session remains. Wired into both existing "Sign out" surfaces: `SettingsScreen`'s confirm dialog
  (previously a bare `onSignOut()` no-op) and, newly, a "Sign out" row added to the avatar
  `ProfileMenu` dropdown (previously Unpair-only) — both share the new `SignOutConfirmOverlay`.
  `RaviloApp.kt`'s `onSignOut`/`onSignedOut` callbacks now check `MultiTokenStore.getAll()` after the
  revoke and route to `ProfilePicker` if another cached profile remains, else `Login`.
- **FR-R191-3 (un-conflate copy):** `profile.sign_out` dropped "/ Unpair"; `settings.sign_out_confirm`/
  `_desc` now name the profile (`"Sign out of {name}?"`) and state other profiles are unaffected;
  added `pm.sign_out`. Done for en/da/fo. `settings.unpair_desc`'s existing "every signed-in profile"
  wording was already unambiguous and needed no change.
- **FR-R191-4 (Tizen parity):** `ravilo-tizen`'s `ProfileMenuScreen.signOut()` now also calls
  `app.api.signOutSession(session.userId)` (best-effort) before the local removal it already did.
- **FR-R191-5 (route docs):** added `GET /api/tv/sessions` / `DELETE /api/tv/sessions/{userId}` to
  `specs/ravilo/plan.md`'s route table.
- **Unplanned but fixed in passing — a real, pre-existing "Unpair this TV" bug**: while wiring the
  new sign-out call next to `unpair()`, found that `TvApiClient.unpair()` POSTed to
  `/api/tv/unpair`, but the server has only ever registered `/api/tv/pair/unpair`
  (`TvRoutes.kt:280`) — confirmed via `plan.md`'s own route table, which documented the same wrong
  path. Every "Unpair this TV" call was silently 404ing (the local store was cleared regardless, so
  nothing user-visible ever surfaced the failure — the device always *looked* unpaired locally even
  though its server-side token stayed live). Fixed the client path and corrected `plan.md`'s
  documented route to match the server's actual one.
- **Open decisions from the original spec left unresolved, as planned:** no `ProfileTile` remove
  affordance was added to `ProfilePickerScreen`, and `ProfilePickerScreen` still doesn't reconcile
  against `GET /api/tv/sessions` — both remain explicit follow-ups, not required for this phase's
  core ask.

## Problem — three concrete, verified gaps

1. **"Sign out" is currently a no-op that lies about what it does.** `RaviloApp.kt` wires both
   `HomeScreen`'s and `SettingsScreen`'s `onSignOut` to `resetTo(Dest.Login)` (`RaviloApp.kt:638`,
   `:1074`) — it navigates to the login screen and nothing else. It does not touch
   `MultiTokenStore`, does not clear `PlaybackPrefsStore`, and makes no backend call — the
   "signed out" profile's `LocalSession` (including its still-valid `deviceToken`) stays cached in
   `localStorage`/`SharedPreferences` exactly as before, and its server-side `ravilo_device` row is
   untouched. `HomeScreen.kt`'s own doc comment (`:535-537`) claims Sign out "clears the local
   session and best-effort revokes it server-side" — that description matches a *different*
   function (`unpairAllSessions`, see #2) and is simply wrong for what `onSignOut` actually calls.

2. **The one action that *does* revoke anything always revokes everyone.** `unpairAllSessions`
   (`SettingsScreen.kt:116-123`) loops `apiClient.unpair()` (→ `POST /tv/pair/unpair`,
   `TvRoutes.kt:280-288`) over **every** cached `LocalSession` and then `MultiTokenStore.clear()`s
   the whole store. This is the right behavior for "Unpair this TV" (the destructive,
   whole-device action users should reach for rarely) — but it is currently the *only* real
   sign-out path in the primary `ravilo-ui` module (used by both Android and web/wasm targets per
   `phase-R175-login-screen.md:106-107`), so a household with two profiles signed into one TV has
   no way to remove just one without kicking the other one off too. The product's own copy
   reflects this conflation: `profile.sign_out` = `"Sign out / Unpair"` and
   `settings.sign_out_confirm` = `"Sign out?"` (`Strings.kt:120,190,192`) treat "sign out" and
   "unpair" as one idea.

3. **The backend already has the right per-profile primitive — it's just never called.** The
   `ravilo_device` table's PK is `(device_id, jellyfin_user_id)` (`RaviloDevice.sq:1-21`; fixed in
   migration `20.sqm` after a real PK-collision incident, see project history) — one physical
   device legitimately holds N rows, each with its own unique `device_token`. `removeSession(deviceId, userId)`
   (`RaviloDeviceService.kt:177-186`, `deleteByDeviceAndUser`) already deletes exactly one such row
   and is already exposed at `DELETE /tv/sessions/{userId}` (`TvRoutes.kt:306-313`) — but that route
   has **zero callers** anywhere in `ravilo-ui`/`ravilo-tizen`, isn't documented in
   `specs/ravilo/plan.md`'s route table, and the admin side (Phase 143's `DELETE
   /tv/admin/devices/{deviceId}?userId=`, confirmed per-(device,user) in that spec's dev-review
   addendum) is the *only* thing that currently exercises this exact revoke shape. The
   `ravilo-tizen` module's own `signOut()` (`ravilo-tizen/.../ProfileMenuScreen.kt:44-60`) comes
   closest to correct client behavior — `MultiTokenStore.remove(session.userId)` then routes to
   `ProfilePickerScreen` or `LoginScreen` depending on what's left — but it's **local-only**: it
   never calls the backend, so the revoked profile's server-side token silently keeps working.

## Goal
Pressing "Sign out" while profile **A** and profile **B** are both signed into the same TV signs
**A** out — revoking A's session server-side and forgetting it client-side — while B stays fully
signed in and unaffected, on both `ravilo-ui` (Android + web/wasm) and `ravilo-tizen`. "Unpair this
TV" remains available as the separate, clearly-labeled, whole-device action it already is.

## Current state (verified against live code)
- **Data model already supports this**: `ravilo_device` composite PK `(device_id,
  jellyfin_user_id)`, one `device_token` per row (`RaviloDevice.sq:1-21`).
- **Server has no "active profile" concept** — every `/api/tv/**` request is independently
  authenticated by whichever bearer `device_token` it carries (`AuthPlugin.kt:120-136`,
  `RaviloDeviceService.validateDeviceToken`, `:113-149`); multiple profiles are concurrently valid
  purely because the client holds multiple tokens, one per `LocalSession`.
- **Client already holds one token per signed-in profile, not one swapped token**:
  `MultiTokenStore` (`ProfilePickerScreen.kt:57-64`; wasm actual `MultiTokenStoreWasm.kt`, Android
  actual `MultiTokenStoreAndroid.kt`) stores an array of `LocalSession`s plus which one is
  "active"; `TvApiClient`'s `deviceToken` lambda (`shared/.../TvApiClient.kt:27`) just reads
  whichever one is currently active.
- **The exact backend call this phase needs already exists and is already used by the admin
  console**: `RaviloDeviceService.removeSession(deviceId, userId)` (`RaviloDeviceService.kt:177-186`)
  → `DELETE /tv/sessions/{userId}` (device-token-gated, `TvRoutes.kt:306-313`) on the TV-facing
  side, `DELETE /tv/admin/devices/{deviceId}?userId=` (`TvRoutes.kt:603-611`) on the admin side —
  same underlying method, different auth tier.
- **The exact client-side local-removal call this phase needs already exists too, just unwired**:
  `ProfilePickerStore.removeSession(userId)` (`ProfilePickerScreen.kt:86-90`) calls
  `MultiTokenStore.remove(userId)` + `PlaybackPrefsStore.clearProfile(userId)` — grepping
  `ravilo-ui` finds no call site for it anywhere.
- **`ProfileTile` has no remove affordance** (`ProfilePickerScreen.kt:192-239`) — only `onSelect`
  (switch to that profile) is wired.

## Requirements

### A. A real single-profile "Sign out"
#### FR-R191-1 — Sign out revokes and forgets exactly the active profile
`onSignOut` (currently `HomeScreen.kt`'s and `SettingsScreen.kt`'s wiring in `RaviloApp.kt:638,1074`)
must, for the **currently active** `LocalSession` only:
1. Best-effort call the backend to revoke that profile's session — reuse the existing
   `DELETE /tv/sessions/{userId}` route (self-service form: the caller's own bearer token already
   identifies `deviceId`; pass the active profile's own `userId`), not `POST /tv/pair/unpair`
   (which revokes the *token used to make the call*, i.e. itself, but was designed/named for the
   whole-device unpair flow — a distinct route keeps the two actions' server-side code paths as
   separate as their product meaning already is, and matches what the admin side already does via
   `removeSession`).
2. Remove **only** that profile's entry from `MultiTokenStore` and clear only its
   `PlaybackPrefsStore` data — i.e. actually call the existing (currently unwired)
   `ProfilePickerStore.removeSession(userId)` logic, not `MultiTokenStore.clear()`.
3. Navigate based on what's left locally: if another cached `LocalSession` remains, go to
   `ProfilePickerScreen` (so the household can pick up as the other profile immediately, no
   re-entering credentials); if none remain, go to `Login`.

#### FR-R191-2 — Best-effort revoke, never blocks the sign-out
If the backend revoke call fails (offline TV, transient network error, server already down), the
local removal (step 2) and navigation (step 3) still happen — mirroring the existing
`runCatching` shape in `unpairAllSessions` (`SettingsScreen.kt:118`). A user must never be stuck
unable to sign out of a profile because of a network blip; the stale server-side token is simply
revoked later (e.g. by the Phase 143 admin console, or the next time the app can reach the
backend — an idle/best-effort reconciliation is not required by this phase, see Non-goals).

### B. Keep "Unpair this TV" as the distinct, clearly-labeled whole-device action
#### FR-R191-3 — Un-conflate the copy
Split the existing conflated strings (`profile.sign_out = "Sign out / Unpair"`,
`settings.sign_out_confirm = "Sign out?"`, `Strings.kt:120,190,192`) into two distinct, honestly-
labeled actions with distinct confirmation copy:
- **Sign out** (this phase's new behavior) — confirm copy names the profile being signed out
  (e.g. "Sign out of {displayName}?") and, when other profiles remain locally, should make clear
  they're unaffected.
- **Unpair this TV** (existing `unpairAllSessions` behavior, unchanged) — confirm copy makes the
  "every profile on this TV" scope explicit (e.g. "Unpair this TV? Every signed-in profile will be
  signed out.").
Both affordances keep their existing locations (`ProfileMenu`, `SettingsScreen`) — this is a
copy/semantics fix, not a navigation redesign.

### C. Parity across both Ravilo client targets
#### FR-R191-4 — `ravilo-tizen`'s sign-out also revokes server-side
`ravilo-tizen`'s existing `ProfileMenuScreen.signOut()` (`ProfileMenuScreen.kt:44-60`) already does
the correct **local** half (remove just the active profile, route to picker or login depending on
what's left) — add the same best-effort backend revoke call from FR-R191-1 so a Tizen sign-out
stops leaving a live, unrevoked `device_token` on the server. This brings `ravilo-tizen` to parity
with `ravilo-ui` rather than introducing new behavior there.

### D. Route surface documentation
#### FR-R191-5 — Document the now-load-bearing routes
`GET /tv/sessions` and `DELETE /tv/sessions/{userId}` (`TvRoutes.kt:291-313`) are currently absent
from `specs/ravilo/plan.md`'s `/api/tv/**` route table (`plan.md:104-121`), which only lists
`POST /tv/login` and `POST /api/tv/unpair`. Once this phase makes `DELETE /tv/sessions/{userId}`
a real, called-in-production route (rather than orphaned admin-only-adjacent plumbing), add both to
that table.

## Invariants (must not change)
- **`POST /tv/pair/unpair` and `unpairAllSessions`'s "revoke every cached session" behavior are
  unchanged** — "Unpair this TV" stays a real, available, whole-device action.
- **No server-side "active profile" concept is introduced** — sign-out is expressed purely as
  "delete this one `(device_id, jellyfin_user_id)` row," consistent with how the admin console
  already models a session.
- **A revoked token 401s exactly as any other missing/invalid token does today**
  (`AuthPlugin.kt:133`, TV returns to login) — no change to that fallback.
- **Jellyfin credentials are never stored** (existing constitution invariant) — sign-out never
  needs them; it only ever deletes a `ravilo_device` row/token.

## Open design decisions
- **Should `ProfilePickerScreen`'s `ProfileTile` also gain a remove affordance** (e.g. a
  long-press/overflow "Remove" on a profile you're *not* currently active as, using this same
  `removeSession(deviceId, userId)` call)? The backend and `ProfilePickerStore.removeSession`
  plumbing already support removing any co-signed profile on the same device, not only the active
  one — this would be a natural, low-cost companion to FR-R191-1 using the exact same call, but is
  not required to satisfy the core ask ("sign out without signing everyone out") and is left as a
  follow-up rather than blocking this phase.
- **Reconciling the local profile list against server truth.** `ProfilePickerScreen` derives its
  grid purely from local `MultiTokenStore.getAll()`; if a profile was already revoked elsewhere
  (e.g. an admin used Phase 143's console, or a *different* device removed it), the picker still
  shows it as selectable until the stale token 401s at time of use. `GET /tv/sessions` (also
  currently unwired) could opportunistically reconcile this, but doing so is not required for the
  core single-profile-sign-out ask and is left as a follow-up.
- **Exact route shape for the self-service revoke** — reusing `DELETE /tv/sessions/{userId}`
  as-is (caller's own token supplies `deviceId`, path supplies `userId`) is the recommended,
  lowest-risk choice since it already exists, is already exercised by the admin path via the same
  service method, and needs no new backend code — but confirm during implementation that nothing
  about its current (currently dead-code) behavior needs adjusting now that it has a real caller.

## Reuse (don't rebuild)
- `RaviloDeviceService.removeSession(deviceId, userId)` and `DELETE /tv/sessions/{userId}` —
  already implemented, already correct, just uncalled.
- `ProfilePickerStore.removeSession(userId)` — already implemented, already correct, just unwired.
- `ravilo-tizen`'s `signOut()` local-removal-and-navigate logic — reuse the same shape for
  `ravilo-ui`'s FR-R191-1, and only add the missing backend call to `ravilo-tizen`'s existing
  version (FR-R191-4).
- `unpairAllSessions`'s `runCatching`-per-token best-effort pattern — mirror it for the new
  single-profile revoke call (FR-R191-2).

## Non-goals
- **No change to `POST /tv/pair/unpair` or the "Unpair this TV" flow's actual behavior** — copy
  only (FR-R191-3).
- **No background/periodic reconciliation of stale local sessions against server truth** — see
  Open design decisions; a stale entry still safely 401s and falls back to login exactly as today.
- **No change to how a profile is *added*** (`LoginScreen`, `MultiTokenStore.add`) — unaffected.
- **No new admin-console behavior** — Phase 143's admin revoke paths are unchanged; this phase only
  gives the *TV itself* an equivalent for the profile that's using it.
- **No change to the server's per-request auth model** — still no "active profile" concept
  server-side; every request is independently authenticated by its own bearer token.

## Acceptance
- With profiles A and B both signed into one TV, choosing "Sign out" while A is active: A's
  `ravilo_device` row/token is deleted server-side (verifiable via Phase 143's admin overview or a
  direct `GET /tv/admin/overview` check), A's `LocalSession` is gone from `MultiTokenStore`, and
  the TV lands on the profile picker showing only B — B can be selected and used immediately, no
  re-login.
- With only A signed in, "Sign out" revokes A and lands on the `Login` screen (no profiles left).
- "Unpair this TV" still signs out every profile and still requires its own (now more explicit)
  confirmation.
- Triggering "Sign out" with no backend connectivity still removes the local session and navigates
  correctly (best-effort revoke doesn't block the UI flow).
- `ravilo-tizen`'s sign-out now also revokes server-side, not just locally.

## Source references
- Schema/PK: `src/commonMain/sqldelight/dev/jellystructure/db/RaviloDevice.sq:1-21`,
  migration `db/20.sqm`.
- Backend service: `src/linuxX64Main/kotlin/dev/jellystructure/tv/RaviloDeviceService.kt`
  (`loginDevice` :46-111, `validateDeviceToken` :113-149, `unpair` :151-154, `listSessions` :157-174,
  `removeSession` :177-186, `deleteAllForUser` :208-216).
- Backend routes: `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/TvRoutes.kt`
  (`/tv/login` :205-278, `/tv/pair/unpair` :280-288, `GET /tv/sessions` :291-304,
  `DELETE /tv/sessions/{userId}` :306-313, admin routes :603-700).
- Auth gating: `src/linuxX64Main/kotlin/dev/jellystructure/auth/AuthPlugin.kt:120-136`.
- Client store: `ravilo-ui/src/commonMain/.../screens/ProfilePickerScreen.kt` (`LocalSession` :47-54,
  `MultiTokenStore` expect :57-64, `ProfilePickerStore.removeSession` :86-90, `ProfileTile` :192-239);
  wasm actual `MultiTokenStoreWasm.kt`; Android actual `MultiTokenStoreAndroid.kt`.
- Client wiring to fix: `ravilo-ui/.../RaviloApp.kt:638,1074` (`onSignOut`),
  `ravilo-ui/.../screens/SettingsScreen.kt:105-145,184,193-216` (`unpairAllSessions`, sign-out UI),
  `ravilo-ui/.../screens/HomeScreen.kt:535-544`, `ravilo-ui/.../components/ProfileMenu.kt:57,75-141,124-140`.
- `ravilo-tizen` precedent to extend: `ravilo-tizen/src/jsMain/.../ProfileMenuScreen.kt:44-60`.
- Copy to split: `ravilo-ui/.../i18n/Strings.kt:120,190,192`.
- Admin-side consistency reference: `specs/requirements/phase-143-users-devices-overview.md`
  (dev-review addendum on `removeSession` being per-(device,user)).
- Route table to update: `specs/ravilo/plan.md:104-121`.

## Relationships
- **Complements Phase 143** (admin Users & Devices overview) — reuses its exact
  `removeSession(deviceId, userId)` service method so the TV-side and admin-side revoke stay
  identical in shape; this phase does not change Phase 143.
- **Consistent with the R175 login/multi-profile model and the constitution's per-(device,user)
  device-identity invariant** — no changes to how sign-in or token issuance works, only to how a
  single profile's token is torn down.
- `scripts/check-phases.sh` will want a `STATUS.md` row — **STATUS.md is code-owned; do not add the
  row from the design side.**
