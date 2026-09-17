# Phase 216 — The library indexed by studio, network and genre

> The server half of **R243**. Jellystructure's Metadata page already groups the library by studio,
> network and genre and states an item count for each; Ravilo has no equivalent, and no viewer-side way
> to ask "what else is from here?". This phase makes that index a served, per-viewer resource: one
> endpoint, counts that are true for the profile asking, and a seed the existing browse query can take
> verbatim. No new metadata field, no new artwork pipeline, no third kind of list.

## Status

`✓ Built` — written 2026-09-16, **dev-reviewed 2026-09-16 against `main`** (see §Dev review at the
bottom; six requirements were corrected against the real code and the shape of the phase changed
substantially — most of what this phase proposed to build already ships), **implemented 2026-09-16**
(see §Implementation notes). Compiled (`compileKotlinLinuxX64`, `:ravilo-ui:compileKotlinWasmJs`) and
unit-tested (`BrowseServiceTaxonomyTest`, `ConditionEvaluatorTest`); not deployed, not run against
production data.

**Numbering:** verified against `main` on 2026-09-16 — admin taken through **215**, Ravilo through
**R242**, no `phase-216-*` file and no `STATUS.md` row for it. Pairs with **R243**. Next free: 217 / R244.

Design: `design/ravilo/Ravilo TV.html` + `ravilo-app.js` (`renderTaxonomy`/`taxoTile`),
`ravilo-data.js` (`taxonomy`/`taxonomySummary`/`taxoValues`/`libraryFor`/`normAge`),
`ravilo.css`, `Ravilo Mobile.html`. Admin precedent: `design/app/metadata.html`.

## Current state

Three facts about the library are already resolved per title and already shown:

- **Studio and network are two separate columns** on `media` (`Media.sq:7-8`), written down two different
  scanner paths: a film's `studio` from TMDB's `productionCompanies` (with the rest in
  `secondaryStudios`), a series' `network` from `networks.firstOrNull()`. The media detail labels its
  fact **Network** or **Studio** by reading whichever column is populated — there is no flag.
  *(Corrected in dev review; the original text described a single company field plus an `isNetwork`
  flag, which is the design mockup's resolver, not this backend.)*
- **Genres** are a real `List<String>` on the item in TMDB's own order, normalised by phase 155/R221's
  rules, and already drive R221's genre chips and the browse facet. *(Corrected in dev review: they are
  a list, not a ` · `-separated string.)*
- The **admin** Metadata page counts all three across the whole library and shows the counts to an
  operator, with logos where one was captured at scan time.

What is missing is the viewer's version of the same index. Nothing on the wire tells Ravilo how many
titles a studio has, and nothing could: the client holds one page of one feed, never the library, so any
count it derived would be a count of what it happens to have loaded. The count is the whole point of the
surface — a wall of logos without numbers is a wall of logos.

Two constraints make this a server phase rather than a client one:

1. **Counts must be scoped to the viewer.** A kids profile can see a smaller library, and a count that
   promises 14 titles and opens a grid of 6 is worse than no count.
2. **The count and the grid must come from one query.** R233 FR-R233-5 settled the shape for system rows:
   the canonical list is built once per `(user, visibility scope)` and every surface is a *filtered view*
   of it, never a re-derivation. The same rule applies here, for the same reason.

## Goal

One per-viewer taxonomy index, correct by construction against the grid it opens, carrying artwork only
where artwork genuinely exists.

## Functional requirements

**FR-216-1 — Extend the endpoint that already counts these, do not add a second one.** ⚠ **Corrected in
dev review.** `GET /api/tv/facets?kind=` already exists (`TvRoutes.kt:536`) and already returns exactly
this data: `BrowseService.facets(device, kind)` builds `BrowseFacets(genres, studios, networks, tags)` as
`FacetItem(name, count)`, counted over `mediaStore.liveItems(device)` — so already per-viewer — and
already sorted count-descending. The shared client already has `getFacets(kind)` and `BrowseScreen`
already consumes it.

A new `/api/tv/taxonomy` would therefore be a **second counter of the same three things**, which is the
precise failure R233 exists to prevent: two surfaces deriving the same number independently and drifting
apart. This phase **extends `BrowseFacets` and `BrowseService.facets()`** instead:

