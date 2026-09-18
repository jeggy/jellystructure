# Phase R190 — Filter by person (cast/crew) + Seerr overflow row

> A viewer watching something often wants "everything else with **this** actor/director". Today the
> cast & crew circles on a Movie/Series detail are focusable but inert (they only flash the name).
> This phase makes each one a **link into the browse page (R187) seeded to that person** — their
> whole filmography in the library — and, when Seerr is configured, appends a **Seerr overflow row**
> of requestable titles with that person that the library doesn't hold yet. This is the Seerr
> integration R187 deliberately deferred.

## Goal
Press OK on any cast/crew face on a detail page → open the browse page showing every library title
featuring that person, with the full R187 facet bar available to narrow further (by genre, year,
type…). If Seerr is enabled, a single row at the very bottom — after **all** library results —
offers "More with {name} · request on Seerr", each tile opening the normal Seerr request flow.

## Current state
- `castCircle` (`ravilo-app.js`) renders a focusable `.cast` face carrying `_cast = { n, r }`; its
  activate handler only `flash()`ed the name/role.
- The browse page (R187, `ravilo-browse.js`) already seeds from a **row**, a **kind** (Movies/
  Series), or the whole catalog. It owns the facet bar, sort, and count.
- `rankTile` + `seerrCatalog()` + `seerrEnabled()` already power the Discover/Seerr request tiles
  and their `discoverDetail` request flow.

## Requirements

### A. Entry point — the cast/crew face is a link
#### FR-RV-PPL1-1 — OK on a cast/crew face opens a person browse
Each `.cast` face on a Movie/Series detail activates into `go({ type:'browse', person:{n,r},
personFrom:<sourceTitle>, from:<detailView> })`. Focus shows an affordance (a `→` overlay on the
avatar) so it reads as navigable, not just a label. Back returns to the originating detail page.

### B. The person browse page
#### FR-RV-PPL1-2 — Seeded to the person's filmography
The browse page seeds to **every library title the person appears in** (production: a Jellystructure
people/credits query keyed by person id; the design build derives a deterministic filmography from
the catalog, always including the source title). All other R187 behaviour is unchanged: the full
facet bar (Genre · Type · Maturity · Year · Watched · Audio · Channel · Quality), sort (default
Recently added), live counts, D-pad popover ownership. The **Type facet stays** (a person spans
films and series).

#### FR-RV-PPL1-3 — Header carries the person, not a chip
Breadcrumb `◂ <sourceTitle> · <person name>`, the person's **name** as the page title, and the
**role/department** (`Detective`, `Director`…) as a meta line under it. The person is the seed, not
a removable filter chip — exactly as row seeds are handled in R187.

### C. Seerr overflow row
#### FR-RV-PPL1-4 — After all library results, when Seerr is on
When `config.seerr` is set **and** the page is in person mode, append **one** row below the entire
library grid: `⚡ Seerr — More with {name} · request on Seerr`. It holds requestable titles featuring
that person that are **not already in the library** (production: a Jellyseerr person-credits query
minus library matches; design build: a deterministic slice of the Seerr catalog excluding seeded
titles, capped at 12). Tiles are the standard `rankTile`s and open the existing Seerr
`discoverDetail` request flow; **Back from a request returns to the person browse**, not to Discover
(the `discoverDetail` `from` now carries the actual origin view). The row is person-scoped, not
filter-scoped — it does not react to the facet bar. No Seerr, or no overflow matches ⇒ no row.

### D. Admin: a Cast-or-crew facet in the shared filter workbench
#### FR-RV-PPL1-6 — Person is a first-class workbench facet
The Jellystructure admin **filter workbench** (the R32/Phase-140 boolean condition builder shared by
Library filters, Channels, and Content rows — `design/app/ravilo-builders.js`) gains a **Cast or crew**
facet under a new **People** group, alongside Studio/Network/Genre/Tag/Age rating/Audio. It is an
ordinary multi-value list facet: `is any of` / `is none of`, value picker with per-value item counts
(count-ordered, channel-scope-narrowed like every other facet), composable inside AND/OR blocks and
NOT groups. This lets an operator author a channel or row like *"Cast or crew is any of {person} AND
Genre is any of Crime"* — the authoring-side complement to the viewer-side person browse (A–C).
Production maps it to the same cast/crew index the people/credits query uses; the design mock derives a
deterministic 2–3-person cast per title from a shared pool so a person spans several titles.

## i18n
#### FR-RV-PPL1-5 — en/da/fo
One new string, `br_seerr_more` = `More with {name} · request on Seerr` (da: `Mere med {name} ·
anmod på Seerr`; fo: `Meira við {name} · bið á Seerr`), in `Strings.kt` and
`design/ravilo/ravilo-i18n.js`. The role/department line reuses the existing credit label.

## Reuse (don't rebuild)
The whole R187 browse engine (facet bar, sort, popover, `buildGridRows`); `rankTile`,
`seerrCatalog`, `seerrEnabled`, and the `discoverDetail` request flow; the `.cast` component.

