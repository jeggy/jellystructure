# Backend performance — investigation report

**Date:** 2026-06-26
**Question:** Why are the **Library page** and **text search** slow to load, why can the
**Ravilo (sofa) experience** feel sluggish, and what are the best-practice options —
server-side caching, better indices, request splitting, payload slimming — to make the
API endpoints fast and smooth?

**Scope:** read-only investigation of the jellystructure backend (Kotlin/Native, Ktor CIO
server, SQLDelight + SQLiter over SQLite). Covers the data layer, the admin read endpoints,
the Ravilo `/api/tv/**` endpoints, the outbound HTTP clients, and the scan/write path. No
code changes and no phase specs are produced here — this is research only. Sibling document:
`ravilo-jellyfin-decoupling-investigation.md` (which the R82–R85 phases came out of; some of
its detail-path findings are now **implemented** and are noted as such below).

**Method:** source read of every layer + four parallel deep-dive audits, cross-checked
against a **snapshot of the live database** (`config/jellystructure.db`, copied and queried
read-only). All measurements in §2 are from that snapshot.

---

# PART I — As-is audit

## 1. Executive summary

**Root cause, in one sentence:** the `media` table is used as a **dumb JSON-blob key-value
store**, and essentially every read endpoint — admin *and* Ravilo — **reads and
deserializes the entire library into memory on every request**, then filters, sorts and
paginates in Kotlin. SQLite does almost no work; kotlinx.serialization on Kotlin/Native does
all of it, single-threaded, repeatedly, to return 20–60 cards.

Concretely, from the live DB (§2): every such request parses **up to ~18.7 MB of JSON across
307 deeply-nested objects (3,909 episodes)** — and a *single* cold Library page paint or a
*single* Ravilo home open triggers **several** of those full passes.

**The eight key findings**

1. **No SQL pushdown.** `MediaStore.list()` deliberately passes `search = null` and
   `filterAttention = 0` to the SQL query (`MediaStore.kt:104,108`), so `listFiltered`
   (`Media.sq:45-50`) returns **all blobs of a kind**; search, all facet filters, sort and
   pagination then run **in-memory after decoding every blob** (`MediaStore.kt:106-166`).
   The `page`/`pageSize` params are honoured by `drop().take()` *after* the full decode —
   there is **no `LIMIT`/`OFFSET`** anywhere.

2. **No decoded-library cache.** `allItems()` (`MediaStore.kt:186-189`) re-reads and
   re-decodes the whole 18.7 MB on every call, and it is called by `HomeFeed`, `Browse`,
   `Detail`, `/stats`, facets, triage — every read hot path. The codebase *already* has the
   exact write-invalidated cache pattern (`peopleIndexCache`, `jellyfinIdIndex`,
   `MediaStore.kt:23-27,312-313`) but does **not** apply it to the decoded item set.

3. **The Library first paint fans out into ~5–6 full-library decodes.** The grid loads in
   60-item slices and **keeps requesting slices until the viewport is full** (`Library.kt:40,
   208-211,883-887`) — each slice is a full decode + sort. On top of that the page loads
   `track-facets` + `meta-facets` (`Library.kt:226,230`), each a separate full decode
   (`MediaStore.kt:248,275`), and the shell fires `/api/triage` on every navigation
   (`Shell.kt:164` → `TriageRoutes.kt:103`), another full decode. **None are cached.**

4. **Search decodes the whole library on every keystroke-batch.** The SQL `LIKE` prefilter
   that exists (`Media.sq:50`) is intentionally bypassed to get multi-language coverage
   (`MediaStore.kt:104,119-124`); there is a 250 ms client debounce (`Library.kt:265`) but no
   server-side min-length, no limit, and no index — every search is a full decode + in-memory
   substring scan across title + originalTitle + every `titlesByLang` value, then a sort.

5. **Card lists ship the full `MediaItem`.** `/api/media` returns the whole object graph
   (cast[], crew[], tracks[], titlesByLang — only `episodes[]` is stripped,
   `MediaRoutes.kt:185`) for every grid card, then the server sends it **uncompressed** —
   `Server.kt` installs **no `Compression` plugin** (`Server.kt:138-159`). Grid cards only
   need poster/title/year/badges.

6. **Ravilo home compounds the decode with per-row work.** `/api/tv/home` decodes the library
   **once** and reuses it (good, `HomeFeedService.kt:35,38`), but then **each CUSTOM row
   re-runs `ConditionEvaluator.matches` over all 307 items** (`HomeFeedService.kt:228`),
   GENRE/NEWLY rows each do their own full filter+sort pass, and `heroIds` is rebuilt inside
   the loop per CUSTOM row (`:227`). Cost scales as **rows × items**. The home response is
   also **gated on two live Jellyfin calls** (resume + next-up for the Continue row,
   `:277-278`) and **nothing on the TV path is cached**.

7. **Detail decodes the entire library to return one item** — and bypasses the O(1) index it
   already has. `getMovieDetail`/`getSeriesDetail` call `allItems()` then
   `all.firstOrNull { it.jellyfinId == … }` (`DetailService.kt:33-34,56-57`) instead of
   `resolveByJellyfinId` (`MediaStore.kt:178`). Catalog detail is now **Jellyfin-free** (R83 —
   good), so the only remaining cost here is pure local waste.

