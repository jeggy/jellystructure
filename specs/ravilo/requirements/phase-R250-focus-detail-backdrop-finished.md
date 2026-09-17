# Phase R250 — J's backdrop, finished: a scrim you can read through, and a panel inside the safe area

> **R242** shipped with one open question — *"exact scrim contrast needs device verification"* — and
> the answer came back on 2026-09-16 from the stue TV: not enough. Over *The Riddle of Pine Isle* and
> *Frigear* the panel's synopsis sits on bright, high-frequency picture and reads badly at sofa
> distance; the row headings above and below lose contrast with it. The same frames showed a second
> thing R242 could not have known: the panel's text box ends at **x = 1920**, the last pixel column of
> the screen.

## Status

`✓ Built` — written 2026-09-16 from the live stue-TV sweep
(`specs/research-reports/stue-tv-test-sweep-2026-09-16.md`, findings F5, F6 and the season-pill note
in F14), **implemented 2026-09-16** (see §Implementation notes). Not dev-reviewed; **FR-R250-7's
on-device verification is not done** (no device this session), so the contrast numbers below are
computed, not measured. Client-only; closes R242's open question and one R240 layout assumption.
`:ravilo-ui:compileDebugKotlinAndroid` + `:ravilo-ui:compileKotlinWasmJs` clean; `FocusDetailPanelClampTest`
(5) + `GutterBringIntoViewTest` (3) green, R240's scroll tests untouched and green.

**2026-09-17 — scrim numbers superseded by R255** (`phase-R255-backdrop-gradient-in-the-page-colour.md`):
the owner's reading of this build on the stue TV was *readable, not nice* — the wash dims the whole picture
and the panel scrim is a box. R255 replaces both with one gradient in `--bg` and takes over FR-R250-7's
undone measurement. FR-R250-3/-4/-5/-6 and the heading halo stand.

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

## Implementation notes (2026-09-16)

- **FR-R250-1 — panel-local scrim.** `FocusDetailPanel` draws a horizontal gradient of
  `colors.background` under its own column: alpha 0 at the tile-facing edge, **0.94** from 10 % of the
  width onward (Noir **0.96**), over R242's full-screen wash, which is otherwise untouched outside the
  panel. The number is arithmetic, not taste: Aurora's `textSecondary` (#AEB4CB, L≈0.46) clears 4.5:1
  against a pure-white worst-case region only when the blended ground is at L≤0.06, i.e. ≥0.94 of
  `background` (L≈0.003); the title clears 3:1 from ~0.73. The panel gained a 12 dp end padding so text
  never touches the gutter line. The open question (a scrim under L) — no; L has no backdrop under it.
- **FR-R250-2 — headings.** Two mechanisms, as the spec allows: the vertical wash's band stops rise
  0.36/0.46 → **0.50/0.54** (Noir 0.48/0.56 → **0.62/0.66**; the edge stops nudged 0.62/0.60 →
  0.66/0.64, Noir 0.74/0.72 → 0.76/0.74), and every row heading in `StaticContentRow` carries a
  12 px halo in the page's own background colour (`TextStyle(shadow)`) — invisible on plain `--bg`, a
  dark ground behind the letters while the backdrop is lit under the row. No new state or locals.
- **FR-R250-3** — `AppBar(opaque = …)`: Home passes `fdUi?.mode == "rowOpen"`, and the bar's solid
  layer goes to a fully opaque `surface` (from 0.95) for as long as the backdrop is up.
- **FR-R250-5 — the 66 px, found.** `LazyListLayoutInfo.viewportEndOffset` is the row's far edge net
  of its *start* padding only — the screen edge in content space, not the end gutter. `StaticContentRow`
  targeted it, so the panel was allowed to end at x = 1920 and the tile stopped short of the content
  start by the end gutter it had been allowed to spend (96 px, less the tile's own inset ≈ the measured
  66). The target is now `viewportEndOffset − afterContentPadding`; with it the tile lands at the content
  start and the declared width ends at the gutter. Tested with the TV's own numbers.
- **FR-R250-4 — clamp from the measured landing.** After the row-open scroll settles the row reads the
  opening tile's actual offset and computes `focusDetailPanelAvailableWidthPx` (end gutter − grown
  tile − gap); `focusDetailPanelClampedWidthPx` narrows the panel to it when it is smaller than the
  declared width by more than 2 px, never widens it, never below the 280 dp floor. `openPanel` now
  receives that maximum and `HomeScreen` renders `minOf(panelWidth, maxWidth)`. Measuring here is not
  R240's circularity — the tile is always placed; only the panel was not.
- **FR-R250-6** — `rememberGutterBringIntoViewSpec(raviloHPad)` (new, `focus/BringIntoView.kt`, pure
  `gutterBringIntoViewDistance` tested) provided as `LocalBringIntoViewSpec` around the season row: a
  focused pill is never closer than the gutter to either edge; R201's selection `scrollToItem` untouched.
- **Not done:** FR-R250-7 (screenshots + measured contrast on the stue TV) and verification 1's Compose
  UI test — `ravilo-ui` has no UI-test source set; the arithmetic is unit-tested instead. The mockup's
  `.jpanel` arithmetic was not touched (verification 3 does not apply).