## Dependencies & relationships
- **R187** — this is a fourth seed source (person) for the same browse page. Ships on top of it.
- **R81** cast & crew supplies the faces and, in production, the person ids the credits query needs.
- **Seerr pivot (R170/R171)** owns the request flow the overflow row reuses.
- Degrades cleanly: without Seerr the page is just the person's library filmography.

## Non-goals
- **No standalone people index / "browse all cast" page** — entry is always from a detail face.
- **No person artwork/headshots** in this phase (the avatar keeps the initials treatment).
- **No phone/mobile person browse** (follows with the mobile browse page).
- **No persistence** — person seed + filters are per-visit, on the view object.

## Acceptance
- OK on a cast/crew face opens a browse page titled with the person, breadcrumbed to the source
  title, role line beneath; Back returns to the detail. *(Verified in the design build.)*
- The grid holds the person's library titles and the R187 facets narrow within them. *(Verified.)*
- With Seerr on, a single `⚡ Seerr` overflow row sits below all library results; its tiles open the
  request flow and Back returns to the person browse. With Seerr off, no row. *(Verified.)*
- The admin filter workbench offers a **Cast or crew** facet under a **People** group with counted
  values, composable in AND/OR/NOT blocks like any other facet. *(Verified in the design build.)*

## Status
**`Implemented`** (2026-08-02). §A, §B, §C, §D, and the i18n string all built and compile-checked
end to end (backend, admin Kotlin/WASM workbench, ravilo-ui/Compose, ravilo-tizen); **not yet
live-tested on real hardware/browser** — see the closing dev-review addendum for exact scope, the
one deliberate Tizen omission, and what a live pass should check first. Design lives in
`design/ravilo/ravilo-browse.js` (person seed + Seerr row), `design/ravilo/ravilo-app.js` (cast face
→ person browse, `discoverDetail` origin fix), `design/ravilo/ravilo.css` (face affordance + Seerr
row), `design/ravilo/ravilo-i18n.js`, and `design/app/ravilo-builders.js` (the admin workbench
**Cast or crew** facet, §D).

## Dev-review addendum (2026-08-02 — backend-reality check before implementation starts)

Traced every "production: …" and "reuse, don't rebuild" claim above against the actual backend. One
part holds up cleanly; the rest is more work than the spec implies, and one piece (§C/FR-RV-PPL1-4) is
blocked on an external unknown that needs research before backend work starts.

**✅ Confirmed correct — the browse-seed mechanism needs no new plumbing.** `BrowseService.browseByQuery`
(`BrowseService.kt:47-81`) filters via `ConditionEvaluator.matches(item, query, ...)` against *any*
`ConditionGroup` — `Row.seedQuery` (`Models.kt:246-267`) is just one producer of such trees. A person
seed is naturally `ConditionGroup(children=[Condition(facet="cast_crew", op="is_any_of",
values=[tmdbId.toString()])])`, submittable to the existing `POST /api/tv/browse/seeded` unchanged.
FR-RV-PPL1-2's browse-page mechanics (facets, sort, popover) are genuine reuse, exactly as claimed —
*conditional on* the new `cast_crew` facet existing (next point).

**⚠️ Person id is right, but there is no person→titles index — needs new backend work.**
`Person.tmdbId` (`shared`/`model/Media.kt:85-86`, embedded per-item in `MediaItem.cast`/`.crew`,
`Media.kt:218-219`) is a real, stable TMDB person id, so the spec's core premise holds. But the only
existing person-keyed structure, `MediaStore.peopleIndexCache: Map<Int, String>`
(`MediaStore.kt:71`, built by `buildPeopleIndex()` at `:532-542`), is an **image-URL lookup only** —
first-hit-wins (`!map.containsKey(p.tmdbId)`), so it can't even answer "does item X have person Y", let
alone enumerate every item a person appears in. FR-RV-PPL1-2's "production: a Jellystructure
people/credits query keyed by person id" reads as if this already exists; it doesn't. What's actually
needed (and directly reusable for §D's admin facet too, so build it once): a new
`ItemFacets.castCrew: Set<String>` (person ids, lowercased/stringified) alongside the existing
studio/network/genre/tag facet sets in `ConditionEvaluator.kt:50-73`, plus a `"cast_crew"` case in
`evalOne`'s `when` (`:95-129`) — structurally trivial (the pattern is a straight copy of any existing
list facet) but it is new code, not "reuse."

**❌ Seerr person-credits: unverified, possibly nonexistent — do this research before backend work
starts.** `SeerrClient.kt` implements exactly `ping`, `discover`, `search`, `movieDetails`/`tvDetails`,
`createRequest`, `resolveUserId` (`:155-251`) — no person/credits call, and no comment anywhere
referencing one. FR-RV-PPL1-4's "production: a Jellyseerr person-credits query" is asserted with no
existing code support and no confirmation Seerr's public REST API even exposes such an endpoint.
**Recommend:** before scoping backend work for §C, check Seerr's own OpenAPI spec (`seerr-api.yml` on
`github.com/seerr-team/seerr` — already used as ground truth for this project's Seerr work this same
session, e.g. phase 156) for a `/person/{id}` or `/person/{id}/combined_credits`-shaped endpoint. If it
doesn't exist, §C either needs a different data source (e.g. a direct TMDB person-credits call, since
jellystructure already has a TMDB client) or should move to a later phase rather than blocking R190's
otherwise-ready §A/§B/§D on an unresearched external dependency.

