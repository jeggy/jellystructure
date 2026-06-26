# Phase 90 — Transport & SQLite engine hardening

> Compress API responses, never let a slow Jellyfin stall a request, cache static assets, and tune the
> SQLite engine + scan write-path so writes don't fsync-thrash or contend with reads. Cross-cutting
> backend infrastructure — low risk, broad benefit to both admin and Ravilo.

## Problem
Independent of how much JSON we decode, the transport and storage layers leave easy wins on the table:
- **No `Compression` plugin** — every API response (Library grid, Ravilo home rows, series detail with
  every episode overview) goes over the wire **uncompressed** (`Server.kt:138-159`). Worst over wifi to
  a TV.
- **No `HttpTimeout` on any outbound Curl client** (`JellyfinClient.kt:34` + 6 others) — a slow/hung
  Jellyfin blocks the coroutine, and therefore the whole request, until libcurl's coarse defaults fire.
- **Static assets are re-read into memory and re-sent every request** with no `Cache-Control`/
  `ETag`/`Last-Modified` (`Server.kt:319-345`) — the WASM bundle especially.
- **SQLite runs untuned**: `synchronous=FULL` (an fsync per commit), a **single reader connection**
  (reads serialize), and the WAL has grown to **22 MB uncheckpointed** (no manual checkpoint anywhere).
- **A scan re-encodes the whole library twice** — per-item `addOrUpdate` (each its own fsync) **then**
  `update(allItems)` which `deleteAll()` + re-encodes all 307 items in one transaction
  (`MediaStore.kt:55-78,213-220`; `MediaRoutes.kt:1679,1727`), with no dirty-check.
- **`ActivityLog` rewrites the entire `activity-log.json` on every log line** (`ActivityLog.kt:62,84-96`)
  — a scan emits hundreds of full-file rewrites.
- **`ArtworkDownloader.download` is unbounded** (`ArtworkDownloader.kt:84`) — a latent FD-ceiling
  fan-out reached from a detached `appScope.launch` (`MediaRoutes.kt:1588`).

## Architectural constraint (driving decision)
Every change here must respect the Kotlin/Native runtime constraints: the **FD-ceiling**
([[reference-ktor-native-fd-setsize]]) — any new fan-out stays `Semaphore`-bounded — and **WS/coroutine
crash-safety** ([[reference-ktor-native-ws-crash]]) — no exception may escape a handler/background
coroutine. Compression must not break the WebSocket endpoints (`/ws`, `/api/tv/events`) or chunked
streams. `synchronous=NORMAL` is durable under WAL; engine changes preserve data integrity.

## Current state (as-is)
- `server/Server.kt:138-159` — installs only `ContentNegotiation`, `WebSockets`, `CORS`, AuthPlugin (no
  Compression, no CachingHeaders); `:319-345` `serveFrontendFile` reads whole file, no cache headers.
- `auth/JellyfinClient.kt:34`, `tmdb/TmdbClient.kt:266`, `media/{Logo,Artwork}Downloader.kt`,
  `arr/ArrClient.kt`, `torrent/QBittorrentClient.kt`, `chart/NetflixTudumProvider.kt` — 7 long-lived
  `HttpClient(Curl)` singletons, **none** with `HttpTimeout`.
- `db/Database.kt:15-46` — no PRAGMA tuning; SQLiter defaults (WAL on, `busy_timeout=5000`,
  `synchronous=FULL`, `maxReaderConnections=1`, 2 MB cache).
- `media/MediaStore.kt:55-78,213-220`, `server/routes/MediaRoutes.kt:1679,1727` — the double write.
- `media/ActivityLog.kt:62,84-96` — full-file rewrite per line; `media/ArtworkDownloader.kt:84` — no
  semaphore (`media/LogoDownloader.kt:32` is the bounded precedent to mirror).

## Requirements

### A. Transport (WS-D)
1. Install `Compression` (gzip/deflate) in `Server.kt`; exclude/῾pass-through the WebSocket upgrades and
   any byte-stream image routes so they aren't double-handled. Verify `/ws` + `/api/tv/events` still
   work.
2. Add `HttpTimeout` (connect + request + socket) to every outbound Curl client — `JellyfinClient`
   first (it gates Ravilo requests), then TMDB and the downloaders. Pick timeouts that fail a hung
   upstream in low single-digit seconds.
3. Add `Cache-Control`/`ETag` (and ideally `respondFile`/streaming) for `*.wasm/js/css/png/svg` in
   `serveFrontendFile` (`Server.kt:319-345`); keep `index.html` uncached for SPA freshness.

### B. SQLite engine (WS-F)
4. Set `synchronous = NORMAL` (durable under WAL) in `db/Database.kt:44`.
5. Raise `maxReaderConnections` (e.g. 4) so concurrent admin + Ravilo reads don't serialize on one
   reader connection (`db/Database.kt:46`).
6. Run `PRAGMA wal_checkpoint(TRUNCATE)` after the scan rewrite (`MediaRoutes.kt:1727`) and/or
   periodically, to stop the WAL growing past the DB; consider a modest `cache_size`/`mmap_size` bump.

### C. Scan write-path (WS-F)
7. Eliminate the double full-encode: either keep per-item `addOrUpdate` and replace the trailing
   `update(allItems)` with a **targeted delete of now-missing ids**, or keep one transaction and drop
   the per-item writes — don't encode the whole library twice. Add **dirty-checking** so unchanged
   blobs aren't rewritten (`MediaStore.kt:55-78,213-220`).
8. Make `ActivityLog` persistence incremental/batched (append or debounced snapshot) instead of an
   O(N) full-file rewrite per line (`ActivityLog.kt:62,84-96`); keep it off the request thread and
   crash-safe.

### D. Concurrency safety (WS-H)
9. Bound `ArtworkDownloader.download` with a `Semaphore` mirroring `LogoDownloader.kt:32`
   (`ArtworkDownloader.kt:84`), so the detached per-save fetch (`MediaRoutes.kt:1588`) can't flood
   sockets. Optionally add a process-wide outbound semaphore across the Curl engines.

## Invariants
- FD-ceiling respected — every fan-out stays `Semaphore`-bounded; no new unbounded outbound path.
- WS/background-coroutine crash-safety preserved — no exception escapes a handler.
- No behaviour change beyond latency/durability; scan output (the stored items) is identical.
- Compression and caching must not corrupt WebSocket, image-byte, or streaming responses.

## Out of scope
- The decoded-library cache + projection + computed caches — **Phases 88/89**.
- The Ravilo home-feed cache + device-token cache — **Phase R86** (this phase provides the
  `HttpTimeout` that R86's cold-Continue path relies on).
- A full normalized/SQL-filter schema redesign — a deferred WS-I Tier 3 option, not this phase.

## Source references
- `src/linuxX64Main/.../server/Server.kt` (138-159, 319-345), `auth/JellyfinClient.kt:34`,
  `tmdb/TmdbClient.kt:266`, `media/{Artwork,Logo}Downloader.kt`, `db/Database.kt:15-46`,
  `media/MediaStore.kt:55-78,213-220`, `media/ActivityLog.kt:62,84-96`,
  `server/routes/MediaRoutes.kt:1588,1679,1727`
- Research report: `specs/research-reports/backend-performance-investigation.md` (WS-D, WS-F, WS-H, §4.4–4.5)
- Related memory: [[backend-performance-investigation]], [[reference-ktor-native-fd-setsize]],
  [[reference-ktor-native-ws-crash]]
- Independent of Phases 88/89; provides `HttpTimeout` used by **Phase R86**.
