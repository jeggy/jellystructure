# Phase 269 — A *Recommended* row, built for each viewer in the background

> Owner, 2026-09-26: *"Let's add support for a new content row type. So I in jellystructure ravilo layout
> settings, can add a Recommended section row. Where it always provides 20 items (even when it's the first
> time entering). And it should be user specific, so based on user activity (probably fetched from
> jellyfin etc). I don't think this should be calculated on the fly, so this would be something that's
> generated and maintained in some background job as part of the whole pipeline schedule or worker job
> etc. Let's investigate if we should start gathering more metadata about our media that we can store in
> jellystructure, so we can provide better recommended content."*

## Status

`✓ Built` 2026-09-26, **not deployed** (see *Build notes* at the end). Written 2026-09-26,
**dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*). Backend (the metadata, the engine, the job, the row) and the admin (row editor, Users &
devices). The client half is **R318**. The optional AI layer on top is **Phase 270**; this phase stands
on its own without it. **Numbering:** verified against `STATUS.md` the same day — admin taken through
**268**.

## What there is to build on, measured on production 2026-09-26

**What a viewer did.** Jellyfin holds every viewer's history from every client, not only from Ravilo:

- `JellyfinClient.getRecentlyPlayedAll` pages `/Items?userId=…&Filters=IsPlayed` over films and episodes,
  newest first, with `UserData` (`LastPlayedDate`, `PlayCount`, `Played`). Continue Watching already
  calls it (R219). For the owner that is 1,104 episodes (~1 MB).
- `getResumeItemsAll`: what is in progress.
- `getFavoriteItemIds`: *My List*.
- `PlaystateCache` has played / progress / favourite per title, refreshed every 20 s, but no dates.
- Ravilo's own `playback_qoe` is per device and Ravilo-only (810 sessions for the owner, 22 for another
  viewer). Jellyfin's history is the better source.

**What a title is.** Of 528 films and series: 527 have a TMDB id, 522 genres, 496 cast, 521 a synopsis.
Studio or network, year, original language, certifications and IMDb rating (158) are stored too.
**Not stored:**

- TMDB **keywords**: themes such as *heist*, *time travel*, *coming of age*;
- TMDB's own **recommendations** for a title: which titles people who liked it also liked;
- a film's **collection**, i.e. its franchise;
- **vote counts**, to tell a well-rated title from one rated by three people.

TMDB serves all four on the details request with `append_to_response`. Today `TmdbClient` makes separate
requests for details, credits, external ids, videos and ratings, and uses `append_to_response` nowhere.

**Rows.** `RowKind { CONTINUE, NEWLY_ADDED, GENRE, CUSTOM }`. Continue Watching is the only per-viewer
row. A row's kind is sent to the apps twice:

- `Row.kind`, on every Home and channel feed;
- `RowConfig.kind`, on `/api/tv/config`.

The apps decode it strictly: `ignoreUnknownKeys`, but no enum fallback. **A new kind sent as it is would
fail Home and the config on every installed TV and phone.**

## Requirements

### The row

