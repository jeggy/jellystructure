# Phase R175 — Ravilo username/password login screen (replaces code pairing) (FR-RV-AUTH1)

> The Ravilo-client half of the auth overhaul (**Phase 141** is the backend). The TV/phone stops showing
> a pairing **code** and instead shows a **username + password LoginScreen** that posts credentials to
> jellystructure's new `POST /api/tv/login` proxy. Wires straight into the existing multi-user plumbing:
> a successful login appends a profile, and `ProfileMenu → Add user` re-opens the same screen. Restricted
> users just render whatever the server serves — no client-side ACL. Related: R18/R57 (multi-user +
> pairing), R170 (ProfileMenu). **Numbering note:** R174 is taken (grid columns), so this is **R175**.

## Goal

Sign in to Ravilo with a real Jellyfin username + password entered on the device, so each profile is a
genuine per-user session (fixing the "every TV is the admin" problem end-to-end). Adding a second person
to a shared TV is just another login.

## Current state (verified)

- `PairingScreen.kt` shows a 6-char **code** (`PairingState.Waiting`) and polls
  `apiClient.pollPairing` until an operator approves it in the jellystructure admin web; on approval it
  stores `TokenStore.set(deviceToken)` + appends a `MultiTokenStore.add(LocalSession(...))`
  (`PairingScreen.kt:80-95`). There is **no** username/password field, no QuickConnect.
- Multi-user support already exists: `MultiTokenStore` (localStorage `ravilo_sessions`/
  `ravilo_active_user`), `LocalSession(userId, displayName, deviceToken, isAdmin, isKids, avatarUrl)`
  (`ProfilePickerScreen.kt:47-64`), `ProfilePickerScreen`, and `ProfileMenu → onSwitchProfile`
  (`ProfileMenu.kt:51-101`). The server PK `(device_id, jellyfin_user_id)` already models several users
  per device.
- `TvApiClient.startPairing()/pollPairing()` (+ shared `PairingChallenge`/`PairResult` DTOs) drive the
  current flow.

## Requirements

### A. LoginScreen (replaces PairingScreen)
1. A **LoginScreen** with **Username** + **Password** fields and a **Sign in** action, using Ravilo's
   existing on-screen keyboard component (the same one Search uses) for D-pad text entry; the password
   field masks input. Reuse `PairingScreen`'s error/expiry/loading state shapes where they still apply
   (`PairingState.Starting/Errored`, an "signing in…" state, an error state) and drop the code-specific
   `Waiting/Expired` states.
2. On **Sign in**, generate/read the device's stable `deviceId` (persisted; the identity the server mints
   the Jellyfin token under, per Phase 141) and call a new `TvApiClient.login(username, password,
   deviceId, deviceName)` → `POST /api/tv/login`. On success (`PairResult`), `TokenStore.set(deviceToken)`
   + `MultiTokenStore.add(LocalSession(...))` and navigate in, exactly as `pollPairing` did today
   (`PairingScreen.kt:82-93`). On failure, show the login error (invalid credentials / server
   unreachable). No polling, no code.
3. Remove `TvApiClient.startPairing/pollPairing` (and the code UI) once `login` replaces them.

### B. Add-user / multi-user
4. `ProfileMenu → Add user` (and the empty-profile initial-launch path) open the **same** LoginScreen;
   each successful login appends another `LocalSession` and switches the active profile to it — the
   existing `ProfilePicker`/switch machinery is unchanged (it already keys off `MultiTokenStore`).
5. A user who signs out of a profile (existing `unpairAllSessions`/`removeSession` paths) removes that
   `LocalSession`; nothing else changes.

### C. Restricted users are transparent to the client
6. The client applies **no** access logic. A restricted user's Home/Browse/Search/Detail simply contain
   only what the server (Phase 142) chose to serve — the UI renders it normally, no empty-state special-
   casing needed.

## Non-goals
- QuickConnect or any code-based flow (removed).
- Storing the password on-device — only the returned device token + `LocalSession` are persisted (as
  today).
- Any catalog/ACL filtering on the client (that is server-side, Phase 142).
- Redesigning `ProfilePicker`/`ProfileMenu` — only the entry point changes from "pair a code" to "log in".
- Backend auth, session management, or the removal of `/tv/pair/*` — that is **Phase 141**.

## Acceptance
- On first launch (no profiles), Ravilo shows the LoginScreen; entering a valid Jellyfin username +
  password signs in and lands on Home as **that** user (not the admin).
- `ProfileMenu → Add user` opens the LoginScreen; a second user can be added and switched to on the same
  device; each profile carries its own device token / `LocalSession`.
- A restricted user (e.g. `charlotte`) signs in and sees a fully working Ravilo scoped to her libraries
  (content filtered server-side per Phase 142) with no errors or blank rails.
- Invalid credentials show a clear error; no pairing code appears anywhere.
- Verified via `:ravilo-ui:compileKotlinWasmJs` **and** `:ravilo-android:compileReleaseKotlin` (the real
  TV release target); on-device sign-in confirmed after a rebuild/deploy.

## Design addendum (2026-07-09, design-side — mockup shipped, not yet dev-reviewed)

Mocked directly in the main TV mockup (`design/ravilo/ravilo-app.js` + `ravilo.css` + `ravilo-i18n.js`);
the old pairing-code panel, its `.pair-code` CSS and pairing copy are removed — no code appears anywhere.

1. **LoginScreen** (§A1): a centered panel — title "Sign in to Jellyfin", one-line explainer, Username +
   Password fields (masked with `•`, blinking cursor in the active field, accent ring marks which field
   receives input) — with a D-pad **on-screen keyboard** below: digits row, three letter rows ending in
   `- . _ @`, and wide `⇧ Shift · Space · ⌫ Delete` keys. Enter on a field re-targets typing; the
   existing row-based D-pad grid handles navigation unchanged. Physical/remote keyboards also type
   directly (printable keys + Backspace); Escape/Back returns to the profile gate or switcher it came
   from.