8. **Transport & engine gaps amplify everything.** No gzip (above); **no HTTP timeouts on any
   outbound client** so a slow Jellyfin stalls a whole request (`JellyfinClient.kt:34`, etc.);
   the SQLite page cache is the 2 MB default; a scan **re-encodes the entire library twice**
   and the WAL has grown to **22 MB uncheckpointed**; and **every TV API call does a SQLite
   `SELECT`+`UPDATE` to validate the device token** (`RaviloDeviceService.kt:108-110`).

## 2. Empirical baseline (live DB snapshot)

`config/jellystructure.db` — WAL mode on (`-wal`/`-shm` present), `page_size=4096`,
`cache_size=-2000` (≈2 MB), `wal_autocheckpoint=1000`.

| Metric | Value |
|---|---|
| `media` rows | **307** (222 MOVIE, 85 TV_SHOW) |
| Total episodes (`SUM(episode_count)`) | **3,909** |
| Sum of `json` blob bytes | **18.7 MB** |
| Blob size — median / mean / max | **22.7 KB / 61 KB / 1.18 MB** |
| Blob size — p90 / p99 | 104 KB / 783 KB |
| Largest 4 blobs | Big Bang Theory 1.18 MB (279 eps), Modern Family 898 KB (250), New Girl 860 KB (146), Two and a Half Men 783 KB (261) |
| DB file / WAL file | 19 MB / **22 MB (uncheckpointed)** |
| Indices on `media` | `media_kind`, `media_scanned_at` only (`Media.sq:17-18`) |

**Why this matters:** a handful of large TV blobs dominate. Any operation that touches
`allItems()` pays to parse all of them — including the 279-episode Big Bang Theory blob with
its per-episode tracks, guest stars and crew — even to render a movie grid or a search box.
The 2 MB page cache cannot hold the 19 MB DB, so cold reads also hit disk.

## 3. The read path, end to end

```
Library page paint (admin):
  GET /api/media?page=1&pageSize=60   ─┐
  GET /api/media (slice 2, 3, …)       │  each: SELECT all blobs of kind → decode ALL
  GET /api/media/track-facets          │  → in-memory filter/search/sort → drop/take 60
  GET /api/media/meta-facets           │  facets/triage: allItems() → full decode
  GET /api/triage   (from shell)      ─┘  ⇒ ~5–6 × full 18.7 MB decode, uncompressed JSON

Ravilo home open (sofa):
  GET /api/tv/home
     ├── allItems()  ───────────────► decode ALL 18.7 MB (once, reused)
     ├── per CUSTOM row: ConditionEvaluator × 307     (rows × items)
     ├── per GENRE/NEWLY row: filter+sort × 307
     └── buildContinueRow: getResumeItems ∥ getNextUp  ← 2 live Jellyfin calls GATE the response
     ⇒ response built, then sent uncompressed; nothing cached
```

Where the wall-clock goes, ranked: **(1) JSON deserialization of the full library**,
repeated per request/slice — the dominant, universal cost; **(2)** in-memory sort/filter over
the decoded set (and, on home, `rows × items` ConditionEvaluator); **(3)** the gating live
Jellyfin round-trip on home; **(4)** uncompressed transfer of an over-fat payload to the
client; **(5)** SQLite read/decode serialized through a single reader connection.

## 4. Findings by layer

### 4.1 Data layer — the core problem (`MediaStore.kt`, `Media.sq`)

- **Blob store, in-memory everything.** `listFiltered` only ever filters by `kind` (indexed)
  and `poster_path IS NULL`; everything else is Kotlin (`MediaStore.kt:106-166`). Pagination
  is `sorted.drop((page-1)*pageSize).take(pageSize)` (`:165`) after the full decode.
- **`allItems()` is the hot primitive** (`:186-189`) and is uncached. Same `MediaItem.
  serializer()` encodes and decodes; `Json` instances are class-level (fine) — the cost is
  **volume, not config** (no pretty-print).
- **Denormalized columns already exist and are under-used.** The table carries
  `title, year, kind, studio, network, poster_path, issue_count, language_mix, scanned_at,
  tmdb_id, episode_count` as real columns (`Media.sq:1-15`) — enough to render a grid card and
  to do most facet counts **without decoding `json` at all**. Today they're written but only
  `kind`/`poster_path` are read by a query.
- **Counts that are already cheap** go through SQL aggregates (`count`, `countByKind`,
  `sumEpisodeCount`, `MediaStore.kt:230-238`) — good. The expensive outlier is
  `nfoCoveragePercent()` → `nfoCoveredCount()` = `allItems().count { NfoWriter.exists(it) }`
  (`:240-246`): a full decode **plus a filesystem `stat()` per item** (`NfoWriter.kt:69`), run
  on every dashboard load via `/stats` (`MediaRoutes.kt:1485`).

### 4.2 Admin read endpoints (`MediaRoutes.kt`, `MetadataRoutes.kt`, `TriageRoutes.kt`)