- add `logoUrl` to `FacetItem`, present only per FR-216-5;
- add the summary fields below to `BrowseFacets`;
- add the cache in FR-216-8 to `facets()` itself, which is currently uncached (see FR-216-11).

The response gains four summary fields alongside the existing four lists, and `logoUrl` on the items:

```jsonc
{
  "groups":  10,    // = the requested kind's list length, so the header needs no arithmetic
  "titles":  22,    // titles carrying ≥1 value of this kind, counted ONCE (see FR-216-3)
  "library": 50,    // titles visible to this profile, for "22 of 50" style copy
  "scoped":  true,  // this profile sees less than the whole library (FR-216-2)
  "networks": [
    { "name": "Dansk TV", "count": 4, "logoUrl": "/api/metadata/networks/Dansk%20TV/artwork" },
    { "name": "DR",       "count": 3 }
  ],
  "studios": [ /* … */ ], "genres": [ /* … */ ], "tags": [ /* … */ ]
}
```

`groups` is per-request because `kind` already is; the other three are properties of the profile's whole
visible library and do not vary by kind. The client must still not be able to ask for "the library" and
count it itself, and no per-title fetch backs this surface.

**FR-216-2 — Counts are the profile's, not the library's.** ⚠ **Corrected in dev review — the original
requirement described a maturity filter this server does not have.** Every count is computed over
`mediaStore.liveItems(device)`, and `MediaItem.visibleTo(device)` (`MediaStore.kt:60-61`) is **exactly
two things**: the Phase 142 library allow-list, and Jellyfin's `AllowedTags`/`BlockedTags` policy. There
is **no age or maturity filter anywhere in the server-side catalog**. `DeviceData.isKids` exists but is
read only by the Seerr request path; `JellyfinPolicy.maxParentalRating` is read once at login to set that
boolean and never consulted again.

So a kids profile sees a smaller library **because its Jellyfin tag policy excludes titles**, not because
anything compares ages. Phase 155's "no mapped rating ⇒ 18" rule governs the browse page's own Maturity
facet (`normAge`), which is a viewer-applied filter, not a visibility rule, and it must not be restated
here as though it gated counting.

`scoped: true` when `allowedLibraries != null` or either tag set is non-empty, so the client can say the
count is narrowed rather than silently under-reporting.

**FR-216-3 — A title counts once per surface, but under every value it names.** ⚠ **Corrected in dev
review — nothing splits on ` · ` anywhere in this codebase.** The real multi-value mechanism is
`MediaItem.secondaryStudios` (`Media.kt:226`), populated by the scanner from
`productionCompanies.drop(1)` (`Scanner.kt:415`). `facets()` already counts it correctly:

```kotlin
(listOfNotNull(item.studio) + item.secondaryStudios).distinct().forEach { … }
```

**Networks are strictly single-valued** — there is no `secondaryNetworks` column and the scanner writes
`networks.firstOrNull()?.name`. A network naming two broadcasters is therefore **not representable**
today, so the `RÚV · Dansk TV` example is a studios example or it is nothing. Making networks
multi-valued is a scanner + schema change and belongs in its own phase, not here.

`titles` at the top level is **not** the sum of `count` — it is the number of distinct titles carrying at
least one value of that kind.

**FR-216-4 — Studio and network are already separate columns; there is no flag to consult.** ⚠
**Corrected in dev review.** `media` carries `studio TEXT` and `network TEXT` as two columns
(`Media.sq:7-8`), written down two different scanner paths. There is no `isNetwork` flag anywhere in the
codebase — that was a design-mockup construct standing in for a resolver the backend does not need. So
`kind=networks` is `item.network`, `kind=studios` is `item.studio` plus `secondaryStudios`. No
inference, no new column, no per-kind metadata pull, and the mockups' "films resolving out of the
broadcaster pool" problem cannot occur server-side.

**One real inconsistency this phase must settle, in one direction.** The admin Metadata page filters
networks to `MediaKind.TV_SHOW` (`MetadataRoutes.kt:193`) while `BrowseService.facets()` counts
`item.network` for **every** kind. Both ship today. Whichever rule wins, both call sites adopt it, or the
viewer wall and the admin page state different numbers for the same network. Recommended: adopt the
admin's TV-only rule, since a film with a broadcaster in `network` is the mis-filed case, and record that
acceptance test 7 tests exactly this.

