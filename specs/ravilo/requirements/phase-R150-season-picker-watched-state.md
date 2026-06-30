# Phase R150 — Season Picker: Watched State & Auto-Selection

## Goal

Improve the series detail page season picker so users land on the right
season immediately and can see at a glance which seasons they have
completed.

## Requirements

### FR-R150-1 — Season ✓ badge

A season pill in the picker shows a **✓ suffix** (`"Season N  ✓"`) when
every episode in that season has been marked as played in the playstate
overlay. The ✓ is only visible once the overlay has loaded (empty overlay
= no badges shown).

### FR-R150-2 — Auto-select active season

When the series detail screen opens (or the overlay first arrives),
the picker **auto-selects the first incomplete season** — the first season
that contains at least one unwatched episode.

- If all seasons are fully watched → select the last season.
- The auto-selection fires **once per series** (guarded by an
  `autoSeasonDone` flag keyed on `detail.card.id`). Manual picker
  navigation afterwards is never overridden.

### FR-R150-3 — Episode row scroll to next unwatched

After the active season is selected (either by auto-selection or user
navigation), the episode `LazyRow` **scrolls to the first unwatched
episode** in that season. If all episodes are watched the row stays at
index 0. Fires on both `selectedSeasonIdx` and `overlay` changes so it
stays correct after the user marks episodes as watched.

## Implementation

| Layer | File | Change |
|---|---|---|
| Component | `SeasonPicker.kt` | Add `watchedSeasons: Set<Int>` param; append `"  ✓"` to season name text when `season.index in watchedSeasons` |
| Screen | `SeriesDetailScreen.kt` | Compute `watchedSeasons` via `remember(detail.seasons, overlay)`; add `autoSeasonDone` + `LaunchedEffect` for FR-R150-2; add `epRowState = rememberLazyListState()` + `LaunchedEffect` for FR-R150-3 |

## Non-goals

- No server-side change — all logic is derived from the existing playstate
  overlay already fetched by R83/R84.
- No "mark entire season watched" button (removed by prior request).
