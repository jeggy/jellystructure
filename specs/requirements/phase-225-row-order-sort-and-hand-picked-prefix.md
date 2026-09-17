# Phase 225 — How a content row lines up: sort key, direction, and a hand-picked front

> Every workbench row on the TV is ordered the same way today, and nothing in the editor says so:
> newest added first, then title, cut at 30. That is the right order for a row called *Newly Added* and
> an accidental one for a row called *Danish drama*. This phase gives `RowConfig` an order — one of three
> automatic keys in either direction, optionally behind an **ordered hand-picked prefix** with the
> automatic key as its fallback — resolves it **server-side** in `HomeFeedService`, and adds one section
> to the row editor. The TV renders what it is sent; no client learns a comparator. The default is
> byte-for-byte today's order. The number of titles a row shows becomes a per-row setting too, and it is
> the ceiling on hand-picks.

## Status

`Planned` — written 2026-09-17, **not dev-reviewed**. Owner answered the design's open questions the same
day (newest first is the default; pins only from matches; the row's shown count is per-row and caps the
pins; See all does not put pins first; there are no genre rows any more) — all folded in below.

**Numbering:** verified against `main` on 2026-09-17 — admin taken through **224**, Ravilo through
**R252**, no `phase-225-*` file and no `STATUS.md` row. Pairs with **R253** (the one client change: See
all opens in the row's order). Next free: 226 / R254.

Design: `design/app/Row Sorting - Directions.html` (round 1 — three hand-pick directions, states, the
TV consequence, ten decisions), then built into `design/app/ravilo-config.html` + `ravilo-builders.js`
(the Order section of the row editor, direction 2 · *Arrange the row*) and `ravilo-builders.css`.

## Current state

- `HomeFeedService` sorts **every** filter row with one hard-coded comparator —
  `compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.title }` — and takes
  `ROW_ITEM_LIMIT = 30` (`HomeFeedService.kt:48,582,596,626,631,633,679,709`). Home rows and a collection's
  `custom` rows all go through it. **The viewer sees 10** — the TV row shows fewer than the server sends;
  the owner confirms 10 is what runs. `RowKind.GENRE` still exists in the enum but nothing creates one —
  every non-system row is a workbench filter (`CUSTOM`).
- `RowConfig` (`shared/.../tv/Models.kt:843`) has `id · kind · title · enabled · order · mediaKind ·
  match · conditions · query`. **No sort field, no pin list.** `order` is the row's position in the
  stack, not the order of its items.
- `Row` (the served DTO) carries `items` already ordered plus R187's `seedQuery` / `seedMediaKind` /
  `seedTotalCount`. The client never re-sorts a row.
- **R187's See-all page sorts on its own**, client-side, over the full `BrowseCard` set: *Recently
  added* (default) · A–Z · Z–A · Release year · Maturity · IMDb. So a row and its See-all page already
  disagree about order the moment a viewer changes the facet; with this phase they would disagree by
  default unless R253 lands with it.
- The row editor (`design/app/ravilo-builders.js` `openFilter(mode:'row')`) edits query blocks, Include
  and the title. The row list's second line (`.src`) summarises the filter in words.
- `recencyKey()` is the library's date-added (Jellyfin `DateCreated`, per `Media.kt:246`), with the
  scan-order fallback the store documents. Title sorting today uses `title` as stored — Jellyfin's own
  `SortName` (which drops a leading article) is fetched nowhere in our catalog.

## Goal

A row's order is a property of the row, set once in the editor, resolved on the server, and honoured
identically on Home, inside every collection the row appears in, and on the row's See-all page.

## Functional requirements

**FR-225-1 — Two additive fields on `RowConfig`.**

```kotlin
data class RowConfig(
    …,
    val sort: RowSort? = null,                 // null = RowSort("added", descending = true) — today's order
    val pinned: List<String> = emptyList(),    // ordered Jellyfin item ids; empty = no hand-picks; size ≤ limit
    val limit: Int? = null,                    // titles the TV shows of this row; null = 10 (today); 3..30
)
@Serializable data class RowSort(val by: String = "added", val descending: Boolean = true)   // by ∈ added · title · year
```

All three are ignored by any installed Ravilo build (`ignoreUnknownKeys`; the TV never evaluates rows).
An existing config with none of them **must produce the identical row it produces today** — same order,
same number of tiles — this is the migration, and it is the first acceptance test.

**FR-225-1b — The row's length is the row's.** `HomeFeedService` takes `row.limit ?: 10` per row in place
of the shared `ROW_ITEM_LIMIT`, and the TV row renders every item it is sent (it stops trimming to 10
itself — that trim is what `limit` replaces; R253 FR-R253-1b). `seedTotalCount` remains the pre-limit
match count. The editor's *Show N titles* stepper (FR-225-9) is the only place the number is set; 3–30.

**FR-225-2 — Three keys, each in both directions, resolved server-side.** `HomeFeedService` builds
every workbench (`CUSTOM`) row as:

```
pinsInOrder(row.pinned ∩ matches)  ++  matches \ pins, sorted by (row.sort.by, row.sort.descending) then SortName
```

then takes `limit` (FR-225-1b). Keys: `added` → `recencyKey()`; `year` → `year ?: 0`; `title` → the
sort name of FR-225-3. The tie-break is always the sort name, ascending, so an order is total and stable
across rebuilds. **`descending: true` for `added`/`year` means newest first; for `title` it means Z → A.**
No comparator exists anywhere else — not in the client, not in R187's page (which has its own facet, see
R253), not in the admin.

