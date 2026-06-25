# Phase 78 — File-descriptor exhaustion crash on person-image floods (FR-FD1)

**Status:** Planned

> The backend crashed hard with `IllegalStateException: File descriptor 1024 is larger or
> equal to FD_SETSIZE (1024)` while serving a flood of `/api/people/{id}/image` requests.
> This is a fatal, unrecoverable process exit. This phase documents the root cause and the
> layered fix so it cannot recur.

## Symptom

```
[be] kotlin.IllegalStateException: File descriptor 1024 is larger or equal to FD_SETSIZE (1024)
[be]   at io.ktor.network.selector.SelectorHelper.addInterest
[be]   at io.ktor.network.selector.SelectorHelper.fillHandlersOrClose
[be]   at io.ktor.network.selector.SelectorHelper.$selectionLoopCOROUTINE$0.invokeSuspend
[be]   ... runBlocking (top of process) ...
[be] > Task :runDev FAILED
```

The throw is in the **Ktor CIO server selector loop** (`SelectorHelper`), running under the
top-level `runBlocking`. When that coroutine throws, the exception propagates to the root and the
**entire backend process exits** — every in-flight request and websocket dies (the wall of
`ECONNREFUSED` proxy errors in the log is the frontend hammering a now-dead backend).

Immediately before the crash the log shows hundreds of concurrent
`GET /api/people/{id}/image` requests (the new cast & crew feature) plus
`Downloaded logo: …/people/<id>.jpg` lines — the backend was fetching person photos from TMDB
on-demand, at high concurrency.

## Root cause — the FD_SETSIZE 1024 ceiling

Ktor's CIO engine on Kotlin/Native multiplexes sockets with the POSIX **`select()`** syscall
(via `io.ktor.network.selector.SelectorHelper`). `select()` uses `fd_set` bitmasks whose size is
fixed at compile time to **`FD_SETSIZE` = 1024** on Linux. A file descriptor whose **numeric
value is ≥ 1024 cannot be placed in an `fd_set` at all** — so when the selector tries to register
such an FD, `addInterest` throws `IllegalStateException`.

Critically, this is about the **FD number**, not a configurable quota:

- Once ~1024 file descriptors are open **simultaneously** in the process, the kernel starts
  handing out FD numbers ≥ 1024 for the next `accept()`/`socket()`/`open()`.
- The CIO server then tries to register that high-numbered inbound socket in `select()` → fatal throw.
- **Raising `ulimit -n` does not help** — it permits *more* FDs to open, all still numbered
  ≥ 1024, which the selector still rejects. The constraint is structural to `select()`.
- Ktor's Native target has **no epoll/kqueue-based engine** today; CIO (select) is the only option.

**Therefore the hard invariant is: the process must keep its concurrent open-FD count well below
1024 (target a ceiling of ~512 with headroom). Every fan-out endpoint and every outbound-fetch
path must be concurrency-bounded.**

## How a single page load blew the budget

The crash is an interaction between several unbounded paths, all newly stressed by the Phase
75/76 cast & crew feature:

1. **Every person card emits an eager `<img>`.**
   `renderPersonCardHtml` (`MediaDetail.kt:3133/3142`) renders
   `<img src="/api/people/{tmdbId}/image">` with **no `loading="lazy"`**. A series detail page
   with a large aggregate cast (Phase 76 pulls main cast + per-episode guest stars + crew —
   easily 50–200 people) makes the browser open that many parallel image requests at once. The
   webpack dev-proxy fans them straight at the backend.

2. **Each request opens an inbound CIO server socket** — and these are exactly the FDs that
   must live in `select()`. Hundreds of concurrent requests = hundreds of select()-tracked FDs.

3. **On cache miss, each request does an on-demand outbound download.**
   The endpoint (`MediaRoutes.kt:1346–1351`) calls `logoDownloader.fetchPersonImage`, which
   does `http.get(url).readRawBytes()` (`LogoDownloader.kt:103`) to `image.tmdb.org`. There is
   **no concurrency cap** — N concurrent misses open N outbound sockets **plus** N `.tmp` file
   sinks simultaneously.

4. **Person images are never pre-warmed.** Unlike studios/networks (which have
   `batchFetchStudios`/`batchFetchNetworks`), `fetchPersonImage` is only ever called from the
   live request path (`MediaRoutes.kt:1350` is its sole caller). So the **first** view of any
   large cast is a worst-case cold-cache stampede.

5. **The lookup is O(all items × all people) per request.**
   On a miss the handler runs `store.allItems().flatMap { cast + crew + episodes…guestStars + crew }`
   (`MediaRoutes.kt:1346–1348`) to find one `profilePath`. Under a few hundred concurrent misses
   this is also a CPU/allocation storm that keeps each request — and its FDs — alive longer,
   widening the simultaneous-FD window.

Net effect: a few hundred concurrent cold person-image requests each hold ~2–3 FDs (inbound
socket + outbound socket + temp file), plus the always-open FDs (SQLite, websockets, pooled
client connections). The simultaneous total crosses 1024, the next inbound socket is numbered
≥ 1024, and the CIO selector kills the process.

> **Not the cause:** the shared `HttpClient(Curl)` singletons (one per client class, never
> `.close()`d) are the **correct** pattern — they live for the whole process and are reused. The
> bug is unbounded *concurrency* and the absence of an FD budget, not the singletons themselves.
> (Outbound Curl sockets go through libcurl's own poller, not Ktor's `select()`, but they still
> occupy FD slots in the process table and so push the inbound socket numbers over 1024.)

