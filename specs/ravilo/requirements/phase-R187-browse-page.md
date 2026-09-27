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
Implemented 2026-07-31 — backend (§G) and Compose UI (facet bar, popovers, Maturity range picker, sort,
See-all tiles) — see the two implementation addenda below. One deliberate scope trim: Movies/Series nav
still uses the pre-existing `BrowseScreen`/`BrowseKind` flow rather than delegating to the new screen
(FR-RV-BROWSE1-2) — see the UI addendum. Design lives in `design/ravilo/ravilo-browse.js`
(the whole page + popover engine — a JS prototype, not the implementation target), wired via
`design/ravilo/ravilo-app.js`, styled in `design/ravilo/ravilo.css`, localized in
`design/ravilo/ravilo-i18n.js`; exploration in `design/ravilo/Ravilo Browse - Filter UI Directions.html`
(Direction A chosen). Depends on **Phase 155** (implemented). `scripts/check-phases.sh` will
flag it for a `STATUS.md` row — **STATUS.md is code-owned; do not add the row from the design side.**
**Next Ravilo number after this is R188.**

## Implementation addendum (2026-07-31) — backend (§G)

One real design pivot from §G's plan, plus straightforward execution of the rest:

- **Pivoted from "per-facet narrowed-counts endpoint" to "return the full seed-matching set once."**
  §G originally planned a device-scoped sibling of the admin's `MediaStore.facetsNarrowed`, called once
  per open popover with a different query each time (seed AND every other active facet). Building that
  meant reimplementing narrowed-counting for Maturity/Year/Watched/Channel/Quality, none of which exist
  in `MetaFacets`/`TrackFacets` today. Since the confirmed catalog size (428 items) makes "hand the whole
  matching set to the client" cheap, and `BrowseCard` (new, additive-only DTO — see below) already needs
  to carry genres/audio/quality/channels/IMDb per item for display anyway, computing every facet's counts
  and every sort reactively from that one in-memory list client-side eliminates six separate
  server-side counting code paths for one network round trip instead of one-per-popover-open. Genre/Type/
  Maturity/Year/Watched/Audio/Quality/Channel/sort-by-IMDb are ALL client-computed from a single
  `POST /tv/browse/seeded` response — no facets endpoint exists or is needed.
- **`BrowseCard`** (`shared/.../tv/Models.kt`) wraps a plain `MediaCard` (unchanged, used everywhere else)
  with the extra per-item fields the facet bar needs: `genres`, `audioLanguages`, `quality`, `channels`,
  `imdbRating`. Deliberately not folded into `MediaCard` itself, for the same payload-bloat reason R164
  kept IMDb rating off it.
- **Quality facet (G-6) data capture**: `Track` gained `width`/`height`/`videoRange` ("SDR"/"HDR" only,
  not a full HDR10/HDR10+/DV breakdown — R183's playback-negotiation code already owns that finer
  distinction for streaming decisions; this is display/filtering only), populated by `FfprobeRunner.probe`
  from ffprobe's existing full stream JSON (no command change needed, the fields were always in the
  output, just not parsed). `BrowseService.qualityLabel()` buckets to "4K"/"1080p"/"720p"/"SD" (+" HDR").
- **Channel facet (G-5)**: computed per-request, per-item, by evaluating every enabled channel's
  `effectiveQuery()` via the existing `ConditionEvaluator` — exactly the "cheap at this scale, no reverse
  index needed" approach the spec anticipated.
- **Row seed threading (G-1)**: `Row` gained `seedQuery: ConditionGroup?` + `seedMediaKind: String?`.
  `CUSTOM` rows reuse `rowCfg.effectiveQuery()` directly. `GENRE` rows needed one real subtlety: their
  live substring match (title term "sci" catching "Science Fiction") isn't expressible as
  `ConditionEvaluator`'s exact-match "genre" facet, so the seed is built from the real genre *strings*
  that matched in the row's own candidate set, not the search terms — reproducible via the seeded-browse
  endpoint, correct for that row's actual membership. Channel-scoped rows AND the channel's own
  `effectiveQuery()` onto the row's query (`withChannelSeed`). `CONTINUE` rows get no seed (G-4, see
  below). `NEWLY_ADDED` rows were left unseeded too — deliberately out of this pass: a Newly-Added row's
  natural "see all" is just the Movies/Series nav page, whose default sort is already "Recently added"
  (FR-RV-BROWSE1-7), so a distinct seeded page for it is redundant.
