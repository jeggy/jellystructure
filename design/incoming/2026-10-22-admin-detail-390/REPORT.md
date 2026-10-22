# The admin's film detail page at 390 px

Task: DEB-2145 · drawn and measured 2026-10-22 · promoted into `design/app/wf.css` and
`design/app/detail.css` in the commit that follows this one.

This is not a direction round: there was one defect, one cause and one shape of fix. What the round
produced is the measurement, the frames and two divergences between the mockup and the shipped page
that a build needs to know about.

## What was measured

Everything below is a `document.scrollWidth` against a 390 px viewport, rendered with the repo's own
Playwright in Docker (`render.mjs` beside this file). *Shipped markup* is `real-markup.html` here —
the detail page's own elements copied out of `MediaDetail.kt`, `TrackEditor.kt` and `Shell.kt` with
stand-in data, which is as close to the live page as a seat with no admin credential can measure.

| | before | after |
|---|---|---|
| shipped markup · overview | **400** | 390 |
| shipped markup · tracks | **498** | 390 |
| `design/app/media.html` · overview | **724** | 390 |
| `design/app/media.html` · tracks | **669** | 390 |

At 1040 px the detail page does not move at all: **0 of 1309 elements change width**, on either tab,
in either theme. Across the 23 admin mockup pages at 1040 px, three pages change — `settings.html`
(1214 → 1040), `ravilo-config.html` (1258 → 1040) and `series-johnnybravo.html` (1175 → 1158) — and
every one of those is an existing sideways scroll going away, not a desktop row being re-drawn.

## The cause, and why the one-property fix is not enough

`.col.fill` is a flex item whose `min-width` defaults to `auto`, so it will not shrink below its own
contents and pushes out of its `.row` — the same thing `.shell`'s `minmax(0, 1fr)` was fixed for in
`app.css`. But `.col.fill { min-width: 0 }` **on its own makes the page worse, not better**: with
`flex: 1` the column's flex base size is 0, so once it can shrink it stops wrapping below the 220 px
poster rail and is handed the ~112 px that is left. Rendered with only that declaration, the Tracks
card header's `Audio | Subtitles` control measures **2 px wide** with both its labels outside it —
which is the "~4 px sliver" this task reports as a second symptom. It is not a second defect; it is
the first one's fix applied halfway.

So the promotion is four rules, three of them phone-only (`max-width: 640px`):

```css
.col.fill { min-width: 0; }                 /* wf.css, all widths */
@media (max-width: 640px) {                 /* wf.css */
  .row { flex-wrap: wrap; }
  .row > .fill { flex-basis: 100%; }
  .fill { min-width: 0; }
}
@media (max-width: 640px) {                 /* detail.css, in the block that is already there */
  #trailer-card .tr-thumb { width: 100%; height: auto; aspect-ratio: 16 / 9; }
}
```

The trailer rule is its own defect: a 190 px thumbnail leaves ~116 px beside it at 390 px, which is
narrower than the card's own `Re-fetch from TMDB` button, so that button hung 10 px off the viewport
with every row above it already wrapping.

## The trap this leaves behind

**A `.row` that a page turns into a column must now also say `flex-wrap: nowrap`.** A wrapping
*column* flex container lays its overflow out in a second column and gets wider, not narrower.
`design/app/subtitles.html` is the one such row in the tree — its own `@media (max-width: 1080px)`
rule makes `.subwrap` a column — and it went from 390 px to 405 px until that line was added. It is
the only page in the sweep that regressed, and it is now green again. The rule carries this warning
in `wf.css` beside it.

## Two places where the mockup and the shipped page disagree

Found while building the harness, both of them things a build has to know and neither of them fixable
from this seat:

1. **The Tracks column-header strip is an inline grid in `TrackEditor.kt:209`, not `.trk-cols`.** So
   `detail.css`'s `.trk-cols { display: none }` — the AVI→MKV round's phone treatment, which hides
   that strip at 390 px and drops the track rows to three columns — **does not reach the live page**.
   The rows narrow and the header does not, so the labels stop lining up with what is under them.
   Visible in `shipped-markup-*-tracks-390-*.png`, both before and after; the mockup does not show it
   because the mockup uses the class. One attribute in the Kotlin.
2. **The shipped overview row already carries inline `flex-wrap: wrap` and a 220 px rail** (the
   mockup: no wrap, 240 px), and the shipped `Genres` field is full width rather than sharing a row
   with `Original language`. Neither changes the fix; both change which numbers you get if you
   re-measure, which is why they are written down.

`Settings.kt:81` and `RaviloConfig.kt:350` already carry `style="min-width:280px"` on their
`.col.fill`, which is the same fix made by hand in one place. Inline style wins, so those two pages
are untouched by the stylesheet rule in production — the mockup improvement measured above is the
mockup only.

## Seen in the PNGs, not fixed here

Pre-existing at 390 px, unchanged by this promotion (none of them is a `.row` or a `.fill`, and the
element-by-element diff confirms not one of them moved):

- The full-width `.note` strips — *Locked in Jellyfin*, *Drift detected*, *Can't play in Ravilo*,
  *Cover as video* — keep a desktop three-part layout at 390 px: the badge holds a wide empty column
  and the sentence is squeezed into ~150 px beside it.
- The rail's *Language mismatch* note reads one word per line, at 1040 px as well as 390 px.
- The sidecar list breaks a filename mid-token (`...forced.s / rt`) once the card fits the viewport.

Each is a phone treatment for a shared component rather than a layout bug, so each is Bárður's call
whether it is worth a round.

## Frames

16 PNGs in `png/`, 1× scale, full page:

- `admin-detail-390-media-{overview,tracks}-{390,1040}-{dark,light}.png` — the mockup detail page.
- `admin-detail-390-shipped-markup-{before,after}-{overview,tracks}-390-{dark,light}.png` — the
  shipped markup, with the four declarations in place and reverted (`?state=before`, held in the
  harness itself so the pair is one file and one render).

`render.mjs` is a prototype beside the round, not a replacement for `scripts/render-design.sh`: that
script cannot pin a theme, and headless Chromium reports light, so half of these frames need
`js-theme` set before the page boots.
