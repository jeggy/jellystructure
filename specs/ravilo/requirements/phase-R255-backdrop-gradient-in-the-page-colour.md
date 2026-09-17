# Phase R255 — A gradient in the page's own colour over J's backdrop

> **R242** put the focused title's backdrop behind the whole screen; **R250** made the panel readable
> over it with an opaque box. Owner direction 2026-09-17, after living with R250 on the stue TV: put
> a **small, dark gradient in the page's own background colour** over the backdrop while it is shown,
> so the picture is easier on the eyes and the description and facts are easier to read. This phase
> replaces R242's flat wash and R250's box with **one gradient, in `--bg`, shaped for where the text
> is** — and makes the mockup and the app draw the same thing, which today they do not.

## Status

`⚠ Partial` 2026-09-17 — **coded at the starting stops, not measured.** `FocusDetailScrims.kt` is the one stop table (FR-R255-7); `FocusDetailBackdrop.kt` draws the head/mid/floor gradient, `FocusDetailPanel.kt` the reading gradient (40 % feather + a 16 dp top/bottom DstIn mask in its own offscreen layer, so the mask never touches the text); `ravilo.css` carries the same numbers via a new per-skin `--bg-rgb` and gains the mockup's first panel scrim. Android + Wasm compile, 126 unit tests green, both CSS fences green. **FR-R255-6 (the stue-TV measurement, R250-7's debt included) is NOT done** — the TV was in use; it stays `Partial` until the owner schedules that pass and the stops are corrected from pixels. Was `Planned` — written 2026-09-17, not dev-reviewed, not built. Client (`ravilo-ui`) plus the design
mockup (`design/ravilo/ravilo.css`); no backend, payload or config change. Supersedes the scrim
*numbers* of R242 FR-R242-7 and R250 FR-R250-1/-2 and takes over R250's still-open FR-R250-7 (the
on-device contrast measurement was never done). Everything else in R242/R250 stands.

**Numbering:** verified against `STATUS.md` on 2026-09-17 — Ravilo taken through **R253**, and
**R254** by the sibling TV-only spec the same day. Ravilo-only, no admin pair.