**FR-225-3 — Title sorts by Jellyfin's `SortName`, falling back to the title.** The scanner already
fetches every item from `/Items` with a `Fields=` list (`JellyfinClient.kt:353,364`); add `SortName` and
store it on `MediaItem`. Where it is absent, use `title`. *The Bear* files under B on the TV as it does
in Jellyfin. The browse page's A–Z facet must use the same field (R253 FR-R253-3) so the row and its
page cannot disagree about where a title sits.

**FR-225-4 — Hand-picks are a prefix, not a replacement.** `pinned` items that match the row's query (as
scoped — FR-225-6) come first, in `pinned` order; everything else the query matches follows under the
automatic key. A row that gains fifty titles next month still lines up. **`pinned.size ≤ limit`** — the
number of titles the row shows is the ceiling on hand-picks (owner decision), so every pin is a tile the
viewer can actually reach. Pins are chosen **only from the row's current matches**; the editor offers
nothing else (a pin from outside the filter would be stale on arrival).

**FR-225-5 — A stale hand-pick is kept and skipped, never deleted.** A `pinned` id whose item no longer
matches the row's query (the filter tightened, Include changed, the title was removed from Jellyfin) is
**left in the config** and simply does not appear in the served row. The server never writes to
`pinned` on the admin's behalf. The editor shows it, marked, with one sentence saying what the TV will
do (FR-225-9). Reason: a pin is deliberate work, and the filter may tighten temporarily.

