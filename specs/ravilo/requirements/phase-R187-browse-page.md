# Phase R187 — Ravilo: the browse page — See-all rows, stacking filters, sort (FR-RV-BROWSE1)

> Movies / Series are today a flat grid with a single-select genre chip row (UI only — the backend
> underneath already supports more); a content row's "See all" isn't merely undecorated, it doesn't
> exist at all in the row composable that actually renders on screen. This phase replaces both with one
> **generic browse page**: reached from the Movies/Series nav *or* from a **→ See all** tile at the end
> of any long row, seeded to that row's query, with a **top facet bar** (Direction A of the design
> exploration) whose filters stack on the seed. Maturity is a D-pad range picker over the Phase 155
> normalized ages — viewers only ever see numbers.

**Status:** Planned. Backend-reviewed 2026-07-31 (see addendum) — the design tool that authored this
(no code access, only JS/HTML mockups + `specs/ravilo/constitution.md`/`plan.md`) got the "Reuse"
section's targets largely right in spirit but pointed at JS mockup files instead of the real Compose
code, and — more importantly — never addressed how a seeded, faceted, live-counted browse set is
actually supposed to reach the client. That's now its own section (§G) and is the load-bearing gap to
close before implementation starts.

## Goal
From anywhere a viewer meets a long row — a genre row, a channel row, Continue Watching — one press on
the row-end **→ See all** tile opens the full set as a filterable, sortable grid. The same page *is* the
Movies and Series nav pages. Added filters always narrow (they stack AND-wise on whatever the page was
seeded with); a Reset chip clears them; Back returns to where the viewer came from.

## Current state (corrected)
- **`BrowseScreen`/`BrowseStore`/`BrowseService` already exist and are a substantially better starting
  point than "the home-feed row renderer."** `BrowseScreen.kt`
  (`ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/BrowseScreen.kt`) is already a
  working paginated poster grid — `BrowseGrid()` (`LazyVerticalGrid` + the shared `Tile` composable,
  `ravilo-ui/…/components/Tile.kt:73`, `TileVariant.{POSTER,LANDSCAPE,SQUARE}`) — driving Movies/Series/My
  List today. `BrowseStore` calls `TvApiClient.browse(kind, genre, page, pageSize)` →
  `GET /tv/browse` → `BrowseService.browse()`
  (`src/linuxX64Main/kotlin/dev/jellystructure/tv/BrowseService.kt:32-91`). **The backend there already
  supports more than the UI exposes**: multi-select genre/studio/network/tag filters (all present as
  params, just not surfaced in `BrowseScreen`'s single-select `GenreChips`), a `sort` param
  (title/year/recency), and real pagination including "return everything" (`pageSize = null`, R118). What
  the design tool correctly observed is UI-only: the Compose layer today exposes single-select genre and
  no sort control — extend this screen, don't rebuild it.
- **A row's "See all" is not decorative — it's absent from the composable that actually renders.**
  `StaticContentRow` (`ravilo-ui/…/components/ContentRow.kt:56-217`) *does* support
  `seeAllLabel`/`onSeeAll` with real, working `dpadFocusable` wiring (a prior bug fix at lines 172-189
  made it actually clickable). But `ContentRowItem` in `HomeScreen.kt` (the composable genre/custom/
  Continue Watching rows actually go through, `HomeScreen.kt:355-396`) **never passes those parameters**
  — `HomeScreen`'s own `onSeeAll: (String?) -> Unit` (declared at line 84, threaded to `HomeLoaded` at
  124/145) is plumbed all the way down and then never invoked. `ChannelScreen.kt`/`DiscoverScreen.kt`
  have the same gap. The only live "See all" in the app today is the Movies/Series nav itself
  (`RaviloApp.kt:599`: `onSeeAll = { push(Dest.Browse(BrowseKind.ALL, dest.displayName)) }`) — unseeded,
  whole-library only.
- Row facets (`GET /tv/facets` → `BrowseService.facets()`, `BrowseService.kt:128-155`) are computed once
  per kind over the *whole* catalog and never re-narrowed as filters change (`BrowseStore.load()`/
  `filterByGenre()` fetch once, `BrowseScreen.kt:122-124,147`) — today's counts are not "narrowed by
  other active facets," which is exactly what FR-RV-BROWSE1-5 below requires and doesn't exist yet on
  the viewer-facing API. See §G for where the real narrowing engine already lives (admin-only, today).