## Solution — layered, defense-in-depth

The fix must (a) prevent the stampede, (b) bound concurrent FDs structurally so no future
fan-out can recur this, and (c) survive gracefully if it ever does.

### A. Bound outbound-download concurrency (primary fix)

Add a single shared `kotlinx.coroutines.sync.Semaphore` (e.g. **8 permits**) inside
`LogoDownloader.download(...)` so that no matter how many callers arrive, at most N downloads —
and therefore at most N outbound sockets + N temp-file FDs — exist at once. This is the smallest
change that directly caps the dominant FD source.

Apply the same bounded-concurrency pattern to the other on-demand fetchers that can fan out
(`ArtworkDownloader`, per-request TMDB calls).

### B. Bound the person-image endpoint + make lookup O(1)

- Gate the cache-miss download path behind the same semaphore (or a dedicated one) so a flood of
  cold requests queues instead of all opening sockets at once.
- Replace the per-request `store.allItems()` scan with a prebuilt `Map<Int, String>`
  (tmdbId → profilePath) maintained alongside the store, so a miss is O(1) and the request
  releases its FDs quickly. Return **404 fast** for unknown ids without scanning.

### C. Pre-warm the people cache at scan/sync time

Add `batchFetchPeople(...)` to `LogoDownloader` (mirroring `batchFetchStudios`) and call it from
the scan/sync pipeline so all cast/crew/guest profile images are downloaded **once, sequentially
(or at low bounded concurrency), off the request path**. After a scan the runtime endpoint is
almost always a pure cache hit = one file read, zero outbound sockets.

### D. Frontend: lazy-load person images

Add `loading="lazy"` (and `decoding="async"`) to the person-card `<img>` in
`renderPersonCardHtml` (`MediaDetail.kt:3142–3143`), exactly as the artwork gallery already does
(`MediaDetail.kt:2253`). The browser then only requests images for cards near the viewport,
collapsing a 200-request burst into a handful. This is a one-line change with outsized impact and
should ship even though the backend fix is the real guard.

### E. Cap client-side connection pools

Configure the shared `HttpClient(Curl)` engines with an explicit max-connections cap
(`engine { }` / Curl options) so the client layer can never open an unbounded number of sockets
even under bursty internal use.

### F. Process-wide FD budget invariant (structural guard)

Document — in `constitution.md` (or `plan.md`) — that the Native CIO server is `select()`-bound at
FD_SETSIZE 1024, and codify the rule: **every endpoint that fans out per-row work and every
outbound-fetch path must be concurrency-bounded**, targeting a process-wide simultaneous-FD ceiling
of ~512. Optionally introduce one shared "network FD budget" semaphore that all outbound fetchers
acquire from, so the cap is global rather than per-component.

### G. Crash containment (last resort)

Prevention (A–F) is primary — once the CIO selector loop throws, the server is unrecoverable. But
to avoid a dead backend requiring a manual restart during dev/prod, wrap the process in a
supervised restart (dev run script / container `restart: unless-stopped`) so an unforeseen FD spike
self-heals instead of leaving the frontend in an `ECONNREFUSED` wall.

## Recommended sequencing

- **Minimal hotfix (stops the bleeding):** A (semaphore in `download`) + D (`loading="lazy"`).
  Together these cut both the outbound-FD source and the inbound-request burst.
- **Durable fix:** add B (O(1) lookup + gated miss) + C (scan-time pre-warm) so the runtime path is
  cache-hit-only.
- **Hardening:** E + F + G.

## Scope / files

- `src/linuxX64Main/kotlin/dev/jellystructure/media/LogoDownloader.kt` — semaphore in `download`;
  new `batchFetchPeople`.
- `src/linuxX64Main/kotlin/dev/jellystructure/server/routes/MediaRoutes.kt` — person-image handler
  (`:1334–1358`): O(1) lookup, gated miss, fast 404.
- Scan/sync pipeline (`Scanner.kt` / `Main.kt`) — call `batchFetchPeople` after a scan.
- `src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt` — `loading="lazy"` on person `<img>`.
- `src/linuxX64Main/.../media/ArtworkDownloader.kt`, `tmdb/TmdbClient.kt` — bounded concurrency /
  connection caps (hardening).
- `specs/constitution.md` — record the FD_SETSIZE invariant.

## Verification

1. Reproduce pre-fix: open a series detail with a large Phase-76 cast against a **cold** people
   cache and confirm the FD spike (e.g. watch `ls /proc/<pid>/fd | wc -l` climb toward 1024).
2. Post-fix: same page, cold cache — confirm the FD count stays bounded (well under ~512) and the
   process does not crash; images fill in progressively.
3. Confirm scan-time pre-warm populates `config/artwork/people/*.jpg` so a second load is
   pure cache-hit (no `Downloaded logo:` lines on view).
4. Stress: fire a few hundred concurrent `/api/people/{id}/image` requests (cold) with a load tool
   and confirm they queue rather than crash the selector.

## Non-goals

- Swapping the Native server engine off CIO/`select()` (no epoll-based Ktor Native engine exists).
- Closing the shared singleton `HttpClient`s (correct as-is; they are process-lifetime and reused).
- Raising `ulimit -n` (does not address the FD-number ceiling and can mask the real fix).
