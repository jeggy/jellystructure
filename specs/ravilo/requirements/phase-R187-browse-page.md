# Phase R187 — Ravilo: the browse page — See-all rows, stacking filters, sort (FR-RV-BROWSE1)

> Movies / Series are today a flat grid with a single-select genre chip row, and a content row's
> "See all ›" header label is decorative. This phase replaces both with one **generic browse
> page**: reached from the Movies/Series nav *or* from a **→ See all** tile at the end of any
> long row, seeded to that row's query, with a **top facet bar** (Direction A of the design
> exploration) whose filters stack on the seed. Maturity is a D-pad **range picker** over the
> Phase 155 normalized ages — viewers only ever see numbers.

## Goal
From anywhere a viewer meets a long row — a genre row, a channel row, Continue Watching — one
press on the row-end **→ See all** tile opens the full set as a filterable, sortable grid. The
same page *is* the Movies and Series nav pages. Added filters always narrow (they stack AND-wise
on whatever the page was seeded with); a Reset chip clears them; Back returns to where the viewer
came from.

## Current state
- `renderGrid` (Movies/Series/My List) offers one single-select genre chip row; no sort, no other
  facets.
- `contentRow` renders a "See all ›" header label with no behaviour.
- R186 widened the Continue Watching fetch window — a row can now hold far more than it shows,
  which is exactly what a seeded browse page needs.

## Requirements

### A. Entry points
#### FR-RV-BROWSE1-1 — The → See all end-of-row tile
Every **library-backed** row (genre/custom content rows, Newly Added, channel-scoped rows on a
channel page, and **Continue Watching**) whose item count exceeds **8** gains a final tile in the
row's own focus track — poster- or landscape-shaped to match its siblings, showing `→ See all` and
the count. OK opens the browse page **seeded to that row**. Rows of 8 or fewer get no tile.
Discover/Seerr ranked lists and Live TV rows are excluded (different domains).