| Endpoint | Store call | Full-lib decodes/req | Ships full MediaItem? | Cached? |
|---|---|---|---|---|
| `GET /api/media` (grid + search) | `list()` `MediaRoutes.kt:183` | **1 per slice** | Yes, minus `episodes[]` (`:185`) | No |
| `GET /api/media/track-facets` | `trackFacets()` | 1 | n/a | No |
| `GET /api/media/meta-facets` | `metaFacets()` | 1 | n/a | No |
| `GET /api/stats` | counts (cheap) + `nfoCoveragePercent()` | 1 + **307 `stat()`** | n/a | No |
| `GET /api/triage` / `/count` (shell, every page) | `allItems()` `TriageRoutes.kt:84,103` | 1 | TriageItem DTOs | No |
| `GET /api/metadata/{studios…tags}` | `allItems()` each `MetadataRoutes.kt:60,77,158,172` | 1 each | n/a | No |
| `GET /api/people/{id}/image` | `personProfilePath()` | 0 warm / 1 first call post-write | bytes | **Yes** (write-invalidated) |
| `POST /api/media/batch-count` | `countBatch()` `MediaStore.kt:302` | 1 (single pass, N stacks) | n/a | No |

- **Library cold paint ≈ 5–6 full decodes** (grid slices ×N + track-facets + meta-facets +
  triage), all uncompressed. This is the reported "library takes very long."
- **`batch-count` is the one good pattern** — one decode, N condition stacks evaluated in a
  single pass (`countBatch`, `MediaStore.kt:302-309`). It shows the team already knows the
  decode-once technique; it just isn't generalized.

### 4.3 Ravilo TV endpoints (`HomeFeedService.kt`, `BrowseService.kt`, `DetailService.kt`, `TvRoutes.kt`)

