# Collections and content rows filter by year, rating and franchise, sort best-rated first, and lay out one row per franchise

Task: DEB-2837

> Owner, 2026-10-29 (DEB-799): *"You can also suggest new filters, then we'll create a task to add support for
> this new filter. Also let's focus on things that are not really shown on our current home page, so we will have a
> better way and easier to browse and find everything we have without it being cluttered."* Then, on the three
> filters Tróndur proposed: *"Can you hand those new filters to Bárður and he'll make sure it'll be created."*

## Scope

Backend, admin, and one small Ravilo client piece (FR-7). Three new conditions in the workbench query tree
(phase 140), one new row-order key (phase 225, the way phase 268 added *size*), and one new row mode. Every
installed Ravilo build keeps working unchanged: rows reach it as ordinary rows.

Measured on prod on 2026-10-29 (no titles here: the repository is public):

- **Year.** Every title but one has a `year`. 385 of 614 titles are from the 2020s; *Newly Added* and *Anbefalet*
  keep the 229 older ones out of sight.
- **Rating.** 195 titles have an IMDb rating (`MediaItem.imdbRating`, phase 131) and 372 more have only TMDB's
  vote average and count (`tmdbVoteAverage` / `tmdbVoteCount`, phase 269). 48 have neither. So the TMDB fallback is
  not a corner case: it carries two thirds of the rated library. No title today has fewer than 200 IMDb votes
  while having 200 or more on TMDB.
- **Franchise.** `collectionId` / `collectionName` (TMDB `belongs_to_collection`, phase 269) is on 135 films. 31
  franchises have two or more films (85 films); 50 have one. Every stored name but one ends in *Collection*. TMDB
  gives a collection to films only, never to a series.
- **Release date.** `releaseDate` is on 181 of 367 films, so release order falls back to the year.

The editor's facets before this spec: `studio`, `network`, `genre`, `tag`, `audio_language`, `audio_codec`,
`age_rating`, `cast_crew`, `metadata_language`, `track_title`, `hero_item`, `content_row`, plus the row's
*Include* (films/series). None filters on year, rating or franchise.

**UX surface.** Admin only, and built from controls the workbench already has: the checklist facet for
franchises, number fields for the bounds, the segmented control and stepper from the *Order* section. The TV,
phone and browser see rows and nothing new. No design round.

**Not in this spec** (owner agreed on DEB-799 to leave them out): per-viewer watched state, 4K/HDR and running
time filters. Setting up any particular collection with these filters is the operator's, in the admin.

## Requirements

### FR-1 — One rating rule, shared by the filter, the sort and the recommendation engine

A title's **rating** is IMDb's `aggregateRating` with IMDb's `voteCount` when the title has an IMDb rating;
otherwise TMDB's `tmdbVoteAverage` with `tmdbVoteCount` when that count is above zero; otherwise the title has no
rating. A title **meets a vote minimum *m*** when its rating exists and the vote count of the source that gave it
is at least *m*. A title whose IMDb rating is thinly voted does not fall through to TMDB: the source is chosen
first, then the minimum is checked.

This is the rule `RecommendationEngine.rating()` already uses (FR-269-5). It moves to common code, as one
function the server and the admin's preview both call, and the engine calls the moved function. The engine's
behaviour does not change.

`DEFAULT_MIN_VOTES = 200`. It is the rating condition's default (FR-3) and the sort's floor (FR-4).

### FR-2 — Condition `year`: a from–to range

`Condition(facet = "year", op = "between", values = [from, to])`. Each bound is a four-digit year as a string, or
`""` for *no bound on this side*. Both bounds are inclusive. A title matches when its `year` is within the bounds.
A title with no `year` matches no range. A series counts by its `year`, which is the year it started.

- `["", "1989"]` = before 1990; `["1990", "1999"]` = the 1990s; `["2020", ""]` = 2020 and later.
- The condition is **live** only when at least one bound is not blank. `isLive()` (`QueryTree.kt`) and the
  editor's `nodeLive` both learn this, so a fresh, unfilled range is skipped like any other empty condition, and
  `pruned()` drops it on save.
- A NOT block over a range means *outside the range, or no year*. That follows phase 140's block semantics and
  needs no special case.

**Editor.** Facet group *Metadata*, label **Year**. Two number fields, *from* and *to*, with *any* as the
placeholder. Under them, quick picks that fill both fields: *Before 1990 · 1990s · 2000s · 2010s · 2020s*. The
summary reads *Year 1990–1999*, *Year up to 1989*, *Year 2020 or later*.

### FR-3 — Condition `rating`: at least *r*, with at least *m* votes

