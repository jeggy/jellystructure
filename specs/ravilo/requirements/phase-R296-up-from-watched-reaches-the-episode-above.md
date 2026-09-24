# Phase R296 — Up from an episode's Watched button reaches the episode above it

## Status

`Planned` — written 2026-09-24 from a device test on the stue TV, not dev-reviewed. Spec first.

## What happens today

On a TV series page with **one season**, each episode is a card with a **Watched** toggle directly
under it. The episode row carries `onKeyEvent(upToHero)` on the whole `LazyRow`
(`SeriesDetailScreen.kt`): with no season picker above, Up from the row has to be bridged to the hero's
Play button, which may no longer be composed once the page has scrolled.

Because the handler is on the row, it also receives Up from the **Watched** toggle (key events bubble
from the focused child to every ancestor) and consumes it before Compose's own focus search can move to
the card directly above. So:

1. Down from Play lands on the first episode card; a second Down lands on its Watched toggle.
2. Up from the toggle jumps to the hero, skipping the card.
3. `focusRestorer()` remembers the toggle, so every later Down into the row lands on the toggle again.

The card becomes unreachable by D-pad until the page is re-entered. OK on the toggle is the only
action left, and it un-marks the episode. Seen 2026-09-24 on the stue TV while trying to play a
specific episode: a viewer trying to open an episode is steered into changing its watched state instead.

Shows with several seasons are unaffected: their row has no `upToHero`, and native search reaches the
card, then the season picker.

## Requirements

- **FR-R296-1 — Up from a Watched toggle focuses the card above it**, in every row, single-season or not.
- **FR-R296-2 — Up from a card still reaches the hero** on a single-season show, with R223's guarantee
  intact (the list is scrolled to item 0 on the way, however fast Up is pressed).
- **FR-R296-3 — The bridge sits on the card, not the row.** Each card (single and multi-episode) is
  wrapped in a container carrying `onKeyEvent(upToHero)` for a single-season show; the row keeps only
  `focusRestorer()`. The card components' signatures do not change.

## Non-goals

- Where Back from the player lands, or which card the row restores to. Unchanged.
- The Watched toggle's own behaviour.

## Acceptance (stue TV, release build)

1. On a single-season show: Down, Down (on a toggle), Up → the card above is focused; Up again → Play,
   with the page scrolled to the top.
2. Down back into the row from Play → a card or toggle as the restorer decides, and Up from either
   behaves as in 1.
3. A multi-season show: Up from a toggle → its card; Up from a card → the season picker. Unchanged.