#### FR-RV-BROWSE1-2 — Movies / Series nav routes here
The Movies and Series nav items open the same page seeded to the whole library filtered to that
type, with the **Type facet hidden** (it's fixed by the page). Row-seeded pages keep the Type
facet. My List keeps the existing plain grid.

### B. The page
#### FR-RV-BROWSE1-3 — Header carries the seed; the seed is not a chip
Row-seeded pages show a breadcrumb (`◂ Home · Nordic Noir` / `◂ <channel> · <row>`), the row title
as the page title, and a subtitle (`From "Nordic Noir"` + the active filter summary). The seed is
**not removable** — no seed chip; clearing filters returns to the seed set, Back leaves the page.
A live title count sits right of the header. The grid reuses the existing poster grid (R174
grid-columns config applies).

### C. Facet bar + popover (Direction A)
#### FR-RV-BROWSE1-4 — Facets
One horizontal pill row under the header: **Genre · Type · Maturity · Year (decade) · Watched ·
Audio (language) · Channel · Quality** — then a **✕ Reset filters** chip (only while something is
active) and the **Sort** control right-aligned. Semantics: **multi-select OR within a facet, AND
across facets**, always stacking on the seed. Active facets show a count badge (Maturity shows its
range label, §D). Values derive from library metadata (audio languages from the track lists;
Channel from Jellystructure channel membership; Quality from the source badge).

#### FR-RV-BROWSE1-5 — Checklist popover, count-ordered
OK on a facet chip opens a checklist popover anchored under it. Values are shown with live counts
computed against the seed **with every other active facet applied**, and are ordered by **count
descending, ties alphabetical** (numeric-aware, so `7+` sorts before `16+`). OK toggles a value
and the popover **stays open** (grid, header count, chip badges and popover counts update live);
a selected value never vanishes from the list even when sibling filters drop its count to 0.
Back/left/right closes the popover.

### D. Maturity — a range, not a checklist
#### FR-RV-BROWSE1-6 — D-pad range picker over the normalized ladder
The Maturity popover is a **range picker**: a ladder visualization of ages **0–18** with the
selected span highlighted, and two rows — **From** and **Up to** — each `◂ value ▸`. ▲▼ switches
rows, **◂ ▸ steps through every integer 0–18** (plus **Any** past each end), OK confirms/closes,
Back closes. Bounds can't cross (adjusting one pushes the other). The chip and filter summary show
the range as **`≤ 7`**, **`7–11`**, **`15+`**, **`4–14`**; both bounds Any = filter off. Ages are
Phase 155's `ageRating` — an uncertified or unmapped title arrives as **18** (155 FR-AGE1-2), so
it only matches ranges that reach 18, and with the filter off (**Any/Any — "Øll"**) it shows like
everything else. The 18 is a gate value only: unrated titles are **never displayed as "18+"**
anywhere in the UI. Expressible by
construction: *up to 7* · *between 7 and 11* · *15 and up* · *up to 15 inclusive* · any 0–18 pair.

### E. Sort
#### FR-RV-BROWSE1-7 — Recently added by default
Sort options: **Recently added** (default — the library feed's newest-first order) · A–Z · Z–A ·
Release year · Maturity (by normalized age; no-rating titles sort as 18) · IMDb rating (R164 data;
unrated sink to the end).
Single-select popover on the Sort control; choosing closes it.

### F. D-pad ownership
#### FR-RV-BROWSE1-8 — The popover owns the remote while open
While any popover is open it captures all D-pad keys (the player language-picker pattern):
▲▼ move, OK act, Back/Escape close; the page's focus stays parked on the owning chip. Navigating
anywhere (`go()`) force-closes a stray popover — filter state lives on the view, never in the DOM.

## i18n
#### FR-RV-BROWSE1-9 — en/da/fo
New strings: facet names, sort names, `See all`-tile count line, `Reset filters`, `From`/`Up to`/
`Any`, the range hint (`◂ ▸ adjust · OK done`), watched-state values, Film/Series values, and the
`From "{row}"` subtitle — in `Strings.kt` and `design/ravilo/ravilo-i18n.js`.

## Reuse (don't rebuild)
The existing poster grid + tile components and `buildGridRows`; the D-pad focus-row system; the
langPicker capture-key pattern; R174 grid columns; R176/R185/R186 Continue Watching data lanes;
R164 IMDb ratings; Phase 155 `ageRating`.

## Dependencies & relationships
- **Phase 155** for normalized ages. Graceful degradation: without it the Maturity facet is hidden
  entirely (never falls back to raw certification strings — viewers must never see `TV-PG`).
- **R186** makes row-seeded browse sets meaningful beyond the old top-20 window.
- The admin **filter workbench (R32 / Phase 140)** is unrelated: that authors *rows*; this filters
  *within* what a row yields. No shared taxonomy beyond the row definitions themselves.

## Non-goals
- **No phone/mobile browse page** in this phase (`Ravilo Mobile` follows separately).
- **No persistence of a viewer's filters** — state is per-visit, on the view object.
- **No free-text search** inside facets; Search remains its own page.
- **No Discover/Seerr or Live TV integration.**

## Acceptance
- A 9+-item row shows the → See all tile; an 8-item row doesn't. OK on it opens the seeded page
  with breadcrumb + subtitle; Back returns to the origin. *(Verified in the design build.)*
- Movies/Series nav pages hide Type; row-seeded pages show it; a single-type seed truthfully
  offers one Type value.
- Genre popover on the demo library orders `Action 10 · Documentary 10 · Family 10 · Drama 9 ·
  Sci-Fi 9 …` — count desc, ties A–Z. Toggling updates grid/counts live without closing.
- Maturity: `≤ 7`, `7–11`, `15+` and `4–14` are each settable with ◂ ▸ alone; the ladder
  highlights the span; the chip shows the exact label. *(All verified in the design build.)*
- Sort defaults to Recently added and survives filter changes.

## Status
Design-complete, **`Planned`**. Design lives in `design/ravilo/ravilo-browse.js` (the whole page +
popover engine), wired via `design/ravilo/ravilo-app.js` (See-all tiles, nav routing, activate/
back/go), styled in `design/ravilo/ravilo.css`, localized in `design/ravilo/ravilo-i18n.js`;
exploration in `design/ravilo/Ravilo Browse - Filter UI Directions.html` (Direction A chosen).
Depends on **Phase 155**. `scripts/check-phases.sh` will flag it for a `STATUS.md` row —
**STATUS.md is code-owned; do not add the row from the design side.** **Next Ravilo number after
this is R188.**
