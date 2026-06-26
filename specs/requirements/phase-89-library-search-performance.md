# Phase 89 — Library & search read performance

> Make the **Library page** and **text search** fast. Serve grid cards from a slim projection of the
> already-denormalized columns instead of decoding full `MediaItem` blobs, push pagination/sort into
> SQL for the common case, give search a real index, and memoize the facet/stats/triage results the
> Library and shell re-fetch on every visit.

## Problem
The user reports the Library page and search are very slow to load. Behind both:
- **Every list/search request decodes the whole (kind-filtered) library**, then filters/sorts and
  `drop().take()` in memory — no SQL `LIMIT`/`OFFSET` (`MediaStore.kt:106-166`). The `page`/`pageSize`
  params are honoured only after the full decode.
- **Search deliberately bypasses the SQL `LIKE` prefilter** (`MediaStore.kt:104,119-124`) to get
  multi-language coverage, so every keystroke-batch is a full 18.7 MB decode + in-memory substring scan
  across title + originalTitle + every `titlesByLang` value + a sort. No index, no server min-length.
- **The grid ships the full `MediaItem`** (cast/crew/tracks/titlesByLang — only `episodes[]` stripped,
  `MediaRoutes.kt:183-185`) for every card, though the card only needs poster/title/year/badges.
- **A cold Library paint = ~5–6 full-library decodes**: the grid loads in 60-item slices and re-requests
  slices until the viewport fills (`Library.kt:40,208-211,883-887`), plus `track-facets` + `meta-facets`
  (`Library.kt:226,230`), plus the shell's `/api/triage` on every navigation (`Shell.kt:164`). None are
  cached; facets re-decode the whole library each call (`MediaStore.kt:248,275`).

## Architectural constraint (driving decision)
Builds on **Phase 88** (the decoded-library cache) — with that in place these changes either avoid the
decode entirely (projection) or amortise it (memoized facets keyed on the same write-invalidation
stamp). Behaviour is preserved: search must keep its multi-language coverage (Phase 29), facet counts
and grid contents must match today's, and the in-memory condition-stack/audio-facet path
(`ConditionEvaluator`, R74) stays for filters SQL can't express. FE renders server-pushed state only.

## Current state (as-is)
- `media/MediaStore.kt:80-167` — `list()`; `Media.sq:45-50` — `listFiltered` (filters only `kind` +
  `poster_path IS NULL`; `search`/`filterAttention` passed as no-ops).
- `Media.sq:1-18` — table already carries `title, year, kind, studio, network, poster_path, issue_count,
  language_mix, scanned_at, tmdb_id, episode_count` as columns + indexes on `kind`, `scanned_at`.
- `server/routes/MediaRoutes.kt:183-185` — list handler; strips `episodes[]` only.
- `media/MediaStore.kt:248-299` — `trackFacets()`/`metaFacets()` (full decode each); `:240-246` —
  `nfoCoveredCount()` = full decode + a `SystemFileSystem.exists` stat **per item** (`NfoWriter.kt:69`),
  reached from `/stats` (`MediaRoutes.kt:1485`); `server/routes/TriageRoutes.kt:84,103` — triage
  count/list (full decode), fired from `Shell.kt` on every page mount.

## Requirements

### A. Slim projection + SQL pushdown for the grid (WS-B)
1. Add a `MediaSummary` DTO (id, kind, title, year, studio, network, posterPath, issueCount,
   languageMix, scannedAt — everything a grid card renders) and a SQLDelight `listSummary` query that
   selects those **columns**, not `json` — so the common grid/search path does **zero** JSON decode.
2. Push pagination + sort into SQL for the no-condition fast path: `ORDER BY scanned_at DESC | title |
   year LIMIT :n OFFSET :m` (indexes exist for `kind`/`scanned_at`; add `title`/`year` indexes if the
   sort needs them). Keep the in-memory decode path **only** for condition-stack/audio-facet filters
   that can't be expressed in SQL (route via the Phase-88 cache).
3. Stop shipping `cast/crew/tracks/titlesByLang` for grid cards (`MediaRoutes.kt:183-185`) — the grid
   consumes `MediaSummary`. Detail pages keep fetching the full item by id.

### B. Search (WS-E)
4. Add a denormalized, lowercased **search column** (title + originalTitle + all `titlesByLang` values,
   concatenated) written at scan/edit time and indexed — or an FTS5 virtual table. Search becomes an
   indexed SQL query instead of a full decode + scan (`MediaStore.kt:104,119-124`), while preserving the
   multi-language coverage Phase 29 added.
5. Add a server-side **min query length** and a query-length cap; keep the 250 ms client debounce
   (`Library.kt:265`).

### C. Computed-result caches (WS-C, admin surfaces)
6. Memoize `trackFacets()`/`metaFacets()` (`MediaStore.kt:248,275`) against the Phase-88
   library-version stamp; recompute on write. Removes 2 of the Library paint's full decodes.
7. Make `/stats` cheap: cache the nfo-coverage percentage or persist an `nfo_exists` flag at scan/edit
   time, dropping the **307 filesystem stats** per dashboard load (`MediaStore.kt:240-246`).
8. Memoize the triage count/list (`TriageRoutes.kt:84,103`) the shell fires on every navigation.

## Invariants
- Grid contents, facet counts, search results, and sort order are **identical** to today (verify on the
  307-item DB), including Phase-29 multi-language search hits.
- Facets/counts/triage reflect the latest write (invalidated on the Phase-88 stamp), never stale.
- The R74 condition-stack semantics (ANY ORs, `is_none_of`/`not_contains`) are unchanged.
- FE renders server-pushed state only.

## Out of scope
- The decoded-library cache itself — **Phase 88** (hard dependency).
- gzip / HTTP timeouts / SQLite engine tuning — **Phase 90**.
- Ravilo TV search min-length + the home-feed cache — **Phase R86** (TV search shares the same engine;
  coordinate the search-column work).
- Replacing the infinite-scroll slice loop with classic pagination UI (only the *server* gains SQL
  paging here; the existing scroll UX stays, just cheaper).

## Source references
- `src/linuxX64Main/.../media/MediaStore.kt` (`list` 80-167, facets 248-299, nfoCoverage 240-246),
  `src/commonMain/sqldelight/.../Media.sq` (1-18, 45-50)
- `src/linuxX64Main/.../server/routes/MediaRoutes.kt` (list 183-185, stats 1478-1488),
  `server/routes/TriageRoutes.kt` (84,103); frontend `src/wasmJsMain/.../ui/Library.kt`
  (40,208-211,226-230,265,833-887), `ui/Shell.kt:164`
- Research report: `specs/research-reports/backend-performance-investigation.md` (WS-B, WS-E, WS-C, §4.1–4.2)
- Related memory: [[backend-performance-investigation]], [[fe-reflects-be-no-derived-state]]
- **Depends on:** Phase 88.
