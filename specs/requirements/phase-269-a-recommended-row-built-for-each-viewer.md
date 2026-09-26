# Phase 269 — A *Recommended* row, built for each viewer in the background

> Owner, 2026-09-26: *"Let's add support for a new content row type. So I in jellystructure ravilo layout
> settings, can add a Recommended section row. Where it always provides 20 items (even when it's the first
> time entering). And it should be user specific, so based on user activity (probably fetched from
> jellyfin etc). I don't think this should be calculated on the fly, so this would be something that's
> generated and maintained in some background job as part of the whole pipeline schedule or worker job
> etc. Let's investigate if we should start gathering more metadata about our media that we can store in
> jellystructure, so we can provide better recommended content."*

## Status

`Planned` — written 2026-09-26, not dev-reviewed. Backend (the metadata, the engine, the job, the row) and
the admin (row editor, Users & devices). The client half is **R318**. The optional AI layer on top is
**Phase 270**; this phase stands on its own without it. **Numbering:** verified against `STATUS.md` the
same day — admin taken through **268**.

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
scopes the system rows. Its title defaults to *Recommended for you* and is editable. It shows **20**
titles. It takes no conditions, no sort and no hand-picks (a recommendation *is* an order), and the
editor says so instead of showing those sections.

**FR-269-2 — The new kind never reaches an app that cannot read it.** On the wire it is a plain content
row:

- `/api/tv/home` and channel feeds send it as `kind: CUSTOM` with no `seed_query` and no
  `seed_media_kind`, so every installed app draws it as an ordinary row with no *See all*
  (`HomeScreen.canSeeAll`);
- `/api/tv/config` sends its `RowConfig` as `CUSTOM` too (the apps never evaluate rows).

A test decodes both responses with the shipped client's `Json` settings. R318 makes the apps tolerant of
unknown kinds from now on. This rule stays anyway, for the apps already installed.

### What it knows about a title

**FR-269-3 — Four more facts per title, at no extra TMDB request.** The details request gains
`append_to_response=keywords,recommendations`. Stored on the title:

- `keywords`: TMDB ids and names;
- `tmdbRecommendations`: TMDB's list, top 20, in its order;
- `collectionId` and name (films; `belongs_to_collection`, already in the details body);
- `voteCount` (also already in the body).

That is the answer to *"should we gather more metadata"*: these four are what similarity needs, and they
cost bytes, not requests. Existing titles get them by treating "no keywords yet" as *missing* in
`pull_tmdb`'s skip rule (Phase 183 FR-183-4) for one pass. That is 527 details calls, paced by 183's
token bucket and spread over pipeline runs by the freshness filter. Not needed, and deliberately not
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

- its similarity to the viewer's weighted history over keywords, genres, cast and crew (weighted by
  billing), studio or network, collection, original language and era;
- plus a strong edge when a title they watched lists it in `tmdbRecommendations`;
- plus a quality prior (IMDb rating, damped by vote count);
- plus a small boost for titles added in the last 30 days.

**Then diversity:**

- at most two titles from one collection or series family;
- no genre over half the list;
- never three titles in a row that share a first genre.

The result is **deterministic**: the same inputs give the same list, ties broken by id.

**FR-269-6 — Always 20, from the first visit.** A viewer with no history gets the scope's **starter
list**, built by the same job:

1. titles the household watches most, as anonymous counts across viewers of the same visibility scope;
2. then the highest rated with enough votes;
3. then the newest.

A viewer with some history gets their scored list topped up from the starter list when it has fewer than
20. The row has fewer than 20 only when fewer than 20 titles are eligible at all, and it never pads
with titles already watched.

**FR-269-7 — Stored, never computed on a request.** Table `recommendation (user_id, scope_hash, rank,
item_id, score, reason_code, reason_item_id, source, built_at)`, keyed per viewer and visibility scope
(the Home cache's key, R233 FR-R233-5). **40** are stored. The row serves the first **20 still eligible
at request time**: a title finished since the build is skipped using `PlaystateCache`, which is a
filter and not a recompute, so the row stays at 20 between builds.

**FR-269-8 — When it is built.**

1. **A pipeline step**, `build_recommendations`, in 261's step table, with its own cadence (daily by
   default). It rebuilds every viewer and every scope's starter list.
2. **When a viewer finishes something.** A played-state change for that viewer (seen by
   `PlaystateCache`'s refresh, whichever client played it) marks the viewer stale, and a debounced
   background rebuild of **that viewer only** follows within ten minutes, on `GateClass.BACKGROUND`.
3. **A viewer with no list yet** gets the starter list at once (FR-269-6), and a build is queued.

Measured cost: one paged Jellyfin history read per viewer, about 1 MB for the heaviest, and a score over
about 500 titles. Seconds per household, and never on a request path.

### Seeing why

**FR-269-9 — The admin can see each viewer's list and why.** *Users & devices*, per user:

- a *Recommended for {name}* expander with the 20, each with its reason in words (*because you watched
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
- a viewer with no history gets 20;
- a viewer with three watched titles gets 20, topped up;
- an eligibility filter per rule;
- the decay and the weak negative;
- the FR-269-2 wire mapping decoded by the shipped client;
- the FR-269-7 request-time skip.

## Non-goals

- Titles not in the library (a *Request* suggestion belongs to Seerr) and Live TV.
- Several *Because you watched X* rows. One row, with the reason kept for the admin.
- Thumbs up / down from the viewer. There is no rating control, and adding one is a design question.
- The AI re-rank and theme tags: Phase 270.

## Acceptance

1. Add a *Recommended* row to Home in the layout editor. The TV shows 20 titles on its next Home load,
   none finished, none in Continue Watching.
2. A new viewer with no history sees 20 on their very first Home.
3. The owner finishes a film: within ten minutes their row has changed and that film is gone from it,
   and a household member's row has not changed.
4. A kids profile's row has only titles that profile can see.
5. An app installed before this phase shows the row as an ordinary row, and Home and Settings still load.
6. *Users & devices* lists each viewer's 20 with a reason each.
