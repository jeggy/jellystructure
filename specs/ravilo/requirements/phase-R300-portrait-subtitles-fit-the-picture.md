# Phase R300 — Portrait subtitles fit the picture

## Status

`Planned` — written 2026-09-24 from a Pixel 9 observation, not dev-reviewed. Spec first. Amends
**R244** FR-R244-10 (the S/M/L row) and R77's subtitle placement on the phone.

## What happens today

In the phone player held in portrait, a 16:9 picture takes the middle third of the screen and the
`SubtitleView` fills the whole screen (it is a sibling of the video box, at the outer `fillMaxSize()`
level, so its bottom padding measures from the true screen edge — a fix for the TV's letterbox case).
Media3 sizes captions as a fraction of **the view's height** (`setFractionalTextSize`,
`DEFAULT_TEXT_SIZE_FRACTION` = 1/20 of the view). On a 2142 px-tall portrait view that is ~96 px
before the S/M/L scale — a line takes a third of the screen width, wraps to four lines, and sits
over the Subtitles / Next / Lock rail (seen 2026-09-24, Pixel 9, Play 1.36; the same in the debug
build at HEAD). In landscape the view is 960 px tall and the size is right.

## Requirements

- **FR-R300-1 — Caption size follows the picture, not the window.** The text size is a fraction of
  the **video box's** height (the DAR-constrained box, or the window when DAR is unknown): the same
  `DEFAULT_TEXT_SIZE_FRACTION × 0.9 × scale` as today, of that height. Landscape, the TV and "fill"
  mode are unchanged by construction (the box is the window).
- **FR-R300-2 — Captions stay off the chrome.** The caption view keeps its screen-anchored bottom
  inset (R251's `subtitleBottomInset`), so nothing about where captions sit relative to the rail
  changes; only their size does.
- **FR-R300-3 — S/M/L keeps meaning S/M/L.** R244's scale multiplies the new base; a viewer's choice
  survives rotation.

## Non-goals

- Moving captions into the letterbox bar in portrait (a design choice for R244's owner, not a fix).
- Web and TV: not affected.

## Acceptance (Pixel 9, debug)

1. Play an episode with subtitles on, portrait: a two-line caption fits within the picture's width
   and does not touch the rail. Rotate to landscape: size unchanged from today.
2. S / L in the sheet still shrink / grow the caption in both orientations.
