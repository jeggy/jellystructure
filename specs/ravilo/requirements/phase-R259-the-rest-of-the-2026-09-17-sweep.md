# Phase R259 — The rest of the 2026-09-17 sweep

> Everything the stue-TV and Pixel 9 passes recorded as *seen, not fixed* in R257/R258, closed in one
> client phase. None of it could be re-checked on a device the same evening (the TV was in use; the
> Pixel runs the Play build and refuses a sideload as a downgrade) — every item is compiled, and
> unit-tested where it has arithmetic.

## Status

`✓ Built` 2026-09-17 — not dev-reviewed. **On the stue TV (release `1.20-20`):** FR-R259-1 (Search kept *ruffy · 3 results* across Back) and FR-R259-2 verified; FR-R259-5 (Channel 4 / Hulu / TV3 on the dark card), FR-R259-6 (*GONE MISSING* in light ink, *North Ridge* untouched) and FR-R259-7 (6-up walls) verified there later the same evening against v1.22/v1.23. **Still not seen on a device:** FR-R259-3/-3a (the guide label pin — unit-tested only; the TV was in use) and FR-R259-4 (the phone — the Pixel runs the Play build). Client-only (`ravilo-ui`,
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

- **FR-R259-6 — a dark-ink title logo is drawn in the page's light ink.** *Gone Missing*'s black clearlogo
  was nearly invisible on the tinted detail hero (stue TV, 2026-09-17 evening). When `logo_ink` is `dark`
  (232 FR-232-5/6) `TitleLogoOrText` draws the logo through a tint in `colors.text` — the shape survives,
  the ink becomes legible; a multi-colour dark logo flattens to a silhouette, which is still its wordmark.
  `light` or unknown draws the artwork untouched. Applies to the Home hero and both detail heroes (one
  composable).
- **FR-R259-3a — the guide label reaches the visible edge.** Seen on the TV: a cell mostly scrolled off
  showed the *middle* of its title ("m den … gefamilie"), because the label's reserved minimum (120 dp)
  stopped the shift short. The minimum is 48 dp: the label always starts at the visible edge and
  ellipsizes in what is left.

- **FR-R259-7 — the walls are 6-up on a TV (owner decision, 2026-09-17, on seeing them on the stue TV).**
  Supersedes R243 FR-R243-2's 4-up (studios, networks) and 5-up (genres), which were the mockup's numbers
  for a 1920-px canvas and left each tile ~200 dp wide. All three walls are 6 across on a TV; the tile is
  ~130 × 88 dp (genres 72), the caption 15 / 13 sp stacked, a wordmark 15 sp on up to three lines. The
  phone's 2-up / 3-up is unchanged. The mockup's `.taxo-row` follows (`repeat(6, 1fr)`).

## Still open, deliberately

A *positioned* subtitle cue (a release tag pinned bottom-left) is not moved by R251's lift. It is one
cue in one file's credits; the lift works for every ordinary cue. Not worth a player change.