**Net effect on scope:** §A (entry point) and the browse-seed half of §B are cheap once the facet
exists. The facet itself (§B's index + §D's admin UI) is the one piece of real, shared backend work —
build it once, both features consume it. §C should be split out or re-scoped pending the Seerr API
check above, so it doesn't block the rest of R190 shipping.

## Implementation notes (2026-08-02 — end-to-end build)

Built all of §A–§D plus i18n on top of the Seerr-blocker resolution above. Everything compiled clean
(`compileKotlinLinuxX64`, admin `compileKotlinWasmJs`, `ravilo-ui`/`ravilo-web` `compileKotlinWasmJs`,
`ravilo-tizen` `compileKotlinJs` + a full production webpack bundle) — **not yet exercised on live
hardware or a browser**, so treat the wiring as correct-by-construction until someone actually presses
OK on a cast face.

- **Backend** — `cast_crew` facet in `ConditionEvaluator.kt` (`ItemFacets.castCrew`, drawn from
  `item.cast + item.crew`, matched on tmdbId — no name matching). `SeerrClient.personCombinedCredits`
  (`GET /person/{id}/combined_credits`) + `SeerrDiscoverService.getPersonOverflow` (dedupes cast+crew,
  drops library matches via the existing `libByTmdb` pattern, caps at 12) behind a new
  `GET /tv/browse/person/{tmdbId}/seerr-overflow` route and a matching `TvApiClient.getPersonOverflow`.
- **Admin workbench** — new **People** group / **Cast or crew** facet in `Workbench.kt`, backed by a
  new `MetaFacets.castCrew`/`NarrowedFacets.castCrew` (`MediaStore.buildMetaFacetsFrom`, capped at the
  library's top 500 most-credited people — a picker, not a full index) threaded through
  `/api/media/meta-facets` and `/api/media/facets`. `TrackFacetItem` gained an optional `label` field
  (value = tmdbId, label = person name) since this is the first facet whose stored value isn't its own
  display text.
- **ravilo-ui** — `CastCircle` gained `onSelect` + a focused `→` overlay (previously genuinely inert).
  `RaviloApp.openPersonBrowse` pushes `Dest.SeededBrowse` with a `cast_crew` seed + the new
  `personRoleLine`/`personTmdbId` fields; `MovieDetailScreen`/`SeriesDetailScreen` thread
  `onCastSelect` down to each `CastCircle`. `SeededBrowseScreen` renders the role meta line and, when
  `personTmdbId` is set, a trailing full-grid-span Seerr overflow row (`SeededBrowseStore.seerrOverflow`,
  fetched independently of the facet-filtered grid, per FR-RV-PPL1-4's "does not react to the facet
  bar") using the existing `RequestTile`/Seerr request flow. New string `browse.seerr_more` in en/da/fo.
- **ravilo-tizen** — new `PersonBrowseScreen.kt`: same `cast_crew`-seeded `browseSeeded` call, rendered
  as a plain poster grid (this client has no facet-bar UI at all, matching `BrowseScreen.kt`'s existing
  scope). Wired into `DetailScreen`'s previously-dead `"cast"` activate branch.
  **Deliberately not built on Tizen: §C's Seerr overflow row.** It opens the Seerr request-detail flow,
  and the entire Discover tab (which owns that flow) was explicitly scoped OUT of the Tizen build
  (`phase-R189-tizen-samsung-tv-client.md`) — there is nothing on this client for an overflow tile to
  open. Revisit if/when Discover ever lands on Tizen.

**Before calling this done:** live-test OK-on-a-cast-face on at least one real client (soveværelse TV
or the Pixel 9 per this project's usual testing convention) — the D-pad focus affordance, the person
browse page's facet bar, and the Seerr overflow row's request flow are all new interaction surfaces
that only a compile check cannot verify.

## Dev-review addendum update (2026-08-02 — Seerr blocker resolved, full implementation starting)

Checked the real, live Seerr instance (`stream.jebster.net`) directly with the configured API key rather
than relying on the vendored OpenAPI doc:

```
GET /api/v1/person/500            -> 200, full TMDB-shaped person object (name, biography, ...)
GET /api/v1/person/500/combined_credits -> 200, { cast: [...], crew: [...] }, each credit carrying
                                            id (TMDB id), mediaType ("movie"/"tv"), title, posterPath, etc.
```

Both endpoints exist and work exactly as FR-RV-PPL1-4 assumed. §C is unblocked. Credit entries carry no
`mediaInfo`/library-match field, so "not already in the library" still needs a client-side (i.e.
backend-service-side) cross-reference against `mediaStore.allItems()` keyed by `tmdbId` — the same
`libByTmdb` pattern `SeerrDiscoverService.toDiscoverEntry`/`acquisitionFor` already uses for the regular
Discover tab. No new unknowns; proceeding with full implementation of §A–§D now.
