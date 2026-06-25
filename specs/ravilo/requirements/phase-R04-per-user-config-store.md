# Phase R04 — Per-Jellyfin-user RaviloConfig store (FR-RV4)

## Problem
A viewer's Ravilo layout (hero items, channels, rows, the merged-"Newly Added" toggle, default skin,
playback prefs) must be **stored server-side per Jellyfin user** so that **every device a user signs
into shows the same experience** and any edit syncs everywhere. Device-local-only config would desync
two TVs in the same household.

## Current state (as-is)
- jellystructure stores config two ways: global `config.toml` (operator) and SQLite stores for media
  state. There is no per-Jellyfin-user preference store.
- After R03 we have `ravilo_device` rows mapping device → Jellyfin user.

## Requirements

### Store
1. New SQLDelight table **`ravilo_config(user_id PRIMARY KEY, json, updated_at)`** holding the
   serialized **`RaviloConfig`** (the `:shared` DTO). One row per Jellyfin user.
2. On first read for a user with no row, **synthesize sensible defaults** (a default hero set or
   "auto", the standard channels if discoverable, the default row lineup — Continue Watching, Newly
   Added Movies, Newly Added Series, a few genre rows — `mergeNewlyAdded=false`, `defaultSkin=Aurora`)
   and persist them. Defaults are deterministic.
3. `RaviloConfig` schema (in `:shared`, R01): `heroes[]`, `channels[]` (each → studio/network/genre/
   tag filter + style + brand color), `rows[]` (id, title, kind, visible, order, filter), `mergeNewly
   Added`, `defaultSkin`, `allowSkinOverride`, `showContinueProgress`, `tileShape`.

### Endpoints
4. `GET /api/tv/config` → this device's user's `RaviloConfig` (creating defaults if absent).
5. `PUT /api/tv/settings` → apply the **viewer-tweakable subset** (skin, playback prefs, maybe row
   show/hide) for the current user; validates and merges into the stored config; bumps `updated_at`.
   (The full layout editor is the web screen, R16, writing the same row.)
6. Because config is keyed by **user**, a write from any surface (web R16 or `PUT /api/tv/settings`)
   is visible to **all** that user's devices on their next `GET /api/tv/home` / `GET /api/tv/config`.

### Sync semantics
7. Reads are authoritative from the server; the device keeps only a **cache** + genuinely device-local
   prefs (e.g. a local skin override **iff** `allowSkinOverride`). No device-only layout state.
8. Concurrent writers: last-write-wins on the whole `RaviloConfig` (it's small); `updated_at` lets
   clients detect a newer server copy and refetch.

## Invariants
- **Config is server-owned and keyed by Jellyfin user** — never device-only.
- A save from any surface **syncs to all the user's devices**.
- Defaults are deterministic and self-healing (missing row → recreated).

## Out of scope
- The web editing UI (R16) and the on-device settings UI (R15) — this is the store + contract.
- Home composition from this config (R05).
