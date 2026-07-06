# Phase R174 — Configurable poster-grid columns (landscape + portrait) (FR-RV-PT2)

## Goal
The number of tiles per row on Ravilo's **poster grids** — the all-movies / all-series **Browse** grid and
both **Search** grids (catalog search + Request/Seerr search) — becomes operator-configurable instead of a
hardcoded constant. Two values: a **landscape** count (TVs, desktop web — default **6**, range **2–10**) and
a **portrait** count (a phone held upright, a portrait browser window — default **2**, range **1–4**). The
app selects by its own viewport via the existing `LocalPortrait` (R159), so a rotation flips it live. This is
the second portrait-only override, added to R159's "Portrait screen" section as that phase intended.

## Current state (verified in code)
- Three independent hardcoded constants: `BrowseScreen.kt` `GRID_COLS = 6`; `SearchScreen.kt`
  `GRID_COLS_SEARCH = 5`; `SeerrSearchScreen.kt` `GRID_COLS_SEERR_SEARCH = 5`. The search constants are
  also used in the grids' edge-exit focus math (`focusedGridIdx % cols`, `< cols`).
- These screens read neither the `HomeFeed` nor `tileShape` — they only take a store + apiClient. So a
  server value must arrive via the app-wide config (`apiClient.getConfig()` → `RaviloConfig`, applied in
  `RaviloApp.refreshConfig()`, live-updated via `/api/tv/config/rev`), **not** the home feed (which is how
  R159's hero height travels).
- R159 pattern to extend: `PortraitConfig` block on `RaviloConfig`, clamped in `RaviloConfigService.normalize()`,
  selected client-side by `LocalPortrait`.

## Requirements

### A. Config model (`shared/.../tv/Models.kt`)
1. `RaviloConfig` gains `@SerialName("grid_columns") gridColumns: Int = 6` — the landscape/TV count.
2. `PortraitConfig` gains `@SerialName("grid_columns") gridColumns: Int? = null` — the portrait override.
   `null` = the built-in portrait default (**2**), so an absent portrait block still yields 2 in portrait
   (unlike hero height, whose `null` inherits the *landscape* value — a phone wants fewer columns, not the
   TV's count).

### B. Backend
1. `RaviloConfigService.normalize()`: clamp `gridColumns` to **2–10**, and `portrait.gridColumns` to **1–4**.
2. No feed change: `getConfig()` already returns the normalized `RaviloConfig`, so both values ride the
   existing `/api/tv/config` payload the app polls.

### C. Applying it (app)
1. Two `CompositionLocal`s beside `LocalTileScale` in `RaviloApp.kt`: `LocalGridColumns` (default 6) and
   `LocalPortraitGridColumns` (default 2), set in `refreshConfig()` from `cfg.gridColumns` and
   `cfg.portrait?.gridColumns ?: 2`.
2. Each grid computes `val cols = if (LocalPortrait.current) LocalPortraitGridColumns.current else
   LocalGridColumns.current`, used for both `GridCells.Fixed(cols)` and the search screens' edge-exit focus
   math. The three constants are removed. Presentation selection by the client's own viewport — server-pushed
   numbers, constitution holds (same class as R159 / `uiDensity`).
3. Rotating across the portrait boundary re-applies live (state-driven recomposition; the value is already
   present).

### D. Config editor (`RaviloConfig.kt`)
1. **Landscape**: a "Items per row on grids" slider (2–10) in the global **Behaviour & preferences** card,
   next to Content size. A layout field (not a per-field behaviour overlay); global scope only — per-user
   scope preserves the stored value (`collectConfig` falls back to `currentConfig.gridColumns` when the
   control isn't rendered).
2. **Portrait**: an "Items per row" slider (1–4, default 2) in the **Portrait screen** section, alongside the
   R159 hero-height override but **not** gated by its enable toggle. Stored as `null` when at the default 2,
   so the portrait block only persists when it carries a real override.
3. `collectConfig` reads both, adds `gridColumns` to the reconstructed `RaviloConfig`, and builds the portrait
   block from `heroHeightPct != null || portraitGridColumns != null`. Live number labels on drag.

### E. i18n
No TV-side strings (behaviour is silent). Editor strings are admin-side English like the rest of the editor.

## Scope
- Shared: `RaviloConfig.gridColumns` + `PortraitConfig.gridColumns` (`Models.kt`).
- Backend: `RaviloConfigService.normalize()` clamps.
- App: `LocalGridColumns`/`LocalPortraitGridColumns` (`RaviloApp.kt`); `cols` selection in `BrowseScreen`,
  `SearchScreen`, `SeerrSearchScreen` (grid + focus math), constants removed.
- Admin FE: `RaviloConfig.kt` — Behaviour landscape slider, Portrait portrait slider, `collectConfig`,
  live-label handlers.

## Non-goals
- No per-screen counts — one landscape + one portrait value for all three grids (search's old 5 becomes the
  configured landscape count, default 6).
- No per-user grid-columns override surface (it rides the R51 full-layout override when one exists, but has no
  dedicated per-user control).
- No row-tile (home/channel) reflow — this is grids only. No mockup portrait section (the R159 portrait card
  was never added to `design/app/ravilo-config.html`; out of scope here).

## Acceptance
- Landscape default is 6 on every grid; setting it to e.g. 4 shows 4 across on a TV, clamped 2–10 server-side.
- Portrait default is 2 with no portrait block; setting it to 3 shows 3 across in portrait, clamped 1–4;
  landscape is unchanged.
- Rotating a phone between portrait and landscape flips the column count live without a refetch.
- Both values round-trip through save/reload; the portrait block is absent when hero override is off and
  portrait columns are 2.
