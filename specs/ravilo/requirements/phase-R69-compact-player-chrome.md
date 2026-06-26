# Phase R69 — Compact player chrome + modern play/pause (FR-RV-P2)

> Authored from the design project. Refines the **[R14](phase-R14-player.md)** player and its transport
> chrome; complements **[R44](phase-R44-media-transport-keys.md)** (media keys) and
> **[R77](phase-R77-player-automatic-aspect-ratio.md)** (auto aspect-ratio).

## Problem
The player's transport chrome is heavier than it needs to be: a tall control bar, oversized blocks,
and a play/pause control that reads as dated next to a current streaming app. On a TV the chrome
covers too much of the picture when summoned, and the big glyphs feel clunky. The information is right
(scrubber, time, episode rail, next-up) — the **packaging** is bulky.

## Goal
Slim, modern player chrome: a **compact** transport row that covers less of the frame, with a clean
**modern play/pause** button as the focal control, while keeping every existing affordance (seek bar +
time, skip ±, episode rail, audio/subs picker, next-up countdown) reachable.

## Requirements
1. **Compact transport bar.** Reduce the control-bar height and the surrounding padding/scrim so the
   bar occupies a slimmer band at the bottom; the gradient scrim is lighter and shorter, revealing more
   of the picture. Idle-hide timing unchanged.
2. **Modern play/pause.** Replace the play/pause glyph with a clean, appropriately-weighted modern mark
   (crisp triangle / paired bars), as the visually primary, center-or-left focal control with a tidy
   focus ring (R43 draw-only focus glow). Size for legibility at 10 feet without dominating.
3. **Keep every control.** Scrubber + elapsed/remaining time, skip-back / skip-forward, episode rail,
   Audio & Subtitles picker, and the next-up countdown all remain — restyled to the compact scale, not
   removed. Hardware transport keys (R44) keep working.
4. **Consistent across targets.** The compact chrome applies to the Android and web players from the
   shared player UI; the mockup (`ravilo-player.css`/`js`) is the visual reference for both.
5. **Readable over any frame.** With R77 letterboxing, the bar sits over the black band / lower picture
   cleanly; ensure contrast (scrim) so controls read over a bright scene.

## Invariants
- No loss of function — this is a visual/compactness pass over existing transport controls.
- Reduced-motion + idle-hide behaviour from R14 preserved.

## Out of scope
- Aspect-ratio handling (R77) and subtitle cleaning (R68).
- New player features (chapters, PiP) not already in R14.

## Mockup
`design/ravilo/ravilo-player.css` + `ravilo-player.js` (transport bar, play/pause control, scrim,
scrubber, episode rail). Code: `ravilo-ui/.../screens/PlayerScreen.kt` + transport composables.
