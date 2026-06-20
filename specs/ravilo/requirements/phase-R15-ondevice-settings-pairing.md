# Phase R15 — On-device settings + first-run pairing UX (FR-RV15)

**Status:** Planned · _how a device gets signed in, and the few things a viewer can tweak on-device._

## Problem
A fresh Ravilo device needs a friendly, keyboard-free **pairing** experience, and the viewer needs a
small **settings** surface (skin, playback prefs) that writes back to the server so it **syncs across
their devices**.

## Current state (as-is)
- R03 provides the pairing backend (code flow, poll, approve); R04 provides the config store +
  `PUT /api/tv/settings`. No on-device pairing or settings UI.

## Requirements

### First-run pairing
1. On launch with no valid device token, show a **pairing screen**: the human-readable **code** (from
   `pair/start`) + simple instructions ("On another device, open jellystructure → Ravilo → Pair a TV,
   and enter this code"), polling `pair/poll` until approved, then entering Home.
2. Handle expiry (regenerate code), error, and a "signed in as <name>" confirmation. A **Sign out /
   unpair** action elsewhere clears the device token and returns here.

### Settings
3. A focusable **Settings** screen with the **viewer-tweakable subset**: **skin** (Aurora / Midnight /
   Noir — only if `allowSkinOverride`), **playback prefs** (autoplay next episode, show progress on
   Continue Watching), and an **account** section (who's signed in, sign out).
4. Changes write via **`PUT /api/tv/settings`** → the per-user config store (R04), so they **sync to
   all the user's devices**; a purely device-local skin override is allowed only when
   `allowSkinOverride` is set.
5. Layout-level config (hero/channels/rows/merge) is **not** edited here — that's the jellystructure
   web screen (R16); Settings may link/explain that.

## Invariants
- **No password typed on the TV**; pairing is code-based (R03).
- Viewer settings **sync via the server** (R04) across devices; not device-only (except an allowed
  local skin override).
- Layout editing stays on the web (R16); on-device settings are a small subset.

## Out of scope
- The full layout editor (R16); multi-user profile switching on one device (future).