**FR-216-5 — Artwork only where artwork exists.** `logoUrl` is present **only** when a logo was actually
captured for that value. There is no placeholder URL, no "no logo" sentinel, and no on-demand fetch
triggered by this endpoint — TMDB has no network search, which the admin page already states, so most
networks will never have one. Absent `logoUrl` is a normal, expected, permanent state and the client
renders the name instead (R243 FR-R243-2).

**The source already exists and is the only one permitted.** `LogoDownloader.hasLogo("studios"|"networks",
name)` is the exact "was a logo really captured" predicate — the admin Metadata page already calls it to
populate `MetadataEntry.hasLogo` (`MetadataRoutes.kt:129`) — and
`GET /api/metadata/{studios|networks}/{name}/artwork` already serves the bytes. So `logoUrl` is that URL
when `hasLogo` is true and the key is **absent** otherwise. Do not introduce a second logo store, and do
not derive the URL from `studioLogoPath`/`networkLogoPath`: those are TMDB-side paths recording where a
logo *could* come from, which is not the same claim as having one on disk. **Genres have no logo source
at all** and must never carry the key.

**FR-216-6 — Zero never ships.** A value with no visible titles for this user is omitted entirely. A kids
profile therefore sees a shorter wall, not a wall of zeroes, and no tile can open an empty grid.

**FR-216-7 — The grid is seeded from the same resolver.** Opening a value runs the existing browse query
with a taxonomy seed (`kind` + `value`) resolved by the **same** code that produced the count. The
acceptance test for this phase is arithmetic: the grid's result count equals the tile's count, for every
value, for every profile. Genres reuse R221's existing genre seed — no second genre path.

**Dev review — the server half of this already ships; the client half does not.** `GET /api/tv/browse`
already accepts repeated `studio`, `network`, `genre` and `tag` query parameters and applies them
case-insensitively (`TvRoutes.kt:495-497`, `BrowseService.kt:147-151`). What is missing is on the shared
client: `TvApiClient.browse()` sends only `kind`, `genre`, `page` and `pageSize`, so studio and network
seeds cannot currently be expressed from Ravilo at all. Adding those two repeated parameters is the whole
of this requirement's client work, and R243 FR-R243-5 depends on it.

**One hazard the arithmetic test must not trip over.** `browse()` returns `total = sorted.size` but maps
its cards through `distinctBy { it.id }`, so `total` and the number of rendered cards can differ when the
library holds duplicate ids. Compare the tile's count against `total`, and treat any divergence between
`total` and the rendered card count as a separate pre-existing defect to report, not as a failure of this
phase.

**FR-216-8 — Cached like the home feed is, and invalidated by `feedVersion` — not `libraryVersion`.** ⚠
**Corrected in dev review; naming the wrong counter here would reproduce a measured production
regression.** Phase 204 (FR-204-2) added `MediaStore.feedVersion` precisely because `libraryVersion`
bumps on **every** write regardless of content, so during a scan it discarded the assembled Home feed for
every user on every item write — the 120× p50 degradation phase 182 measured. A taxonomy cache keyed on
`libraryVersion` reproduces that exactly.

Match `HomeFeedService`'s real shape rather than the abstraction: key the map on `userId`, and validate
the entry against `feedVersion` **and** `allowedHash`, where
`allowedHash = (allowedLibraries.hashCode() * 31 + allowedTags.hashCode()) * 31 + blockedTags.hashCode()`
(`HomeFeedService.kt:159`). That triple is what "per `(user, visibility scope)`" actually means in this
codebase. Never key on the requested `kind`, the selected value, or anything narrower — R233 FR-R233-5's
rule, restated because this is the same trap: filtering is a view, never a re-derivation.

**FR-216-9 — Values are normalised once, in a resolver both counting and filtering call.** Trim, collapse
internal whitespace, and group case-insensitively while displaying the most frequent spelling. `HBO
Nordic` and `HBO  nordic` are one network with one count, or the wall quietly lies twice.

