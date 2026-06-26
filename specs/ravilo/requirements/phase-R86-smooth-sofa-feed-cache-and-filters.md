# Phase R86 — Smooth sofa: atomic home-feed cache, cheap filters, token cache

> Make the Ravilo home open instantly while keeping it **one atomic frame** — never a row that pops in
> after paint. Serve the assembled home feed from a stale-while-revalidate cache, make the workbench
> filter engine allocation-free, cache the per-request device-token validation, and remove the
> redundant full-library scans on the detail path.

## The hard rule (drives every decision here)
A Ravilo screen paints as **one atomic, fully-laid-out frame**. Nothing may add, remove, or resize a
row or tile after first paint — a row must **never** appear because something below it finished loading
first ([[feedback-ravilo-no-flicker]]). Every optimisation below is therefore either purely server-side
(same response, faster) or a cache that returns a **complete** feed; **no work in this phase splits the
home response into phases.** This continues R83's decision to keep the Continue row inline
([[ravilo-jellyfin-decoupling]]).

## Problem
With the Phase-88 decode cache in place, the remaining Ravilo costs are:
- **`/api/tv/home` is rebuilt from scratch every open** and is **gated on two live Jellyfin calls**
  (`getResumeItems ‖ getNextUp` for the Continue row, `HomeFeedService.kt:277-280`) — so home is only as
  fast as Jellyfin's slowest response, every time. Nothing caches the assembled feed.
- **Every TV API call validates the device token with a SQLite `SELECT` *and* an `UPDATE last_seen`**
  (`RaviloDeviceService.kt:108-110`) — a per-request read **and write** on the single writer connection.
- **`ConditionEvaluator` re-lowercases the condition values and the item's facet list on every call**
  (`ConditionEvaluator.kt:23,58`); home CUSTOM rows run it over all items, and `heroIds` is rebuilt
  inside the row loop per CUSTOM row (`HomeFeedService.kt:227`).
- **Detail decodes/scans the whole library to return one item** via `all.firstOrNull { jellyfinId == … }`
  (`DetailService.kt:34,57`) instead of the O(1) index `MediaStore` already exposes
  (`resolveByJellyfinId`, `MediaStore.kt:178`).

## Architectural constraint (driving decision)
Home stays **atomic**; the Continue row's membership/order are Jellyfin-derived and cannot be produced
locally, so it is never split out (R83 invariant). Per-user state stays **read-only** from Jellyfin
([[ravilo-off-jellyfin-data]], [[fe-reflects-be-no-derived-state]]). All Jellyfin fan-out stays
`Semaphore`-bounded ([[reference-ktor-native-fd-setsize]]); the cold-Continue fetch relies on the
`HttpTimeout` from **Phase 90**. Filter results are cached at the **feed** level, not in a separate
`filter→ids` table (see report WS-I: the feed cache already handles ordering/limit and the per-viewer
`hero_item` facet correctly; a materialized table is deferred until a graduation criterion hits).

## Current state (as-is)
- `tv/HomeFeedService.kt:32-48` — `getHomeFeed` builds the whole feed per request; `:277-280` the
  gating Continue fetch; `:227` `heroIds` rebuilt per CUSTOM row; `:228` per-row `ConditionEvaluator`.
- `tv/RaviloDeviceService.kt:108-110` — `validateDeviceToken` = `getByToken` + `updateLastSeen`, no cache.
- `tv/ConditionEvaluator.kt:23,58` — per-call lowercasing of values + the item's `have` list.
- `tv/DetailService.kt:33-34,56-57` — `allItems()` + linear `firstOrNull`; `:136-141` related = full
  genre-overlap pass. `MediaStore.kt:178-184` — the unused-by-detail O(1) `jellyfinIdIndex`.
- `TvEvent.rev` (config revision) already exists for live-config push (R33).

## Requirements

### A. Atomic home-feed cache, stale-while-revalidate (WS-C)
1. Cache the **fully assembled** `HomeFeed` per `(jellyfinUserId, configRev)`. On open, return the last
   complete feed **instantly and atomically** (catalog rows **and** the Continue row together), then
   refresh in the background for the **next** open. The served feed is at most one open stale; **nothing
   changes mid-view.**
2. Invalidate/refresh on: config change (`TvEvent.rev`), library write (the Phase-88 stamp), and a short
   time-TTL so Continue stays reasonably fresh. A background refresh updates the cache for the next
   request only — it must **never** mutate the feed the client is currently showing.