**FR-269-1 — A *Recommended* row kind.** `RowKind.RECOMMENDED`, added in Jellystructure → Ravilo layout
like any row: on Home, and in a channel, where it is scoped to the channel's own filter the way R233
scopes the system rows. Its title defaults to *Recommended for you* and is editable. **Each viewer has 50
recommendations** (owner, 2026-09-26). The row shows as many as its limit says, like every row (Phase
225's `limit`, default 20, at most 30), and ***See all*** opens the full 50 (R318). It takes no
conditions, no sort and no hand-picks (a recommendation *is* an order), and the editor says so instead
of showing those sections.

**FR-269-2 — The new kind never reaches an app that cannot read it.** On the wire it is a plain content
row:

- `/api/tv/home` and channel feeds send it as `kind: CUSTOM` with no `seed_query` and no
  `seed_media_kind`, so every installed app draws it as an ordinary row with no *See all*
  (`HomeScreen.canSeeAll`). An additive `recommendations: true` lets an app with R318 add the *See all*,
  backed by `GET /api/tv/recommendations` (the viewer's still-eligible list, up to 50);
- `/api/tv/config` sends its `RowConfig` as `CUSTOM` too (the apps never evaluate rows).

A test decodes both responses with the shipped client's `Json` settings. R318 makes the apps tolerant of
unknown kinds from now on. This rule stays anyway, for the apps already installed.

### What it knows about a title

**FR-269-3 — Four more facts per title.** The details request gains
`append_to_response=keywords,recommendations`, and two more requests fetch recommendation pages 2 and 3
(owner, 2026-09-26: *"the more the better"*). Stored on the title:

- `keywords`: TMDB ids and names;
- `tmdbRecommendations`: TMDB's list, pages 1–3 (up to 60), in its order;
- `collectionId` and name (films; `belongs_to_collection`, already in the details body);
- `voteCount` (also already in the body).

That is the answer to *"should we gather more metadata"*: these four are what similarity needs. Keywords
and page 1 cost bytes on a request already made; pages 2–3 cost two requests per title, once per TMDB
pull. Measured on production 2026-09-26 (all 527 titles):

| | Films | Series |
|---|---|---|
| page 1 recommendations in the library | 779 of 6,400 (12.2 %) | 162 of 4,080 (4.0 %) |
| pages 2–3 in the library | +802 (6.3 %) | +244 (3.0 %) |
| TMDB's *similar* list instead (100-title sample) | 1.4 % | |

So pages 2–3 roughly double the in-library links, at half the density: a lower-ranked recommendation
counts less (FR-269-5). *Similar* is not used. **Genres are read by id** (Phase 271, built first), because
stored names are split across languages (41 names for 24 genres).

Existing titles get the four facts by treating "no keywords yet" as *missing* in `pull_tmdb`'s skip rule
(Phase 183 FR-183-4) for one pass: 527 details calls plus 1,054 page requests, paced by 183's token
bucket and spread over pipeline runs by the freshness filter. Not needed, and deliberately not
fetched: reviews, watch providers, full crew, per-country popularity.

### How a viewer's list is built

**FR-269-4 — Signals, per viewer, from Jellyfin.** Per build:

- **played history** (`LastPlayedDate`, `PlayCount`);
- **in progress** (resume position as a share of runtime);
- **favourites**.

A played episode counts toward its series, weighted by how much of the series was watched. Each signal
has a weight:

- finished = 1;
- in progress = its share;
- started, stopped under 10 % and untouched for 30 days = a weak negative;
- a rewatch (`PlayCount > 1`) and a favourite count extra;
- age decays with a 120-day half-life.

**FR-269-5 — Score, then diversify.**

**Candidates** are the titles this viewer may see, from the same visibility the Home feed uses (library
access and tag policy, so a kids profile gets a kids list), minus:

- what they have finished;
- what is already in their Continue Watching;
- what they left as a weak negative.

**Each candidate's score** is:

- its similarity to the viewer's weighted history over keywords, genre ids, cast and crew (weighted by
  billing), studio or network, collection, original language and era. Each feature is weighted by how
  rare it is in the library (inverse document frequency): *Drama* is on half the library and says little,
  a keyword on six titles says a lot. Similarity is the cosine between the title's weighted features and
  the viewer's summed profile;
- plus a strong edge when a title they watched lists it in `tmdbRecommendations`, divided by
  `1 + rank / 10`, so page 1's first entry counts about three times as much as page 3's last;
- plus a quality prior (IMDb rating, damped by vote count);
- plus a small boost for titles added in the last 30 days.

**A quality floor:** a title rated below 5.5 by at least 1,000 IMDb voters is never recommended. The
prototype (below) put a 4.7-rated horror film second on the owner's list without it.

**Then diversity:**

- at most two titles from one collection or series family;
- no genre over half the list;
- never three titles in a row that share a first genre.

The result is **deterministic**: the same inputs give the same list, ties broken by id.

**FR-269-6 — Always a full list, from the first visit.** A viewer with no history gets the scope's **starter
list**, built by the same job:

1. titles the household watches most, as anonymous counts across viewers of the same visibility scope;
2. then the highest rated with enough votes;
3. then the newest.

A viewer with some history gets their scored list topped up from the starter list when it has fewer than
50. The list is shorter than 50 only when fewer titles are eligible at all, and it never pads with titles
already watched.

**FR-269-7 — Stored, never computed on a request.** Table `recommendation (user_id, scope_hash, rank,
item_id, score, reason_code, reason_item_id, source, built_at)`, keyed per viewer and visibility scope
(the Home cache's key, R233 FR-R233-5). **50** are stored. The row serves the first *limit* that are
**still eligible at request time**, and *See all* serves all still-eligible ones: a title finished since
the build is skipped using `PlaystateCache`, which is a filter and not a recompute. The per-viewer
rebuild after a finish (FR-269-8) restores the 50 within minutes.

**FR-269-8 — When it is built.**

1. **A pipeline step**, `build_recommendations`, in 261's step table, with its own cadence (**weekly**
   by default; owner, 2026-09-26). It rebuilds every viewer and every scope's starter list.
2. **When a viewer finishes something.** A played-state change for that viewer (seen by
   `PlaystateCache`'s refresh, whichever client played it) marks the viewer stale, and a debounced
   background rebuild of **that viewer only** follows within ten minutes, on `GateClass.BACKGROUND`.
3. **A viewer with no list yet** gets the starter list at once (FR-269-6), and a build is queued.

Measured cost: one paged Jellyfin history read per viewer, about 1 MB for the heaviest, and a score over
about 500 titles. Seconds per household, and never on a request path.

### Seeing why

**FR-269-9 — The admin can see each viewer's list and why.** *Users & devices*, per user:

- a *Recommended for {name}* expander with the 50, each with its reason in words (*because you watched
  {title}*, *liked in your household*, *highly rated*, *new in the library*), plus when it was built
  and by what (standard, or Phase 270's AI);
- *Rebuild now*.

The row editor's preview gains *Preview as {viewer}*.

**FR-269-10 — Nothing about one viewer reaches another.** A viewer's list is built only from their own
history. The only cross-viewer input is FR-269-6's anonymous count, and only within one visibility
scope, so no kids list ever learns from an adult's history. Recommendations are never sent to any third
party by this phase.

**FR-269-11 — Tests.**

- scoring determinism;
- a viewer with no history gets 50;
- a viewer with three watched titles gets 50, topped up;
- an eligibility filter per rule;
- the decay and the weak negative;
- the FR-269-2 wire mapping decoded by the shipped client;
- the FR-269-7 request-time skip.

## Prototype run on production data (2026-09-26)

The algorithm above was run in a scratch script over production's data: the owner's Jellyfin history
(90 films and 1,458 episodes played, 178 library titles, 90 in progress excluded), all 527 titles'
TMDB keywords and recommendation pages 1–3, the genre ids, and the stored cast, crew, network,
language and IMDb ratings. Three things it taught, all folded into the requirements above:

1. **Genre names split by language.** Without ids (Phase 271), *Komedie* and *Comedy* were different
   features.
2. **A quality prior alone is not enough.** A 4.7-rated film ranked second until the floor was added.
3. **The list is only as personal as the account.** The owner's account is shared with children, so
   the owner's list leaned towards children's titles. It was right about the account and wrong about the
   person. Separate Jellyfin users for the children fix it; nothing in the algorithm can.

104 of the 527 titles (20 %) have no TMDB keywords. Those are where Phase 270's theme tags help.

## Non-goals

- Titles not in the library (a *Request* suggestion belongs to Seerr) and Live TV.
- Several *Because you watched X* rows. One row, with the reason kept for the admin.
- Thumbs up / down from the viewer. There is no rating control, and adding one is a design question.
- The AI re-rank and theme tags: Phase 270.

## Acceptance

1. Add a *Recommended* row to Home in the layout editor. The TV shows the row's limit (20 by default)
   on its next Home load, none finished, none in Continue Watching, and *See all* (R318) opens 50.
2. A new viewer with no history sees a full row on their very first Home.
3. The owner finishes a film: within ten minutes their row has changed and that film is gone from it,
   and a household member's row has not changed.
4. A kids profile's row has only titles that profile can see.
5. An app installed before this phase shows the row as an ordinary row, and Home and Settings still load.
6. *Users & devices* lists each viewer's 50 with a reason each.

## Dev review (2026-09-26, against `main` `0e5e434f`)

The foundations are where the spec says. Eight items, two of them corrections.

1. **The wire mapping has exactly two exits.** Home and channel rows are built in `HomeFeedService`. The
   recommended row is constructed there with `kind = RowKind.CUSTOM`, so the server never builds a
   `Row` with the new kind. The TVs' config is `raviloConfigService.getConfig(userId)` at
   `TvRoutes.kt:800`; wrap that response in a `forClients()` that maps `RECOMMENDED` → `CUSTOM` in its
   rows and channel rows. The admin's own config route must keep `RECOMMENDED`, or the editor would
   save the row back as `CUSTOM`. Test both routes' JSON with the client's `Json` (`TvApiClient.kt:42`).
2. **Correction to FR-269-3: `append_to_response` on the first attempt only.** `pull_tmdb` reaches
   details through `getMovieDetailsLocalized` / `getTvDetailsLocalized` (`Scanner.kt:345`, `:845`, `:926`,
   `:1129`), which call `getMovieDetails` (`TmdbClient.kt:573`) **once per language** until one fits. So
   the extras ride the chain's first request only, and are kept from whichever response carries them.
   Keywords and the recommendations' ids do not depend on the language. The details cache
   (`detailsCache`, keyed by id) caches the base call only; the extras are parsed and stored on the item,
   not re-cached.
3. **The backfill trigger is `null`, not empty.** `TitleChecks` decides whether `pull_tmdb` applies to an
   item (`TitleChecks.kt:122`: `scope == "all" || tmdbId == null`). Add `|| item.keywords == null`.
   `null` means *never fetched*, `[]` means *TMDB has none*, so a title with no keywords is not fetched
   forever.
4. **Correction to FR-269-4: history comes through `PlaystateCache`'s own pattern.** One recently seen
   device per user, `jellyfinClient.tvToken(base, device, adminToken)` (`PlaystateCache.kt:91-95`,
   `:131-133`), then `getRecentlyPlayedAll(base, token, userId)` (`JellyfinClient.kt:787`, already on
   Jellyfin 12.1's `/Items?userId=` shape since Phase 208). That device also carries the user's
   visibility scope. A user not seen in 30 days gets no rebuild, and never opens a Home to see one.
5. **The "finished something" trigger.** `PlaystateCache.refreshOne` replaces a user's map (`:109-140`).
   Compare old and new `played` flags there: any newly played id marks that user stale. Ravilo's own stop
   already calls `refreshOne` through `HomeFeedService.invalidatePlaystate`, so a Ravilo finish is seen
   at once, and any other client's within a 20 s cycle. A per-user debounced job then runs within ten
   minutes.
6. **The step.** `build_recommendations` joins `PipelineEngine`'s step dispatch (`:472`, `:539` show the
   pattern), `PipelineStepPool`'s concurrency map (`:239`, one worker), and the admin's `PIPE_BLOCKS` /
   `PIPE_SHORT` (`Settings.kt:2618`, `:2637`, moving with 265) and Activity's `stepLabel`. It is a whole-
   library step, not per item: it runs once per pipeline run, at most once per `cadence` (default
   daily). Its last run is the newest `built_at` in `starter_list` (item 7), which every run writes, so
   no separate state is kept. `scan_state` holds only the current run and is not the place for it.
7. **Storage.** Migration **55** (271 took 54 for `genre_label`) creates `recommendation` with PK
   `(user_id, scope_hash, rank)`, an index on `(user_id, scope_hash)`, and a `starter_list` table keyed
   on `scope_hash`. `scope_hash` is the same hash `BrowseService.facets` keys its cache on (library
   allow-list, allowed and blocked tags, `BrowseService.kt:260`), so one definition of "scope" serves both.
8. **`RowKind` is shared.** Adding `RECOMMENDED` to `RowKind` (`Models.kt:10`) also changes the apps'
   enum. That is harmless for them only after R318, and irrelevant before it, because item 1 never sends
   it. The admin editor (`RaviloConfig.kt`, the row list and its add menu) gets the kind, its fixed count
   and the note that it takes no conditions or order.

**Net effect.** A new service (`RecommendationService`: signals, scoring, starter list), one pipeline
step, one stale-marking hook in `PlaystateCache`, migration 55, four stored item fields from one TMDB
parameter, the row in `HomeFeedService`, one response mapping, and two admin additions (editor kind,
*Recommended for*). No change to the apps beyond R318.

## Build notes (2026-09-26)

Built on `main` after Phase 271 (`84d2dbdd`), the same day. Where the build differs from the text or the
dev review above, the build is recorded here.

1. **The TMDB signals ride a request that was already made.** The re-pull paths (`syncMovie`,
   `syncSeriesEpisodes`, `rescanMetadata` for films and series) already made a request of their own for
   keywords (they become tags). It is replaced by one details request with no language and
   `append_to_response=keywords,recommendations`. That request answers keywords, page 1, the collection and
   the vote count. Pages 2–3 follow only when TMDB has them (`TmdbClient.getRecommendationSignals`).
   - The cost over before is the two pages, as measured in FR-269-3.
   - The localized chain is untouched, so the dev review's item 2 was not needed.
   - A failed request keeps what the title had, and the old keywords request is its fallback for tags.
   - TMDB's own vote average is stored too, as the quality prior's fallback where IMDb has no rating.
   - Phase 174's *Clear TMDB match* clears all six fields.
2. **The backfill** is `needsRecommendationSignals()` (`tmdbId != null && keywords == null`, not music
   videos), in `pull_tmdb`'s *missing* scope and on the title's Checks card (dev review item 3).
3. **Storage.** Migration **55**: `recommendation` (PK `user_id, scope_key, rank`) and `starter_list`
   (PK `scope_key, rank`, 200 kept per scope).
   - `scope_key` is an FNV-1a hash of a sorted canonical string of the library allow-list and the tag
     policy. It is not a set's `hashCode()`, because a stored key must survive a restart.
   - Item ids are Jellyfin ids (what a card carries, and stable across a slug rename).
4. **What is never recommended**: anything the viewer finished or started (a series with any watched
   episode: a half-watched series is Continue Watching's job), anything in progress, anything in *My List*
   (already chosen), a weak negative, a title below the quality floor, and anything the viewer cannot see.
   The floor applies to TMDB's vote too where there is no IMDb rating.
5. **Scoring**: the prototype's weights, unchanged.
   - Features, each by family weight × ln(N / titles carrying it): genres by id, keywords, the first five
     billed cast, directors/creators, studio (films), network, **collection** (weight 1.5, not in the
     prototype), original language, decade.
   - Score = cosine + 0.25 · min(1, TMDB-edge / 1.5) + 0.12 · quality + 0.05 if added in the last 30 days.
   - A title needs a cosine of at least 0.02, or a TMDB edge, to be "like what you watch". The rest of the
     50 comes from the starter list.
   - The reason item is the watched title with the strongest TMDB edge, else the most similar watched title.
   - Diversity relaxes rather than cutting the list short: the run rule first, then the half rule; the
     collection limit never relaxes.
6. **Triggers.**
   - `PlaystateCache.refreshOne` compares played flags against the user's previous map (never on the
     first, cold map). Any newly played id marks the viewer stale.
   - A loop polling every 60 s rebuilds a viewer once they have been quiet for two minutes, so an evening
     of episodes is one rebuild, and a finish shows within about three minutes.
   - At boot, if nothing was ever built, one full build runs.
   - All builds are serialized on one lock, on the BACKGROUND gate class.
7. **The pipeline step** `build_recommendations`:
   - `rebuild_every = daily | weekly` (default weekly), with an hour's slack so a weekly run at the same
     time is never "not due" by minutes.
   - Seeded once into a configured pipeline (`scan.recommendations_step_seeded`, kept by a Settings save,
     like 261's file steps), and part of the built-in default.
   - Skipped for a single-item run, and left off a title's Checks card (it is done to the library, not to
     a title).
   - Admin pipeline block *Build recommendations* with a week/day toggle. Activity label *Recommendations*.
8. **Home.**
   - The row serves the stored list, still-eligible, kept to the page's titles (a channel's own on a
     channel page), cut to the row's `limit` (default **20**, choices 10–30).
   - `seedTotalCount` is the eligible count, and `recommendations: true` marks it for R318.
   - The Home and channel caches include the viewer's list version, so a rebuilt list never waits out a
     cached feed.
   - *See all* is `GET /api/tv/recommendations[?channel=]`, answering the browse page's
     `SeededBrowseResponse` in the list's own order.
9. **`/api/tv/config`** goes through `forClients()`: `RECOMMENDED` → `CUSTOM` in Home's rows, a channel's
   rows, and every `content_row` reference inside a condition tree. The admin's route is untouched.
10. **Admin.**
    - Layout editor: *+ Recommended for you* (one per list, Home and a collection's own rows). The row
      shows its badge, its title, *shows N of 50*, and says it has no filter or order. It has no *Edit
      filter*.
    - Users & devices: *Recommended for {name}* (lazy, like *Recently watched*) shows when the list was
      built and by what, the titles with their reasons in words, and *Rebuild now*.
    - Routes: `GET /api/tv/admin/users/{id}/recommendations`, `POST …/rebuild`.
    - **Not built: *Preview as {viewer}*** in the editor. The editor's preview is a schematic of row
      titles, not of cards, so a viewer's list has nothing to draw there; Users & devices is where it is
      read.
11. **Backwards compatibility** (owner, 2026-09-26). `RowKind.RECOMMENDED` never leaves the server on
    `/api/tv/**`. Every new field is additive with a default (`Row.recommendations`, the item's six
    signal fields, `PipelineStep.rebuild_every`, `JellyfinUserData.PlayCount`), and the new routes are
    new paths. A test decodes the config with the enum an installed app knows.
12. **Tests.**
    - `RecommendationEngineTest` (8): decay and the weak negative, rewatch and favourite; no history → 50;
      three watched → 50 that lead with the similar family and a TMDB edge; determinism under reordered
      input; every eligibility rule, including the floor and hidden titles; diversity; the household
      starter; a stable scope key.
    - `RecommendationServiceTest` (3): 50 per viewer, each their own; the request-time skip; the wire
      mapping.
    - `HomeFeedRecommendedRowTest` (1): the row on Home as CUSTOM + `recommendations`, its limit, See all's
      count, and a rebuild replacing a cached feed.

**Not done here:** deploying, running the first build against production, and acceptance 1–6 on the TVs
(a release, a restart and the owner's go-ahead). The client half (See all, tolerant enums) is R318.