- **Continue Watching's own See-all (G-4)**: `HomeFeedService.continueWatchingAll()` reuses
  `buildContinueRow`'s exact resume/next-up join logic with the Home row's 30-item cap lifted, behind a
  dedicated `GET /tv/continue/all` (plain `MediaCard`s — no facet bar on this page, per FR-RV-BROWSE1-1's
  framing of it as "the viewer's full in-progress list," not a filterable catalog view).
- Verified: `compileKotlinLinuxX64`, `linuxX64Test` (full suite), `compileKotlinWasmJs` (admin),
  `:ravilo-ui:compileDebugKotlinAndroid` + `:ravilo-ui:compileKotlinWasmJs` (Compose consumers of the
  changed shared models) all pass. Not yet exercised live (needs the Compose UI to call it, in progress,
  plus a backend restart).

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
   **Superseded**: the operator chose to build all 8 in this pass — see the backend addendum's Quality/
   Channel notes (§G-5/G-6 both implemented, not deferred).
5. Confirmed accurate and left as-is: catalog size (428 items) makes the whole approach performance-safe;
   R174 grid-columns reuse; the Maturity range-picker UX itself (no code conflicts found); numbering
   (R187/R188 don't collide with this session's R183–R186 work).

## Implementation addendum (2026-07-31) — Compose UI

- **`SeededBrowseScreen.kt`** (new file) implements the facet bar, popovers, and Maturity range picker
  directly against the backend's "fetch once, compute reactively" design (previous addendum) — `matches()`/
  `valuesFor()`/`sortedFiltered()` are plain Kotlin over the in-memory `List<BrowseCard>`, recomputed via
  `remember(...)` keyed on every active filter, matching FR-RV-BROWSE1-5's live-update requirement with
  zero network round trips per popover interaction.
- **Popover "owns the remote"**: confirmed live in code (not just theory) that Compose's own focus
  exclusivity is sufficient — each popover is a `Box` with its own `dpadFocusable` rows, focused via
  `LaunchedEffect` the moment it opens; no PlayerScreen-style root key interceptor was needed, closing
  the open question FR-RV-BROWSE1-8 originally flagged.
- **See-all wiring**: `HomeScreen`'s `onSeeAll` param changed from the dead `(String?) -> Unit` to
  `(Row) -> Unit`, actually threaded into both `ContentRowItem` calls; `ChannelScreen` gained the same
  `onSeeAll` param and wiring (not in the original backend-review scope, added for parity — a channel
  row without a See-all tile would have been a visible gap). Both gate the tile on `row.items.size > 8`
  AND the row having a resolvable seed (`CONTINUE`, or `seedQuery`/`seedMediaKind` non-null) — Newly-Added
  rows correctly get no tile (deferred server-side per the backend addendum, not a UI oversight).
- **Two self-review catches, fixed before commit** (no live device to test against, so this pass leaned
  on rereading the diff rather than trusting it): the Maturity picker's ▲▼ between "From"/"Up to" were
  wired as literal no-op stub lambdas (`{ }`) instead of moving focus between the two rows — fixed with a
  second `FocusRequester`. Separately, `BrowseCardGrid` took an `onGridUp` parameter that was never
  actually connected to anything inside the function (dead code masquerading as a working bridge) —
  removed rather than left in, with a comment on why native spatial search is expected to suffice here
  (the facet bar and grid are both left-anchored at the same offset, unlike the full-width-hero case
  elsewhere in this app that genuinely needs an explicit bridge).
- **Deferred**: Movies/Series nav delegating to this screen (FR-RV-BROWSE1-2) — the existing
  `BrowseScreen`/`BrowseStore`/`BrowseKind` flow has ~10 call sites across the app (every screen's nav
  bar); re-pointing MOVIES/SERIES to `SeededBrowseScreen` in the same pass as building it fresh was judged
  higher-risk than shipping the new screen for rows/Continue-Watching first and doing the nav delegation
  as a follow-up once it's been exercised on a real device. My List keeps its existing plain grid either way.