Design: `design/ravilo/ravilo.css` `.jbg-scrim` + `.jpanel` (this phase's mockup home; both change),
`design/ravilo/ravilo-focus.js` (`showBg`/`bgLayer`, unchanged), and the same two round-2 canvases
R242 built on.

## Current state (traced against `main`, 2026-09-17)

**The app draws two layers, and they are two different ideas.**
- `FocusDetailBackdrop.kt` — R242's full-screen **vertical wash** of `colors.background`, raised by
  R250 to alpha **0.66 / 0.50 / 0.54 / 0.64** at 0 / 20 / 62 / 100 % (Noir 0.76 / 0.62 / 0.66 / 0.74).
  Its thinnest point is still half-opaque: the whole picture is dimmed roughly evenly, and the
  backdrop reads as a grey photograph rather than a picture sinking into the page.
- `FocusDetailPanel.kt` — R250's **panel-local horizontal gradient**: transparent at the tile's edge,
  **0.94** from 10 % of the panel's width onward, flat to the end (Noir 0.96), on a `Row` that fills
  the row band's height. Ten percent of a ~480–630 dp panel is 48–63 dp of feather; after that it is
  an opaque rectangle with a straight top edge and a straight bottom edge on the picture. The
  arithmetic behind 0.94 is right (`textSecondary` needs a blended ground at L ≤ 0.06 to clear 4.5:1
  over a pure-white region) — it is the *shape* that reads as a box.
- Plus R250-2's 12 px `background`-coloured halo behind every row heading and R250-3's opaque app bar
  while J is open. Both stay.

**The mockup draws something else, in a different colour.** `ravilo.css` `.jbg-scrim` is
`rgba(0,0,0,…)` — **black**, at R242's original pre-R250 stops (0.62 / 0.36 / 0.46 / 0.60) — and the
mockup has **no panel scrim at all** (R250's implementation note: "the mockup's `.jpanel` arithmetic
was not touched"; its scrim was not added either). So the design file the owner reviews shows a black
tint and unreadable text where the TV shows a page-coloured wash and a box. Neither is the design.

**Everywhere else, the product darkens a picture with the page colour, as a gradient, never black.**
The hero: `HeroCarousel.kt` `tintGradient` (`background` 0.82 → 0.35 → transparent, left to right) and
`floorGradient` (transparent → 0.6 at 55 % → `background` at the bottom); in CSS the skin's own
`--hero-tint` / `--hero-floor` (`rgba(10,12,19,…)` for Aurora — the page colour as a literal, because
a CSS variable cannot take an alpha). The detail hero, the app bar, L's own foot strip (`Transparent →
background 0.96`) — all of them. J's backdrop is the one surface whose mockup reaches for black and
whose app build reaches for a flat wash. Both skins' `--bg` values are already identical in the two
files: Aurora `#0A0C13`, Midnight `#04101A`, Noir `#080807` (`Colors.kt`, `ravilo.css` `:root` /
`[data-skin]`).

**What the owner has seen.** R250 is on the stue TV (build `1.18-3-g0a6b45ea`, sideloaded
2026-09-17); this ask is the reading of that result at sofa distance: readable, not nice. The
sweep's F5 (`stue-tv-test-sweep-2026-09-16.md`) is the underlying observation and still the only
device evidence — R250's numbers were computed, never measured (FR-R250-7, not done).

## Goal

One gradient, in the page's background colour, over the backdrop for exactly as long as the backdrop
is shown: nearly transparent where the picture is, sinking to the page where the text is, with no
straight edge anywhere on the picture. The contrast floor R250 set is kept as the test; the picture
staying visible becomes a test too. The mockup and the Compose build carry the same stops.

## Functional requirements

**FR-R255-1 — The colour is the page's own background, per skin, never black.** Every stop of every
gradient in this phase is `colors.background` / `--bg` with an alpha — Aurora `#0A0C13`, Midnight
`#04101A`, Noir `#080807` — so the picture sinks into the page it sits on rather than turning grey.
The mockup's `.jbg-scrim` drops its `rgba(0,0,0,…)`; because a CSS variable cannot be given an alpha,
the mockup states the page colour as a literal per skin, exactly as `--hero-tint` already does, or
gains a `--bg-rgb` triple per skin — either is fine, black is not.

**FR-R255-2 — A gradient shaped for the text, not a wash and not a box.** Three parts, all in
`--bg`, all inside the backdrop's own layer so they fade with it (FR-R255-5):

| part | axis | starting stops (alpha of `--bg`) | what it is for |
| --- | --- | --- | --- |
| **head** | vertical, top of screen | `0.70` at 0 % → `0` at 18 % | the app bar's ink and the heading of the row above the open row |
| **floor** | vertical, bottom of screen | `0` at 45 % → `0.55` at 75 % → `1.0` at 100 % | the next row's heading; the picture dissolves into the page instead of ending at the screen edge — the hero's own `floorGradient` idiom |
| **mid** | vertical, between them | **≤ 0.25** across the band where the picture is (R250 had 0.50–0.54 here) | the "small" in the ask: the picture is seen, not dimmed |
| **reading** | horizontal + vertical, under the panel | `0` at the tile's edge → `0.94` by **40 %** of the panel's width (was 10 %), flat to the end; **and** feathered `0 → full` over the top and bottom **16 dp** of the panel's height | the text's ground, reaching the contrast floor behind the text without a straight edge on any side |

The head, floor and mid are one `verticalGradient` (the successor of R242's wash); the reading part
is the successor of R250's panel scrim and stays panel-local (it opens wherever the tile is —
open question 1). How the reading part's two axes are combined is the implementer's call (two brushes
with `BlendMode.DstIn`, a radial, or a rounded-corner feather); the requirement is that **no straight
edge of the scrim is visible on the picture** on any side of the panel, at any tile position, on any
of the three tile variants.

These numbers are **starting values**, not taste and not measurement: the reading part's 0.94 is
R250's arithmetic and holds; every other stop is a guess to be corrected by FR-R255-6.

**FR-R255-3 — The contrast floor stays, and the picture's visibility becomes a number.** As R250
FR-R250-1/-2: panel body text ≥ 4.5:1 and the title ≥ 3:1 against the worst-case region of any
backdrop; the row headings above and below the open row read as they do on plain `--bg` (the R250-2
halo stays and is allowed to do part of this work). New: in the **mid** band, away from the panel,
the scrim may reduce the picture's mean luminance by **no more than half** of what R250's wash does
today — i.e. the picture is measurably more visible, not merely differently tinted. Both are read
from screenshots (FR-R255-6), not judged by eye.

**FR-R255-4 — Noir goes deeper by the same margin as today, and keeps the image.** R242 FR-R242-7's
rule restated: Noir adds roughly `+0.10` to every stop (its wash today is `+0.10` over Aurora's, its
reading part `0.96` over `0.94`) and never drops the picture — less colour, not less picture. The
three skins otherwise share one shape.

**FR-R255-5 — Nothing else changes.** R242's two-speed fade (FR-R242-3: `ROW_OPEN_BG_FADE_IN_MS` 550 /
`_OUT_MS` 500 against the 220 ms panel tween), the hop crossfade (FR-R242-5), the fade-out on clear
(FR-R242-6), the no-prefetch rule (FR-R242-8), R250's opaque app bar (FR-R250-3), the panel clamp
(FR-R250-4) and the heading halo (FR-R250-2) are untouched. The vertical gradient stays inside the
backdrop's `AnimatedVisibility` (it already is), and the reading part stays inside the panel's, so
neither ever appears or disappears on a different clock from the thing it is scrimming — a scrim that
flashes without its picture is a new defect, not a smaller one.

