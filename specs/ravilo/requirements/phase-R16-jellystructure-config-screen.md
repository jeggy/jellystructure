# Phase R16 — jellystructure web: Ravilo config screen (FR-RV16)

## Problem
The per-user `RaviloConfig` (R04) needs a real editing surface in the **jellystructure web app** — the
screen mocked in `design/app/ravilo-config.html`. It must let a user arrange their Ravilo home: hero
items, channel buttons, content rows, the merged-"Newly Added" toggle, and skin — and make clear it's
stored **per Jellyfin user and synced to all their devices**.

## Current state (as-is)
- R04 provides the store + `GET /api/tv/config`; a write path is needed for the full layout.
- The HTML/CSS mockup exists (`design/app/ravilo-config.html`): hero list + height, channels (logo/
  text, filter mapping), rows with merge toggle + show/hide, behaviour (default skin, tile shape),
  live preview, per-user banner. The jellystructure sidebar already has a **Ravilo TV** entry.
- The jellystructure admin frontend is **DOM/`kotlinx.browser` + Tailwind** (its constitution) — this
  screen is built that way, **not** in Compose.

## Requirements

### Backend
1. `GET /api/tv/config?userId=…` and **`PUT /api/tv/config`** (full `RaviloConfig`) for the editor —
   the admin/user counterpart to the TV's `PUT /api/tv/settings` (which only writes the viewer subset).
   Validates and persists to the R04 store; bumps `updated_at`.
2. A way to pick **which Jellyfin user** is being edited (admin may edit any; a non-admin edits their
   own) — `GET` of the Jellyfin user list for the picker, reusing existing Jellyfin integration.

### Frontend (jellystructure admin app — DOM/Tailwind, page `ravilo`)
3. Implement the mocked screen: **Hero** (reorderable list + per-item show/remove + hero-height
   control + auto-advance), **Channels** (reorderable; each mapped to a studio/network/genre/tag
   filter; logo/text style; brand color), **Content rows** (reorderable; show/hide; the **merge
   Newly-Added** toggle swapping the two rows for one), **Behaviour** (default skin, allow-override,
   tile shape, show-progress).
4. A **per-user banner** + user picker making the "stored per Jellyfin user, synced to all devices"
   model explicit.
5. A **live preview** (the `design/ravilo` look) reflecting the current edits; **Save** → `PUT
   /api/tv/config`. Open-Ravilo / compare-skins links.
6. Reuse jellystructure facets (Phase 30) to populate channel/row filter pickers (studios/networks/
   genres/tags).

## Invariants
- Writes the **same per-user store** the TV reads (R04); a save **syncs to all the user's devices**.
- Built in the **DOM/Tailwind** admin frontend (not Compose) — the two web apps stay separate.
- Channel/row filters reuse Phase 30 facets — no parallel taxonomy.

## Out of scope
- The on-device settings subset (R15); the TV rendering of this config (R05/R10).
