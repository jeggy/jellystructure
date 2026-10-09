# Phase R325 — A title's About section, and the audit's three other open calls

> Owner, 2026-09-28, on the About section of a film or series page: *"Let's keep all of that about section and then
> write a spec for it."* The same week the owner answered the design-vs-implementation audit's other three calls
> (tile badge, a Cast or crew facet, genres in search).

## Status

`✓ Built` 2026-09-29 (§Build notes; commit `12feb9c4`) — written 2026-09-28 from `research-reports/ravilo-design-vs-implementation-audit-2026-09-27.md` §2.1, §2.2,
§2.5 and the mockups `design/ravilo/ravilo-app.js` (TV detail, `tileQ()`), `design/ravilo/Ravilo Mobile.html`
(`aboutHTML()`, `.pb`), `design/ravilo/Focus Detail - Directions.html` §F. **Dev-reviewed 2026-09-28** against `main` `32daeee2` (§Dev review). Number verified free
on `main` 2026-09-28 (Ravilo tops at R323; R324 is the speakers phase).

## Decisions (owner, 2026-09-28)

| Audit | Question | Answer |
|---|---|---|
| §2.1 | The About section (drawn since the Focus Detail round, never spec'd, not built) | **Keep all of it**, TV and phone |
| §2.2 | The quality badge on a tile | **Keep it, only for 4K and HDR** |
| §2.5 | A viewer *Cast or crew* facet | **Yes, a facet like Genre** |
| §2.5 | Does search match genres | **Yes — genres as their own result group** |

## Requirements

**FR-R325-1 — The About section** sits on a film or series detail page after the synopsis (and after Episodes on a
series), headed **About**, a two-column grid of label · value pairs, each absent when the server has no value:

| Label | Film | Series |
|---|---|---|
| Runtime | *112 min* | *42 min per episode* |
| Released / First aired | the date in the viewer's locale | the first air date |
| Director | the first director | the creator when there is no director |
| Studio / Network | the first studio | the first network |
| Country | production country | origin country |
| Original language | the language's name in the viewer's language | same |
| Seasons | — | *3 · 30 episodes* (library counts) |
| In your library since | the date the first file was added | same |

On the TV the full synopsis sits beside the grid; on the phone the grid follows the synopsis. No value is invented
and nothing says *Unknown*.

**FR-R325-2 — Where the values come from.** `GET /api/tv/items/{id}` gains an additive `about` block
`{runtime_min, released, director, studio_or_network, country, original_language, added}` from the facts the
server already holds (NFO / TMDB / the scan's added date); `seasons`/`episodes` are the library's counts. WireCompat
(R319) passes: the block is optional, an older app ignores it.

**FR-R325-3 — The tile quality badge** is shown only for **4K** and **HDR** (and *4K HDR*, *Dolby Vision*); a 1080p or
SD title has no badge. The hero and the detail page's meta line keep the full quality as today.

**FR-R325-4 — A *Cast or crew* facet** in the viewer's browse facets, beside Genre: a searchable list of people in
this viewer's libraries (top 40 by title count, then *Search…*), multi-select OR within the facet, AND with the
others; each person's count. The admin workbench's facet of the same name (R190 §D) is the model.

**FR-R325-5 — Search matches genres** as their own result group: *Genres* with a chip per matching genre (the
viewer's normalised genre names, R-normGenre), above titles when the query is a genre's full name; a chip opens
Browse filtered to it.

## Acceptance

1. A film with all eight facts shows all eight; a film with no director shows seven and no gap.
2. The Danish UI reads *Originalsprog · Engelsk*.
3. A 1080p film's tile has no badge; a 4K HDR film's reads *4K HDR*.
4. Browse ▸ Cast or crew ▸ one actor narrows the grid to that actor's titles, with the count shown.
5. Searching *horror* shows a Genres group with *Horror* above the titles.

## Open questions (for the dev review)

1. Whether *In your library since* should be the first file's date or the title's (they differ for a series).

## Dev review (2026-09-28, against `main` `32daeee2`)

Buildable. Two of the eight facts are not held anywhere yet, and two of the four calls cannot be client-side. Nine
items.

1. **Six of the eight facts are held; two are not.** `MediaItem` has `runtime`, `year`, `director`, `studio`/`network`
   (+ `secondaryStudios`), `originalLanguage`, `crew` (with a `Creator` job where TMDB gives one — `RecommendationEngine`
   already keys on it) and `createdAt`. It has **no country** and **no full release / first-air date** (only `year`).
   So FR-R325-2's `about` block needs two fields persisted by `pull_tmdb` (`release_date` / `first_air_date`, and
   `production_countries[0]` / `origin_country[0]`), filled for existing titles by their next pull; until then the
   row is absent and FR-R325-1 hides it without a gap ✓. Small admin-side work inside this phase (R270's precedent
   for a Ravilo spec carrying its backend half); no new number.
2. **Open question 1 → `createdAt`**, our first-insert stamp — never `addedAt` (Jellyfin's `DateCreated` is the file's
   mtime; 181 found half of them junk). For a series: the series record's own `createdAt`, not `max(episode.createdAt)`
   (that float is for *recently added*; the About line answers "since when have we had this show").
3. **Seasons · episodes** are the item's own library counts ✓.
4. **The tile badge (FR-R325-3) is additive, not a filter.** `MediaCard` carries no quality field (only
   `BrowseCard.quality` and `FocusDetailFacts.quality`) and `Tile.kt` draws no badge today. Add
   `MediaCard.quality_badge: String?` = `4K` · `HDR` · `4K HDR` · `Dolby Vision`, null otherwise, resolved server-side
   from the file's video track (the resolver `BrowseCard.quality` uses); the tile draws it top-right; hero and detail
   untouched; old apps ignore it.
5. **Cast or crew (FR-R325-4) must be server-side.** The browse page filters **client-side over the loaded page**
   (`SeededBrowseScreen.kt:534`) and counts facet values from the cards (`:307`) — a card carries no people. So:
   `BrowseFacets` gains `people: List<FacetItem>` (top 40 by title count within the scope) and the browse query gains
   `person=` (multi, OR) — R190's `personTmdbId` seed already filters server-side by one person, so the filter exists
   and the facet is its multi-select form; *Search…* is `GET /api/tv/people?q=` over this profile's libraries. The
   chip applies by re-query — a stated exception to the page's local-filter model, like the seed itself.
6. **Genres in search (FR-R325-5).** `BrowseService.search` matches titles only (`:232`). Add `genres:
   List<{id, label, count}>` to `SearchResults` (additive), matched server-side on the R271 labels (`GenreLabels`) when
   the query equals or prefixes a genre's name in the viewer's language; the TV and phone Search screens render the
   chip row above titles; a chip opens Browse seeded to the genre (R221's contract). Old clients ignore the field.
7. **Where About renders:** `MovieDetailScreen` / `SeriesDetailScreen` (TV) and the phone detail; the mockup's
   `aboutHTML()` is the model. No new screen; the section is absent when the block has no value at all.
8. **Strings** × en/da/fo from the design (`about.title`, `about.runtime`, `about.released`, `about.first_aired`,
   `about.director`, `about.creator`, `about.studio`, `about.network`, `about.country`, `about.original_language`,
   `about.seasons`, `about.in_library_since`, `about.per_episode`); language names through the existing native-name
   table (R180); country names through the platform's locale (`Locale` display names — no table of ours).
9. **Wire:** all additive (`about`, `quality_badge`, `people`, `genres`, `person=`) — R319 passes.

## Build notes (2026-09-29)

Built from the dev review (commit `12feb9c4`):

1. **The About section** — `AboutSection.kt` (TV + phone), under the detail's rows on both `MovieDetailScreen` and
   `SeriesDetailScreen`: the synopsis and every fact the payload carries. The facts the payload lacked are added
   additively to `MediaDetail` (`Models.kt`: production countries, original title/language, studios/networks,
   status, first/last aired…), filled by `DetailService.kt` from what the scanner now keeps (`Media.kt`,
   `Scanner.kt`, `TmdbClient.kt`, respecting `TmdbMatchLock`). Country codes are named client-side (`CountryNames.kt`).
2. **The tile badge is only 4K / HDR** — one resolver, `Quality.kt` (`tileQuality`), used by the home feed, browse and
   channel cards (`Tile.kt`); the hero and the detail meta are unchanged.
3. **A Cast or crew facet** in browse (`BrowseService.kt`, `SeededBrowseScreen.kt`) — the same person filter R190 gave
   the admin workbench.
4. **Search matches genres, as their own result group** (`SearchScreen.kt`, `BrowseService.kt`).
5. 32 strings × en/da/fo (da/fo drafts; the shipped table wins, R279).

Not deployed and not device-tested: the owner withdrew backend-restart and device permission on 2026-09-29, mid-round. Verified by compile (`compileKotlinLinuxX64` · `compileKotlinWasmJs` · `:ravilo-ui:compileDebugKotlinAndroid` · `:ravilo-web:compileKotlinWasmJs` · `:ravilo-cast:compileKotlinJs`), the unit tests named below, and the six fences.

### Verified locally (2026-09-29)

Emulator against the local backend: a film's detail shows the About section under the actions — *Released ·
Original language · In your library since* for the fixture film, then More Like This.

### Found live 2026-10-09 — fixed (search)

- **A title with punctuation was not found by its words.** *Defect:* `/api/tv/search` matched a plain substring of the
  lowercased title, so *"Name-Name Word"* did not find *"Name-Name: Word Word Word"* (the colon). *Fix:* the query and
  the title are reduced to their words (any run of characters that are not letters or digits is one space); a title
  matches when it holds the query's words in order, holds them with the spaces dropped (*namename*), or every query word
  starts one of its words in any order — the plain substring still matches (`SearchQuery`, `SearchQueryTest`). Music
  search is unchanged. **Re-test owed:** the Mac's search for the film's first words.

**Mac re-test (2026-10-09 evening, backend v1.50-130): PASS** — the words before and after the colon of the stand-in
title (*Web-Slinger Brand* for *Web-Slinger: Brand New Dawn*) find the one film.