**FR-R255-6 — Measured on the stue TV before `✓ Built`, the R250-7 debt included.** The same frames
R250 named — *The Curse of Oak Island* and *Tomgang* (Continue Watching, LANDSCAPE), one POSTER-row
title, one Noir pass — screenshotted, with contrast for the body text, the title and both headings and
the mid-band luminance delta of FR-R255-3 read from the pixels. The build note records the measured
numbers and the stops they settled on; if the starting stops in FR-R255-2 move, the mockup moves with
them (FR-R255-7). The TV pass happens when the owner schedules it, per the household's own rule.

**FR-R255-7 — One definition, two renderers.** The stop table in FR-R255-2 (as corrected by
FR-R255-6) is the source; `ravilo.css` (`.jbg-scrim` reshaped, a reading gradient added to `.jpanel`
— the mockup's first panel scrim) and `FocusDetailBackdrop.kt` / `FocusDetailPanel.kt` both carry it,
each with a comment naming the table. A future change to one without the other is the defect this
phase found; `scripts/check-mobile-css.sh` and `scripts/check-css-scoping.sh` run green after the
mockup edit.

## Non-goals

- **No blur, no parallax, no Ken Burns** on the image (R242's non-goal, still).
- **No separate switch** for the backdrop or the gradient (R242 open question 4 remains open).
- **No change under L.** L has no backdrop under it (R242 FR-R242-1); its own foot gradient is
  already `--bg`.
- **No change to the hero's or the detail page's scrims** — they are the idiom this phase copies,
  not its subject.
- **No new payload** — the image and its null fallback are as R242 FR-R242-2 left them.

## Acceptance

1. Stue TV, Aurora, J open on *The Curse of Oak Island*: the forest is visibly a forest across the
   middle of the screen; the synopsis, chips and title clear the contrast floor; no straight scrim
   edge is visible around the panel; the headings above and below read.
2. Same on *Tomgang* (bright sign behind the text) and on a POSTER-row title (a narrower tile, a
   wider panel).
3. Noir: deeper, same shape, the picture still there.
4. A lateral hop and a row leave behave exactly as on `main` (R242 FR-R242-5/-6) — the gradient never
   shows without its picture and never lingers after it.
5. `design/ravilo/Ravilo TV.html` with FOCUS = Row · J shows the same shape in the same colour; no
   black anywhere in `.jbg-scrim`.
6. The measured numbers of FR-R255-6 are in the build note, and the two renderers' stops are equal.

## Source references

- `ravilo-ui/src/commonMain/.../components/FocusDetailBackdrop.kt` — the vertical wash (R242 →
  R250 values), `AnimatedVisibility` / `AnimatedContent`, the null-backdrop surface fill.
- `ravilo-ui/src/commonMain/.../components/FocusDetailPanel.kt` — `panelScrim` (R250: `0 → 0.94 at
  10 %`), the `Row(...).fillMaxHeight().background(panelScrim)` that gives it straight edges.
- `ravilo-ui/src/commonMain/.../components/HeroCarousel.kt` — `tintGradient` / `floorGradient`, the
  idiom; `components/ContentRow.kt` — the R250-2 heading halo (`TextStyle(shadow)`);
  `components/AppBar.kt` — `opaque`; `theme/Colors.kt` — `background` per skin; `theme/Motion.kt` —
  `ROW_OPEN_BG_FADE_IN_MS` / `_OUT_MS`.
- `design/ravilo/ravilo.css` — `.jbg`, `.jbg-img`, `.jbg-scrim` (black today), `.jpanel` / `.jp-body`
  (no scrim today), `--bg`, `--hero-tint`, `--hero-floor` per skin; `design/ravilo/ravilo-focus.js` —
  `bgLayer` / `showBg` / `openRow` (the layer's DOM; unchanged).
- `specs/ravilo/requirements/phase-R242-focus-detail-backdrop-background.md` — FR-R242-1/-3/-5/-6/-7/-8,
  open questions 1 and 4. `phase-R250-focus-detail-backdrop-finished.md` — FR-R250-1/-2/-3/-4/-7 and
  the implementation notes (the 0.94 arithmetic, the mockup left untouched).
  `specs/research-reports/stue-tv-test-sweep-2026-09-16.md` — F5.

## Open questions

1. **Panel-local or page-anchored reading gradient?** The reading part follows the panel here because
   the panel opens wherever the focused tile is (first tile, last tile, POSTER or LANDSCAPE); a fixed
   bottom-left page gradient would be simpler and edge-free but would miss a panel that opens at the
   top of the screen. Lean: panel-local, as specified; revisit only if the two-axis feather proves
   costly on the BRAVIA (invariant 11 — a second full-band brush per open is cheap, but say so with a
   frame trace, as R240 did).
2. **Does the R250-2 heading halo become redundant** once the head and floor bands carry the
   headings? Keep it until FR-R255-6 has numbers; removing it is a one-line follow-up, adding it back
   after a regression is a sweep.
3. **Should the mid band be even lighter?** "≤ 0.25" is chosen so that a bright, high-frequency
   backdrop behind the *tiles* of the open row (not the panel) does not fight their labels; if the
   measured frames show the labels fine at 0.15, go lighter — the picture is the point.