⚠ **Dev review found this is not merely additive — the two sides already disagree.** Counting groups with
an exact-match `groupBy` (`facets()`, and `MetadataRoutes.kt:124`), while filtering compares with
`equals(ignoreCase = true)` (`BrowseService.kt:148-150`). So a case variant **already** makes a tile's
count disagree with its own grid, before this phase adds anything. Normalisation that lands only on the
counting side moves the disagreement rather than closing it. It must live in **one** shared resolver that
both `facets()` and `browse()`'s studio/network/genre predicates call, and FR-216-7's arithmetic test is
what proves it.

**FR-216-10 — No new config gates this.** These three tabs read the library the viewer already has, so
they need neither Seerr nor Sonarr nor a per-user switch, and Ravilo's Discover nav item no longer
depends on either integration being configured (R243 FR-R243-1). If a household wants them off, that is a
new `RaviloConfig` field and a follow-up phase — see open question 1 — not something to design in
speculatively.

**FR-216-11 — This must not add an uncached full-library pass to a hot read path.** ⚠ **Added in dev
review.** `BrowseService.facets()` today iterates the entire visible library on **every** call and caches
nothing — acceptable while its only caller is the browse page's facet bar, which a viewer opens rarely.
Putting a Discover wall in front of it makes it a tab-open cost, on the same read path phases **204–208**
were spent reducing. FR-216-8's cache is therefore not an optimisation to defer: it is a precondition of
shipping the wall at all.

Two things follow. The cache goes on `facets()` itself, so the browse facet bar gets it too rather than
only the new caller. And the summary fields in FR-216-1 are computed in the same single pass as the four
count maps — `library` is `all.size`, `titles` is a distinct count accumulated while iterating — never as
a second traversal.

## Non-goals

- **No tags and no age-rating tab.** The admin Metadata page has six tabs; three of them are operator
  tools (tags a human maintains, certification mappings, trackers) and have no viewer meaning.
- **No artwork acquisition.** This endpoint reads what the artwork pipeline (phase 192) already captured.
  It never fetches, never queues a fetch, and never reports a missing logo as a problem.
- **No new list type.** Selecting a value opens the browse grid that already exists.
- **No cross-over with Seerr.** These counts are the library on the shelf. Requestable titles are the
  Request tab's business and must never be added into a count of what you can press play on.
- **No sort control.** Ordering is fixed by FR-216-1 (count descending, name as tie-break) — a sort
  affordance on a wall of ten tiles is a control nobody needs on a remote.
- **No Channels/Collections here.** Jellystructure's configured channels (the Disney+-style rail on Home)
  are a curated, operator-defined concept. Conflating them with scanner metadata would put two different
  meanings of "studio" on one screen.

## Acceptance

1. `GET /api/tv/facets?kind=movie` as an adult profile returns every studio with ≥1 visible title,
   count-descending, and `groups` equals the returned `studios` list length.
2. The same call **as a profile whose Jellyfin policy carries `BlockedTags`** returns fewer groups and
   smaller counts than the adult profile, and `scoped` is `true`. (Corrected in dev review: the original
   test asserted that no unrated title is counted for a kids profile, which **cannot pass** — nothing in
   the server-side catalog filters on age. See FR-216-2.)
3. A film carrying a second production company in `secondaryStudios` appears in the count of **both**
   studios, and raises top-level `titles` by exactly **one**. (Corrected in dev review: the original
   test used a two-broadcaster **network**, which the schema cannot represent.)
4. For every value in every kind, opening the browse grid with that seed returns exactly `count` titles,
   for the same profile — compared against `SearchResults.total`, per FR-216-7's note.
5. A value whose only titles are hidden from this profile is **absent**, not present with `count: 0`.
6. A network with no captured logo returns **no** `logoUrl` key; nothing in the response distinguishes
   "no logo" from "logo pending". Every genre returns no `logoUrl` key, ever.
7. `networks` returns no films and `studios` no series — **and the same call against
   `GET /api/metadata/networks` returns the same count for the same value**, which is the test that the
   FR-216-4 inconsistency was actually settled rather than merely noted.
8. Two spellings differing only by case or padding produce **one** group, **and** seeding the grid with
   either spelling returns that group's full count (FR-216-9's two-sided form).
9. A library write that changes content bumps `feedVersion` and the next call recomputes; a write that
   changes nothing does not; repeated calls in between are served from cache and re-iterate no items.
