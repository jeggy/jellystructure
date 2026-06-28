# R135 — Focusable, expandable description (detail)

> Builds on **R47/R89** (detail draw-only focus pattern). Movie + series detail.

## Problem

The detail synopsis is truncated (`maxLines = 3` on movie, `2` on series) with an ellipsis and **isn't
focusable** — on a TV with only a D-pad there is no way to read the rest of a long description.

## Current

- `MovieDetailScreen.kt` ~209–219 / `SeriesDetailScreen.kt` ~278–288: a plain `Text(synopsis, maxLines =
  3/2, overflow = Ellipsis)`. No focus, no "more" affordance.
- The screen's focusables (`RaviloButton`, `EpisodeCard`, `CastCircle`) use the R47 pattern: an outer
  `Modifier.dpadFocusable(...)` + an inner `graphicsLayer` running scale/glow **draw-only** (no viewport
  jump), with an `onSelect` callback.

## Requirements

1. The synopsis becomes a **D-pad focusable** element, sitting in the vertical focus order between the
   action row and the next section, using the R47 draw-only focus treatment (subtle scale/glow; never a
   layout/viewport jump).
2. When focused **and truncated**, show a quiet affordance (e.g. a "▾ more" hint or an underline) so it's
   discoverable.
3. **SELECT opens the full description** — a dim-backed **overlay** with the complete synopsis, vertically
   scrollable when long, dismissed with **Back**. (An overlay is preferred over inline expansion so a long
   synopsis doesn't push the season picker / episodes far down and disrupt R138's vertical nav.)
4. Focusing Play/Resume still triggers the hero scroll-to-top (R72/R115) — the description focusable must
   not break that, and DOWN from the description continues to the season picker (series) / next section.

## Approach

Extract a shared `DetailSynopsis(text)` composable: the truncated `Text` wrapped in
`Modifier.dpadFocusable(onSelect = { open = true })` with the inner `graphicsLayer` focus animation (mirror
`RaviloButton`). On select, set a screen-level `synopsisOverlay` state that renders a `SynopsisOverlay`
(dim scrim + a focusable, scrollable text panel, `BackHandler`/Back closes, restores focus to the synopsis).
Use both detail screens.

## Files

- `ravilo-ui/.../components/DetailSynopsis.kt` + `SynopsisOverlay.kt` (new), reusing `dpadFocusable` +
  `RaviloMotion`.
- `ravilo-ui/.../screens/MovieDetailScreen.kt`, `…/SeriesDetailScreen.kt` (swap the `Text`; host the overlay).

## Implemented (R135)

Shipped as **inline expand**, not a modal overlay: `components/DetailSynopsis.kt` is a draw-only
`dpadFocusable` that toggles `maxLines` on SELECT with a ▾more/▴less chevron, inserted into the hero focus
order (action-row UP → synopsis → AppBar; DOWN → Play). A modal with custom D-pad focus/scroll was dropped
to avoid adding a new focus path on this focus-sensitive screen; inline-expand reuses the page's scroll.

## Out of scope

Editing the synopsis; read-aloud/TTS; per-paragraph navigation.
