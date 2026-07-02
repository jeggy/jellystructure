# Phase 106 — Age ratings: region-cascade config + TMDB ingest + detail display + workbench facet (FR-AR1)

## Goal
Fetch each title's **content certification (age rating)** from TMDB and surface it in Ravilo and the admin
detail page. Because TMDB carries a **different certification per country**, add a global
**region cascade** (an ordered list of countries) in **Settings → Metadata**: Jellystructure resolves the
rating to show by walking the cascade top-to-bottom and using the **first region that has a
certification** for that title. Make the resolved rating **filterable** in the workbench, and write it to
the NFO so Jellyfin inherits it.

The name "region cascade" is deliberate — it mirrors the existing **language cascade** (walk an ordered
list, take the first that resolves), so admins meet one consistent mental model.

## Background (verified against TMDB live, 2026-07-02)
- **Movies:** `GET /movie/{id}/release_dates` → `results[]` of `{ iso_3166_1, release_dates[] }`, each
  release entry `{ certification, type, release_date, … }`. Release `type`: 1 Premiere · 2 Theatrical
  (limited) · 3 Theatrical · 4 Digital · 5 Physical · 6 TV. **Empty `certification: ""` entries are
  common** (e.g. premiere rows). Also fetchable via `append_to_response=release_dates`.
- **TV:** `GET /tv/{id}/content_ratings` → flat `results[]` of `{ iso_3166_1, rating }` — one rating per
  country, no type/date. **Episode-level ratings do not exist on TMDB** — series-level only. Also
  fetchable via `append_to_response=content_ratings`.
- **Per-country picking rule (movies):** take the first **non-empty** `certification` preferring release
  type **3 → 4 → 5 → 2 → 6 → 1** (theatrical first); values rarely differ across types.
- Rating scales per country (from `GET /certification/movie/list` — live-confirmed): DK `A · 7 · 11 · 15`
  (+ NR/F), US `G · PG · PG-13 · R · NC-17` / TV `TV-Y … TV-MA`, GB `U · PG · 12A · 12 · 15 · 18 · R18`,
  DE `0 · 6 · 12 · 16 · 18`, NO `A · 6 · 9 · 12 · 15 · 18`, SE `Btl · 7 · 11 · 15`. Movie and TV values
  can differ within one country — treat codes as opaque strings, never a shared enum.
- Jellystructure already resolves **metadata language** by an ordered cascade; this phase adds the
  analogous **age-rating region cascade** as a sibling global metadata setting.
- **Renders server state only** — Ravilo never derives the rating; the backend resolves it and serves the
  resolved value on the TV DTOs (R153).

## Current state (code)
- `MediaItem` (`src/commonMain/…/model/Media.kt:79`) has **no certification fields**; new fields are
  additive with kotlinx-serialization defaults (no SQLite migration — items are JSON blobs).
- `TmdbClient` (`src/linuxX64Main/…/tmdb/TmdbClient.kt`) has no release-dates/content-ratings call; it
  already routes every call through `OutboundHttp.withPermit` (`:287-288`) — new endpoints inherit the
  FD bound. Details are fetched **per language** (`getMovieDetailsLocalized`), so certifications should be
  a **dedicated once-per-title call**, not an `append_to_response` on the localized detail fetch.
- `AppConfig` (`config/AppConfig.kt`) has no `[metadata]` section yet; `LanguageRules.fallbackLanguage`
  is the sibling precedent. Config round-trips via `GET/PUT /api/config` + the `ConfigApi` wasmJs mirror.
- `MediaCard.rating` (`shared/…/tv/Models.kt:123`) **already exists and is `null` everywhere**
  (`HomeFeedService.kt:422`, `DetailService.kt:174`, `BrowseService.kt:159`) — the TV card slot is free.
  `MovieDetailScreen.kt:197-220` already renders `rating` in its meta row when non-null.
- Facet precedents: shared `Condition.facet` (`Models.kt:320`) supports
  `studio·network·genre·tag·audio_language·audio_codec·track_title·hero_item·content_row`;
  `ConditionEvaluator` + `MediaStore.buildMetaFacetsFrom` + `facetsNarrowed`/`countBatch` + the wasmJs
  `Workbench.kt` FACETS list are the five touchpoints a new facet must hit.
- `NfoWriter` (`nfo/NfoWriter.kt`) writes **no `<mpaa>`** today.

## Requirements

### A. Global config — Settings → Metadata
1. Add an **"Age ratings — region cascade"** card: an **ordered, editable list of countries**. Each row
   shows the ISO code, country name, its rating system (Medierådet/MPA/BBFC/FSK/…), and the system's
   scale. Support **reorder** (▲▼ / drag), **remove**, and **add region** (from a catalog of supported
   countries). The **first** region is visually marked as the default winner ("shown by default").
2. Persist as a new config section: `[metadata]` with
   `age_rating_cascade = ["DK", "US"]` → `AppConfig.metadata: MetadataConfig` /
   `@SerialName("age_rating_cascade") val ageRatingCascade: List<String>` (ktoml-friendly flat list).
   Round-trip through `GET/PUT /api/config` + the `ConfigApi` mirror, like `language_rules`.
3. Empty list ⇒ feature off (no badge, facet hidden, no `<mpaa>`). It is a **global** setting; per-library
   override is out of scope.

### B. Ingest from TMDB (scan/refresh)
1. `TmdbClient` gains `getMovieReleaseDates(tmdbId)` and `getTvContentRatings(tmdbId)`. During the
   scanner's TMDB pull (full scan + both re-pull paths), fetch **once per title** (not per language) and
   store the **raw per-country map** on the item: `MediaItem.certifications: Map<String, String>`
   (uppercase ISO-3166-1 → code, only non-empty codes, movie picking rule as in Background). Additive
   field, defaults to empty — no migration.
