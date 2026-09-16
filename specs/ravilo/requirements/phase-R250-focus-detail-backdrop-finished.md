# Phase R250 — J's backdrop, finished: a scrim you can read through, and a panel inside the safe area

> **R242** shipped with one open question — *"exact scrim contrast needs device verification"* — and
> the answer came back on 2026-09-16 from the stue TV: not enough. Over *The Riddle of Pine Isle* and
> *Frigear* the panel's synopsis sits on bright, high-frequency picture and reads badly at sofa
> distance; the row headings above and below lose contrast with it. The same frames showed a second
> thing R242 could not have known: the panel's text box ends at **x = 1920**, the last pixel column of
> the screen.

## Status

`Planned` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, findings F5, F6 and the season-pill note
in F14). Not dev-reviewed, not built. Client-only; closes R242's open question and one R240 layout
assumption.

**Numbering:** verified against `STATUS.md` on 2026-09-16 — Ravilo taken through R245; R246–R249 by
sibling specs the same day.

## What was observed

Two frames, both with J open on a Continue Watching (LANDSCAPE) tile, Aurora skin:

- *The Riddle of Pine Isle* — synopsis in `textSecondary` grey over sunlit forest; genre chips
  `Action & Adventure` / `Reality` / `Documentary` at low contrast; "Continue Watching" and "Newly
  Added — Movies" headings washed; the Collections rail, scrolled under the translucent app bar, now
  lit by the backdrop and visible as clutter behind `Home · Movies · Series · Discover`.
- *Frigear* — the synopsis runs across the backdrop's own FRIGEAR sign; `"…tvinger det ham t…"` is
  ellipsised at the screen's right edge.

From the semantics tree in the *Frigear* frame (px, 1920-wide):

```
open tile        [162,307][822,678]      ← the previous tile still shows 160 px at the left
synopsis         [864,585][1920,761]     ← measured to the last column
```

## What the code does (traced against `main`, 2026-09-16)

- **Scrim.** `FocusDetailBackdrop.kt:74-90`: a vertical gradient of `colors.background` at alpha
  0.62 / 0.36 / 0.46 / 0.60 (Aurora; Noir 0.74 / 0.48 / 0.56 / 0.72) — dark at the top and bottom edges,
  thinnest across the band where the panel actually sits (0.36 at 20 %, 0.46 at 62 %). It was built
  "behind full-width row content on every edge alike" (its own comment) — i.e. for the picture, not
  for the text.
- **Panel width.** `FocusDetailPanel.kt:69-74` — `screenWidth − 2·raviloHPad − grownTile −
  itemSpacing`, on the stated assumption that "the panel always ends exactly at the row's own end
  gutter" *because the opening tile is brought to the row's content start*. `raviloHPad` is 48 dp
  (`Dimens.kt:26-31`), 96 px on this set. The tile landed at **162 px**, 66 px right of the content
  start, with 160 px of the previous tile still visible — so the panel, sized for a tile at 96 px,
  overruns the end gutter by the same 66 px, and the synopsis (`:149-158`, `maxLines = 4`,
  `Ellipsis`) is measured to the screen edge. The mockup's own panel (`design/ravilo/ravilo.css`) has
  the same arithmetic and the same assumption.
- **Season pill.** `SeasonPicker.kt:66` scrolls only on `selectedIndex`; a merely *focused* pill is
  brought into view by the lazy list's default edge alignment — to the viewport edge, inside the
  content padding — which is how *Season 17* sat 25 px from the screen edge while focused.

## Requirements

**FR-R250-1 — A contrast floor for the panel, met by a scrim under the panel.** Body text in the
panel meets ≥ 4.5:1 and the title ≥ 3:1 against the worst-case region of any backdrop behind it. The
mechanism is a second, panel-local scrim — a horizontal gradient under the panel's column (opaque
enough behind the text, feathering out toward the tile) — layered over R242's full-screen vertical one,
**not** a darker full-screen wash: the picture is the point of R242 and stays visible outside the
text. Noir keeps its own deeper values (R242 FR-R242-7's precedent).

**FR-R250-2 — Row headings keep their contrast.** The row title above the open row and the next row's
heading below it read against the backdrop as they do against `--bg`: the vertical scrim's band
values (the 0.36 / 0.46 stops) are raised or the headings get their own local backing. Verified on the
same two frames.

**FR-R250-3 — The app bar is opaque to what scrolls under it while J is open.** The Collections rail
(or anything else) scrolled beneath the translucent app bar must not show through over a backdrop; the
app bar's own scrim deepens for as long as `bgActive`, or the content is clipped at the app bar's
bottom edge.

**FR-R250-4 — The panel ends at the end gutter, whatever the tile did.** The panel's right edge is
`viewportEnd − raviloHPad`, computed from where the open tile *actually* landed (its measured x), not
from where the row intended to put it. If the scroll lands short, the panel is narrower, never wider
than the screen. Acceptance: with J open on any tile in any row, no text node's bounds exceed
`screenWidth − raviloHPad`.

**FR-R250-5 — Land the tile where the arithmetic says.** Separately from FR-4's clamp: `StaticContentRow`'s
"bring the opening tile to the row's content start" must actually land it at the content start (the
66 px miss is measured; find whether it is the focus-scale inset, the ring, or the scroll target). FR-4
makes the panel correct either way; FR-5 makes the row honest.

**FR-R250-6 — A focused season pill sits inside the safe area.** Bring-into-view for the season row
uses `raviloHPad` as its margin on both sides: a focused pill is never closer than the gutter to
either screen edge. Selection-driven `scrollToItem` (R201) unchanged.

**FR-R250-7 — Verified on the device that produced the frames.** *The Riddle of Pine Isle*, *Frigear*,
one POSTER-row title and one Noir pass, screenshots in the build note, before `✓ Built`. Measure
contrast from the screenshots, not by eye.

## Non-goals

- R242's fade timings, crossfade, or when the backdrop shows.
- R240's reflow / scroll-target rules beyond FR-5's landing accuracy.
- L (the line) — untouched.
- **The foreign-certification badge** (`SE · Från 7 år` on a Danish/Faroese household's TV) seen in
  the same panel is a *decision*, recorded here so it is not lost, and not an FR: phase 155 normalises
  certifications to a number for filtering and the badge deliberately shows the source cert with its
  country. Owner's call whether the panel should prefer the normalised age when the cert's country is
  not the household's.

## Verification

1. FR-R250-4's bounds assertion as a Compose UI test at 960 dp × 540 dp with a LANDSCAPE and a POSTER
   tile opened at the row's end and at its start.
2. FR-R250-7 on the stue TV.
3. `scripts/check-mobile-css.sh` green if the mockup's `.jpanel` arithmetic is touched to match FR-4.

## Open questions

- Whether FR-R250-1's local scrim should also sit under L's line (it does not today; L reads fine on
  `--bg` because there is no backdrop under L — R242 FR-R242-1). Probably no.
