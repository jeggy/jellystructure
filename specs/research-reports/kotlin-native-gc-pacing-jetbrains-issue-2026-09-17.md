# Kotlin/Native GC pacing on a server process — draft JetBrains issue (2026-09-17)

> Phase 228's second finding, written up for YouTrack (project KT, Kotlin/Native runtime). This is a
> question-plus-feature-request, not a bug claim: the standalone reproducer below shows the scheduler
> doing what its documentation says, and the surprising numbers from the real process turned out to have
> a measurement component. What remains is real, measured, and worth asking about: a busy server with a
> modest live heap collects more than once a second, the API's "heap after" cannot be compared with the
> tuner's target on such a process, and the only remedy (`GC.minHeapBytes`) needs a code change and a
> rebuild rather than a deploy-time option.

## Status

**Standalone reproducer: `~/IdeaProjects/kotlin-native-gc-pacing`** (its README is the issue text; this file is the
background). It measured, on 2026-09-17: defaults 2.6–2.8 collections/s with 134–140 ms pauses at a 326 MB
resident set (37 % of wall time stopped); `MIN_HEAP_MB=512` no effect (floor below the 648 MB auto target);
`UTILIZATION=0.25` 1.1–1.2/s (17 %); `MIN_HEAP_MB=2048` 0.6–0.7/s (9 %); the pause is the mark of 3.15 M
objects and never moves; `memoryUsageAfter` read 593 MB after a forced collection with the same kept count
that measured 326 MB, matching RSS. The app's "target below live" reading was that field, not the tuner.

Draft. Numbers marked *(prod, v1.20)* are filled from `/api/health` samples of the deployed backend.
Post to <https://youtrack.jetbrains.com/newIssue?project=KT> once the overnight graph confirms the 30-minute picture.

---

## Summary (issue title)

Kotlin/Native GC: on a server process with ~100–300 MB live heap and ~100 MB/s allocation the
adaptive scheduler collects every ~0.6 s (60–165 ms pauses); `lastGCInfo.memoryUsageAfter` includes
objects allocated during the concurrent cycle, so it cannot be compared with `targetHeapBytes`; please
expose the target-heap floor as a binary/runtime option.

## Environment

- Kotlin 2.3.21, `linuxX64` executable (release `-opt` in production, debug locally — same behaviour)
- Runtime defaults: `gc=cms` (concurrent mark & sweep), custom paged allocator, `autotune=true`,
  `targetHeapUtilization=0.5`, `heapTriggerCoefficient=0.9`, `minHeapBytes=5 MiB`, `regularGCInterval=10 s`
- Ktor 3.6.0 (CIO server, Curl client), kotlinx.coroutines 1.11.0, SQLDelight 2.0.2 (native driver)
- Debian bookworm container, Linux 6.12, 77 threads (fixed pools + `Dispatchers.IO`), 16 GB cgroup cap
- Workload: an HTTP API for a household TV client; four background refreshers make ~10–20 outbound
  HTTP requests/s (≈45 KB JSON each, parsed with kotlinx.serialization) around the clock, plus
  ffmpeg pipes read through `fread`.

## What we observed on the real process

`/api/health` exposes `kotlin.native.runtime.GC.lastGCInfo` and the scheduler getters (patch below).
Three instances against copies of the production database, sampled every 30 s:

| sample | `memoryUsageAfter["heap"]` (MB) | `GC.targetHeapBytes` (MB) | epochs / 30 s | pause (first+second, ms) | kept objects | swept objects |
|---|---|---|---|---|---|---|
| instance A, t+30 s | 287 | 192 | 39 | 70–115 | 1 425 124 | 152 237 |
| instance A, t+5 min | 322–354 | 194–205 | 19–22 | 87–92 | 1.38–1.43 M | 1.9–2.6 M |
| instance A, t+15 min | 552–625 | 233–248 | 20–25 | 42–94 | 1.52–1.60 M | 2.4–3.7 M |
| instance B (refreshers only) | 296–472 | ~200 | 20–40 | 43–110 | 1.38–1.50 M | — |

So: the value the API reports as "heap after collection" sat at 1.5–2.5× the auto-tuned target for the
whole run, and the collector ran 0.7–1.3 times per second. Pauses of 60–165 ms every ~0.7 s is 10–20 %
of wall time stopped, on a process whose users are TV remotes waiting for a list.

Production, v1.20 (Ktor's leak fixed, `minHeapBytes` raised to 512 MiB through the patch's env var), first
30 minutes after the restart, sampled every 60 s from `/api/health`:

| | value |
|---|---|
| collections | 8.8 / min (≈ one per 6.8 s) — the same process on the default 5 MiB floor ran ≈ 90 / min |
| pause (first + second) | 44–118 ms, mean 79 ms |
| `memoryUsageAfter["heap"]` | 307–693 MB (oscillating with the cycle, no trend) |
| `targetHeapBytes` | 512 MiB (the floor) |
| RSS | 390–786 MB, no trend |
| `rootSet.stableReferences` | 55–105, flat |
| kept objects per sweep | 1.52–1.57 M, flat |