2. Best-effort: a missing/failed certifications call leaves the map empty and never blocks the scan.
3. **Backfill:** existing items have no map until re-pulled. The release-age freshness policy (Phase 91)
   re-pulls them over time; for immediate coverage the admin runs the pipeline's *Pull TMDB (scope=all)*
   once. The Settings card shows a small coverage line ("N of M titles have certifications") so the admin
   can see backfill progress.

### C. Resolve the shown rating (region cascade)
1. Resolution is **derived at read time** from the stored raw map + the current cascade — never persisted
   — so editing the cascade changes every surface instantly with zero re-fetch/re-write.
   `CertificationResolver.resolve(cascade, certifications) → { region, code, fallback: Boolean }?`:
   first cascade region present in the map wins; if none matches, fall back to the item's own primary
   certification (deterministic preference: `US`, then `GB`, then first region alphabetically) with
   `fallback = true`; empty map ⇒ `null` (no badge anywhere).
2. Put the resolver in `src/commonMain` (like `LanguageResolver`) so backend and admin FE share one
   algorithm. **Ravilo still only ever sees the backend-resolved value** (constitution: server state only).
3. Derive a **0–4 maturity tier** for badge colour + range-ordering: parse the leading integer of the code
   (`"15"`→15, `"PG-13"`→13, `"TV-14"`→14) with a special-cases table for non-numeric codes
   (`A/U/G/TV-Y/Btl/0`→tier 0 · `PG/TV-PG/7/6`→1 · `11/12/12A/9/TV-Y7`→2 · `13/PG-13/TV-14/14/15`→3 ·
   `16/18/R/NC-17/TV-MA/R18`→4). Unknown codes → tier 2 (neutral).

### D. Serve it (backend surfaces)
1. **Ravilo DTOs:** populate the existing `MediaCard.rating` with the resolved **code** (e.g. `15`), and
   add a `rating_badge { region, code, tier, fallback }` object to `MovieDetail` + `SeriesDetail`
   (nullable, defaulted) for R153's badge. Zero extra round-trips — resolution runs over the in-memory
   item at DTO-build time.
2. **NFO:** `NfoWriter` writes `<mpaa>{resolved code}</mpaa>` in `movie.nfo` and `tvshow.nfo` when a
   resolution exists — Jellyfin's NFO reader maps it to `OfficialRating`, so Jellyfin (and its parental
   controls) inherit the cascade winner on the next sync. No direct Jellyfin field write.

### E. Admin detail page (`media.html` / `series.html`)
1. Show a compact **certification badge** (region tag + code, colour-coded by maturity tier) in the
   pagebar (`MediaDetail.kt` pagebar, beside the TMDB badge / audio flags).
2. Show an **"Age rating — region cascade"** trace card: each cascade region in order, marked
   used / skipped (no certification) / not-reached, plus the fallback row when the cascade missed —
   so the admin can see *why* a given rating was chosen. Data source: the item's raw `certifications`
   map (already on the `MediaItem` the detail page fetches) + the shared resolver.

### F. Filter workbench facet
1. Add an **"Age rating"** facet (`facet = "age_rating"`) filtering on the **resolved code**, wired
   through all five facet touchpoints: shared `Condition` doc, `ConditionEvaluator` (+ its precomputed
   `ItemFacets`), `MediaStore.buildMetaFacetsFrom` (+ `facetsNarrowed` value counts, ordered by tier then
   count), `countBatch`, and the wasmJs `Workbench.kt` FACETS list (`RaviloBuilders` design mirror is
   already built). It appears in "Add filter" everywhere the workbench is used (Library, Ravilo config
   channels/rows) with the usual active-chip round-trip.
2. Because resolution is cascade-dependent, facet values/counts are computed against the **current**
   cascade (derive-on-read makes this automatic).

## Scope
- `design/app/settings.html` — Metadata tab region-cascade card (**built**).
- `design/app/media.html` (+ `series.html`) — pagebar cert badge + cascade-trace card (**built**).
- `design/app/ravilo-builders.js` — `ageRating` facet (**built**); `design/ravilo/*` — R153 (**built**).
- Backend: `TmdbClient` (2 endpoints), `Scanner` (fetch + store), `model/Media.kt`
  (`certifications` map), `commonMain` `CertificationResolver`, `AppConfig`/`ConfigRoutes`/`ConfigApi`
  (`[metadata] age_rating_cascade`), `NfoWriter` (`<mpaa>`), TV feed/detail DTO population,
  `ConditionEvaluator`/`MediaStore` facet wiring, `Settings.kt` + `MediaDetail.kt` + `Workbench.kt` UI.

## Non-goals
- Per-library or per-user cascades (global only this phase).
- Parental-control / content-locking behaviour — display + filtering only, no gating (Jellyfin can gate
  off the NFO-synced `OfficialRating` if the operator configures it there).
- Icon artwork per national system — a colour-coded code badge, not official rating logos.
- Editing a title's certification by hand (TMDB-sourced; manual override is a later phase).
- Episode-level ratings (TMDB has none — series-level only).

## Acceptance
- Settings → Metadata has a reorderable region cascade; the top region is marked as the default winner;
  a coverage line shows how many titles carry certification data.
- After a scan/re-pull, each item carries its raw per-country certifications map.
- Ravilo detail and the admin detail page show the resolved certification badge; the admin page shows the
  cascade trace (used / skipped / not-reached / fallback).
- Moving Denmark below the United States in the cascade changes the shown rating for titles that have
  both — **without any re-scan** (derive-on-read).
- `movie.nfo`/`tvshow.nfo` contain `<mpaa>` with the resolved code after the next NFO write.
- "Age rating" is selectable in the workbench and filters the grid, with removable active chips and
  correct counts.
