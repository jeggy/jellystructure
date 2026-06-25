# Phase R71 — "Merge newly added" controls ONE vs TWO Newly-Added rows (FR-RV-MA1)

## Problem
In the Ravilo config Content-rows section, the **"Merge newly added"** switch currently behaves like it
just **enables/disables** the Newly Added row. It should instead control **whether Newly Added is one
merged row (all media) or two rows (Newly Added Movies + Newly Added Series)**.

## Findings (it's a data-shape mismatch introduced by R61 — NOT a pure data error and NOT a wiring bug)
- **The switch is wired correctly** to `currentConfig.mergeNewlyAdded` (not to any row's `enabled`) —
  `RaviloConfig.kt:1576,1607-1611,1630-1633,1965`. The only visible effect today is **cosmetic**: it greys
  the NEWLY_ADDED row in the editor (`:1584-1585 isMergedOut … opacity:0.45`) and collapses the preview
  rows (`:1901-1913`) — which is why it *looks* like enable/disable.
- **`HomeFeedService.buildRows` was written for two stored typed rows** (`HomeFeedService.kt:153-244`):
  with `mergeNewlyAdded=true` it `continue`-skips every NEWLY_ADDED row and injects one combined row
  (`:222-241`); with `false` it renders each NEWLY_ADDED row filtered by `mediaKind`
  (`MOVIE`/`SERIES`/null) (`:175-190`).
- **R61 changed the data out from under that logic.** The post-R61 default stores a **single `newly-all`
  row with `mediaKind=null`** (`RaviloConfigService.kt:26-29`, `RaviloConfig.kt SYSTEM_ROW_DEFAULTS
  :1562-1565`, `normalizedRows :1567-1572`), and the typed `newly-movies`/`newly-series` rows **no longer
  exist anywhere** (grep confirms only `newly-all` is referenced; `+ Add row` only makes CUSTOM rows).
  So:
  - `merge=false` → `newly-all` renders via the `else -> all` branch = **one** combined row.
  - `merge=true` → `newly-all` skipped, one merged row injected = **one** combined row (same content).
  Both states produce the same single row → the "two rows" outcome is **impossible**, and the flag is a
  content no-op. A DB tweak alone can't fix it — the feed logic must change (or the data shape must be
  reverted).

## Goal
The switch genuinely controls the split: **ON → one "Newly Added" (all media); OFF → two rows "Newly Added
Movies" + "Newly Added Series"** — without re-cluttering the editor with two system rows (the thing R61
deliberately removed).

## Requirements (recommended: keep the single `newly-all` row, split it at feed-build time)
1. **Feed logic** (`HomeFeedService.buildRows`): for a NEWLY_ADDED row with `mediaKind = null` (the
   `newly-all` system row), branch on `config.mergeNewlyAdded`:
   - `true` → emit **one** "Newly Added" row (all media, newest first) — current behavior.
   - `false` → emit **two** rows in place: "Newly Added Movies" (`kind == MOVIE`) then "Newly Added
     Series" (`kind == TV_SHOW`), each newest-first, capped at `ROW_ITEM_LIMIT`. (Reuse the existing
     `defaultRowTitle` MOVIE/SERIES titles.) This replaces the old "needs two stored rows to split" model.
2. **Editor preview** (`RaviloConfig.kt`): make `previewRowTitles`/the schematic (`:1901-1913`) and the
   row list reflect the new meaning — when merge is OFF, the single Newly Added system row previews as
   **two** rows (Movies + Series); when ON, as one. Drop/repurpose the `isMergedOut` greying (`:1584`) so
   the toggle reads as "1 row ↔ 2 rows", not "shown ↔ hidden". Keep the single `newly-all` row in the
   editor list (no second system row).
3. **Flag default**: leave `mergeNewlyAdded` default as-is (`false` ⇒ split by type, matching most TV
   layouts) — or pick the desired default explicitly; document it.

## Data note (the user's "fix in the DB" question)
This is **not** a stored-data error that a manual DB edit fixes — with the recommended approach the stored
config (single `newly-all` row + `mergeNewlyAdded` flag) is already the right shape; only the **feed +
editor code** need to interpret the flag as 1-vs-2. (The user's global config currently has `newly-all`
and whatever `merge_newly_added` value — no migration needed.)

### Alternative (option C, not recommended)
Revert to storing two typed rows (`newly-movies` + `newly-series`) as the default + migrate existing
configs; then the *existing* `buildRows`/editor logic works unchanged. Rejected because it re-introduces
two system rows in the editor — the exact clutter R61 removed — and requires a config migration.

## Scope
- `src/linuxX64Main/.../tv/HomeFeedService.kt` (`buildRows` — split a `mediaKind=null` NEWLY_ADDED row by
  the flag).
- `src/wasmJsMain/.../ui/RaviloConfig.kt` (preview + toggle semantics/greying; keep one system row).
- No model change (`RaviloConfig.mergeNewlyAdded`, `RowConfig.mediaKind` already exist).

## Acceptance
- With one Newly Added system row configured: turning "Merge newly added" **off** shows two rows on the TV
  — "Newly Added Movies" and "Newly Added Series"; turning it **on** shows a single "Newly Added" row. The
  editor preview matches, and the toggle no longer reads as enable/disable.