3. **Cold miss** (no cached feed yet): build synchronously — with the Phase-88 decode cache the catalog
   is fast — and bound the Continue `getResumeItems ‖ getNextUp` with the Phase-90 `HttpTimeout`. If it
   times out, return home **without** the Continue row for that build (a missing row is not a jumping
   row); the next refresh fills it in for the subsequent open.
4. The feed remains a single response. Do **not** introduce any "home returns, then Continue arrives"
   path (explicitly forbidden, R83/R84 invariant).

### B. Device-token validation cache (WS-G)
5. Add an in-memory `deviceToken → DeviceData` cache (mirror the 5-min token-validity cache in
   `PlaybackService.kt:27`) so the hot path skips the SQLite `SELECT`.
6. Debounce `updateLastSeen` (e.g. at most once/minute per token) so the per-request **write** is removed
   from the hot path (`RaviloDeviceService.kt:110`), cutting writer-lock contention.

### C. Cheap filter evaluation — WS-I Tier 1 (facet index)
7. Precompute a **lowercased `Set` per facet per item** (studio/network/genres/tags/audio-languages/
   audio-codecs) once at decode/scan time, so `ConditionEvaluator.evalOne` matches via allocation-free
   `Set.contains` instead of re-lowercasing both sides every call (`ConditionEvaluator.kt:23,58`). Keeps
   live filtering fast enough to defer any materialized `filter→ids` table (report WS-I Tier 3).
8. Hoist `heroIds` out of the CUSTOM-row loop in `buildRows` — compute once (`HomeFeedService.kt:227`).

### D. Detail micro-optimisations
9. `getMovieDetail`/`getSeriesDetail` resolve the target via `mediaStore.resolveByJellyfinId(...)`
   (O(1), `MediaStore.kt:178`) instead of `all.firstOrNull { … }` (`DetailService.kt:34,57`). Optionally
   precompute a `genre → cards` map so "related" is a lookup, not a full pass (`:136-141`).

## Invariants
- **No flicker, no row-jump:** home is one atomic frame; the served feed always includes its final row
  set (including Continue or deliberately excluding it on cold timeout). A background refresh only
  affects the *next* open. ([[feedback-ravilo-no-flicker]])
- Per-user state is read-only from Jellyfin; nothing here writes/mirrors ownership locally.
- All Jellyfin fan-out stays `Semaphore`-bounded; the cold-Continue path is timeout-bounded (Phase 90).
- Filter results are identical to today's `ConditionEvaluator` output (the facet index is a
  representation change, not a semantics change — including `is_none_of`/`not_contains`/`hero_item`).
- FE renders server-pushed state only — the cache is a server-side transport change.

## Out of scope
- A materialized `filter→ids` / content-hashed filter-result table, and SQL filter pushdown — **deferred
  (WS-I Tier 3)**; build only when library size, filter cost, or shared-filter fan-out hits the
  graduation criteria in the report. This phase is Tier 1 (facet index) + Tier 2 (the feed cache).
- Splitting the home Continue row into a late phase — **explicitly forbidden** (R83/R84).
- The decoded-library cache (Phase 88), gzip/`HttpTimeout`/engine tuning (Phase 90), and the admin
  Library/search work (Phase 89) — dependencies, not this phase.
- TV search min-length (shares the Phase-89 search-engine work — coordinate there).

## Source references
- `src/linuxX64Main/.../tv/HomeFeedService.kt` (32-48, 227-228, 277-280), `tv/RaviloDeviceService.kt`
  (108-110), `tv/ConditionEvaluator.kt` (23,58), `tv/DetailService.kt` (33-57,136-141),
  `tv/PlaybackService.kt:27` (token-cache pattern), `media/MediaStore.kt:178-184`
- Research report: `specs/research-reports/backend-performance-investigation.md` (WS-C home, WS-G, WS-I,
  §"no-flicker rule", §6)
- Related memory: [[feedback-ravilo-no-flicker]], [[backend-performance-investigation]],
  [[ravilo-jellyfin-decoupling]], [[ravilo-off-jellyfin-data]], [[reference-ktor-native-fd-setsize]]
- **Depends on:** Phase 88 (decode cache) + Phase 90 (`HttpTimeout`). Continues R83/R84 (home stays
  atomic).