10. Response for a 50-title library is ≲4 KB and issues no image request of its own.
11. With the cache warm, a Discover tab open performs **no** full-library iteration (FR-216-11).

## Source references

- `design/app/metadata.html` — the admin half this mirrors: grouping, counts, the "TMDB has no network
  search" note that FR-216-5 formalises.
- `design/ravilo/ravilo-data.js` — reference resolvers `taxonomy()`, `taxonomySummary()`, `taxoValues()`,
  `libraryFor()`, `normAge()`; the demo's stand-in for this endpoint, written so the TV wall, the phone
  wall and the filtered grid cannot state different numbers.
- Related: **155** (age normalisation; no rating ⇒ 18), **R221** (genre naming + the existing genre
  browse seed), **R233** (one canonical list per `(user, visibility scope)`; filtering is a view),
  **R219** (the visibility-scope cache key), **192** (artwork/clearlogo pipeline — the only source of
  `logoUrl`), **R187** (the browse page this seeds).

## Open questions

1. **Should the tabs be per-user config?** Nothing gates them today (FR-216-10). The argument for a
   switch is a household that wants Discover to stay a request surface; the argument against is that
   every other library-browse affordance (Movies, Series, browse facets) is ungated too. Left out
   deliberately rather than guessed.
2. **Precomputed at scan time, or per request?** The counts are cheap over a 500-title library and
   FR-216-8's cache absorbs the rest, but a large library may want the index materialised alongside the
   scan. Measure before building it.
3. **Is the company field trustworthy enough per-kind?** FR-216-4 says a mis-filed film is a metadata
   problem. Whether the real library has enough of them to need a repair sweep (phase 192's shape) is a
   question for the first run against production data, not for this spec.
4. **Should `titles` vs `library` be surfaced at all?** R243 renders "10 networks · 22 titles". Whether a
   viewer benefits from also knowing 28 titles carry no network is untested copy.

## Dev review (2026-09-16)

Reviewed against `main` at `080364b4`. **The shape of this phase changed: most of what it proposed to
build already ships, and four requirements described a backend that does not exist.** Nothing here
changes what the viewer sees — R243's wall is unaffected — but the server work is smaller and lands in
different code than the spec assumed.

### What already exists, and made FR-216-1 a rewrite

| Claimed as new | Already on `main` |
|---|---|
| An endpoint returning counted, sorted taxonomy values per viewer | `GET /api/tv/facets?kind=` → `BrowseService.facets()` |
| Per-viewer scoping of those counts | `mediaStore.liveItems(device)` |
| Count-descending ordering | `sortedByDescending { it.value }` in `facets()` |
| A client method to fetch them | `TvApiClient.getFacets(kind)`, consumed by `BrowseScreen` |
| A browse query seeded by studio/network/genre | `GET /api/tv/browse` with repeated `studio`/`network`/`genre` params |
| Multi-studio counting | `(listOfNotNull(studio) + secondaryStudios).distinct()` |
| A "was a logo captured" predicate | `LogoDownloader.hasLogo(kind, name)`, already used by the admin page |

What is genuinely new: `logoUrl` on the facet items, the four summary fields, the cache, the shared
normalisation resolver, and two repeated query parameters on `TvApiClient.browse()`.

### The four corrections, and why each mattered

1. **FR-216-2 described an age filter that does not exist.** `visibleTo(device)` is the library
   allow-list plus Jellyfin's tag policy, and nothing else. `isKids` reaches only the Seerr request path.
   The original acceptance test 2 could not have passed.
2. **FR-216-3/4's `isNetwork` flag and ` · ` splitting are design-mockup constructs.** The schema has two
   separate columns and a `secondaryStudios` list. The practical consequence is that the phase gets
   *easier*, except that multi-valued networks are not representable at all.
3. **FR-216-8 named `libraryVersion`.** Phase 204 introduced `feedVersion` specifically because keying
   feed caches on `libraryVersion` discarded them on every item write during a scan — the 120× p50
   regression phase 182 measured. This would have reproduced it.
4. **FR-216-9's normalisation was framed as additive, and it is not.** Counting already groups
   case-sensitively while filtering already compares case-insensitively, so FR-216-7's arithmetic
   acceptance test **fails on `main` today** for any library holding a case variant. Worth confirming
   against production data before build, since it decides whether this is a one-line resolver or a
   data-repair job.

### One new requirement

**FR-216-11** — the wall must not add an uncached full-library pass to a hot read path. `facets()` caches
nothing today, which was fine for a rarely-opened facet bar and is not fine for a Discover tab.

### Left alone deliberately

The non-goals all survive review. FR-216-5/6/7/10 are unchanged in intent. Open questions 1, 2 and 4
stand; **open question 3 is largely answered** — a film cannot be mis-filed into the broadcaster pool
server-side, because films and series write different columns, so the repair-sweep risk the mockups
found does not carry over. What remains of it is the narrower question in FR-216-4: whether real data has
films carrying a `network` value at all.

## Implementation notes (2026-09-16)

Built as the dev review resized it — `BrowseService.facets()` extended, nothing added beside it.

- **`TaxonomyKey`** (`tv/TaxonomyKey.kt`) is FR-216-9's one resolver: `key()` (trim, collapse
  whitespace, lowercase) for grouping and matching, `display()` for what a viewer sees, and a
  `Counter` that groups by key and shows the **most frequent spelling**. It is called by `facets()`,
  by `browse()`'s four taxonomy predicates, by `ConditionEvaluator.ItemFacets` (so the seeded
  `POST /tv/browse/seeded` path — the one R221's genre chip and R243's tiles actually use — matches
  under the same key), and by the admin `GET /api/metadata/{studios,networks,genres}` grouping.
  `ConditionEvaluatorTest` (14 cases) still passes; the taxonomy facets got strictly more permissive.
