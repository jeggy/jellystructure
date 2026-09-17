# Phase R253 — See all opens in the row's own order

> The client half of **225**. A row that has been given an order on the server must not hand the viewer a
> See-all page that opens in a different one. This phase makes the row's key the page's *initial* sort,
> keeps the viewer's own Sort control exactly as R187 built it, and adds **no** visual trace of ordering
> to the row itself — the viewer sees an order, never a reason.

## Status

`Planned` — written 2026-09-17, **not dev-reviewed**.

**Numbering:** verified against `main` on 2026-09-17 — Ravilo taken through **R252**. Pairs with **225**.
Next free: R254.

Design: nothing to draw — the row renders as it renders today (see `design/app/Row Sorting -
Directions.html`, frame *TV · the consequence*). The admin half is in `design/app/ravilo-config.html`.

## Current state

- `SeededBrowseScreen.kt` (R187) fetches the full `BrowseCard` set once and computes every facet and
  sort client-side. Its Sort control defaults to **Recently added** on every open
  (FR-RV-BROWSE1-7: "Sort defaults to Recently added and survives filter changes").
- A Home row today is always newest-first, so the page's default happens to agree with the row.
  After 225 a row sorted A → Z would open a See-all page sorted newest-first — the first tile on the
  row and the first tile on its page would differ, silently.
- `Row` carries `seedQuery` / `seedMediaKind` / `seedTotalCount`; 225 FR-225-8 adds `sort_by` /
  `sort_descending`.
- The A–Z / Z–A facet sorts `card.title`. 225 FR-225-3 introduces Jellyfin's `SortName` server-side.

## Goal

The first tile of a row and the first tile of its See-all page are the same tile, and the viewer can
still re-sort the page however they like.

## Functional requirements

**FR-R253-1 — Nothing changes on the row.** No pin glyph, no caption, no badge, no new string in
en/da/fo. `Row.items` arrives in the server's order and `ContentRow` draws it as it draws every row. A
hand-pick the server skipped (225 FR-225-5/6) leaves no trace — the viewer cannot tell a row with pins
from a row without.

**FR-R253-1b — The row draws every item it is sent.** 225 FR-225-1b makes the row's length a per-row
setting (`limit`, default 10 — what the viewer sees today). Wherever the TV row trims the served `items`
to 10 today, that trim goes; the server's list is already the right length. A row served with 25 items
shows 25 before the See-all card. Continue Watching (its own `CONTINUE_ROW_LIMIT`) is untouched.

**FR-R253-2 — The page opens in the row's order.** When `→ See all` is opened from a row whose `Row`
carries `sort_by`, the Sort control's initial value maps from it:

| `sort_by` · `sort_descending` | initial Sort |
|---|---|
| `added` · true / false | Recently added / *Oldest added* (new option, see FR-R253-4) |
| `title` · false / true | A–Z / Z–A |
| `year` · true / false | Release year (newest first) / *Release year (oldest first)* (new option) |
| absent (CONTINUE, NEWLY_ADDED, Movies/Series nav, taxonomy tiles) | Recently added — R187's default, unchanged |

The value is an *initial* value only: the viewer's Sort control works exactly as before, and a change
survives filter changes exactly as before. Nothing is persisted.

**FR-R253-3 — A–Z uses the same key as the server.** `BrowseCard` gains
`@SerialName("sort_name") val sortName: String? = null` (additive, populated from 225 FR-225-3's stored
field, null when the item has none). The A–Z / Z–A facet sorts on `sortName ?: card.title`. Without
this the row files *The Bear* under B and the page files it under T, which is the disagreement this
phase exists to remove.

**FR-R253-4 — Two reverse options on the Sort control.** R187 ships *Recently added*, *A–Z*, *Z–A*,
*Release year*, *Maturity*, *IMDb rating*. So that every server order has a page equivalent, add
**Oldest added** and **Release year · oldest first** — the only two new strings this pair of phases
introduces, on the browse page's Sort popover, × en/da/fo. They are ordinary options: a viewer can pick
them on any browse page, seeded or not.

**FR-R253-5 — Hand-picks do not reach the page.** The See-all page shows the *full seed set* in the
automatic key's order — pins are a property of the 30-tile row, not of the library view behind it.
Rationale: a pinned prefix over 200 titles is meaningless past the first screen, and carrying `pinned`
to the client would be the first time a client learned *why* a row is ordered (225 non-goals). The
first tile may therefore differ between a **pinned** row and its page; this is accepted and is the one
place the two are allowed to disagree. The row's *automatic* order and the page's opening order are
always the same.

**FR-R253-6 — The phone.** `Ravilo Mobile`'s browse grid is the same composable; the same initial-sort
rule applies. No new phone surface.

## Non-goals

- No sort control on the row, no long-press "sort this row", no viewer-side override of a row's order.
- No new page: See all is R187's page with a different opening value.
- No change to Movies / Series nav or the R243 taxonomy grids — they carry no `sort_by`.
- No change to Continue Watching's See-all (R187 §G-4 / R219) — chronological, no `sort_by`.

## Acceptance

1. Open See all from a row served with `sort_by:"title", sort_descending:false`: the Sort chip reads
   *A–Z* and the first grid tile is the first row tile (a row without pins).
2. The same from a `sort_by:"year"` row: chip reads *Release year*, first tiles match.
3. From a row with **no** `sort_by` (Continue Watching, Newly Added, a taxonomy tile): chip reads
   *Recently added* — identical to today.
4. Change the Sort on a seeded page, toggle a facet: the chosen Sort survives (R187's rule unchanged).
5. A `BrowseCard` with `sort_name:"Bear, The"` sorts under B in A–Z; one without sorts by title.
6. From a row with pins: the page opens in the row's *automatic* key; no pin is visible or inferable on
   the page or the row.
7. `Strings.kt` gains exactly two keys (the two new Sort options) in en, da and fo; a grep for any
   "pinned" / "hand-picked" / "sorted by" string on the client finds nothing.

## Source references

- `ravilo-ui/…/SeededBrowseScreen.kt` — R187's `sortedFiltered()` and the Sort popover this extends.
- `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt` — `Row` (gains `sort_by` /
  `sort_descending` in 225), `BrowseCard` (gains `sort_name` here).
- `design/app/Row Sorting - Directions.html` — frame *TV · the consequence*; decision 7.
- Related: **225** (the server half), **R187** (the page), **R219** (Continue Watching exempt), **R243**
  (taxonomy grids carry no `sort_by`).

## Open questions — for the dev team

Design has no further owner questions (the one owner question — whether a pinned row's page puts the
pins first — was answered **no** on 2026-09-17; FR-R253-5 stands). Build-time calls to record in the
dev-review addendum:

1. **Where the TV's 10 comes from.** See 225 open question 1 — FR-R253-1b names the intent (the row draws
   every item served), the dev team names the mechanism and removes it, or reports why it must stay.
2. **The two new Sort options' Danish and Faroese.** *Oldest added* / *Release year · oldest first* need
   da/fo strings. Design has not drafted them (the shipped `Strings.kt` table wins wherever a phrasing
   exists — R187's *Recently added* / *Release year* should be the pattern to extend).
3. **The phone's narrower grid.** Same initial-sort rule as the TV; no reason to think it wants a
   different default. Noted only because R244 found every phone regression by someone using the phone —
   check it on a handset once, not by review.
4. **Sort chip mapping when `sort_by` names an option the client doesn't know.** A newer server could
   one day send a key this build has no Sort option for. Fall back to *Recently added* silently (R187's
   default), never to an empty chip.
