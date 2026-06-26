# Phase 88 — Decoded-library cache (read-path keystone)

> Stop re-reading and re-deserializing the **entire** media library on every API request. Cache the
> decoded `List<MediaItem>` in `MediaStore`, invalidated on any write — the exact write-invalidated
> pattern already proven by `peopleIndexCache`/`jellyfinIdIndex`. One small, low-risk change that makes
> **every** admin and Ravilo read path faster, and the keystone the other performance phases build on.

## Problem
The `media` table stores each item as a JSON blob (`Media.sq` — `json TEXT`). `MediaStore.allItems()`
reads all blobs and runs `Json.decodeFromString(MediaItem.serializer(), …)` over **every one on every
call**, and `list()` does the same for a kind-filtered set before filtering/sorting/paginating in
memory. Measured on the live DB: **307 items, 3,909 episodes, 18.7 MB of JSON, largest blob 1.18 MB,
mean 61 KB.** `allItems()` sits on the hot path of the Ravilo home/browse/search/detail feeds and the
admin facets/triage/metadata/stats endpoints, so a single cold Library paint triggers ~5–6 full-library
decodes and every Ravilo screen open triggers at least one. **Kotlin/Native serialization — not
SQLite — is the dominant cost**, and nothing caches the decoded set today.

## Architectural constraint (driving decision)
The cache is **server-side and transparent** — it changes latency, never behaviour, so it is compatible
with *frontend renders server-pushed state only* ([[fe-reflects-be-no-derived-state]]). It is purely
in-memory, so it does **not** interact with the FD-ceiling ([[reference-ktor-native-fd-setsize]]). It
must be invalidated **synchronously on every write** so no endpoint ever serves stale catalog. The
pattern is not new: `MediaStore` already holds two write-invalidated indexes built from the same data.

## Current state (as-is)
- `media/MediaStore.kt:186-189` — `allItems()` = `getAll().executeAsList().mapNotNull { decode }`, uncached.
- `media/MediaStore.kt:106-166` — `list()` decodes the (kind-filtered) blobs, then filters/searches/
  sorts and `drop().take()` in memory (no SQL `LIMIT`/`OFFSET`).
- `media/MediaStore.kt:23-27` — `peopleIndexCache` + `jellyfinIdIndex`, both `null` until first use.
- `media/MediaStore.kt:312-313` — `upsertItem()` sets both caches to `null` (the single write funnel for
  per-item writes); `update()` (`:55-78`) does `deleteAll()` + N× `upsertItem` inside one transaction.
- Consumers that call `allItems()` every request: `tv/HomeFeedService.kt:35`, `tv/DetailService.kt:33,56`,
  `tv/BrowseService.kt:39,83`, `media/MediaStore.kt` facets (`:249,276`) + `nfoCoveredCount` (`:240`),
  `server/routes/TriageRoutes.kt:84,103`, `server/routes/MetadataRoutes.kt:60,77,158,172`.

## Requirements

### A. The cache
1. Add `private var allItemsCache: List<MediaItem>?` to `MediaStore`; `allItems()` returns it if present,
   else decodes once, stores, and returns it. Mirror the lazy-build shape of `jellyfinIdIndex`
   (`:178-184`).
2. Invalidate it in the **same place** the existing indexes are invalidated — `upsertItem()`
   (`:312-313`) — and on `deleteAll()`/`update()`/`deleteById` paths, so no write can leave a stale list.
3. `list()`, `resolveByJellyfinId()`, `personProfilePath()`, `trackFacets()`, `metaFacets()`,
   `countBatch()` all read through the cached list (they already go via `allItems()` or can).
4. Keep the existing `peopleIndexCache`/`jellyfinIdIndex` — they can be rebuilt from the cached list, but
   their current independent lifecycle is fine; just ensure all three invalidate together on write.

### B. Correctness & safety
5. Invalidation must cover **every** mutation: per-item edits (`upsertItem`), full rescan
   (`update()`/`deleteAll`), single delete, and JS-tag changes that alter an item's effective tags.
6. The background scan coroutine writes while requests read; the rebuild must be safe under that
   interleaving. The Native CIO event loop is single-threaded, but the scan runs on
   `scanDispatcher`/`Dispatchers.Default` — treat the cache field as shared state (rebuild-and-swap a
   new immutable list; never mutate in place).
7. **Measure before/after**: time `allItems()` and a cold Library paint on the 307-item DB; record the
   delta in `STATUS.md`/the report so the win is verified, not assumed.

## Invariants
- No endpoint returns stale catalog after a write — invalidation is synchronous with the write.
- Behaviour is byte-identical to today; only latency changes.
- FE renders server-pushed state only — this is a pure server-side cache.
- No new outbound sockets or FDs (in-memory only).

## Out of scope
- Slim projection / SQL pagination for card lists — **Phase 89** (builds on this cache).
- Computed-result caches (facets/stats/triage) — **Phase 89**.
- gzip / HTTP timeouts / SQLite engine tuning / scan write-path — **Phase 90**.
- The atomic Ravilo home-feed cache + device-token cache + facet index — **Phase R86**.
- A slim-`MediaSummary`-only cache variant (lower memory, decode full items lazily) — noted as an open
  decision in the report; default here is to cache full decoded items.

## Source references
- `src/linuxX64Main/.../media/MediaStore.kt` (`allItems` 186-189, `list` 106-166, caches 23-27,
  invalidation 312-313, `update` 55-78)
- Consumers: `tv/HomeFeedService.kt`, `tv/DetailService.kt`, `tv/BrowseService.kt`,
  `server/routes/{Triage,Metadata}Routes.kt`
- Research report: `specs/research-reports/backend-performance-investigation.md` (WS-A, §4.1)
- Related memory: [[backend-performance-investigation]], [[fe-reflects-be-no-derived-state]]
- **Unblocks:** Phase 89, Phase R86. Independent of Phase 90.
