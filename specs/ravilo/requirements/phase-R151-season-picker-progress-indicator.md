# Phase R151 — Season picker: per-season watched indicator + remove "Mark all watched"

> Builds on **[R150](phase-R150-season-picker-watched-state.md)** (season ✓ badge, auto-select, scroll to
> next unwatched) and **[R142](phase-R142-watched-state-everywhere.md)** (tile/episode watched state). This
> phase makes the season picker's watched state **glanceable for every season** — not only fully-watched
> ones — and removes the season-level **Mark all watched** button from the series detail page.

## Problem
On the series **detail** page:
1. The only whole-season "watched" signal was R150's ✓ badge, which appears **only at 100%**. In practice
   no season a viewer is mid-way through shows any indicator, so **you cannot tell from the picker which
   seasons you have (partly) seen**.
2. The episodes section carries a **"Mark all watched / Mark all unwatched"** button (Faroese
   *"Merk øll sum sædd"*). Per prior product direction (already noted as a non-goal in R150) the
   season-level mark-all action is being retired — episodes are marked individually, and completion rolls
   up automatically.

## Requirements

### FR-R151-1 — Remove the season mark-all button
Remove the season-level **Mark all watched / Mark all unwatched** control (the `data-markall` button in the
episodes-section action row) from the series detail page. Whole-season completion is still reached by
marking the individual episodes; the item/season watched roll-up (R142) is unchanged. The episodes-section
header keeps its **"{w} of {n} watched"** sub-label and progress bar.

### FR-R151-2 — Always-visible per-season indicator in the picker
Each season pill shows its watched state at a glance, in three states derived from the playstate overlay:
- **Complete** (every episode watched) → the green **✓** badge (R150 FR-R150-1).
- **Partial** (1..n−1 watched) → a small **`w/N` count badge** (e.g. `3/8`) plus a thin **progress sliver**
  along the bottom edge of the pill, width = watched fraction, in the accent colour.
- **None watched** → no badge (plain pill).

The indicator reads the same overlay as R150/R142 (empty overlay → no badges). It updates live when the
viewer marks/unmarks episodes. On the focused (highlighted) pill the count badge and sliver invert so they
stay legible against the light focus fill.

## Implementation

| Layer | File | Change |
|---|---|---|
| Component | `SeasonPicker.kt` | Add a `watchedCount(season): Int` (or `Map<Int,Int>`) input alongside R150's `watchedSeasons`. Render: ✓ when `count == total`; else a `count/total` badge + a bottom progress sliver when `count > 0`. Add the focused-state inversions for badge/sliver. |
| Screen | `SeriesDetailScreen.kt` | Compute per-season watched counts via `remember(detail.seasons, overlay)` (reuse the R150 overlay derivation). **Remove** the season mark-all button/composable and its `markAllWatched(...)` action wiring. |

### Design reference (already built)
`design/ravilo/Ravilo TV.html` (`ravilo-app.js` season-pill loop + `ravilo.css` `.spill-frac` / `.spill-prog`
/ `.spill-check`) is the visual target: partial seasons show a rounded `w/N` badge and an accent sliver;
complete seasons show the green ✓; the mark-all action row is gone.

## Non-goals
- No server-side change — everything derives from the playstate overlay already fetched (R83/R84).
- No re-introduction of a "mark entire season" action (this phase removes it).
- R150's auto-select-first-incomplete-season and scroll-to-next-unwatched behaviour are unchanged.
