# Phase R259 — The rest of the 2026-09-17 sweep

> Everything the stue-TV and Pixel 9 passes recorded as *seen, not fixed* in R257/R258, closed in one
> client phase. None of it could be re-checked on a device the same evening (the TV was in use; the
> Pixel runs the Play build and refuses a sideload as a downgrade) — every item is compiled, and
> unit-tested where it has arithmetic.

## Status

`✓ Built` 2026-09-17 — not dev-reviewed. **On the stue TV (release `1.20-20`):** FR-R259-1 (Search kept *bluey · 3 results* across Back) and FR-R259-2 verified; FR-R259-3/-4/-5 not yet seen on a device (the guide, the phone, and 232 not deployed). Client-only (`ravilo-ui`,
`ravilo-android`); FR-R259-5 consumes phase **232**'s new field.

**Numbering:** verified against `STATUS.md` 2026-09-17 — Ravilo taken through **R258**.

## Requirements

- **FR-R259-1 — Search remembers what it searched for.** Back from a result showed the previous
  results under an *empty* field, relabelled *Suggestions*: the store outlives the composable, the
  text field's `remember` did not. The field now starts from the retained `SearchState.Loaded.query`.
- **FR-R259-2 — the row that holds focus keeps its own heading clear.** The general form of FR-R257-5.
  Moving Down out of an opened row, the row above releases its held height *after* bring-into-view has
  parked the new row, dragging it under the app bar. FR-R257-5 corrected that only in a row J opens on;
  *On Now*, a See-all tile or a system row never ran it (measured: the **On Now** heading at y = 72 px,
  bar ends at 120). `HeadingClearOfAppBar` wraps every Home row: once focus has been in a row for one
  tween + 160 ms it applies `focusDetailRowOpenHeadingDelta` (foot still wins).
  *First attempt, removed:* having the **collapsing** row hand its released height back to the scroll.
  Measured on the stue TV, it did nothing — that row scrolls out of the lazy list and is disposed before
  its delayed work runs. The focused row is never disposed, so the correction lives there.
  **Verified on the stue TV:** walking Down through four rows and into *On Now*, every focused row's
  heading rests at y = 152 px.
- **FR-R259-3 — a long programme keeps its title in view.** In the TV Guide a programme that began
  before the visible window (DR2, 13:00–18:15, seen at 16:20) drew as an untitled slab. The cell's
  label is pinned to the visible part of the cell and measured against the width that is left
  (`guideLabelShiftPx`, 4 tests); it never gets less than 120 dp.
- **FR-R259-4 — the phone's profile picker wraps.** Four tiles are wider than a phone: the first sat
  flush at x = 0 and *Settings* was cut off. The row is a centred `FlowRow` inside the page gutter.
  And the Android window takes the page colour (`#0A0C13`) instead of AppCompat's default grey, which
  showed as a grey band above the app and above the player wherever the system reserves space Compose
  does not paint.
- **FR-R259-5 — light ink gets the dark card back.** A logo tile whose `logo_ink` is `light` (232)
  draws on the dark card; `dark` or unknown keeps R257's light plate.

## Still open, deliberately

A *positioned* subtitle cue (a release tag pinned bottom-left) is not moved by R251's lift. It is one
cue in one file's credits; the lift works for every ordinary cue. Not worth a player change.
