# Phase 228 — The backend grows until the container cap kills it: one pinned object per HTTP request

> The Grafana panel for the `jellystructure` container is a saw-tooth: from every restart the process
> climbs at roughly 3 GB an hour, reaches the 16 GB `mem_limit`, swaps, and dies; `restart: unless-stopped`
> brings it back and the tooth starts again. Nothing in the product's own caches explains 13 GB. The
> memory is Kotlin objects that the garbage collector *cannot* free: every outbound HTTP request made
> through Ktor's Curl engine leaves one `StableRef` behind, and a `StableRef` is a GC root. Ktor fixed it
> yesterday (3.6.0, KTOR-9819). This phase takes the fix, adds the instrumentation that would have found
> it in an hour instead of a day, and records the two smaller things the investigation turned up.

## Status

`Planned` — investigated and spec'd 2026-09-17, not dev-reviewed. The measurements below were taken on
the production container (`v1.18`, up since 2026-09-17 07:06Z) and on three instrumented local instances
run against copies of the production DB and config.

## The finding

### What kind of memory it is

- `docker stats` on the container at 4 h uptime: 13.5 GiB of 16 GiB, one prior restart. The cgroup's
  `memory.stat` said `anon 14.0 GB, file 2.8 GB, kernel 0.36 GB`; `memory.events` showed `max` hit 357 860
  times and 90 MB already in swap. Not page cache — reclaimable file pages would not push the cgroup into
  swap.
- The backend process itself: `RssAnon 13.47 GB, RssFile 23 MB`, 77 threads, 18 135 mappings. `[heap]`
  (glibc `brk`) was 0.5 MB and the 64 MB-aligned glibc arenas held 80 MB in total, so it is not libcurl,
  SQLite or any C-side allocation. 12.9 GB sat in anonymous regions of 256 KB–4 MB: the shape of
  Kotlin/Native's paged allocator, i.e. Kotlin objects.
- Thread churn (a known way to bleed the per-thread allocator pages) was ruled out: every one of the 77
  threads was older than three hours and none appeared or disappeared over a 45 s window.

### What the runtime says from the inside

Kotlin/Native exposes `kotlin.native.runtime.GC.lastGCInfo` (live heap after sweep, kept/swept object
counts, root-set counts). Nothing in the product surfaced it, so the investigation had to build a local
binary with a `memory` block on `/api/health` (FR-228-2) and run it against copies of production's
`config.toml` and `jellystructure.db` with every write-capable integration switched off. Three instances,
paged allocator, debug build:

| instance | workload | live heap after GC | GC root `stableReferences` |
|---|---|---|---|
| refreshers + waveform backfill + artwork presize | everything | 310 MB → 625 MB in 18 min | — |
| refreshers + waveform backfill | prod-like | 296 MB → 452 MB in 4 min | 204 → 1 102 in 200 s |
| detection jobs, Jellyfin **unreachable** | every request fails fast | 289 MB → 518 MB in 2 min | 263 → 2 203 in 100 s |