- **`BrowseFacets`** gained `library`, `scoped` and `titles: Map<String, Int>` (distinct titles per list
  name); `FacetItem` gained `logoUrl: String?` (absent from the JSON when null — the server's
  `Json` does not encode defaults). `groups` was **not** added: it equals the list's length and the
  client reads that directly. `titles` is a map rather than one field because the endpoint returns all
  four lists in one response and each has its own distinct-title count.
- **Cache** (FR-216-8/11): one `FacetsEntry` per Jellyfin user holding the *all / movie / series*
  slices from **one pass**, validated against `feedVersion` + `allowedHash` (the exact
  `HomeFeedService` triple) with a 5-minute TTL backstop. Never keyed on `kind` or a value.
- **FR-216-4 settled: networks count for `TV_SHOW` only**, adopting the admin page's rule; a film
  hand-edited to carry a broadcaster is not a wall entry. Studios stay kind-neutral like the admin page.
  The scanner only writes `network` on the series path and `studio` on the movie/music-video paths, so
  neither rule excludes anything scanned. **`ConditionEvaluator`'s `network` facet is deliberately left
  kind-neutral** (its tests assert a film matching `network is HBO`); R243 therefore seeds a network
  tile with `seedMediaKind = "SERIES"`, which is what makes the count and the grid agree.
- **`logoUrl`** is `RaviloImageUrl.taxonomyLogo(kind, name)` = `/api/tv/image/logo/{studios|networks}/{name}`,
  a new public route under the existing `/api/tv/image/` OPEN_API_PATHS prefix (Coil cannot attach a
  device token, exactly like posters and channel logos). The spec's `/api/metadata/.../artwork` is
  admin-cookie-authenticated and unreachable from a TV. Emitted only when `LogoDownloader.hasLogo` is
  true; `BrowseService` now takes the `LogoDownloader` (nullable, so a test can run without a data dir).
- **`TvApiClient.browse()`** gained repeated `studio`/`network` parameters (FR-216-7's client work).
- **Test** `BrowseServiceTaxonomyTest`: spelling variants are one group with the most frequent spelling;
  a secondary studio counts under both studios and once in `titles`; a film's broadcaster is not a
  network; every value's count equals both grid paths' `total` (acceptance 4 and 8, two-sided); kind
  slices; absent-never-zero; cache hit and `feedVersion` recompute.
- **Not done, noted for a follow-up:** `MediaStore.facetsNarrowed()` (`:857`, the admin *library*
  workbench's own counter) still groups exactly while its filter (`:478`) compares case-insensitively —
  the same pre-existing disagreement on a surface this phase does not own. Acceptance 7's live
  comparison and 10/11's measurements were not run (no production data in this session).