2. **States** (§A1/A2): idle → `Signing in…` (spinner on the primary button, input locked) → success
   (profile appended + switched, toast `Signed in as {name}`) or an inline error banner — empty-field
   prompts and "Wrong username or password — check them and try again" (mockup demos it with password
   `wrong`).
3. **Entry points** (§B4): first-run gate, the profile switcher's `＋ Add user` tile, and a new
   **`＋ Add user` row in the R170 ProfileMenu** all open the same LoginScreen.
4. **i18n**: all login strings added in en/da/fo (`login_*`, `key_*`).

## Dev-review addenda (2026-07-09) — reconciled with live code

Verified against `ravilo-ui`/`shared`. This section **supersedes** the requirements/addendum where they differ.

### Verified (accurate as written)
- The multi-user plumbing exists: `LocalSession(userId, displayName, deviceToken, isAdmin, isKids,
  avatarUrl)`, `MultiTokenStore` (an **`expect object`** — `localStorage` on wasm, **SharedPreferences on
  Android**), `ProfilePickerScreen`, `ProfileMenu → onSwitchProfile`. The server PK `(device_id,
  jellyfin_user_id)` models several users per device.
- `LoginScreen` in **commonMain** compiles for both targets; `:ravilo-android:compileReleaseKotlin` is a real
  task and pulls all screens from `ravilo-ui` commonMain. §Acceptance's compile targets are valid as written.
- §C (restricted users are transparent to the client) is correct and honours "render server-pushed state
  only" — no client ACL.

### ⚠ Two load-bearing premises are false in the current code
1. **There is no reusable on-screen keyboard "the same one Search uses".** `SearchScreen` uses a
   **`BasicTextField` + the native platform IME** (`LocalSoftwareKeyboardController`) — there is nothing to
   reuse, and §A1 contradicts the design addendum §1 (which mocks a full custom D-pad keyboard = net-new).
   **Decide one:** (a) reuse the native IME + add `visualTransformation = PasswordVisualTransformation()`
   for masking (simplest, matches Search, recommended); or (b) build the custom D-pad keyboard the addendum
   draws (net-new component). Make §A1 and the addendum agree; drop "reuse".
2. **The client has no persisted `deviceId` at all.** `deviceId` is **server-minted** today (`pollPairing`
   via `generateSecureToken()`); the client persists only the opaque `deviceToken`. §A2's "generate/read the
   device's stable `deviceId`" is **net-new**: a new `expect object` store (wasm `localStorage` / Android
   `SharedPreferences`). And **`deviceName` has no client source either** — add Android `Build.MODEL` / wasm
   `navigator.userAgent`.
   - **This intersects Phase 141's headline gap.** Because Jellyfin **prunes older tokens per DeviceId on a
     new login under that id**, a shared TV must **not** reuse one physical `deviceId` for every profile —
     the second **Add user** would prune the first profile's Jellyfin token. So generate a **stable
     per-profile deviceId** (one per profile slot, reused on that profile's re-login), which makes the
     Jellyfin login identity unique per user. Persist it **per profile** (e.g. carried on `LocalSession` /
     keyed store), not once per device. This is the load-bearing detail for multi-user; coordinate the exact
     identity string with Phase 141 §A2.

### Corrections to apply
- The poll/token logic §"Current state" points at `PairingScreen.kt:80-95`, but it actually lives in a
  **`PairingStore`** class (the composable is only the view). `TokenStore.set` + `MultiTokenStore.add` move
  into the new `LoginStore`/handler unchanged.
- **`ProfileMenu` has no "Add user" row today** (rows: Switch / My List / Settings / Unpair). §B4 needs a
  **new row + a new `onAddUser` callback** threaded through `ProfileMenu(...)` and its `RaviloApp` call site
  — net-new plumbing, not just "re-open the same screen".
- **The removal surface is larger than §A3 names.** Repoint every `Dest.Pairing` reference: `RaviloApp.kt`
  at ~274/277 (first-run gate), 376 (app-bar hide), 482-498 (`Dest.Pairing → PairingScreen`), and the
  sign-out/unpair resets at 782/787/802; and `ProfilePickerScreen.kt:154-179` (the `AddingUser →
  PairingScreen(PairingStore…)` reuse). Retire `PairingStore` + `PairingState.Waiting/Expired`; the
  `PairingChallenge` DTO orphans; remove `TvApiClient.startPairing/pollPairing`.
- **R170 goes stale here:** `phase-R170-profile-hub-discover-merge.md:106` documents sign-out-all as
  `… → resetTo(Dest.Pairing)`. R175 repoints that to the login destination — note it so R170's behaviour
  doc doesn't lie.

### Docs to reconcile when this ships
- **`specs/ravilo/constitution.md` §"Authentication & device pairing" (161-177)** — pts 2/5/6 mandate the
  pairing-code flow and *"no password typed on the TV / the TV never performs Jellyfin sign-in itself"*,
  which R175 reverses (password is typed on the TV but still proxied server-side, never stored). The
  invariant must be rewritten (shared with Phase 141's doc-reconcile note).
- **`specs/ravilo/plan.md`** — Screens list (163) `Pairing → Login`; DTO/route lines per Phase 141's note.
- **`specs/ravilo/requirements/phase-R161-in-app-settings.md`** (already ✓ Done) uses "pairing"/"the pairing
  this Unpair reverses" terminology; `unpair` itself is retained (141 §B7), so this is a wording refresh
  only, not a behavioural break — low priority.
