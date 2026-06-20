# Phase R28 — Ravilo config editor: fidelity to the R16 mockup (FR-RV28)

**Status:** Done · _depends on R26 (working round-trip) + R27 (model fields). DOM/Tailwind admin
frontend only. Hero item-search picker and an iframe Compose preview were descoped to a lightweight
schematic preview + item-id input — see notes._

## Problem

The implemented `/ravilo` editor (`src/wasmJsMain/.../ui/RaviloConfig.kt`) is a functional skeleton
that diverges substantially from the mockup `design/app/ravilo-config.html` (R16). It offers plain
add/remove lists of text inputs; it has no reordering, no hero-height/auto-advance controls, no
channel logo/text + brand-color + typed-filter pickers, no system-row distinction, wrong
"merge" semantics, invalid tile-shape options, and no live preview or page chrome.

## Current state (as-is)

- **Heroes:** two raw text inputs per item (`item_id`, `item_title`); no thumbnail, no show toggle,
  no reorder, no item picker.
- **Channels:** `label` + kind `<select>` + free-text `filter` + free-text `#color`; no logo/text
  style, no brand-color swatch, no facet-backed filter picker, no reorder, no show toggle.
- **Rows:** every row freely editable (label/kind/filter/hidden) including system rows; no reorder;
  the merge checkbox reads *"Merge Continue Watching + Next Up + Newly Added into one"* — wrong.
- **Behaviour:** tile-shape `<select>` offers `POSTER/THUMB/SQUARE` (not valid `TileShape`s).
- **Chrome:** plain `<h1>Ravilo TV</h1>` + a terse "Editing config for:" select; no live preview,
  no "saved · synced" badge, no "Open Ravilo ↗" link, no jellyfish mark, no explanatory banner.
- **Sidebar:** `Shell.kt` registers `/ravilo` with the icon `"language"` (leftover from the removed
  Language page).

## Requirements

Built in the **DOM/`kotlinx.browser` + Tailwind** admin frontend (its constitution) — **not** Compose.
All filter pickers reuse **Phase 30 facets** (studios/networks/genres/tags) — no parallel taxonomy.

### 1. Hero section
- Reorderable list (drag handle `⠿`) writing `HeroConfig.order`; per-item **show toggle**
  (`enabled`) and remove.
- Replace raw `item_id` text with an **item picker** (search Jellyfin items by title; reuse the
  search/facets API) and show a **thumbnail** + title.
- **Hero-height slider** 30–70% bound to `heroHeightPct` (R27) with a live `%` readout.
- **Auto-advance** select bound to `autoAdvanceSeconds` (R27): Off / 4 / 6 / 8 / 10 s.

### 2. Channels
- Reorderable (writes `order`); per-channel **show toggle** (`enabled`) and remove.
- **Logo / Text** segmented control bound to `ChannelStyle`.
- **Brand-color** swatch + picker bound to `brand_color`.
- **Typed filter** picker (Network / Studio / Genre / Tag) populated from **facets**, writing exactly
  one `filter_network/studio/genre/tag`; no free-text filter value.

### 3. Content rows
- **Badge system rows** (`CONTINUE`, `NEWLY_ADDED`) distinctly and **prevent deletion** of them;
  genre/custom rows are editable and removable.
- Reorderable (writes `order`); show/hide toggle bound to `enabled`.
- The **merge toggle** means exactly *"merge Newly Added Movies + Newly Added Series into one
  'Newly Added' row"* (Constitution: Continue Watching is **always** Continue+Next Up; only the two
  Newly-Added rows optionally merge). Off → two system rows; On → one combined row — reflected in the
  preview. Bind to `RaviloConfig.mergeNewlyAdded`. Do **not** conflate Continue/Next Up.

### 4. Behaviour
- Tile-shape control offering the mockup's options mapped onto `TileShape`:
  **Recommended** (posters portrait, continue-watching landscape) · **All portrait** (`POSTER`) ·
  **All landscape** (`LANDSCAPE`). Remove `THUMB`/`SQUARE`.
- Keep interface language (R19), default skin, allow-skin-override, show-continue-progress.

### 5. Live preview + links
- A **live preview** panel (sticky, ~300 px) reflecting the current edits in the `design/ravilo`
  look, plus an **"Open Ravilo ↗"** link and a **compare-skins** link, as in the mockup.

### 6. Page chrome
- Pagebar: the **jellyfish mark** + "Ravilo TV" + an **"app config"** badge, a **"saved · synced"**
  status indicator, and the explanatory **per-user banner** ("stored per Jellyfin user, syncs to all
  their devices") alongside the user picker.

### 7. Sidebar
- Fix the `/ravilo` nav entry icon in `Shell.kt` (use an appropriate icon such as `tv`, not
  `language`).

## Invariants

- **DOM/Tailwind**, not Compose — the two web apps stay separate (R16 invariant).
- Channel/row filters **reuse Phase 30 facets** — no parallel taxonomy.
- Every reorder/show-hide edit round-trips through the **shared** `RaviloConfig` (R26) and persists
  `order`/`enabled` correctly.

## Out of scope

- DTO unification (**R26**) and model field additions (**R27**) — prerequisites, assumed done.
- TV rendering of the layout (R05/R10) and the on-device viewer settings subset (R15).