`Condition(facet = "rating", op = "at_least", values = [r, m])`. *r* is a decimal from 0 to 10 written with a
`.`; *m* is a whole number of votes, and `""` or a missing second value means `DEFAULT_MIN_VOTES`. A title matches
when it meets the vote minimum *m* and its rating (FR-1) is at least *r*. A title with no rating, or with too few
votes, does not match.

- Live when *r* is not blank.
- *Below r* is a NOT block over the condition. It also takes in unrated titles. The editor's NOT help text says
  so already for every facet, and nothing more is needed.

**Editor.** Facet group *Metadata*, label **Rating**. A number field for *r* (step 0.1), and *with at least* [200]
*votes*. A line under them in the panel's muted style: *IMDb's rating, or TMDB's when IMDb has none.* The summary
reads *Rating 7.5 or higher (200+ votes)*.

### FR-4 — Row order key `rating`: best-rated first

`RowOrder.KEYS` gains `rating`, through the same resolver the server builds the row with and the editor previews
it with (FR-225-2). The value is the title's rating (FR-1) when it meets `DEFAULT_MIN_VOTES`. Titles without one
go **last in either direction**, then by name: the rule *size* follows (FR-268-6).

- Direction in words (FR-225-9, never asc/desc): *best-rated first* / *lowest-rated first*. The default
  direction is *best-rated first*.
- The *Order* section gains *Rating* beside *Date added · Title · Release year · Size*. Hand-picks first, then the
  rest by rating, works as it does for every other key.
- The config validator's error message lists the new key.
- `Row.sort_by = "rating"` reaches the client. An installed app opens *See all* in its default order (R253: an
  unknown key means *recently added*). FR-7 makes it open in the row's order.

### FR-5 — Condition `franchise`: is any of / is none of

`Condition(facet = "franchise", op = "is_any_of" | "is_none_of", values = [collectionId, …])`. The values are
TMDB collection ids as strings. The labels are not stored, so a renamed collection still matches. It works like
`cast_crew`: a checklist facet whose value is an id and whose label is a name.

- A series has no franchise. It never matches `is_any_of`, and always matches `is_none_of`.
- **The admin facets payload** (`MetaFacets`, and the narrowed set) gains `franchises`: one item per collection id,
  labelled with its display name (FR-6.3) and counted, sorted by count, like every other checklist facet. The
  field is new and additive on an admin-only payload.
- **Editor.** Facet group *Metadata*, label **Franchise**. The summary reads *Franchise "…", "…"*, with the
  display names.

### FR-6 — Row mode: one row per franchise

A content row can be set to **One row per franchise**. The row is then a template. The server serves one row for
each franchise among the row's matches, in the row's place in the list.

**6.1 — The wire.** `RowConfig` gains `@SerialName("split_by") val splitBy: String? = null`. The only value is
`"franchise"`. `null` means the row is served as it is today. The field is additive and ignored by every installed
client, because the TV never evaluates rows. The validator answers 400 for any other value, and for `splitBy` on a
`CONTINUE` or `NEWLY_ADDED` row.

**6.2 — What is served.** The template's matches are worked out as for any custom row: its *Include* and its
query, inside its collection when it is a collection's row. The films are grouped by `collectionId`. Titles with
no franchise drop out. A franchise gets a row when it has **at least two** matching titles. Each served row is
a plain `RowKind.CUSTOM` `Row`:

- `id` = `"<template id>~franchise-<collectionId>"`. It is stable across feeds, so a client's focus and scroll
  memory hold.
- `title` = the franchise's display name (6.3).
- `items` in **release order**: by `year` ascending, then `releaseDate` ascending where both titles have one, then
  by name. The row's `limit` caps each franchise row. It defaults to 30, and no franchise on prod comes near it.
- `seedQuery` = the template's seed ANDed with `franchise is_any_of [collectionId]`. *See all* on any installed
  app therefore opens exactly that franchise, through the same evaluator. `seedTotalCount` is that franchise's
  match count.
- `sort_by = "year"`, `sort_descending = false`, so *See all* opens oldest first: release order, by year.

The franchise rows follow each other, **the franchise with the most recently added film first** (`recencyKey()`,
the library's own *new* signal), ties by display name. This follows the owner's phase-225 answer that newest
first is the default order. A template with no franchise of two or more serves no row, the way an empty custom row
serves none.

**6.3 — The display name.** TMDB's `collectionName` with a trailing ` Collection` removed (case-insensitive,
whole word, with any space or dash in front of it). Nearly every TMDB collection name ends in it, and a
franchise collection does not need the word on every row. If nothing is left, or there is no name at all, the row is not
served. The same name labels the franchise checklist (FR-5).