The live heap (objects the collector *kept*) is what grows, not garbage awaiting a cycle and not
allocator slack: RSS tracked `gc_heap_after_kb` within 60–70 MB throughout. And it grows in step with
the stable-reference root count — 665 new stable references for 320 failed logical requests (each
retried once by phase 194's `HttpRequestRetry`, so one per Curl request), about 4–5 per second on the
connected instance, where the playstate refresher was running at that request rate.

A `StableRef` is a Kotlin object pinned for C code. In this process the only creator of them at scale is
Ktor's Curl client engine.

### The defect, in Ktor 3.5.0's own code

`ktor-client-curl/desktop/src/io/ktor/client/engine/curl/internal/CurlMultiApiHandler.kt`,
`scheduleRequest()` creates three stable references per request:

```kotlin
val responseDataRef = responseData.asStablePointer()   // StableRef<CurlResponseBuilder> → CURLOPT_PRIVATE / HEADERDATA
val responseWrapper = responseBody.asStablePointer()   // StableRef<CurlResponseBodyData>  → CURLOPT_WRITEDATA
val requestWrapper  = setupUploadContent(easyHandle, request)  // StableRef<CurlRequestBodyData> → CURLOPT_READDATA
val requestHolder = RequestHolder(deferred, requestWrapper.asStableRef(), responseWrapper.asStableRef())
```

`RequestHolder.dispose()` disposes the last two. `responseDataRef` is only ever read back with
`fromCPointer()` (a `get`, not a `dispose`) — in `processCompletedEasyHandle`, `processCancelledEasyHandle`
and `collectSuccessResponse` — and `cleanupEasyHandle()` only removes and frees the curl handle. No path
disposes it: not success, not failure, not cancellation, not `close()`. The pinned `CurlResponseBuilder`
references the `CurlRequestData` (URL, headers, attributes, the call's coroutine context) and the response
body object, so the whole per-request graph stays reachable forever.

Ktor's own tracker agrees: **KTOR-9819 "Curl: Requests leak curl_slist and response StableRef"**, affects
3.5.2 and earlier, fix version **3.6.0**, published 2026-09-16. The `curl_slist` half (the C-side request
header list, freed only on normal completion) is the 80 MB in glibc arenas above.

Measured with a 2 000-request probe through the app's own shared `OutboundHttp.client` on 3.5.0 against a
3 KB local endpoint, forcing a full GC every 250 requests: `stableReferences` went 1 → 2 003, live heap
+9 MB (≈4.5 KB per request at that body size). Production requests are larger — a playstate chunk is a
7 KB URL (100 Jellyfin ids) and a ~45 KB JSON body — and at the observed 8–19 requests/s the arithmetic
reproduces the graph.

### Why this workload

Phase 205's `PlaystateCache` fetches every visible id's `UserData` for every recently-seen user every
20 s, 100 ids per request: 9 386 ids ⇒ 94 requests per user per cycle, four users ⇒ up to 19 requests/s,
around the clock, whether or not a TV is on. Add Continue Watching (per user per minute, several fetches
each), the session bridge, artwork proxies, TMDB, Sonarr and the hourly pipeline sweep. Every one of them
leaks one pinned object. The leak predates 205 — the graph has been a saw-tooth for weeks — but 205 is
what made it 3 GB/h.

### The second thing the numbers showed: the collector runs every 0.7 s

On every local instance the auto-tuned `GC.targetHeapBytes` sat at 190–250 MB while the live heap after
sweep was 290–620 MB. The scheduler's target is `heapBytes / targetHeapUtilization(0.5)`, but the
`heapBytes` it tunes from is the allocator's page-level byte counter, which reported roughly a third of
the object total. The result: the trigger (`0.9 × target`) is crossed almost immediately after every
collection, so the GC ran 1.3–1.8 times per second with 60–165 ms stop-the-world pauses, reclaiming a
few tens of MB each time. That is a latency finding, not a memory one — it is the pause every Ravilo
read pays behind — and it is measured, not yet explained. FR-228-4 keeps it observable and bounds what
this phase does about it.

### The third: the waveform envelope copies every chunk

`FfmpegRunner.computeEnvelope` (phase 222) reads ffmpeg's PCM through `fread` into a native 64 KiB
buffer and then `readBytes(n)`-copies each chunk into a fresh Kotlin `ByteArray` for the accumulator to
read once. Each copy is a single-object page; a 20-minute episode is ~300 of them, a film ~1 000. Not a
leak — the probe that ran the routine alone over a 62-minute file ended at 24 MB RSS — but pointless
churn on the lane that runs 9 000 files. FR-228-3 removes it.

## Requirements

**FR-228-1 — Ktor 3.6.0.** `ktor = "3.6.0"` in `gradle/libs.versions.toml` (from 3.5.0). Every module
that consumes the catalog moves together (backend, `ravilo-ui`, `ravilo-web`, `web-static-server`,
`shared`); no per-module override. Verification is FR-228-5's probe on the built test binary, not the
changelog.

**FR-228-2 — `/api/health` carries a `memory` block.** `rss_kb`, `rss_anon_kb`, `swap_kb`, `threads`
from `/proc/self/status`; from `GC.lastGCInfo`: `gc_epoch`, `gc_heap_before_kb`, `gc_heap_after_kb`,
`gc_pause_ms` (both pauses summed), `gc_marked`, `gc_sweep` (`{heap:{swept,kept}, extra:{…}}`),
`gc_roots` (`thread_local`, `stack`, `global`, `stable`), plus the scheduler's current
`gc_target_heap_kb`, `gc_min_heap_kb`, `gc_max_heap_kb`, `gc_utilization`, `gc_trigger_coefficient`,
`gc_autotune`, `gc_interval_ms`. Cheap (one `/proc` read and plain runtime getters, no forced
collection), safe on every probe hit, and the two numbers that decide everything — `rss_kb` against
`gc_heap_after_kb`, and `gc_roots.stable` over time — are the ones Grafana should plot next to the
container graph. The block is `null`-tolerant: before the first collection every GC field is `null`.

**FR-228-3 — Fold the PCM without a per-chunk copy.** `EnvelopeAccumulator` gains a
`feed(CPointer<ByteVar>, n)` overload with the same carry/bucket semantics as `feed(ByteArray)` (which
stays, for the unit tests), and `computeEnvelope` feeds the native buffer directly. Output is
byte-for-byte identical; the existing envelope tests hold.

**FR-228-4 — GC pacing: measure, and bound the intervention.** With the leak gone, the live heap of the
production process is expected to settle in the low hundreds of MB. Record `gc_epoch` deltas and
`gc_pause_ms` from the deployed `/api/health` over an hour before and after. If the collector still
cycles faster than once every few seconds at a stable live heap, the one tunable this phase may set is
`GC.minHeapBytes` (raising the auto-tune floor). It is applied once at startup from the environment —
`JELLYSTRUCTURE_GC_MIN_HEAP_MB`, a positive number of MiB; unset or invalid leaves the runtime's own
default — and logged as the first lines of the process log either way, so the deployed choice lives in
`docker-compose.yml` next to the measurement that justified it and is reversible without a rebuild. No
`targetHeapUtilization`, allocator or page-size changes without their own measurement.

**FR-228-5 — Verification that would catch a regression.** (a) `CurlLeakProbe` stays in
`linuxX64Test` as a manual probe (`CURL_PROBE_URL`, `CURL_PROBE_N`, `CURL_PROBE_MODE=ok|fail|cancel`):
on 3.6.0 `stableReferences` must stay flat across 2 000 requests in every mode. (b) A 20-minute local
run of the upgraded backend against the production DB copy, refreshers on, must hold `gc_heap_after_kb`
flat (no monotonic rise) and `gc_roots.stable` within a constant band. (c) `linuxX64Test` green.

**FR-228-6 — Deploy is one restart, and the memory panel is the acceptance test.** Production is at
15 GB of 16 as this is written and will restart itself within the hour regardless. The deploy batches
this phase with nothing else pending except v1.19's already-published image (224/R252). After it: the
container graph must be flat over a night (allowing the first-hour warm-up), and `/api/health`'s
`gc_roots.stable` must not climb.

## Out of scope

- **The playstate refresher's fan-out.** 94 requests per user every 20 s is the load that made this leak
  fast, and it is a real cost on Jellyfin either way (~1 request per Jellyfin id per 20 s). Whether it
  should page a filtered `/Items` listing instead (phase 208 moved it off `/Users/{id}/Items` for a
  reason that needs re-reading), or refresh only users with a connected device, is its own phase.
- **Allocator changes** (`-Xallocator=std`, `pagedAllocator=false`, `fixedBlockPageSize`). Tried and
  measured: the std allocator ran at 5× the live heap in RSS within a minute (glibc arenas per thread)
  and is a worse fit for this process. `build.gradle.kts` keeps an opt-in `-PnativeAllocator=` for future
  experiments only; the default is unchanged.
- **The session bridge's WebSocket client** (`JellyfinSessionBridge`, its own `HttpClient(Curl)`): the
  same engine, so the same fix applies; nothing to do here beyond FR-228-1.

## Open questions

1. FR-228-4's cause. Why the scheduler's `heapBytes` runs at a third of `totalObjectsSizeBytes` on this
   process (page-level accounting lagging behind objects allocated during concurrent marking is the
   leading guess) — worth a JetBrains issue once the post-fix numbers are in, with the health block as the
   evidence.
2. Whether the 16 GB `mem_limit` should come down once the process is honest again. A cap that a leaking
   process reaches in four hours was hiding the defect, not containing it; at a few hundred MB of live
   heap, 2–4 GB would make the next regression visible in the graph within a day instead of a week.