**FR-225-6 — Pins resolve against the scoped set.** Inside a collection (R59 `inherit` rows scoped to the
channel, or the channel's own `custom` rows) the same rule runs over the channel-ANDed matches: a pin
outside the collection is not a pin there. Hand-picks belong to the row, not the collection; there is
nothing to configure twice and nothing to draw on the TV for a pin that didn't apply (R253 FR-R253-1).

**FR-225-7 — System rows are exempt, and cannot be given an order.** `CONTINUE` is chronological by
activity (R219 §4, `CONTINUE_ROW_LIMIT = 20`) and `NEWLY_ADDED` is by definition newest first.
`sort`/`pinned`/`limit` on a `RowConfig` of either kind are ignored on read and rejected on write (400
from the config route). A legacy `GENRE` row, should one exist in a config file, is treated as `CUSTOM`. The editor never
shows the Order section for them — they don't open the workbench editor at all today.

**FR-225-8 — The served `Row` carries the key.** Add to `Row`:

```kotlin
@SerialName("sort_by") val sortBy: String? = null,           // "added" | "title" | "year" — set on workbench rows
@SerialName("sort_descending") val sortDescending: Boolean? = null,
```

so R253's See-all page can open in the same order without a config round trip — the same
mirror-onto-the-feed discipline `HomeFeed.focusDetail` (phase 202 FR-202-7) already set. **The pin list
is not sent.** The TV has no use for it (R253 FR-R253-1), and `items` is already in the resolved order.
`seedTotalCount` is unchanged.

**FR-225-9 — The editor: one section, Order.** Between *Include / Row title* and the reusable-filter
note in the row editor (`design/app/ravilo-builders.js`, the `orderHtml()` block):

- A segmented control **Date added · Title · Release year · Hand-picked first** and a **direction
  button** whose label is the pair in words for the current key (`newest first` / `oldest first`,
  `A → Z` / `Z → A`, `newest release first` / `oldest release first`). Never "asc"/"desc".
- On the same line, right-aligned: **Show N titles**, a − / + stepper, 3–30, default 10. It cannot step
  below the number of hand-picks (the − is disabled and a toast says *Release a hand-pick to show fewer
  than N*) — a pin is never dropped behind the admin's back.
- The **Matches** panel renders in the chosen order and **numbers the tiles** whenever the order is not
  the default; tiles past the limit are drawn faded with a dashed edge, and past the limit its count line
  reads *the TV shows the first N in this order, See all shows every one*.
- Choosing **Hand-picked first** shows the row as a **strip**: hand-picks left of a dashed **seam**,
  numbered; the automatic remainder right of it, dimmed, with a `+N` count. Drag a tile across the seam
  to pin or release it, within the left zone to reorder; clicking a dimmed tile pins it; ✕ on a pin
  releases it. A **Pin a title…** search pins one the strip cannot show. Beneath it, **Then the rest
  by** — the same three keys and the direction button — is the fallback control, and the seam's own
  label restates it (*then title A → Z*).
- A stale pin (FR-225-5) stays in its numbered place, greyed and marked, and one warning note says:
  *«Title» is hand-picked but no longer matches this row. It stays in your list and is skipped on the TV
  until it matches again, or you release it.* Inside a collection the note names the collection.
- At the ceiling (pins = N): the dimmed tiles stop being pickable, the search field is gone, and the
  line under the strip reads *All N places are hand-picked — release one, or show more titles, to pick
  another.* Any attempt to pin (click, drop, search) toasts the same sentence.
- Switching from **Hand-picked first** back to a plain key **releases every pin** and says so in a toast.
  Saving *Hand-picked first* with nothing picked saves as the plain key.

**FR-225-10 — The row list says it in words.** In *Layout → Content rows* each workbench row's summary
line appends the order **only when it differs from the default**: `· title A → Z`, `· newest release
first`, `· 3 hand-picked, then newest first`, and `· shows 15` when the count is not 10. A row on the
default order and count shows the line it shows today.
System rows are unchanged.

**FR-225-11 — Validation on write.** `sort.by` ∉ {added, title, year} → 400. `limit` outside 3–30 → 400.
`pinned.size > (limit ?: 10)` → 400. `pinned` entries are
Jellyfin item ids as strings; unknown ids are **accepted** (FR-225-5 — a removed title is exactly the
stale case) but deduplicated, first occurrence wins. `RaviloConfigService.normalize()` writes nothing
back for an absent `sort` — absent stays absent, so a config file that never mentions order never
starts mentioning it.

## Non-goals

- **No new strings in en/da/fo.** The viewer sees an order, never a reason: no pin glyph, no "sorted
  by" caption, no badge. R253 adds nothing to `Strings.kt`.
- **No per-viewer sort.** Order is a property of the row config (per Jellyfin user via the layout
  override, as every `RowConfig` already is). The browse facet remains the viewer's own knob.
- **No sort for system rows, the hero, or the collection rail.** The rail is ordered by
  `ChannelConfig.order` already.
- **No IMDb / maturity keys.** R187 has them on the page; a Home row sorted by rating is a *Top 10*
  concept and belongs to the Discover work, not here.
- **No "random" / "shuffle".** A row whose order changes on every feed rebuild fights the D-pad memory
  R200/R236 spent five phases on.
- **No drag-to-reorder of the TV row from the TV.** Curation is an admin act.

## Acceptance

1. A config with no `sort`, `pinned` or `limit` on any row produces, for every row and every user, exactly
   the row — order and tile count — the viewer sees today (fixture diff over the demo library, all three visibility
   scopes).
2. `sort: {by:"title"}` on a row of 40 matches serves items 1–10 A → Z by sort name and
   `seedTotalCount = 40`; `descending: true` serves Z → A; `limit: 25` serves 1–25 and the TV draws all 25.
3. `pinned: [C, A]` on a row whose matches are {A, B, C, D} with `sort: {by:"added"}` serves `C, A` then
   `B, D` newest-first. Reordering `pinned` to `[A, C]` flips the first two and nothing else.
4. A `pinned` id that matches nothing is absent from the served row, present unchanged in the stored
   config after any other save, and shown marked in the editor with FR-225-9's sentence.
5. The same row inherited into a collection serves only the pins inside the collection, then the
   scoped remainder; `pinned` in config is untouched.
6. `sort`, `pinned` or `limit` on a `CONTINUE`/`NEWLY_ADDED` row → 400 on write; eleven pins on a row with
   `limit: 10` → 400; a hand-edited config file with
   them is served as if they were absent.
7. A title stored with `SortName = "Bear, The"` sorts under B under `by:"title"`; a title with no
   `SortName` sorts by its title.
8. The served `Row` for a `CUSTOM` row carries `sort_by`/`sort_descending`; for `CONTINUE` it carries
   neither; `pinned` appears nowhere on the wire.
9. Editor: choosing *Hand-picked first*, dragging two dimmed tiles across the seam, then dragging the
   second above the first, saves `pinned` in that order; switching to *Title* toasts and saves
   `pinned: []`. With 10 pins and *Show 10*, no eleventh pin can be made by click, drop or search, and
   the − step is disabled; stepping to 11 re-enables picking.
10. Row list: a row on the default order shows no order text; the three example rows in
    `design/app/ravilo-config.html` show theirs verbatim.

## Source references

- `design/app/ravilo-builders.js` — `ORDER_KEYS`, `rowSort()`, `rowLimit()`, `autoSorted()`, `orderTitles()`,
  `stalePins()`, `orderSummary()`, the `orderHtml()` block and its `wire()` handlers (incl. `canPin()`): the
  reference implementation of FR-225-1b/2/4/5/9/10 over the demo library. `seed()` reads `data-sort` /
  `data-pinned` / `data-limit`.
- `design/app/ravilo-builders.css` — `.cf-order`, `.cf-limit`, `.cf-strip`, `.cf-seam`, `.cf-zone-*`, `.cf-pin`,
  `.cf-stale`, `.cf-nopick`, `.cf-past`, `.cf-thenby`, `.cf-rk`.
- `design/app/Row Sorting - Directions.html` — the round-1 canvas: the three hand-pick directions and
  why *Arrange the row* was chosen, the collection-scope and stale-pin states, the TV consequence, and
  the ten decisions this spec closes.
- `src/linuxX64Main/kotlin/dev/jellystructure/tv/HomeFeedService.kt` — the seven `sortedWith(
  compareByDescending … thenBy title)` sites this replaces with one resolver; `ROW_ITEM_LIMIT`.
- `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt` — `RowConfig`, `Row`, `RowKind`.
- Related: **R187** (See all's client-side sort — the disagreement R253 closes), **R219** (Continue
  Watching is chronological and canonical — exempt), **R233** (system rows always scoped — exempt),
  **R59/R143** (rows inside collections: inherit vs custom), **202** (mirroring resolved config onto the
  feed), **R200/R236** (why a shifting order is a focus bug).

## Open questions — for the dev team

Design has no further owner questions; everything below is a build-time call the dev team should make
and record in the dev-review addendum. None blocks starting the phase.

1. **Where does the TV's 10 come from?** `HomeFeedService` sends 30 per row, the viewer sees 10. Locate
   the client-side trim (a `take(10)` in the row composable, a `LazyRow` item cap, or a layout
   consequence) and remove it so the row draws every item served (FR-225-1b / R253 FR-R253-1b). If the
   trim turns out to be load-bearing for performance (R240's reflow budget, R242's backdrop prefetch),
   say so and cap `limit` accordingly rather than silently ignoring it.
2. **`SortName` for items already scanned.** FR-225-3 adds the field to the scan fetch. Decide whether
   the existing library gets a one-off backfill (phase 181's set-difference machinery) or waits for the
   next full scan; until it lands, title sort uses `title`, which files *The Bear* under T.
3. **Migration of the seven comparator sites.** FR-225-2 replaces them with one resolver. Confirm the
   fixture diff in acceptance 1 is byte-identical across all three visibility scopes *before* the
   editor ships — the resolver's tie-break (sort name, ascending) differs from today's `thenBy { title }`
   only where two items share a recency key and differ in leading article; if that changes any served
   order, keep `title` as the tie-break for `added` and note it here.
4. **Pin ids across a Jellyfin re-import.** `pinned` holds Jellyfin item ids. If a title is deleted and
   re-added it gets a new id and the old pin goes stale (FR-225-5 tolerates this). Decide whether the
   scanner's existing rename/re-id handling (phase 199's tag-preserve guard) should also rewrite pin ids,
   or whether stale-and-skipped is acceptable for this case too. Design's lean: skip it; a re-imported
   title is rare and the editor marks the stale pin.
5. **Config write path for an unknown pin id.** FR-225-11 accepts unknown ids on write. Confirm the config
   route does not resolve ids against the catalog synchronously (it would block the editor's Save on a
   catalog read); the resolution belongs in `HomeFeedService` at feed-build time.

## Resolved in design (2026-09-17, owner)

- Default is **newest first** — today's behaviour; the brief's "asc" was a slip.
- See all does **not** put the hand-picks first (R253 FR-R253-5 stands); the viewer re-sorts the page
  with R187's control as today.
- **No genre rows** exist any more — every non-system row is a workbench filter; `GENRE` is legacy.
- Hand-picks come **only from the row's matches**.
- The row's shown count is **per row** (`limit`, default 10) and **caps the hand-picks**.