| Endpoint | Full-lib decodes/req | ConditionEvaluator calls/req | Ships slim card? | Live Jellyfin? | Cached? |
|---|---|---|---|---|---|
| `GET /tv/home` | 1 (reused across rows) | **(#CUSTOM rows) × 307** | Yes (`MediaCard`) | 2 (resume+nextUp, gating) | No |
| `GET /tv/channel/{id}` | 1 | 307 (predicate) + rows×N | Yes | 0 (no Continue) | No |
| `GET /tv/browse` | 1 | 0 (inline filters) | Yes | mylist → favorites | No |
| `GET /tv/search` | 1 | 0 | Yes | 0 | No |
| `GET /tv/movie/{id}` | 1 | 0 | Yes (+cast≤20, related≤12) | **0 (R83)** | No |
| `GET /tv/series/{id}` | 1 | 0 | card + all episodes + cast + related | **0 (R83)** | No |
| `GET /tv/playstate?ids=` | 0 | 0 | n/a | bulk UserData, `Semaphore(4)` | No |

- **Home is the priority sofa surface.** One decode (good), but `rows × items`
  ConditionEvaluator on CUSTOM rows (`HomeFeedService.kt:228`), per-row genre/newly
  filter+sort, `heroIds` rebuilt per CUSTOM row (`:227`), and the whole response **blocks on
  resume+next-up** (`:277-280`). `ConditionEvaluator.evalOne` re-lowercases `c.values` per
  item and `listMatch` re-lowercases the item's list **again** per call
  (`ConditionEvaluator.kt:23,58`) — nothing precomputed.
- **Browse/search** decode the whole library and sort it to return one 40-item page / ≤100
  hits (`BrowseService.kt:39,72-73,83,101`); **TV search has no min-length guard** — a 1-char
  query triggers a full decode + scan over every `titlesByLang` value (`:95-99`).
- **Detail** is the worst work-per-byte: full decode to return one item + 12 related, via a
  linear scan instead of the existing O(1) `jellyfinIdIndex` (`DetailService.kt:34,57` vs
  `MediaStore.kt:178`). `related` is another full genre-overlap pass (`:136-141`).
- **Wire DTO is correctly slim** — `MediaCard{id,kind,title,year,genre,rating,posterUrl,
  backdropUrl,progressPct?,nextUpLabel?,badge?,watched}` (`shared/.../tv/Models.kt`). The heavy
  `MediaItem` is decoded server-side and discarded. Minor non-slim spots: `Hero.synopsis`
  (full overview per hero) and `SeriesDetail` ships every episode's overview+still across all
  seasons (`DetailService.kt:66-81`).
- **Two-phase load is already partly built (R83):** detail/series are catalog-only and
  Jellyfin-free; per-user state moved to the separate bulk `/tv/playstate` endpoint with a
  `Semaphore(4)` (`DetailService.kt:25,110-132`). This is the right direction — the work
  remaining is the *local-compute* cost (decode + scans), not Jellyfin coupling.

### 4.4 Outbound HTTP & token hot path (`JellyfinClient.kt`, `RaviloDeviceService.kt`, `PlaybackService.kt`, downloaders)

- **7 long-lived `HttpClient(Curl)` singletons** (Jellyfin, TMDB, qBittorrent, Arr, Tudum,
  Logo, Artwork), all instantiated once in `Main.kt` — pooling/keep-alive works per
  subsystem; no per-call client creation. **But there is no global outbound socket cap**
  across the 7 engines (relevant to the FD ceiling, §8).
- **No `HttpTimeout` on any client** (grep-confirmed). A slow/hung Jellyfin blocks the
  coroutine — and therefore the whole TV request — until libcurl's coarse defaults fire. This
  is the single biggest **reliability** gap on the sofa path (`JellyfinClient.kt:34`).
- **Device-token validation runs on every TV API call and is uncached** —
  `validateDeviceToken` does a SQLite `SELECT` **and an `UPDATE last_seen`**
  (`RaviloDeviceService.kt:108-110`). So every TV JSON call pays a read **and a write** (write
  amplification, serialized on the single writer connection). The Jellyfin **user-token
  validity** *is* cached 5 min (`PlaybackService.kt:27-29,261-278`) — that part is fine.
- **Bounded fan-outs are correct** where they matter: playstate `Semaphore(4)`
  (`DetailService.kt:25`), `LogoDownloader Semaphore(8)`, continue-row = 2 parallel asyncs,
  scan worker pool capped. **One latent unbounded fan-out:** `ArtworkDownloader.download` has
  **no semaphore** (`ArtworkDownloader.kt:84`) and is reached from a detached
  `appScope.launch { artwork.fetch(item) }` per item-save (`MediaRoutes.kt:1588`) — safe only
  because callers happen to be sequential today. Admin-side, but it's the Phase-78 crash class.
- **No Jellyfin response is cached** (resume/next-up/userdata/playback-info). Home's
  resume+next-up are the most-refetched live data. The old movie→play `getItemDetail`
  double-fetch is **gone** (R83); a minor residual: resume position is read in `/tv/playstate`
  and again in `startPlayback`'s `getItemDetail` (`PlaybackService.kt:54`).

### 4.5 Write path & DB engine (`Database.kt`, `Scanner.kt`, `MediaStore.update`, `ActivityLog.kt`)

- **No PRAGMA tuning** (grep-confirmed). Effective SQLiter defaults: **WAL on**,
  `busy_timeout=5000` (already set by the driver — fine), **`synchronous=FULL`** (fsync every
  commit), **1 writer + 1 reader connection** (`maxReaderConnections` defaults to 1), 2 MB
  cache, mmap off.
- **All reads serialize on a single reader connection.** WAL means a scan write does not hard-
  block reads, but two concurrent reads cannot proceed in parallel, and each read holds that
  one connection for a **full library decode**. Under concurrent sofa + admin load this
  serializes.
- **A scan writes the library twice and re-encodes everything unconditionally.** Per item
  during the scan: `addOrUpdate` = decode-one + encode + `INSERT OR REPLACE`, each its own
  auto-commit fsync (`MediaStore.kt:213-220`, `MediaRoutes.kt:1679`). Then at the end:
  `update(allItems)` = a full `allItems()` decode + one transaction of `deleteAll()` + **307
  re-encodes + re-inserts** (`MediaStore.kt:55-78`, `MediaRoutes.kt:1727`) — no dirty-check, so
  unchanged items are re-encoded too. This is why the **WAL ballooned to 22 MB** (one giant
  changeset, no mid-transaction checkpoint; the single reader connection also keeps frames in
  use so PASSIVE auto-checkpoint never truncates). No manual `wal_checkpoint`/`VACUUM`/
  `ANALYZE` anywhere.
- **`ActivityLog` rewrites the entire `activity-log.json` on every log line**
  (`ActivityLog.kt:62,84-96`) via tmp+rename — an O(N) full-file rewrite. A scan emits a line
  per item → hundreds of full-file rewrites (the file was seen at 360 KB). Off the request
  thread, but heavy disk churn that competes with scan I/O.
- **`MediaHistory.record()`** does INSERT **+** a `trimToMax(2000)` anti-join DELETE on every
  edit (`MediaHistory.kt:29-37`) — two writes per edit even far below the cap. Bounded, low
  volume, low priority.

---

# PART II — Optimization plan

Organized as independent workstreams, each tagged **impact / effort / risk**. They are
ordered by leverage. WS-A is the single highest-value change and unblocks the value of most
others.

## WS-A — Cache the decoded library in `MediaStore` ★ the big lever
**Impact: very high · Effort: low · Risk: low**

Add a `private var allItemsCache: List<MediaItem>?`, populate it in `allItems()`
(`MediaStore.kt:186`), and invalidate it in `upsertItem()` right beside the two existing
invalidations (`MediaStore.kt:312-313`). This is the *same proven pattern* already used for
`peopleIndexCache`/`jellyfinIdIndex`.

Effect: every TV home/browse/search/detail and every admin facets/triage/metadata call stops
re-parsing 18.7 MB and reuses an in-memory list; the library only re-decodes after a scan/edit
write. This collapses finding #2 globally and turns the ~5–6 Library-paint decodes and the
per-request TV decode into ~1 (amortized to ~0 between writes). Memory cost is ~the decoded
graph held once (tens of MB) — acceptable; if not, cache only a **slim summary** (WS-B) and
decode full items lazily by id.

Pairs with: **detail should use `resolveByJellyfinId`** (`MediaStore.kt:178`) instead of
`all.firstOrNull` (`DetailService.kt:34,57`), and **hoist `heroIds` out of the CUSTOM-row
loop** (`HomeFeedService.kt:227`).

## WS-B — Slim projection + SQL pushdown for card lists
**Impact: high · Effort: medium · Risk: low**

The grid/rows/facets never need cast/crew/episodes/tracks. Two complementary moves:

1. **`MediaSummary` projection.** Add a SQLDelight query selecting the existing denormalized
   columns (`Media.sq:1-15`) instead of `json`, and a light DTO for grid cards and TV
   `MediaCard`s. Most list/row/facet requests then need **zero JSON parsing**. (`genres`/`tags`
   for facet counts can be a tiny extra column or a precomputed facet table — see WS-C.)
2. **Push pagination/sort into SQL** for the common no-condition cases: `ORDER BY scanned_at
   DESC LIMIT :n OFFSET :m` (both columns are indexed). Removes the decode-all-then-`drop/take`
   (`MediaStore.kt:158-165`). Keep the in-memory path only for condition-stack/audio-facet
   filters that genuinely can't be expressed in SQL.

3. **Slim the admin grid payload** even when sending full items isn't avoidable: stop shipping
   `cast/crew/tracks/titlesByLang` for grid cards (`MediaRoutes.kt:183-185`).

## WS-C — Computed-result caches (facets, stats, triage, home feed)
**Impact: high · Effort: medium · Risk: low–medium**

All of these are pure functions of library state; memoize against a **library-version stamp**
bumped on any write (reuse the WS-A invalidation hook):

- **`trackFacets()` / `metaFacets()`** (`MediaStore.kt:248,275`) — cache; recompute on write.
  Removes 2 of the Library paint's decodes.
- **`/stats` nfo-coverage** — cache the percentage, or persist an `nfo_exists` flag at
  scan/write time, to drop the **307 `stat()` calls** per dashboard load
  (`MediaStore.kt:240-246`).
- **Triage list/count** — cache; removes the per-navigation tax (`TriageRoutes.kt:84,103`).
- **Assembled `HomeFeed` per (user, config-revision)** — `getHomeFeed` is the app-open hot
  path (`HomeFeedService.kt:32`); `TvEvent.rev` already provides a config revision. Cache the
  catalog portion, invalidate on config change + library write. **Keep the Continue row out of
  the cached blob** (it's live per-user) — see WS-G/two-phase.

## WS-D — Transport: compression, timeouts, static caching
**Impact: high (sofa especially) · Effort: low · Risk: low**

- **Install `Compression` (gzip)** in `Server.kt` (`:138`). All JSON feeds — Library grid,
  home rows, series detail with every episode overview — compress well and go uncompressed
  today. Biggest single win for perceived transfer time over wifi to a TV.
- **Add `HttpTimeout` (connect + request + socket) to every outbound Curl client**, first to
  `JellyfinClient.kt:34`. Prevents a slow Jellyfin from stalling whole TV requests; also TMDB
  on metadata paths.
- **Cache/ETag static frontend assets.** `serveFrontendFile` reads the whole file into memory
  per request with no `Cache-Control`/`Last-Modified`/`ETag` (`Server.kt:319-345`) — the WASM
  bundle is re-read and re-sent every load. Add caching headers (and ideally
  `respondFile`/stream) for `*.wasm/js/css`.

## WS-E — Search
**Impact: high (search latency) · Effort: medium · Risk: low–medium**

- **Add a denormalized, lowercased search column** (title + originalTitle + all
  `titlesByLang` values concatenated) written at scan time, indexed — or an **FTS5** virtual
  table. Then search is an indexed SQL query, not a full decode + scan
  (`MediaStore.kt:104,119-124`). Restores the multi-language coverage the in-memory path was
  added for, without the per-keystroke full parse.
- **Guard TV search** with a server-side min-length and a query-length cap
  (`BrowseService.kt:95`) so single-char searches don't trigger a full decode.

## WS-F — Write path & DB engine
**Impact: medium (scan smoothness + read contention) · Effort: medium · Risk: medium**

- **`synchronous = NORMAL`** under WAL (`Extended(..., synchronousFlag = NORMAL)`,
  `Database.kt:44`) — durable under WAL, removes most per-commit fsyncs (the scan does
  hundreds).
- **Raise `maxReaderConnections`** (e.g. 4) in `NativeSqliteDriver(config, …)`
  (`Database.kt:46`) so concurrent sofa + admin reads don't serialize on one connection.
- **`PRAGMA wal_checkpoint(TRUNCATE)`** after the scan rewrite (`MediaRoutes.kt:1727`) and/or
  periodically, to stop the 22 MB WAL growth; consider a modest `mmap_size`/`cache_size` bump
  for the 19 MB DB.
- **Stop the double full-encode at scan end.** Either keep per-item `addOrUpdate` and replace
  the trailing `update(allItems)` with a targeted delete of now-missing ids, or drop per-item
  writes and keep one transaction — don't encode the whole library twice
  (`MediaStore.kt:55-78` vs `:213-220`). Add **dirty-checking** so unchanged blobs aren't
  rewritten.
- **`ActivityLog` incremental/batched persistence** instead of a full-file rewrite per line
  (`ActivityLog.kt:62,84-96`); debounce during scans.

## WS-G — Device-token hot path
**Impact: medium · Effort: low · Risk: low**

- **In-memory cache the device-token → `DeviceData`** lookup (mirror the 5-min token cache in
  `PlaybackService.kt:27`), and **debounce `updateLastSeen`** to e.g. once/minute
  (`RaviloDeviceService.kt:108-110`). Removes a SQLite read **and write** from every TV API
  call — directly reduces writer-lock contention and per-request latency on the sofa path.

## WS-H — Concurrency safety (stability, not latency)
**Impact: stability · Effort: low · Risk: low**

- **Bound `ArtworkDownloader.download`** with a `Semaphore` like `LogoDownloader`
  (`ArtworkDownloader.kt:84`) to close the latent FD-ceiling fan-out
  (`MediaRoutes.kt:1588`). Optionally add a **process-wide outbound semaphore** across the 7
  Curl engines so total in-flight sockets stay under `FD_SETSIZE` regardless of which
  subsystems are busy (§8).

## WS-I — Filter evaluation & result caching (the workbench engine on the sofa)
**Impact: medium now / high at scale · Effort: low (index) → high (materialize) · Risk: low**

The R32 workbench condition stacks (`ConditionEvaluator`) drive Ravilo's CUSTOM rows and
channels (`HomeFeedService.kt:228,340`). **At the current 307 items the filter engine is *not*
the bottleneck — the full-library decode is.** Evaluating ~10 rows × 307 items × a few
conditions is single-digit milliseconds; it only *looks* expensive because it sits behind the
18.7 MB decode. So the ordering is: fix the decode first (WS-A), and only then consider
precomputing filter results. Do **not** build a materialized `filter → ids` table to fix a cost
that WS-A removes.

**Tier 1 — make live evaluation cheap (do this with WS-A).** `evalOne` re-lowercases both the
condition values *and* the item's facet list on every call (`ConditionEvaluator.kt:23,58`).
Precompute a **lowercased `Set` per facet per item** (studio/network/genres/tags/audio-langs/
codecs) once at decode/scan time, so matching is allocation-free `Set.contains`. This keeps live
filtering fast even at ~10× the library and **defers materialization indefinitely**.

**Tier 2 — cache results at the *feed* level, not a filter table (this is WS-C).** A custom
row's output *is* its filter result — already sorted and limited. The WS-C per-(user,
config-rev) feed cache therefore already memoizes filter results, and it correctly handles
(a) ordering/limit, (b) the **per-viewer `hero_item` facet** (`ConditionEvaluator.kt:39-48`, so a
global `filter→ids` would be wrong for those), and (c) all row kinds uniformly. Prefer this over
a dedicated filter-result table.

**Tier 3 — a materialized filter-result table, only when one of these is true:**
- **Library grows large** (~10k+ items) → live eval reaches tens of ms; move it to scan time.
- **Filters get expensive** (full-text/fuzzy/regex on overviews, aggregates over a 279-episode
  `episodes[]`, cross-item "because you watched") → pay the cost once at sync, not per request.
- **The same filter is shared across many viewers AND is viewer-independent** (no `hero_item`) →
  compute once globally, reuse everywhere — the one case the feed cache can't dedupe.

If/when built: key it by a **content-hash of the filter definition** (editing a filter
auto-invalidates), keep `hero_item` filters out of the shared cache (resolve per-viewer), and
invalidate on **scan-complete, JS-tag edit, item delete/repull, and config change** — not a bare
timer, or Ravilo will serve deleted items. Per the no-flicker rule, a refresh updates the *next*
load, never mid-view. **Alternative end-state for a large library with mostly positive-membership
filters:** push filters into SQL via a normalized, indexed schema (junction tables for genre/tag/
studio/network) so a filter is an indexed `WHERE/JOIN … LIMIT`; the workbench already classifies
which stacks are simple/positive (`Workbench.kt:332`). This scales with **library size**;
complex stacks (`is_none_of`/`not_contains`/`track_title`/`hero_item`) map awkwardly to SQL, so
it fits *size* growth, not *complexity* growth.

**Recommendation:** WS-A + Tier 1 (facet index) + Tier 2 (WS-C feed cache). That delivers
"Ravilo reads precomputed results" performance with no new table and no sync-correctness surface,
and is a clean stepping stone to Tier 3 the moment a graduation criterion actually hits.

## 5. Splitting requests for a better feel

The user explicitly asked about breaking heavy requests into smaller ones so the UI feels
responsive. Two principles, both already partly present:

1. **Two-phase load is a *detail-screen-only* technique — never home.** Detail is already
   catalog-only + a separate `/tv/playstate`, and its per-user state lands as **fixed-size
   overlays** on already-rendered tiles (progress bars, watched ticks, the Play→Resume label),
   so it can't move layout. **Do not apply this split to home** (see *The no-flicker rule*
   below): the Continue row's membership and order are Jellyfin-derived
   (`HomeFeedService.kt:277-280`) and cannot ship with the local catalog, so decoupling it
   would pop a row in at the **top** of home and reflow everything below — the exact jump we
   refuse. Make home fast *without* splitting it (atomic feed + stale-while-revalidate cache +
   a tight Jellyfin timeout / pre-warm). Each phase is still a server response, so the
   *frontend renders server-pushed state only* invariant holds.

