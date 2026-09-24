# Phase R298 — focus is drawn only where a remote drives it

> Owner, 2026-09-24, on the phone showing a TV focus ring and the focus-detail line: *"Yes, let's write a
> spec that removes this, so this focus ring is only on devices with remotes (TVs)."*

## Status

`Planned` — written 2026-09-24 from the Pixel 9 sweep (Play Store v1.36 and debug builds), not
dev-reviewed.

## What the phone shows today

Ravilo's focus treatment was designed for a D-pad: the focused element grows (`TILE_FOCUS_SCALE`), gains
a 3 dp accent ring and a glow, and its label turns bright. On Home, R240's focus-detail *line* then states
the focused title's facts in an 88 dp band at the foot of the page.

On a phone none of that has a reason to exist, and all of it appears anyway:

- **Back to Home from a detail page** puts the TV ring, scale and glow on the Continue Watching card the
  viewer tapped, because focus is restored to it (R200/R236's return-focus rules are platform-blind).
- **R240's line** appears at the foot of Home, over the next row, stating the facts of that restored
  card (*"Creatures, Ltd. · 1080p · 2001 · 92 min"*). R254 kept R240's *row-open* (J) off phones but left
  the line alone.
- **Tapping** anything pulls focus to it (`dpadFocusable`'s pointer path, R157, which exists so a mouse
  and a D-pad agree on the web). So every tapped control is left in its focused state: the detail page's
  Play pill in its brighter focused colour, a genre chip with its arrow, a cast face with its →.
- **The audio & subtitles sheet** draws the TV picker's focus outline on the selected row.

None of this is an error a viewer can act on. It looks like the app is waiting for a remote that is not there.

## Requirements

### FR-R298-1 — One seam decides whether focus is drawn
A `LocalFocusVisible` composition local, provided once at the app root, is `false` on a handset (R256's
`isHandset` — a phone form factor, including a phone-sized web window) and `true` everywhere else (every
TV, and a desktop web window, whose focus follows the mouse hover per R157 and is its hover state). No
screen decides this for itself.

### FR-R298-2 — Focus visuals read the seam; focus itself is untouched
Every visual that is drawn from "this element has focus" (ring, scale, glow, bright label, reveal arrow,
focused colour) is drawn only when `LocalFocusVisible` is true. Focus itself still moves exactly as today:
callbacks, requesters, restorers and key handling are unchanged, so nothing about D-pad navigation on a
TV changes, and a phone with a Bluetooth keyboard still navigates. It just does not paint.

The mechanism is `rememberFocusVisual()`, a drop-in for `remember { mutableStateOf(false) }` whose value
reads false when the seam says so. It replaces the declaration in every component that paints focus.
It does not replace state used for logic: `ContentRow`'s `rowFocused` decides focus restoration after a
row's items change, so it keeps its plain state.

### FR-R298-3 — R240's focus detail is off on a handset
`effectiveFocusDetailMode` returns `"none"` on a handset, so neither the line (L) nor the row-open (J) is
composed there, and Home reserves no 88 dp band for it. This extends R254 (J TV-only) to L. On a desktop
web window L stays as configured.

### FR-R298-4 — The phone's sheet has no focus outline
The audio & subtitles sheet on a handset shows the selected row by its tick and tint only, the same way
every other phone list does. The TV picker is unchanged.

## Invariants
- No TV pixel moves: `LocalFocusVisible` is true on every TV, and `effectiveFocusDetailMode` is unchanged
  there.
- No focus behaviour changes on any platform; only drawing.
- No new strings.

## Out of scope
- Whether focus should be *requested* at all on a phone (entry `requestFocus`, restorers). It is harmless
  once nothing is drawn, and removing it touches the navigation code five phases were spent on (R200,
  R201, R223, R236, R251).
- A phone's pressed/ripple state. Ravilo draws none today; adding one is a design question, not this fix.

## Verification
- Unit: `effectiveFocusDetailMode` on a handset → `"none"`; the visual state reads false with the seam
  off and true with it on.
- Compile: `:ravilo-ui` Android + wasmJs; the release dex guard (the picker rows live outside
  `PlayerScreen`'s body).
- Pixel 9 (debug): Home → a Continue Watching card → Back shows no ring, no scale and no line; the detail
  page's Play pill stays in its resting colour after a tap; the sheet shows no outline.
- Stue TV (release build of the same commit): the ring, scale, line and picker outline are all exactly as
  before.