- R186 widened the Continue Watching fetch window server-side, but it's still capped at
  `ROW_ITEM_LIMIT = 30` on the client-facing `Row` (`HomeFeedService.kt:37,482`) — R186 fixed which 30
  make the cut, not "everything." A Continue Watching "See all" needs its own resolution path (§G).

## Requirements

### A. Entry points
#### FR-RV-BROWSE1-1 — The → See all end-of-row tile
Every **library-backed** row (genre/custom content rows, Newly Added, channel-scoped rows on a
channel page, and **Continue Watching**) whose item count exceeds **8** gains a final tile in the
row's own focus track — poster- or landscape-shaped to match its siblings, showing `→ See all` and
the count. OK opens the browse page **seeded to that row**. Rows of 8 or fewer get no tile.
Discover/Seerr ranked lists and Live TV rows are excluded (different domains). **Depends on §G** — a
row must carry (or resolve to) something the backend can turn back into a full, faceted result set;
Continue Watching in particular needs its own path, not a generalization of the others (§G-4).

#### FR-RV-BROWSE1-2 — Movies / Series nav routes here
The Movies and Series nav items open the same page seeded to the whole library filtered to that
type, with the **Type facet hidden** (it's fixed by the page). Row-seeded pages keep the Type
facet. My List keeps the existing plain grid. This entry point already works end-to-end today
(`RaviloApp.kt:599` + `BrowseService.browse`) — FR-RV-BROWSE1-1's seeded rows are the new work.

### B. The page
#### FR-RV-BROWSE1-3 — Header carries the seed; the seed is not a chip
Row-seeded pages show a breadcrumb (`◂ Home · Nordic Noir` / `◂ <channel> · <row>`), the row title
as the page title, and a subtitle (`From "Nordic Noir"` + the active filter summary). The seed is
**not removable** — no seed chip; clearing filters returns to the seed set, Back leaves the page.
A live title count sits right of the header. The grid reuses the existing poster grid (`BrowseGrid`,
R174 grid-columns config already applies via `LocalGridColumns`, `RaviloApp.kt:137-138`).

### C. Facet bar + popover (Direction A)
#### FR-RV-BROWSE1-4 — Facets
One horizontal pill row under the header: **Genre · Type · Maturity · Year (decade) · Watched ·
Audio (language) · Channel · Quality** — then a **✕ Reset filters** chip (only while something is
active) and the **Sort** control right-aligned. Semantics: **multi-select OR within a facet, AND
across facets**, always stacking on the seed. Active facets show a count badge (Maturity shows its
range label, §D). Values derive from library metadata (audio languages from the track lists;
Channel from Jellystructure channel membership; Quality from the source badge). **Genre/Type/Year/
Watched/Audio are buildable on data that exists today; Channel and Quality are not — see §G-5/6.**

#### FR-RV-BROWSE1-5 — Checklist popover, count-ordered
OK on a facet chip opens a checklist popover anchored under it. Values are shown with live counts
computed against the seed **with every other active facet applied**, and are ordered by **count
descending, ties alphabetical** (numeric-aware, so `7+` sorts before `16+`). OK toggles a value
and the popover **stays open** (grid, header count, chip badges and popover counts update live);
a selected value never vanishes from the list even when sibling filters drop its count to 0.
Back/left/right closes the popover. **This is the requirement §G's narrowed-facets endpoint exists to
serve** — it cannot be built as client-side counting over the currently-loaded page (see §G-2/3).

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
anywhere in the UI. Expressible by construction: *up to 7* · *between 7 and 11* · *15 and up* ·
*up to 15 inclusive* · any 0–18 pair. **Blocked on Phase 155 landing `ageRating` on `MediaCard`**
(not yet present on any client-facing DTO today — confirmed).

### E. Sort
#### FR-RV-BROWSE1-7 — Recently added by default
Sort options: **Recently added** (default — the library feed's newest-first order) · A–Z · Z–A ·
Release year · Maturity (by normalized age; no-rating titles sort as 18) · IMDb rating (R164 data;
unrated sink to the end). **Sort by Maturity and by IMDb rating must be computed server-side, in the
resolved result set, not client-side** — `MediaCard` does not carry an IMDb rating today (R164's own
dev-review deliberately kept it off `MediaCard` for payload-size reasons, `MovieDetail`/`SeriesDetail`
only) and Phase 155's `ageRating` is likewise a detail/DTO addition, not something the client can derive
on its own from a card. This phase does not need to (and per R164's prior decision, should not) add
IMDb rating to `MediaCard` just to sort by it. Single-select popover on the Sort control; choosing
closes it.

### F. D-pad ownership
#### FR-RV-BROWSE1-8 — The popover owns the remote while open
While any popover is open it captures all D-pad keys (▲▼ move, OK act, Back/Escape close; the page's
focus stays parked on the owning chip). Navigating anywhere (`go()`) force-closes a stray popover —
filter state lives on the view, never in the DOM/composition. **This needs its own capture-key layer,
built fresh for this screen — it is not a drop-in reuse of the player's picker.** The only existing
"popover captures keys" precedent, the Audio & Subtitles `TrackPicker` (`PlayerScreen.kt:1821`, state at
lines 264-266), has *no* key handling of its own — every D-pad key for the whole player, picker included,
is intercepted by one root-level `Box.dpadFocusable` wrapping the entire `PlayerScreen`
(`PlayerScreen.kt:881-1013`), whose callbacks branch on `when { pickerOpen -> …; else -> … }`. That's a
single-focus-root, manual-state-machine architecture — the opposite of `BrowseScreen`'s native Compose
2-D focus traversal (`Modifier.focusRestorer()` on the grid, no root key interceptor at all). Building
Browse's popover means writing a comparable root-level interceptor for `BrowseScreen` and making it
coexist with the grid's native traversal underneath — informed by the player's approach, not extracted
from it.

## G. Backend requirements (new section — absent from the original spec)
The client-facing `Row` DTO (`shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt:202-207`)
carries only `id`/`title`/`kind`/`items` — never the query that produced it. This section is what has to
exist before FR-RV-BROWSE1-1/5 are implementable for anything beyond a plain single-genre row.

#### G-1 — A resolvable seed reaches the client
The row-authoring side already has what's needed: `RowConfig.query: ConditionGroup?`
(`shared/…/Models.kt:619-630`, the R32/Phase-140 boolean condition tree — nested AND/OR, facet
conditions) is exactly the row's definition — it's just never forwarded past the admin config layer to
the client-facing `Row`. The fix is threading, not invention: give the client-facing `Row` (or the
See-all tile's navigation payload) a resolvable seed — either the row's id (if the backend can look the
`RowConfig` back up by id at browse time) or the `ConditionGroup` itself. A plain **GENRE** row is the
trivial case (already expressible as `/tv/browse?genre=X`); a **CUSTOM** row's arbitrary condition tree
and a **channel-scoped** row's channel-condition-plus-row-condition are the cases that need the seed
threaded through, not re-derived.

#### G-2 — A device-scoped, narrowed-facets endpoint
The narrowing engine this phase needs already exists — **for the admin only**. `MediaStore.facetsNarrowed(query: ConditionGroup)`
(`src/linuxX64Main/kotlin/dev/jellystructure/media/MediaStore.kt:742-746`) filters the catalog through
`ConditionEvaluator.matches()` (a genuinely generic condition-tree evaluator, already reused for channel
membership and row queries, not channel-specific despite that being its most visible use) and returns
count-sorted facets narrowed to that tree — precisely "seed + active facets, counts for everything else."
**The catch, and the reason this can't just be exposed as-is:** `facetsNarrowed` (and its sibling
`countBatch`) call the unscoped `liveItems()` (`MediaStore.kt:485`), not the per-device,
library/tag-restricted `liveItems(device)` (`MediaStore.kt:495`, the Phase 142 scoping every other
Ravilo-facing read path uses). Exposing the admin engine to Ravilo as-is would leak counts and values
from libraries a restricted profile can't see. This phase needs a **device-scoped variant** — same
evaluator, `liveItems(device)` instead of `liveItems()` — before Ravilo can call it safely.

#### G-3 — Performance is not the concern; API shape is
Live catalog size: **428 items** (275 movies + 153 series, `config/jellystructure.db`). `BrowseService`
already does in-memory Kotlin `List` filtering over a warm, in-process `MediaStore` cache today — no DB
round trip per request — and it's fast at this scale. The "server resolves the seed's full matching set
+ computes facet counts in one cheap in-memory pass, narrowed by active filters" architecture this spec
needs is entirely realistic performance-wise; it's G-1/G-2 that are missing, not throughput.

#### G-4 — Continue Watching needs its own See-all path
Continue Watching isn't expressible as a `ConditionGroup` at all — it's a live Jellyfin
`getResumeItems`/`getNextUp` join (`HomeFeedService.buildContinueRow`), capped at `ROW_ITEM_LIMIT = 30`
on the client-facing row today (R186 widened the server-side candidate pool that feeds that cap, not the
cap itself). Its See-all tile needs a dedicated (paginated) resolution path — not a generalization of
the `ConditionGroup`-based browse endpoint.

#### G-5 — Channel facet has no reverse index
Channel membership is only ever evaluated live, per item, against a channel's own condition tree
(`HomeFeedService.matchesChannel`, `HomeFeedService.kt:523-531`) — there's no "which channels is item X
in" lookup. A Channel facet means evaluating every configured channel's tree against every seed-matching
item per request. Cheap at 428 items × a handful of channels, but genuinely new computation to add, not
a lookup to expose.

#### G-6 — Quality facet needs new scan-time data capture
`MediaItem`/`Track` (`src/commonMain/kotlin/dev/jellystructure/model/Media.kt`) store per-track `codec`
but no resolution/width/height/`VideoRange`/HDR field at all — the scanner never captures it today. A
Quality facet is blocked on adding that capture (scan-time `ffprobe` read or query-time Jellyfin
`MediaStreams` lookup), independent of everything else in this phase. Consider shipping Genre/Type/
Year/Watched/Audio/Maturity first and Channel/Quality as a fast-follow, since they're the two facets
needing genuinely new backend capability rather than newly-exposed existing capability.

## i18n
#### FR-RV-BROWSE1-9 — en/da/fo
New strings: facet names, sort names, `See all`-tile count line, `Reset filters`, `From`/`Up to`/
`Any`, the range hint (`◂ ▸ adjust · OK done`), watched-state values, Film/Series values, and the
`From "{row}"` subtitle. Real target: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/i18n/Strings.kt`
— three flat `Map<String,String>` tables (`EN`/`DA`/`FO`, merged into `LOCALES`, looked up via
`str(key, args)` with `{placeholder}` substitution and EN fallback) — add the same key to all three maps.
(`design/ravilo/ravilo-i18n.js` is the design mockup's parallel copy, kept in sync separately per this
project's design/code split.)

## Reuse (corrected — real Compose targets, not the JS mockup)
- **The grid**: `BrowseScreen.kt`/`BrowseStore`/`Tile.kt` — extend, don't rebuild; this is a working
  Movies/Series/My-List grid today (not "the home-feed row renderer").
- **The row/D-pad system**: native Compose focus traversal (`Modifier.focusRestorer()`), not a custom
  row-lane abstraction — `dpadFocusable()` (`ravilo-ui/…/focus/FocusModifiers.kt`) is used for one-off
  bridges, not whole-row description.
- **The popover key-capture layer**: new code for this screen, informed by (not extracted from)
  `PlayerScreen.kt`'s root-level `dpadFocusable` + `when { pickerOpen -> … }` router.
- **R174 grid columns**: `LocalGridColumns`/`LocalPortraitGridColumns` (`RaviloApp.kt:137-138`) — already
  consumed by `BrowseScreen.kt`, `SearchScreen.kt`, `SeerrSearchScreen.kt`; a clean, accurate reuse as
  originally claimed.
- **R176/R185/R186 Continue Watching**: the *data* (not a reusable "See all" path — see G-4).
- **R164 IMDb ratings**: the *field name and shape* (`TvImdbRating`) as a reference for server-side sort,
  not something to add to `MediaCard`.
- **Phase 155 `ageRating`**: once it lands on `MediaCard` per that spec's corrected FR-AGE1-5.
- **The condition-tree evaluator**: `ConditionEvaluator`/`MediaStore.facetsNarrowed` — real, generic,
  reusable engine; needs a device-scoped variant (G-2), not a rewrite.

## Dependencies & relationships
- **Phase 155** for normalized ages — hard blocker for §D, not just a nice-to-have; `ageRating` does not
  exist on any client-facing DTO yet. Graceful degradation: without it the Maturity facet is hidden
  entirely (never falls back to raw certification strings — viewers must never see `TV-PG`).
- **R186** makes row-seeded browse sets meaningful beyond the old top-20 window — but Continue Watching's
  See-all still needs its own path (G-4), R186 alone doesn't provide it.
- **§G (this spec, new)** — the condition-tree threading + device-scoped narrowed-facets endpoint is a
  hard prerequisite for FR-RV-BROWSE1-1 (seeding) and FR-RV-BROWSE1-5 (live counts); without it only a
  plain single-genre "See all" is buildable.
- The admin **filter workbench (R32 / Phase 140)** is unrelated as a *feature* (that authors *rows*; this
  filters *within* what a row yields) but its backend machinery (`ConditionEvaluator`,
  `MediaStore.facetsNarrowed`) is exactly what §G reuses — closer kinship than the original spec implied.

## Non-goals
- **No phone/mobile browse page** in this phase (`Ravilo Mobile` follows separately).
- **No persistence of a viewer's filters** — state is per-visit, on the view object.
- **No free-text search** inside facets; Search remains its own page.
- **No Discover/Seerr or Live TV integration.**
- **No IMDb rating on `MediaCard`** — sort-by-IMDb is server-side only; this phase does not revisit
  R164's payload-size decision.
- **Channel and Quality facets may ship as a fast-follow** rather than in the same release as the other
  six facets, given G-5/G-6's new-computation/new-data-capture requirements — the page must degrade
  gracefully (facet simply absent) if either isn't ready yet.

## Acceptance
- A 9+-item row shows the → See all tile; an 8-item row doesn't. OK on it opens the seeded page
  with breadcrumb + subtitle; Back returns to the origin.
- Movies/Series nav pages hide Type; row-seeded pages show it; a single-type seed truthfully
  offers one Type value.
- Genre popover on the live library orders by count desc, ties A–Z. Toggling updates grid/counts live
  without closing.
- Maturity: `≤ 7`, `7–11`, `15+` and `4–14` are each settable with ◂ ▸ alone; the ladder
  highlights the span; the chip shows the exact label.
- Sort defaults to Recently added and survives filter changes.
- Continue Watching's See-all tile shows the viewer's real full in-progress/next-up set, not just the
  30-item window a Home row ships today.

## Status
Design-complete, **`Planned`**, backend-reviewed 2026-07-31. Design lives in `design/ravilo/ravilo-browse.js`
(the whole page + popover engine — a JS prototype, not the implementation target), wired via
`design/ravilo/ravilo-app.js`, styled in `design/ravilo/ravilo.css`, localized in
`design/ravilo/ravilo-i18n.js`; exploration in `design/ravilo/Ravilo Browse - Filter UI Directions.html`
(Direction A chosen). Real implementation target is `ravilo-ui`'s Compose code (§G, Reuse). Depends on
**Phase 155** and on **§G's backend work landing first or alongside**. `scripts/check-phases.sh` will
flag it for a `STATUS.md` row — **STATUS.md is code-owned; do not add the row from the design side.**
**Next Ravilo number after this is R188.**

## Backend review addendum (2026-07-31)

Reviewed against the real Compose/Ktor codebase before implementation (the design tool that authored
this has no code access — only `design/ravilo/*.js`/`.html` + `specs/ravilo/constitution.md`/`plan.md`).
Corrections folded into the body above; summary of what changed:

1. **Added an entire missing section (§G).** The original spec never addressed how a seeded, live-counted
   browse set reaches the client — it's the single biggest gap, and without it only plain single-genre
   rows are buildable. Confirmed the good news: the hard parts (a generic condition-tree evaluator,
   device-scoped catalog reads, a row's own query definition) **already exist in the codebase**
   individually — `ConditionEvaluator`, `MediaStore.facetsNarrowed`, `RowConfig.query`,
   `liveItems(device)` — they've just never been wired together for Ravilo. This is threading + one new
   device-scoped endpoint variant, not new architecture.
2. **"Current state" undersold the backend and mischaracterized the row header.** `BrowseService`
   already supports multi-select filters, sort, and full-set pagination; only the Compose UI is limited.
   And "See all" isn't a decorative label today — it's entirely absent from the composable real rows
   render through (`ContentRowItem` never passes `StaticContentRow`'s working `seeAllLabel`/`onSeeAll`
   params).
3. **"Reuse" section pointed at JS mockup files** (`ravilo-app.js`'s `buildGridRows`, etc.) instead of
   real Compose code. Corrected per-item with actual file/class names — most reuse targets check out
   (grid, R174 columns, i18n `Strings.kt`), but the claimed "langPicker capture-key pattern" reuse was
   architecturally backwards (see FR-RV-BROWSE1-8) and "R164 IMDb ratings"/Phase 155 `ageRating` needed
   an explicit "server-side sort only, do not add to `MediaCard`" correction given a prior dev-review
   decision the design tool couldn't have known about.
4. **Surfaced two facets (Channel, Quality) that need genuinely new backend capability**, not just newly
   exposed existing capability — flagged as a plausible fast-follow rather than blocking the other six.
5. Confirmed accurate and left as-is: catalog size (428 items) makes the whole approach performance-safe;
   R174 grid-columns reuse; the Maturity range-picker UX itself (no code conflicts found); numbering
   (R187/R188 don't collide with this session's R183–R186 work).