2. **Make each fan-out request cheap rather than serial.** The Library page already fires
   grid + facets + triage in parallel; the problem is each is independently heavy. WS-A/B/C
   make them cheap, after which the existing parallel fan-out feels instant. Also reconsider
   the **slice-until-viewport-full** loop (`Library.kt:883-887`): with SQL pagination (WS-B) a
   single larger first slice is cheaper than N full-decode slices.

## 6. Ravilo sofa priority (the smooth-couch checklist)

Highest-leverage, in order, for the TV experience specifically:
1. **WS-A** decoded-library cache — removes the 18.7 MB parse from every home/browse/search/
   detail open.
2. **WS-D** gzip + Jellyfin `HttpTimeout` — smaller, never-stalling responses.
3. **Make home fast while keeping it atomic** — stale-while-revalidate feed cache (WS-C) + a
   tight Jellyfin timeout / pre-warm for the Continue row; **do not split home into phases**
   (see *The no-flicker rule*). Detail per-user state still hydrates late, but only as
   fixed-size overlays.
4. **WS-C** cache the assembled HomeFeed per (user, config-rev).
5. **WS-G** device-token cache — stop the per-call SQLite write.
6. **WS-B** slim `MediaCard` projection so rows never decode full items.
7. Detail O(1) index + `heroIds` hoist + ConditionEvaluator precompute (cheap polish).

