# R138 — Series detail: don't skip the season picker, smoother scroll, keep hero scroll-to-top

> Builds on **R72/R107/R108/R109/R114/R115** (detail-screen scroll/focus work). Series detail only.

## Problem

On the series detail screen, navigating **DOWN** with the D-pad:
1. **Sometimes skips the season picker** — focus jumps from the hero straight to the episodes.
2. **Jumps too much / too fast** — the scroll from the top toward the season + episode picker is abrupt.
3. …but we still **want a full scroll-to-top** when the Play button / description (the hero) is focused.

## Current

- `SeriesDetailScreen.kt` ~220–456: a `LazyColumn` with items `hero` → `seasons` (only if
  `seasons.size > 1`) → `episodes` → `cast` → `related` → `tail`. Lazy items compose when scrolled near.
- `SeasonPicker.kt` ~47–98: a `LazyRow` with `focusRestorer()`; each season button is **individually**
  `dpadFocusable` (draw-only scale/glow). The row itself is not a `focusGroup`.
- Scroll/focus: a global instant edge `BringIntoViewSpec` — `rememberEdgeBringIntoViewSpec(peek 60dp,
  topInset appBar+24dp)` provided via `LocalBringIntoViewSpec` (~207, ~221). The Play row's
  `onFocusChanged` scrolls the hero to the top **only when already scrolled** (`firstVisibleItemScrollOffset
  > 0`, R115) via `scroll(MutatePriority.UserInput) { scrollBy(-offset) }` (~315–325).

## Root causes

1. **Skip:** the season `LazyRow` is inside a lazy `seasons` item; when DOWN is pressed its children may not
   be composed/placed yet, so native focus traversal finds the next already-composed focusable (the
   episodes). There's no explicit hero→season focus handoff and no `focusGroup` on the picker.
2. **Jumpiness:** bring-into-view is **instant** (no animation); a focus gain on a far-down item produces a
   large immediate scroll that reads as a jerk.
3. **Scroll-to-top** lives in the Play row's `onFocusChanged` (must be preserved).

## Requirements

1. DOWN from the hero **always lands on the season picker first** (never skips it) when the series has a
   season picker; DOWN from the picker then goes to the episodes.
2. The scroll between the hero and the season/episode area is **smooth and calm** (animated, not an instant
   jump), without over-shooting.
3. **Preserve** the hero scroll-to-top: focusing Play/Resume (and the R135 description) still fully reframes
   the backdrop — keep the R115 `offset > 0` guard + `UserInput` priority.

## Approach

1. **No skip:** give the hero action row an explicit `onDown` → request focus on the season picker's first
   button (a `FocusRequester` exposed by `SeasonPicker`), and/or wrap the picker in a `focusGroup` and make
   the `seasons` item eager enough to be in the traversal (e.g. compose its children when the hero is
   focused). DOWN from the picker → episodes (and UP back to the hero actions).
2. **Smooth scroll:** replace the instant bring-into-view with a gentle **animated** scroll for the
   inter-section move — either an animated `BringIntoViewSpec`, or on season/episode focus an
   `animateScrollToItem` with a calm spec — tuned to avoid over-jump (respect the existing top inset / peek).
3. **Keep** the Play/description `onFocusChanged` scroll-to-top exactly as R72/R115 define it.

## Files

- `ravilo-ui/.../screens/SeriesDetailScreen.kt` (hero `onDown` handoff; the BIV spec; keep the play-focus
  reframe).
- `ravilo-ui/.../components/SeasonPicker.kt` (expose a first-button `FocusRequester` / `focusGroup`).
- `ravilo-ui/.../components/BringIntoView.kt` (an animated spec option, if taken).

## Out of scope

The movie detail (no season picker); the detail layout/section order; player.
