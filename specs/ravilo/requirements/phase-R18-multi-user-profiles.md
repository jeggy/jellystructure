# Phase R18 — Multi-user profiles & fast switching (FR-RV18)

**Status:** Planned · _several Jellyfin users signed in on one TV, with instant switching._

> Extends **[R03](phase-R03-device-pairing-auth.md)** (device pairing) and the on-device settings of
> **[R15](phase-R15-ondevice-settings-pairing.md)**. Where R03 pairs *one* user to a device, R18 lets
> a single device hold **multiple** signed-in users and switch between them without re-pairing —
> mirroring how Jellyfin / Wolphin let a household share one TV.

## Problem
A TV is shared. With only single-user pairing (R03), switching viewers means unpairing and
re-pairing every time — far too slow for a living-room device. Households expect a Netflix-style
**"Who's watching?"** picker and a one-press profile switch, with each person landing in *their own*
configured Ravilo (their layout, Continue Watching, watched-state).

## Current state (as-is)
- R03 issues an opaque **device token** bound to one Jellyfin user and stores a `ravilo_device` row.
  R04 keys `RaviloConfig` per Jellyfin user. R15 owns first-run pairing + on-device settings.
- The client assumes a single active user; there is no profile picker or switcher.

## Requirements

### Client — multiple cached sessions
1. The client may hold **multiple device tokens** at once — one per signed-in Jellyfin user — cached
   **on the device** (the TV), so switching needs no network round-trip to re-authenticate. Tokens are
   stored locally (e.g. secure app storage on Android; the web client's equivalent) and are the only
   credential kept — **never** the Jellyfin password (per the constitution).
2. A **"Who's watching?"** profile gate shows when the app launches and more than one user is signed
   in (or none yet). Each profile is a focusable tile (avatar/initials + name; an admin tag; a **Kids**
   badge where applicable). Plus an **"Add user"** tile.
3. A **profile switcher** is reachable any time from the **app-bar avatar** (a focusable control) —
   opening the same picker as an overlay. Selecting a profile **instantly** swaps the active token and
   reloads that user's home/config; no password entry, no pairing.
4. **Add user** runs the R03 **pairing-code** flow (show a code, approve from a signed-in
   Jellystructure web/phone session) — **no password is typed on the TV**. On approval the new user's
   token is cached and they become active. Non-admin Jellyfin users are allowed (R03).
5. **Sign out / remove** a profile from this device clears *that* user's cached token only (the others
   remain); the Jellyfin account is untouched. Removing the last user returns to the pairing gate.

### Per-user isolation
6. The active user determines **everything user-scoped**: `GET /api/tv/home`, `…/config`, browse,
   search, watched-state/resume, and `PUT /api/tv/settings` all operate as the active user (R04–R08).
   Switching users must not leak the previous user's Continue Watching, My List, or layout.
7. A **Kids** profile (a Jellyfin user flagged as such, or a Ravilo setting) may constrain the
   experience (simplified rows / rating ceiling). Detailed parental-control rules are out of scope
   here — R18 only carries the flag + badge and the hook for it.

### Backend — multi-session support
8. `ravilo_device` becomes **per (device, user)** rather than one row per device, so a TV can have
   several concurrent sessions. Pairing an additional user on an already-known device adds a session
   rather than replacing it. Each session token validates independently (R03 guard unchanged).
9. `GET /api/tv/sessions` (device-scoped) lists the users signed in on this device for the picker;
   `DELETE /api/tv/sessions/{userId}` removes one. (Exact shape per `../plan.md`.)

## Invariants
- **Only tokens are cached on the device** — never passwords; switching is local + instant.
- **Strict per-user isolation** of config, watched-state, and lists across a switch.
- **Adding a user uses the R03 pairing-code flow** — no password typed on the TV; non-admin allowed.
- One device, **many sessions**; removing one never affects the others or the Jellyfin account.

## Out of scope
- PIN-locked / full parental-control rules (R18 carries only the Kids flag + hook).
- Cross-device session sync (a user's *config* already syncs via R04; cached *tokens* are per-device).
- Profile avatars/artwork management UI (initials/color is enough; could be a later phase).

## Design reference
`design/ravilo/Ravilo TV.html` — the "Who's watching?" gate, the app-bar avatar switcher, and the
Add-user pairing-code panel are implemented there (Eyð / Olivar / Marjun / Kids demo profiles).