**6.4 — A template's sort and hand-picks do not apply.** A split row is always in release order. The editor's
*Order* section for a split row shows the single line *Each franchise in release order, newest franchise first*
and the *Show N titles* stepper. It does not save `sort` or `pinned` for a split row. The server ignores them if
they are present.

**6.5 — Membership.** A `content_row` condition that references a split row matches on the template's own filter,
its *Include* and query. The split does not change membership. That keeps a reference unchanged when it goes
through a client that does not know `split_by` (a *See all* seed is decoded and sent back by the app, and an older
app drops the field).

**6.6 — Editor.** Under the row's *Include*, a switch: **One row per franchise**. It is on offer only when the row
can contain films. Next to it, *Each film series gets its own row, in release order.* When the switch is on, the
*Matches* panel lists the franchise rows that would be served: name, film count, then the films in release order.
The row list's second line says *One row per franchise · N franchises*.

### FR-7 — Ravilo: *See all* opens a rating-ordered row in the row's order

The client piece. It is additive and needs no new string.

- `BrowseCard` gains `@SerialName("rating_score") val ratingScore: Double? = null`: the title's rating (FR-1) when
  it meets `DEFAULT_MIN_VOTES`, absent otherwise. It is used only on the browse page, like `imdb_rating`.
- `initialBrowseSort` maps `"rating"` to the existing *IMDb* sort field, with the row's direction.
- The *IMDb* sort orders by `ratingScore`, falling back to `imdbRating.aggregateRating`, and puts unrated titles
  last. A row and its *See all* page then agree on titles that only TMDB rates. An app talking to an older server,
  which sends no `rating_score`, sorts exactly as it does today.

The sort popover's label stays *IMDb*, the existing string. Renaming it is a viewer-visible change, so it is left
for the owner to ask for.

### FR-8 — Validation

`RaviloConfigService.validate()` answers 400 with a sentence naming the row or collection, as FR-225-11 does, when:

- a `year` bound is neither blank nor a whole number from 1870 to 2100, or *from* is greater than *to*;
- a `rating` *r* is not a number from 0 to 10, or *m* is not a whole number ≥ 0;
- `split_by` is anything other than `null` or `"franchise"`, or is set on a system row (FR-6.1).

## Acceptance

1. **Nothing moves.** A config with none of the new facets, keys or fields serves the same feed, row for row and
   tile for tile. The existing `HomeFeedService`, `RowOrderTest` and `ConditionEvaluatorTest` suites pass
   unchanged, and the wire baseline differs only by the added fields.
2. **Year.** `["1990","1999"]` matches 1990 and 1999, not 1989 or 2000; `["","1989"]` matches 1950 and not 1990;
   a title with no year matches neither. `["",""]` is not live, and a block holding only that matches everything.
3. **Rating.** For `[7.5, 200]`: IMDb 7.6 with 5 000 votes matches. IMDb 7.6 with 150 votes does not, even with
   TMDB 8.0 from 900 votes. No IMDb rating and TMDB 7.8 from 300 votes matches. No IMDb rating and TMDB 7.8 from
   100 votes does not. Unrated does not. A second value of `""` behaves as 200.
4. **Rating sort.** Best-rated first puts 8.1 before 7.4 before an unrated title. Lowest-rated first puts 7.4
   before 8.1, and the unrated title is still last. The editor's preview and the served row are identical.
5. **Franchise.** `is_any_of [id]` matches the franchise's films and no series. `is_none_of [id]` matches
   everything else.
6. **Split rows.** A split template whose matches hold franchise A (3 films, newest added last week), franchise B
   (2 films, added last year), franchise C (1 film) and two films with no franchise serves two rows, A then B.
   Each is titled without *Collection*, its films in release order, with ids `<template>~franchise-<id>`. Each
   row's `seedQuery` returns that franchise alone from the seeded-browse endpoint.
7. **Back-compat.** A recorded installed-app decode of the new feed (the R319 wire baseline) shows split rows as
   plain `CUSTOM` rows. A `seedQuery` with `year`, `rating` and `franchise` conditions round-trips through the
   shared `Condition` serializer byte for byte.
8. **Validation.** Each bad write in FR-8 answers 400 with its sentence.

## Build order

Three slices for one engineer, each `blocked` by the one before:

1. **Year, rating and the rating sort** (FR-1 to FR-4, FR-8's first two bullets), server and editor. With this
   slice an operator can build a collection by decade or by rating.
2. **Franchise and one row per franchise** (FR-5, FR-6, FR-8's last bullet), server and editor. With this slice
   an operator can build a collection of franchises.
3. **Ravilo *See all*** (FR-7). The wire field, the mapping and the sort.
