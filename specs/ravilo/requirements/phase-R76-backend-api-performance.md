# Phase R76 — Backend TV API: eliminate extra Jellyfin round-trips + parallelize fetches (FR-RV-PF2)

## Problem
Loading a movie or series detail page feels slow. The app shows a loading state for noticeably
longer than the network RTT alone should take.

## Investigation findings

### Finding 1 — `tvToken()` makes a live Jellyfin round-trip on every request (critical)
Every route handler in `DetailService`, `HomeFeedService`, `BrowseService` calls `tvToken()`, which
calls `isTokenValid()` — a `GET /Users/$userId` request to Jellyfin — to decide whether to use the
paired user token or fall back to the server token. This check fires on **every** API request,
adding a full Jellyfin HTTP round-trip as a mandatory prelude to the actual data fetch.

Timeline before fix (movie detail):
```
→ GET /api/tv/movie/:id
  1. isTokenValid()  → GET /Users/$userId  (Jellyfin, ~40 ms on LAN)
  2. getItemDetail() → GET /Users/.../Items/:id  (Jellyfin, ~60 ms)
  ← respond  (total: ~100+ ms, two serial RTTs)
```
The token is almost never stale (it only expires if the user signs out of Jellyfin or the server
restarts). Paying one full RTT to validate it on every call is wasteful.

### Finding 2 — `allItems()` called multiple times per request
`MediaStore.allItems()` executes a full SQLite scan and deserialises every item from JSON on each
call. `DetailService.getMovieDetail/getSeriesDetail` called it twice: once for the item lookup
(`allItems().firstOrNull { it.jellyfinId == id }`) and again inside `relatedItems()`. For a
library of several hundred items this is a measurable redundant cost.

### Finding 3 — `allItems()` and `tvToken()` execute sequentially despite having no dependency
The SQLite scan and the Jellyfin token check share no data — either can complete first — but both
services ran them one after the other on a single coroutine, stacking latencies.

### Finding 4 — `buildContinueRow()` makes two serial Jellyfin calls
`HomeFeedService.buildContinueRow()` calls `getResumeItems()` then `getNextUp()` sequentially.
These two endpoints are entirely independent; executing them in serial doubles the Jellyfin cost
for the Continue Watching row on the home screen.

## Goal
Eliminate the redundant per-request Jellyfin pre-flight check. Where multiple independent
operations must complete before a response can be built, run them in parallel.

## Requirements

### 1. Token validity cache (`PlaybackService.kt` — shared `tvToken` extension)
Cache the result of `isTokenValid()` in a per-token `HashMap<String, Long>` (token → expiry ms)
protected by a `Mutex`. TTL = 5 minutes (`TOKEN_VALID_TTL_MS`).

- **Cache hit** (token seen and not expired): `tvToken()` returns the user token immediately with
  zero network I/O. This is the hot path — all requests after the first.
- **Cache miss** (first request, or after 5 min): call `isTokenValid()` as before, then write the
  expiry into the cache. If invalid, remove the entry so the next call re-checks without waiting
  for the TTL.
- Cache lives in a process-scoped top-level `HashMap`; server restart clears it naturally.

Effect: from the second request onward, `tvToken()` costs a single `Mutex.withLock {}` check (~μs)
instead of a Jellyfin round-trip.

### 2. Single `allItems()` call per request in `DetailService`
Load the full item list once and thread it through to `relatedItems()` rather than re-fetching.
Eliminates one SQLite full-table scan + JSON deserialise cycle per detail request.

### 3. In-memory jellyfinId → MediaItem index in `MediaStore`
Add `jellyfinIdIndex: Map<String, MediaItem>?` built lazily on first use from `allItems()`,
invalidated on every `upsertItem()` write (same pattern as the existing `peopleIndexCache`).
Expose `resolveByJellyfinId(jellyfinId: String): MediaItem?` for O(1) lookup by Jellyfin UUID
instead of a linear scan over the full deserialised list.

### 4. Parallel `allItems()` + `tvToken()` via `coroutineScope { async {} }`
In every service function that calls both, launch them concurrently:

```kotlin
// Before (sequential — stacks latencies)
val all   = mediaStore.allItems()      // SQLite, ~10–40 ms
val token = jellyfinClient.tvToken(…)  // cached → instant; uncached → Jellyfin RTT

// After (parallel — overlap)
val allDeferred   = async { mediaStore.allItems() }
val tokenDeferred = async { jellyfinClient.tvToken(…) }
val all   = allDeferred.await()
val token = tokenDeferred.await()
```

Applied to: `DetailService.getMovieDetail`, `DetailService.getSeriesDetail`,
`HomeFeedService.getHomeFeed`, `HomeFeedService.getChannelFeed`, `BrowseService.browse`,
`BrowseService.search`.

For the `mylist` browse path, `allItems()` and `getFavoriteItemIds(token)` are also parallelised:
`allItems` starts immediately; once the token resolves `getFavoriteItemIds` kicks off, and both
`allItems` + favorites may still be in flight, overlapping.

### 5. Parallel `getResumeItems()` + `getNextUp()` in `buildContinueRow()`
The two Jellyfin calls are fully independent. Wrap in `coroutineScope { async {} }` so they
execute concurrently. The Continue Watching row cost drops from 2 sequential RTTs to 1.

## Expected latency improvement (LAN, warm token cache)

| Endpoint | Before | After |
|---|---|---|
| Movie detail | 2 Jellyfin RTTs sequential | 1 RTT (allItems + token in parallel, then detail) |
| Series detail | 2 Jellyfin RTTs sequential | 1 RTT (episodes overlaps allItems+token) |
| Home feed (no Continue row) | tvToken + allItems sequential | token + allItems parallel |
| Home feed (with Continue row) | tvToken + allItems + resume + nextup = 4 sequential | (token ‖ allItems) + (resume ‖ nextup) = 2 parallel pairs |
| Browse / Search | tvToken then allItems | token ‖ allItems |

## Scope
- `src/linuxX64Main/.../tv/PlaybackService.kt` — token cache (`tokenValidUntil`, `tokenCacheMutex`,
  `TOKEN_VALID_TTL_MS`); updated `tvToken()`.
- `src/linuxX64Main/.../tv/DetailService.kt` — `coroutineScope`/`async` for both detail methods;
  single `allItems()` call threaded to `relatedItems()`.
- `src/linuxX64Main/.../tv/HomeFeedService.kt` — `coroutineScope`/`async` in `getHomeFeed`,
  `getChannelFeed`, `buildContinueRow`.
- `src/linuxX64Main/.../tv/BrowseService.kt` — `coroutineScope`/`async` in `browse` and `search`.
- `src/linuxX64Main/.../media/MediaStore.kt` — `jellyfinIdIndex` lazy cache + `resolveByJellyfinId()`;
  cache invalidated in `upsertItem()`.

## Notes
- `allItems()` is not a `suspend` function (synchronous SQLite call); `async { allItems() }` blocks
  its coroutine thread. Ktor's `Dispatchers.Default` pool has multiple threads, so the SQLite call
  and the Jellyfin call genuinely run concurrently.
- Token TTL of 5 minutes is conservative. Stale token detection still works: on the next miss after
  expiry `isTokenValid()` fires once, updates the cache, and falls back to the server token if
  needed. A Jellyfin restart or user sign-out causes at most one 5-minute window of using the server
  token before the cache re-validates.
- The Ktor Native server uses `select()` (FD_SETSIZE ceiling noted in platform docs). These changes
  add at most 2 concurrent outbound HTTP connections per in-flight request, well within the limit.
