# Phase R300 — Portrait subtitles fit the picture

## Scope

Written 2026-09-24 from a Pixel 9 observation. Amends **R244** FR-R244-10 (the S/M/L row) and R77's
subtitle placement on the phone. Android's Media3 caption path only (`CaptionSizing.kt`,
`PlayerVideoSurface`, `RaviloPlayer.applySubtitleSize`). Amended 2026-10-25 for the TV (DEB-2488),
below.

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

## Amendment 2026-10-25 — a wide film does not shrink the captions (DEB-2488)

**What went wrong.** FR-R300-1 promised that the TV was "unchanged by construction (the box is the
window)". That is true only for a 16:9 film. As built, the base height is
`min(windowHeight, windowWidth / dar)`, and for a film wider than the screen the picture is shorter
than the screen. A 2.40:1 film on a 16:9 TV has a picture 800 px tall in a 1080 px window, so since
2026-09-24 its captions have been sized from 800 px instead of 1080 px. That makes them **26 %
smaller** than before R300, and smaller than a 16:9 episode on the same TV: 3.6 % of the screen's
height instead of 4.8 %. The owner noticed it on the living-room TV on 2026-10-25 while a 2.40:1
film with SubRip subtitles was playing ("Subtitles on tv seems a bit smaller than they should"). The
unit test `cinemascope_on_a_tv_sizes_from_the_narrower_picture` pins the defect as if it were
intended.

- **FR-R300-1 (amended) — Captions are sized by the screen, never by the film's shape.** The text
  size is the same `DEFAULT_TEXT_SIZE_FRACTION × 0.9 × scale` as before, taken of the height of **a
  16:9 frame fitted inside the window**: `min(windowHeight, windowWidth × 9 / 16)`. Neither the film's
  display aspect ratio nor fit/fill enters the calculation. On a 16:9 TV that frame is the screen, so
  every film and every episode draws captions at the same size. In portrait on a phone it is the
  16:9 picture across the width, which is exactly what R300 fixed.

  | Window | Film | Before R300 | R300 as built | Amended |
  |---|---|---|---|---|
  | TV 1920×1080 | 16:9 | 1080 | 1080 | 1080 |
  | TV 1920×1080 | 2.40:1 | 1080 | **800** | 1080 |
  | TV 1920×1080 | 4:3 | 1080 | 1080 | 1080 |
  | Phone, landscape 2142×960 | 16:9 | 960 | 960 | 960 |
  | Phone, landscape 2142×960 | 2.40:1 | 960 | 892 | 960 |
  | Phone, portrait 960×2142 | 16:9 | 2142 | 540 | 540 |
  | Phone, portrait 960×2142 | 2.40:1 | 2142 | 400 | 540 |
  | Phone, portrait, fill or no frame yet | any | 2142 | 2142 | 540 |

  The table gives the base height in px. The caption is about 4.8 % of it at M. The last row also
  closes a gap R300 left: in portrait, fill mode and the moment before the first frame still sized
  captions from the full 2142 px window, which is the ~96 px caption R300 was written against.
- FR-R300-2 and FR-R300-3 are unchanged. S · M · L multiplies the amended base.

**Not changed by this amendment.**
- The `0.9` under Media3's default. If captions still read small on the TV once a wide film matches
  a 16:9 episode, raising it is a separate owner decision.
- Ravilo Web, the Tizen app and the Cast receiver. They size their cues their own way, not through
  Media3.
- A size control on the TV itself. The TV's player has no S · M · L row (FR-R244-10 put it on the
  phone only). The cast remote's S · M · L for a film playing in the TV app is FR-R245-8's, and a
  gap there is a restore of that FR, not part of this amendment.

**Acceptance (amended).**
3. The TV app plays a 2.40:1 film and then a 16:9 episode with subtitles on: the caption letters are
   the same height in both. Before this fix the film's were visibly smaller.
4. Pixel 9, portrait: acceptance 1 still holds for a 16:9 episode, and a 2.40:1 film's caption is
   the same size as the episode's.
