# Phase R257 — TV sweep 2026-09-17: where focus lands, logos you can see, a hero you can read

> The smaller defects from the 2026-09-17 stue-TV sweep, none of them a regression of the last two
> days except the first two, all of them visible within a minute of picking up the remote. Siblings:
> **229** (Continue Watching leaves Home after a stop) and **R256** (the TV renders the phone's player).

## Status

`✓ Built` 2026-09-17 — written the same day from live observation plus a code trace per item, not dev-reviewed, **verified on the stue TV the same day** (release `1.19-14`, sideloaded with the owner's permission): acceptance 1–4 seen; 5 (the phone glyphs) still owed on the Pixel 9. 122 `ravilo-ui` unit tests green; Android + Wasm compile clean.
Client-only (`ravilo-ui`). Touches R187/R243 (entry focus, tile backing), R244 (one glyph), the
detail hero (R24-era gradient) and R240 (the reveal). No wire change.

**Numbering:** verified against `STATUS.md` 2026-09-17 — Ravilo taken through **R256**.

## Findings and requirements

### 1 · Selecting a tile lands you on the *Home* tab

Select *Marvel Studios* on the Studios wall: the seeded grid opens with focus on the app bar's first
item, **Home** — one stray OK leaves the page. `SeededBrowseScreen.kt:359` requests `navBarFR` on every
fresh entry, a rule copied from the tab screens (where the app bar *is* where you came from). A seeded
page is pushed from a tile, a See-all tile or a cast face; the viewer's attention is on the content.
`activeNav` is `-1` there, so the bar's requester falls on index 0.

- **FR-R257-1** — a fresh entry to a seeded browse page moves focus to the **first grid cell** as soon
  as the first non-empty result is composed. Until then the app bar holds focus (R60: something must,
  or Back bypasses Compose). If the viewer has already pressed a key that moved focus off the bar, the
  hand-over is skipped. An empty result leaves focus on the bar. A Back-return still restores the
  opened cell (R139), unchanged.

### 2 · Back from a studio's grid forgets the tile

Back from *Marvel Studios* focuses the **Studios chip**, not the tile. `TaxonomyScreen.kt:168` checks
`focusSegmentOnEntry` first, and the destination on the stack still carries `focusSegment = true` from
the chip press that selected the tab — so the restore branch below it is never reached on the most
common path (switch tab, open tile, Back).

- **FR-R257-2** — a pending tile restore wins over the chip. `lastSelectedKey` is consumed by the
  restore (cleared once used) so a later tab switch back to the same wall focuses the chip, not a
  tile from an earlier visit.

### 3 · Dark logos on a dark card

Columbia, Lionsgate, Pixar and the word *STUDIOS* beside Marvel's red box are invisible: the tile is
`surfaceVariant` and the artwork is dark ink on transparency. Measured over every logo production
holds (94 studios + 41 networks, alpha-weighted ink luminance): **88 dark** (< 0.3), **37 mid**,
**10 light** (> 0.7, two of them opaque white rectangles). TMDB's company logos are drawn for a light
page. This is 192 FR-192-5's finding again, on the viewer's side.

- **FR-R257-3** — a tile **with a logo** draws it on a light plate (`#E8EAF0`, the card's own shape and
  size; focus ring and scale unchanged). A **wordmark** tile keeps the dark card and light ink. Noir
  uses the same plate. The mockup's `.taxo-card` gains the same rule.
- **Open, deliberately:** the ~8 genuinely light-ink logos (Channel 4, Zwart Arbeid, CBeebies…) read
  worse on the plate than they do today. Fixing that needs the *server* to say which ink a logo has
  (one number at capture time, 216's payload); 7 % of tiles did not justify holding the other 93 %.

### 4 · The detail hero has no tint

On *Frigear* the app bar, the facts and the synopsis sit on a sunlit shop front. The detail hero has a
single floor gradient (`transparent → 0.55 at 45 % → background`), unchanged since June; Home's hero
has had a left-to-right `tintGradient` (0.82 → 0.35 → 0) *and* a floor since R24. Same artwork, same
text column, two treatments.

- **FR-R257-4** — both detail heroes (`SeriesDetailScreen`, `MovieDetailScreen`) draw Home's
  `tintGradient` under the floor on non-compact layouts, plus a head band (`background` 0.70 → 0 over
  the app bar's height + 40 dp) so the nav reads over a bright sky. Compact (phone portrait) keeps the
  floor only: the text column is full-width there and a side tint would cover the whole picture.
  One shared definition (`HeroScrims`), used by Home's hero too, so the three cannot drift again.

### 5 · A row heading under the app bar while J is open

With J open on *Newly Added — Movies* the heading sits half under the app bar (y ≈ 100–125 px of
1080; the bar ends at 120). Poster rows are taller than the landscape Continue row, where the same
reveal leaves the heading clear.

- **FR-R257-5** — after J's reveal settles, the opened row's **heading** is fully below the app bar
  (≥ 16 dp clear) on every tile shape, unless the grown row is taller than the space under the bar, in
  which case the foot wins (FR-R240-9 unchanged).
- **Root cause (from the sweep's own screenshots, not re-measured):** it only happens moving Down *out
  of an open row*. Bring-into-view parks the new row first (heading at ~254 px); then the row above
  collapses and gives back its held height (the landscape tile: 240 → 364 px, ≈ 124 px), dragging the
  new row up by the same amount — heading at ~112 px, under a bar that ends at 120. R240's reveal
  never corrects upward by design. Built as `focusDetailRowOpenHeadingDelta` (4 tests): a single
  downward correction to app bar + 16 dp, measured after the neighbour's collapse has finished.

### 6 · The phone's skip arrows point the wrong way

`PlayerHandsetChrome.kt` `HandsetSkipButton`: the **back** glyph puts its arrowhead at the upper
right pointing right (clockwise — every platform's *forward*), the **forward** glyph at the upper
left pointing left. The labels and actions are right; only the pictures are swapped. Seen on the TV
only because of R256, but it is a phone defect.

- **FR-R257-6** — back = head at the upper left pointing left; forward = head at the upper right
  pointing right.

## Observed, not changed here

- `focus_detail_delay_ms` is **3200** in production's global config (default 170). J works; it just
  takes three seconds. Owner's setting — flagged twice now, not touched.
- J's backdrop is still a weak, even wash — that is **R255**, already spec'd.
- Coming Soon shows several identical *Monster (2022)* tiles for a same-day season drop; the tiles
  differ only in text below the fold.

## Acceptance (stue TV)

1. Studios → a tile → focus is on the first poster; Back → focus is on that tile.
2. Every logo on the first two screens of Studios and Networks is legible from the sofa.
3. Frigear's detail page: nav, facts and synopsis legible; the picture still clearly visible right of
   centre.
4. J open on a poster row: heading fully visible.
5. Pixel 9 player: the 10 s arrow turns counter-clockwise, the 30 s arrow clockwise.

## Implementation notes (2026-09-17, after the on-device pass)

- **FR-R257-4 needed its own tint.** Home's `tintGradient` on the detail hero was visibly too weak on
  Frigear — the detail column carries facts, flags and a synopsis out to ~55 % of the width. `HeroScrims`
  gained `detailTint` (0.88 → 0.74 at 35 % → 0.30 at 62 % → 0) and the head band went 0.70 → 0.80. Home's
  own hero is unchanged. Verified on the TV: text column legible, the right 40 % of the picture untouched.
- **Found once R256 let the TV draw its own walls for the first time:** R243's TV sizes had never been
  seen on a TV. At 4-up a tile is ~200 dp: *"Marvel Stu… 36 titles"* and *"Domain Enterta…"*. The caption
  now **stacks** on a TV (name, then count; a handset's 2-up tile and the genre wall keep R243's single
  line), and the wordmark is 20 sp with 14 dp side padding (was 34 sp / 26 dp). The stacked caption was
  verified on the TV; the final 20 sp wordmark was compiled but **not** re-checked on the device.
- FR-R257-1/2 verified: a studio's grid opens on its first poster; Back lands on the studio's tile.
- FR-R257-5 verified: moving Down from an open row, the next row's heading rests at 152 px (bar 120 + 16 dp);
  it was ~112 px.
- Seen in passing, not changed: the player's top-right **DIRECT PLAY · MKV** chips are on the release
  build (R180's no-delivery-cues rule says they should not be), and a paused player leaves a burned-in
  subtitle under the raised chrome.
