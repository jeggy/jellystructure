# Phase 169 — `syncSeriesEpisodes` probes episode files concurrently, matching `scanSeries` (FR-SCAN4)

> Follow-up from the 2026-07-15 `scan_files` parallelism work (`project-phase-perf` session): that pass
> parallelized `scanSeries`'s per-episode ffprobe/TMDB work across a bounded worker set (`ProcessGate`/
> `OutboundHttp` already gate concurrency, so no new bound was needed) so a big show no longer
> monopolizes one scan worker slot for its full sequential probe time. `syncSeriesEpisodes` — the
> parallel code path used for a targeted per-item "Sync" (re-probe + re-pull one series without a full
> library scan) — was never given the same treatment and still probes every episode file one at a time.

**Status:** Implemented 2026-08-21.

## 1. Problem

`Scanner.syncSeriesEpisodes()` (`Scanner.kt`) loops its episode files sequentially:

```kotlin
for (file in episodeFiles) {
    val tracks = FfprobeRunner.probe(file)
    // ... per-file TMDB episode fetch, chapters, etc.
}
```

`scanSeries()` in the same file does the equivalent work concurrently, dispatching every file's probe
+ per-episode TMDB fetch as an `async` under one `coroutineScope`, bounded by the existing
`ProcessGate` (ffprobe/ffmpeg process slots) and `OutboundHttp` (TMDB request semaphore) gates rather
than a new worker-count knob:

```kotlin
val episodes = coroutineScope {
    filesToProbe.map { file -> async { /* probe + build Episode */ } }.awaitAll().flatten()
}
```

For a large show, the manual "Sync" button (`POST /api/media/{id}/sync`, `MediaRoutes.kt`, scope
`"episodes"`) takes as long as the old pre-parallelism `scanSeries` did — every file's ffprobe call and
every episode's TMDB fetch run back-to-back on one coroutine, monopolizing one scan-step worker slot for
the full duration instead of spreading across the same gates the rest of the pipeline already respects.

## 2. Fix (FR-SCAN4-1)

Rewrite `syncSeriesEpisodes`'s per-file loop to the same `coroutineScope { filesToProbe.map { async {
} } }.awaitAll()` shape `scanSeries` already uses — same bounding gates, no new concurrency knob. The
existing per-file body (ffprobe, chapter markers, per-part episode-number resolution via
`resolveSeasonEpisode`, existing-episode match by `(season, episode)`, per-episode TMDB fetch +
guest/crew credits) moves inside the `async` block unchanged; only the dispatch shape changes from
sequential to concurrent-and-collected.

## 3. Non-goals

- No change to `scanSeries` itself (already correct, this phase's reference implementation).
- No change to the `scan_files` bulk pipeline step, `scanMovie`, or `syncMovie` — none of them have
  this sequential-per-file pattern.
- No new config surface — reuses the existing `ProcessGate`/`OutboundHttp` bounds exactly as
  `scanSeries` does.

## 4. Verification

- `compileKotlinLinuxX64` — confirms the rewrite type-checks against `Episode`'s existing constructor.
- `linuxX64Test` — existing scanner test suite stays green (no behavioral change to output, only to
  dispatch order/timing).
- Manual: trigger "Sync" on a large series (50+ episodes) and confirm it completes in roughly the same
  wall-clock time as an equivalent `scanSeries` run, not several times longer — left for the user
  (requires a live library + backend restart to observe, out of scope for this session per standing
  preference).