- **Verified**: `compileKotlinLinuxX64`, `linuxX64Test`, `compileKotlinWasmJs` (admin), `:ravilo-ui:compileDebugKotlinAndroid`,
  `:ravilo-ui:compileKotlinWasmJs` all pass. **Not verified**: D-pad focus/key behavior on a real TV —
  Compose UI can't be screenshotted or exercised the way the admin wasmJs pages were earlier this session;
  this needs on-device testing (stuetv), which is the operator's own to run.

## Phone amendment (2026-09-26) — a popover closes when you leave, and Back closes it first

**Bug confirmed on the Pixel 9** (debug build 1.40-44, during R316's device check). Library's Genre
popover was found open when the Library tab was opened. It stayed open across switches to Home and
Discover and back, and system Back did not close it: Back scrolled the grid to the top instead. It was
live UI, with its rows in the accessibility tree inside the app's own window. A cold start opens
Library with it closed, so a stray tap during the test opened it. What is wrong is that nothing ever
closed it again.

**Why.** FR-RV-BROWSE1-8 already says *"navigating anywhere force-closes a stray popover"*, and that
Back closes it. Both were built for the TV's D-pad:
- **Back** is the `onBack` of the popover's own focused rows, a key event. On a phone, the Back gesture
  never arrives as a key event: `OnBackPressedDispatcher` takes it first, and it runs `RaviloApp`'s Back
  ladder (back-to-top, pop, Home, exit). A popover opened by touch does not hold focus anyway.
- **The open flag** (`openFacet` / `sortOpen`) lives on `SeededBrowseStore`, which outlives the screen:
  the bottom bar's pages keep their stores between visits. So leaving the page never cleared it, and the
  popover came back with the page.

The Library type pill (*Alle*) is unaffected. It is a platform `Popup` with `onDismissRequest`, so Back
and an outside tap already close it.

**FR-RV-BROWSE1-8a — Leaving the page closes its popovers (every platform).** When the browse page
leaves composition, its store's facet and sort popovers are closed. That covers a bottom-bar tab switch,
opening a title, Back, and a Library type switch, which swaps the store. Coming back shows the page with
every popover closed and the filters as they were. The selections are the viewer's; an open popover is
not.

**FR-RV-BROWSE1-8b — On a phone, Back closes an open popover before anything else.** While a facet or
sort popover is open, one Back closes it and does nothing more: no scroll to top, no leaving the page.
The next Back runs the normal ladder. This is FR-R304-3's pattern: a `PlatformBackHandler` registered by
the screen, enabled only while a popover is open, which wins over `RaviloApp`'s handler because it
registers after it. It is off on the TV. The TV's Back is a key event, which the popover's focused row
already consumes, and the dispatcher path stays switched off there, as for the player. The web is
unchanged: `PlatformBackHandler` is a no-op there, and browser Back stays browser Back.

**Not changed:**
- Tapping outside the popover. A tap on a poster opens it, and that closes the popover by
  FR-RV-BROWSE1-8a. The chip still toggles its popover.
- The popover stays open while values are toggled (FR-RV-BROWSE1-5).

**Built 2026-09-26** (`SeededBrowseScreen.kt`).
- `SeededBrowseStore.closePopovers()` and `popoverOpen`.
- `DisposableEffect(store) { onDispose { closePopovers() } }`, keyed on the store, so a Library type
  switch closes the old store's popover too.
- `PlatformBackHandler(enabled = !isTvPlatform && popoverOpen) { closePopovers() }`.

**Verified on the Pixel 9** (debug `1.42-4`, gesture navigation), each step read from the UI tree:
- A Genre popover open, then Hjem → Bibliotek: closed.
- A popover open, then the Back gesture (an edge swipe, first confirmed to be a real Back by taking a
  page with no popover to Home): the popover closed and the page stayed on Library. The next gesture went
  Home.
- The Back key did the same.
- The `ravilo-ui` unit tests and the web compile pass.

**Not verified:** a TV. The TV's path is unchanged by design.

## Amendment (2026-09-27) — Back returns to the grid where you left it

> Owner, 2026-09-27: *"When on the library page and opening some item and going back it just goes back to
> the top of the page, i would like to go back and have it scrolled to the exact same point as before, so
> it looks like a proper back."*

**Bug confirmed on the Pixel 9** (debug `1.42-4`, before the fix). Library → scroll a few rows down → open
a series → Back: the grid is at the top, on the first three titles. Filters and type are kept; only the
position is lost. Same code on the TV, the web and every *See all* page, since all of them are this screen.

**Why.** The position is not lost in storage: `SeededBrowseStore` is kept across navigation (R40) and holds
the `LazyGridState`. Two things throw it away when the page comes back:
- **The sort-reset effect fires on every entry, not only on a sort change.** 6f021b80 added
  `LaunchedEffect(sortField, sortDir) { focusItemKey = null; scrollToItem(0) }` so that a new sort opens at
  its top. A `LaunchedEffect` also runs the first time it composes, and the page composes afresh every time
  it is returned to. So every Back scrolled to the top and dropped the tile to restore focus to (R137/R139's
  TV restore was undone by the same line).
- **The return reloads through `Loading`.** `LaunchedEffect(store) { store.load() }` re-fetches on every
  entry (the watched ✓ must be fresh after watching), and `load()` sets `Loading` first. The grid leaves
  composition, *Indlæser…* flashes, and the list comes back as a new layout. R40 already says a kept store
  "refreshes silently on re-entry"; this one did not.

**FR-RV-BROWSE1-10 — Back returns to the grid exactly where it was (every platform).** Coming back to a
browse page (Back from a title, or back to the Library tab from another tab) shows the grid at the same
scroll position, to the pixel, with the same filters, type and sort. The filter strip keeps its sideways
scroll too (a viewer who swiped it to reach *Sort* finds it there); both states live on the kept store.
Specifically:
- **Only a real sort change scrolls to the top.** The store remembers the sort the grid was last laid out
  in; the reset runs only when the viewer's choice differs from it.
- **A return refreshes silently.** When the store already holds a list, `load()` keeps it on screen and
  swaps in the new one when it lands; there is no `Loading` state and no flash. The grid keeps its place by
  the first visible title's key, so a title added above does not shift what the viewer was looking at. A
  failed refresh keeps the list that is on screen (stale beats an error page); a first load still shows
  *Loading* and, on failure, the error state with Retry.
- **On a TV, focus lands on the tile that was opened** (R139, restored). **On a phone nothing is focused**:
  focusing the tile would bring it fully into view, and a tile tapped at the screen's edge would move.
- **Re-tapping Library still scrolls to the top** (FR-R267-9), and **picking a type opens that type at its
  top**, like a sort: it is a new list. Each type keeps its own store, so without this the pill would bring
  back wherever that type was last left.

**Not changed:** the facet selections and popovers (FR-RV-BROWSE1-8a closes popovers on leaving); what the
refresh fetches; the order of the list.

**Built 2026-09-27** (`SeededBrowseScreen.kt`, `RaviloApp.kt`).
- `SeededBrowseStore.laidOutSort`: the sort effect returns early when the sort equals it.
- `load()` keeps a `Loaded` list while refreshing; a superseded or failed refresh writes nothing.
- `SeededBrowseStore.facetBarState`: the filter strip's `LazyListState`, kept with the grid's.
- `restoreItemKey` is `null` on a handset.
- The Library type pill calls `requestScrollToItem(0)` on the picked type's kept store, if it has one.

**Verified on the Pixel 9** (debug `1.42-30`, before and after):
- Before: Library scrolled down → a series opened → Back: the grid at the top.
- After: Library scrolled to a mid-row offset, the filter strip swiped to *Sort*, a poster opened → Back: the
  area below the app bar is pixel-identical to the frame before the tap (compared from screenshots). Same
  for a poster half off the bottom edge, and under the *Title* sort.
- A frame caught mid-transition after Back shows the grid already in place: no *Loading* frame.
- Library → Home → Library: identical. Re-tapping Library: top. Alle scrolled → Serier: top; Serier
  scrolled → Film → Serier: top. Sort changed to *Title*: top.
- `ravilo-ui` unit tests (260) and the wasmJs compile pass.

**Not verified:** a TV (focus lands on the opened tile again, R139) and the web.
