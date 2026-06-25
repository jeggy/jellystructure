# Phase 29 — Multi-language library search (FR-ML1)


## Problem
Library search only matches the **one** current title — `media.title`, the title in whichever language
the per-file resolver landed on. If a show resolved to its Danish title, searching its English name
finds nothing, and vice-versa. We want search to hit **every title the item has ever been known by**:
each language ever pulled from TMDB (kept forever, even after the displayed title later changes) plus the
original title.

## Goal
Permanently remember, **per item, one title per language code** — last write wins for a given language,
union across all languages ever seen. Searching matches against all of them + the original title. A
title pulled once (e.g. Danish) stays searchable forever, even if the item is later re-resolved to a
different language or the title is edited by hand.

## Current state (as-is)
- **`MediaItem`** (`commonMain/model/Media.kt`) has `title` (current resolved title) and
  `originalTitle`. There is **no per-language title store**.
- **Title resolution** (`Scanner.kt`): `langPriority` is built from the file's audio languages +
  fallback; `tmdb.getMovieDetailsLocalized(id, langPriority)` / `getTvDetailsLocalized(...)` fetch
  localized details. These helpers **loop over the languages but return early** on the first result with
  a non-blank overview, so only the winning language's title is ever seen. `title = details.title`
  (movie) / `details.name` (TV).
- **TMDB translations endpoint is already used** (`getTranslationLanguages`): `GET /movie/{id}/translations`
  and `/tv/{id}/translations` return every localized variant. The DTO `TmdbTranslationData` currently
  only deserialises `overview` — but the same payload includes the localized `title` / `name`, so **all
  localized titles are available in a single existing call**.
- **Storage** (`Media.sq` + `MediaStore`): the full item is JSON in `media.json`; a denormalised `title`
  column exists. `listFiltered` searches only `lower(title) LIKE '%search%'`. `MediaStore.list` already
  decodes every JSON blob into `MediaItem` and sorts **in memory**.
- **Full re-scan** (`MediaStore.update`) does `deleteAll()` then re-inserts every item — so anything not
  carried forward by the scanner is lost. Single-item sync/re-pull uses `upsertItem` (`addOrUpdate` /
  `updateOne`).
- No SQLDelight versioned migrations are configured (`build.gradle.kts` `sqldelight { }` has no
  `schemaVersion`/`verifyMigrations`); the DB is created from the `CREATE TABLE` statements.

## Requirements

### A. Model — remember titles per language
1. Add `titlesByLang: Map<String, String> = emptyMap()` to `MediaItem` (key = normalised ISO-639-1
   language code, value = that language's title). Default empty so existing JSON deserialises cleanly.
2. **One entry per language, last-write-wins per language, never dropped:** re-pulling a language
   overwrites only that language's entry; all other languages persist. The map is only ever **merged**,
   never replaced wholesale.

### B. Population — capture every language, accumulate forever
3. On scan / sync / re-pull, populate `titlesByLang` from TMDB. Preferred source: the **translations
   endpoint** — extend `TmdbTranslationData` to also read `title` (movie) / `name` (TV) and add a client
   method (e.g. `getTranslatedTitles(tmdbId, isMovie): Map<String,String>`) returning lang → localized
   title for all non-blank entries. This captures every available language in one request.
   - Also fold in the resolved-language title and `originalLanguage → originalTitle` so those are always
     present even if translations are sparse.
4. **Merge, don't replace.** Before writing an item, start from the **existing** stored item's
   `titlesByLang` (look it up by id) and merge the freshly-fetched languages on top. This must hold on
   **both** paths:
   - single-item sync/re-pull (`upsertItem`), and
   - **full re-scan** — the scanner must carry forward each item's prior `titlesByLang` across the
     `deleteAll()` + re-insert, so a full rescan never erases languages pulled in earlier scans.
5. **Manual title edits** (user overwrites the title on media detail): update the displayed `title` as
   today **and** write that value into `titlesByLang[resolvedLanguage]` so the manual title is itself
   searchable — but never clear other languages' entries.

### C. Search — match all known titles
6. Library search must match (case-insensitive substring) against the union of: current `title`,
   `originalTitle`, and **all values in `titlesByLang`**. A hit in any of them returns the item.
7. Implementation (pick one, document the choice):
   - **In-memory (recommended for this scale):** `MediaStore.list` already decodes + sorts every row in
     memory; perform the search filter there over the combined title set, and drop the SQL `LIKE` from
     `listFiltered`. No schema change, no migration.
   - **Denormalised column:** add a lowercased `search_text` column to `media` (title + originalTitle +
     all `titlesByLang` values, space-joined), populate it in `upsertItem`, and `LIKE` against it. This
     needs a schema migration (no migration framework is configured today — see §E) and a one-time
     backfill from existing `json` blobs.
8. Search continues to combine with the existing kind / attention / artwork filters and sort, and with
   Phase 28's URL-addressable Library state.

### D. Scope of titles
9. This phase covers **item-level** titles (movies + series) — that is what Library search ranks over.
   Per-episode multi-language titles are **out of scope** (episodes are not Library search hits).

### E. Migration / compatibility
10. Adding `titlesByLang` to the JSON model is backward-compatible (absent → empty). If §C chooses the
    denormalised-column route, define how the column is added given there is no migration framework
    (either introduce SQLDelight `.sqm` migrations + `schemaVersion`, or an additive `ALTER TABLE` guarded
    at startup) and backfill `search_text` from existing rows on first boot. The in-memory route avoids
    all of this.
11. Existing items get their `titlesByLang` filled in lazily as they are next scanned/synced; no forced
    re-scan is required, and search still works against `title`/`originalTitle` in the meantime.

## Invariants (must hold)
- **Frontend renders server-fetched state only** — search results come from the server query, not
  client-side guessing ([[fe-reflects-be-no-derived-state]]).
- **`titlesByLang` is append/merge-only** — no code path may shrink an item's language set; this is the
  "remembered forever" guarantee and the core of the phase.
- Language codes are normalised consistently (reuse `LanguageResolver.normalize`) so the same language
  never appears under two keys.

## Out of scope
- Per-episode localized titles and episode-level search.
- Ranking/scoring search results by which language matched (a hit is a hit; ordering stays by the
  existing sort).
- Surfacing the stored alternate titles in the UI (this phase is about *search reach*, not display) —
  could be a later enhancement.