## ⚠ The no-flicker / no-row-jump rule (governing Ravilo constraint)

**Hard rule:** a Ravilo screen must paint as **one atomic, fully-laid-out frame**. Nothing may
add, remove, or resize a row or a tile after first paint — a row must **never** appear because a
row below it finished loading first, and no late data may change an element's size. This
constraint outranks every latency win below; where they conflict, atomicity wins.

**Why almost all of this report is automatically safe:** every server-side recommendation
(WS-A, WS-B, WS-C, WS-D, WS-E, WS-F, WS-G, WS-H) only makes the **same atomic response** faster
or smaller — it does not change *what* the client renders or *when*. None can introduce a jump.
In fact WS-A and the WS-C feed cache **improve** atomicity, because a complete feed comes back
sooner and more reliably.

| Recommendation | Touches render timing? | Verdict |
|---|---|---|
| WS-A decoded-library cache | No (same response, faster) | ✅ safe — improves atomicity |
| WS-B slim projection + SQL pagination | No (same DTO, smaller) | ✅ safe — keep the DTO contract identical |
| WS-C facet/stats/**feed** caches | No (same response, cached) | ✅ safe — a cached feed is *more* atomic |
| WS-D gzip + timeout + static caching | No | ✅ safe |
| WS-E search (own screen) · WS-F/G/H | No | ✅ safe |
| Splitting **home** into catalog + late Continue | **Yes** | ⚠ **unsafe — forbidden, see below** |

**Home must stay atomic.** The Continue row's *membership and order* are Jellyfin-derived
(`getResumeItems`/`getNextUp`, `HomeFeedService.kt:277-280`), so they cannot be produced from
the local catalog. Rendering the catalog first and letting Continue arrive later would insert a
new row at the **top** of home and reflow the screen — the jump we refuse. (The
`ravilo-jellyfin-decoupling` report reached the same conclusion: keep Continue inline; two-phase
is detail-only.) Make home fast **without** splitting it:
- **WS-A** → catalog rows build instantly (no 18.7 MB decode).
- **Stale-while-revalidate the whole assembled feed** per (user, config-rev) (WS-C): serve the
  last *complete* feed — including a recent Continue row — instantly and atomically, and refresh
  in the background for the *next* load. The feed is at most one load stale; nothing changes
  mid-view.
- **Tight `HttpTimeout` + pre-warm** for a cold Continue (WS-D): bound the first-load
  resume/next-up wait and pre-fetch it on app foreground / via the `/api/tv/events` channel. If
  it times out, render home *without* the Continue row this load — a **missing** row is not a
  **jumping** row.

**When late hydration *is* allowed (detail screens only):** strictly as **fixed-size overlays on
already-rendered elements** — a progress bar or watched tick drawn on top of an existing poster,
a Play→Resume label in a reserved-width slot, the series "X of Y watched" text in a fixed
lower-third. It is never allowed to add/remove a row or tile, change a row's tile count, or grow
an element's height on hydration; reserve space for any late text so it can't nudge layout.
Watch the `SeriesDetailScreen` `remember(detail)` season-reset hazard (decoupling §10.5): a
phase-2 re-emit must not reset the selected season — key season state on a stable `itemId`, or
overlay the per-episode playstate onto the existing object instead of swapping it.

## 7. Constraints & risks to respect

- **FD_SETSIZE ceiling (Kotlin/Native CIO + `select()`):** any FD ≥ 1024 crashes the whole
  process. Every new fan-out or outbound fetch must stay `Semaphore`-bounded; a global
  outbound cap is desirable (WS-H). Cache **hits open no socket**, which is another reason WS-A
  and a high image-cache hit-rate are safety features, not just speed.
- **Kotlin/Native serialization is the bottleneck, not SQLite.** Optimizations should reduce
  *how much JSON is parsed per request* (cache, projection, pushdown), not micro-tune the
  parser.
- **No client-derived state** (project invariant): the frontend renders server-pushed state
  only. Two-phase hydration is fine (each phase is a server response); client-side optimistic
  caches of mutable state are not.
- **WS handler crash-safety** (project memory): any new WS or background coroutine must catch —
  an uncaught exception aborts the Native process.
- **Validate with measurement.** The structural waste (N × 18.7 MB parses to return 20–60
  cards) is certain; the exact millisecond wins are not measured here. Time `allItems()` and a
  cold Library paint before/after WS-A to quantify.

## 8. Recommended sequencing

1. **WS-A** (decoded-library cache) + the two cheap pairings (detail O(1) index, `heroIds`
   hoist). One small change, library-wide effect. *Measure before/after.*
2. **WS-D** (gzip + Jellyfin timeout + static caching). Low-risk, broad benefit.
3. **WS-C** (facets/stats/triage caches + the **atomic** stale-while-revalidate home-feed
   cache, §"no-flicker rule"). The sofa and Library both feel instant after this; home stays
   one atomic response.
4. **WS-B** (slim projection + SQL pagination). Removes the last full-decode paths and shrinks
   payloads.
5. **WS-E** (search column/FTS + TV min-length).
6. **WS-G** (device-token cache), **WS-F** (engine/scan tuning), **WS-H** (ArtworkDownloader
   bound), **WS-I Tier 1** (facet index — folds into WS-A). Contention, scan smoothness,
   stability, cheap filter speed-up. **WS-I Tier 3** (materialized filters) is deferred until a
   graduation criterion hits.

WS-A, WS-D, WS-G, WS-H are largely independent and can land in any order. WS-B/C build on
WS-A's invalidation hook. Nothing here changes the streaming boundary or the R82–R85
direction — it makes the local catalog path fast.

## 9. Open decisions

- **Cache memory budget:** hold full decoded `MediaItem`s in memory (simplest, tens of MB) vs
  cache only a slim `MediaSummary` and decode full items lazily by id (lower memory, a bit more
  code). Affects WS-A/B shape.
- **Search backend:** denormalized lowercased column (minimal) vs FTS5 (ranking, prefix,
  scales further). Affects WS-E.
- **Home-feed cache granularity & TTL:** per-(user,config-rev) only, or also a short time TTL;
  how to key the Continue row's freshness against the cached catalog.
- **Projection vs full-decode for TV rows:** how much of `MediaCard` can be served purely from
  columns vs needs `genres`/track facets that currently live in the blob.
- **Filter scaling axis (WS-I):** which growth is coming — library *size* (→ SQL pushdown /
  scan-time materialization), filter *complexity/cost* (→ in-memory engine + facet index + feed
  cache), or *many viewers sharing filters* (→ content-hashed materialized table). Decides if/when
  Tier 3 is built; until then Tier 1+2 suffice.

---

## Appendix — key files & measured data

**Data layer:** `media/MediaStore.kt` (`list` 80-167, `allItems` 186-189, caches 23-27/
312-313, facets 248-299), `db/Media.sq` (schema+indices 1-18, `listFiltered` 45-50),
`db/Database.kt` (no pragmas; SQLiter defaults), `model/Media.kt` (heavy `MediaItem`/`Episode`/
`Person` graph).
**Admin routes:** `server/routes/MediaRoutes.kt` (list 183-185, batch-count 198-204, stats
1478-1488, people-image 1405-1428, scan 1644-1727), `MetadataRoutes.kt`, `TriageRoutes.kt`
(84,103). Frontend: `ui/Library.kt` (40,208-211,226-230,265,833-887), `api/MediaApi.kt`
(189-227), `ui/Shell.kt` (164).
**Ravilo TV:** `tv/HomeFeedService.kt` (35-47,215-238,277-280), `tv/DetailService.kt`
(33-57,110-141 playstate, 136-141 related), `tv/BrowseService.kt` (39,72-101),
`tv/ConditionEvaluator.kt` (23,58), `server/routes/TvRoutes.kt`. DTO:
`shared/.../tv/Models.kt` (`MediaCard`).
**HTTP / engine:** `auth/JellyfinClient.kt` (34, no timeout), `tv/RaviloDeviceService.kt`
(108-110), `tv/PlaybackService.kt` (27-29 token cache, 54), `media/ArtworkDownloader.kt` (84,
unbounded), `media/LogoDownloader.kt` (32, bounded), `media/ActivityLog.kt` (62,84-96),
`media/MediaHistory.kt` (29-37), `server/Server.kt` (138-159 plugins; no Compression/caching).

**Measured (live DB, 2026-06-26):** 307 items (222 movie / 85 TV), 3,909 episodes, 18.7 MB
JSON; blob median 22.7 KB / mean 61 KB / max 1.18 MB; DB 19 MB + WAL 22 MB; page cache ≈2 MB;
indices `media_kind`, `media_scanned_at`.