The floor turned a ~1.5 Hz collector into a ~0.15 Hz one for about 250 MB of extra resident memory —
the trade-off a server operator wants to make, and today can only make from source.

## What a standalone reproducer shows

`GcSchedulerProbe` (below): build a 328 MB live set of 200-byte objects, force two collections, then
churn 2 000 MB of short-lived 4 KB arrays and print `lastGCInfo` each time an unforced collection has
happened.

```
PROBE config autotune=true utilization=0.5 trigger=0.9 minMB=5 maxMB=8796093022207 interval=10s
PROBE after building live set   epoch=8  liveMB=327 targetMB=648 pauseMs=189 kept=3149868
PROBE second forced collect     epoch=9  liveMB=328 targetMB=648 pauseMs=189 kept=3149869 swept=443
PROBE churn 600 MB              epoch=10 liveMB=652 targetMB=648 pauseMs=166 kept=3149902 swept=66968
PROBE churn 1200 MB             epoch=12 liveMB=628 targetMB=648 pauseMs=179 kept=3149902 swept=67196
PROBE churn 1800 MB             epoch=15 liveMB=613 targetMB=648 pauseMs=178 kept=3149902 swept=72027
PROBE final forced collect      epoch=17 liveMB=402 targetMB=648 pauseMs=213 kept=3149905 swept=19052
```

Two things follow:

1. **The tuner is doing what the docs say.** Target = 648 MB ≈ 2 × the 328 MB live set (utilisation
   0.5), and the churn triggered a collection every ~600 MB allocated (0.9 × 648 − 328 ≈ 255 MB of new
   allocation per cycle… the probe only samples every 100 MB, so the granularity is coarse). No anomaly.
2. **`memoryUsageAfter["heap"].totalObjectsSizeBytes` is not "live after collection".** After an
   *unforced* cycle it reads 612–652 MB with a 328 MB live set: it includes what the mutator allocated
   while the concurrent cycle ran (allocated-black objects survive that cycle by construction). A forced
   `GC.collect()` from a quiescent thread reads 402 MB. On a process that allocates ~100 MB/s and
   collects every 0.7 s, "heap after" therefore over-reads the live set by hundreds of MB, which is
   exactly the gap we first mistook for "target below live".

So the real-process picture is consistent with: true live ≈ 100–150 MB, target ≈ 200–250 MB, allocation
≈ 100–150 MB/s ⇒ one collection per ~0.6 s. Which is the documented policy — and, for a server, a poor
one: pause frequency scales with allocation rate divided by live heap, and a lean server with a small
resident model and heavy request churn is the worst case.

## Questions / requests

1. **API clarity.** Please document that `GCInfo.memoryUsageAfter` includes objects allocated during
   the concurrent cycle, and consider adding the number the tuner actually used (the live/marked bytes
   at the end of marking) to `GCInfo`, so a process can report "live heap" honestly without forcing a
   stop-the-world `GC.collect()` from a request thread.
2. **A deploy-time floor.** `GC.minHeapBytes` is the right knob (raising it to 512 MiB cut our collection
   rate ~4× for ~250 MB more RSS) but it is only settable from Kotlin code at startup. A binary option
   (`-Xbinary=gcMinHeapBytes=…` / `kotlin.native.binary.gcMinHeapBytes`) or a runtime environment
   variable would let operators tune it per deployment, the way JVM users tune `-Xms`/`-Xmn`. We wired
   an env var ourselves (`JELLYSTRUCTURE_GC_MIN_HEAP_MB` → `GC.minHeapBytes`), but every Kotlin/Native
   server author has to rediscover this.
3. **Server-shaped defaults.** With `targetHeapUtilization = 0.5` and a 5 MiB floor, a process whose live
   heap is small relative to its allocation rate collects continuously. Would a time-based pacing term
   (do not collect more often than every N ms unless the heap exceeds a hard bound) or a higher default
   floor on non-mobile targets be considered?

## Attachments to include

- The `/api/health` `memory` block implementation (`MemoryStats.kt`, ~90 lines) — reads `/proc/self/status`
  and `GC.lastGCInfo`, no forced collection.
- `GcSchedulerProbe.kt` (below) and its output above.
- Thirty-minute `/api/health` samples from the production process before and after the floor.

### `GcSchedulerProbe.kt`

See `src/linuxX64Test/kotlin/dev/jellystructure/ops/GcSchedulerProbe.kt` in this repository (runs only
with `GC_PROBE=1`; `GC_PROBE_LIVE_MB`, `GC_PROBE_CHURN_MB`).

## Internal note (not for the issue)

The reproducer's arithmetic says the application allocates on the order of 100–150 MB/s at rest
(2–3.7 M objects swept per cycle, cycles every 0.6–0.7 s). That is the refreshers' JSON parsing plus
whatever else churns; it is the *other* lever on GC frequency and deserves its own look (phase 228 out
of scope: the playstate refresher's 94 requests per user per 20 s).
