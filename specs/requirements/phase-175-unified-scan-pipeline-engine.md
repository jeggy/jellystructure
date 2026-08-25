# Phase 175 — Unify scan and pipeline into one execution engine; fix the freshness/cooldown bug

> Jellystructure has three independent code paths that all "process library items," and they've
> drifted: a plain library scan (`runScan()`), a scheduled/manual pipeline (`executePipeline()`), and
> realtime webhook ingest (`RealtimeIngestService`). Each has its own hand-written idea of which steps
> run and in what order. Only one of the three ever consults the age-tiered freshness/cooldown setting
> (`recheck_unchanged` + `refresh_this_year`/`refresh_1_5y`/`refresh_older`), so a plain "Scan library"
> click, the startup scan, and a pipeline resume all unconditionally reprocess the entire library every
> time regardless of that setting. Confirmed live against this deployment's own `activity-log.json.runs`:
> the manual button took 567s to fully rescan ~490 items with real TMDB calls; the scheduled pipeline
> (which *does* apply the cooldown) finishes the equivalent window's work in 11–60s. There are also two
> independently hand-coded TMDB-fetch implementations (`Scanner.scanMovie`/`scanSeries`'s inline fetch
> vs. `Scanner.rescanMetadata`, used by the `pull_tmdb` step) that have already visibly diverged — e.g.
> stinger/keyword detection exists in one but not the other.

**Status:** Implemented 2026-08-25 (all 4 stages: engine unification, freshness-filter fix, TMDB-fetch dedup, UI). Not yet live-tested (needs a backend restart + browser check). See STATUS.md row 175 for the full implementation summary, including two deliberate scope-narrowings from this doc's original §8 (the TV_SHOW per-episode TMDB fetch and rescanMetadata's MOVIE-only resolvedLang/stinger extras were left un-unified — both are real product decisions, not mechanical dedup, and out of scope for this pass).

## 1. Problem

Three dispatchers exist where there should be one:

1. **Plain library scan** — `POST /api/scan`, `POST /api/scan/resume`, the `SCAN_ON_START` env var, and
   the scheduler's own fallback when `cfg.scan.pipeline` has no enabled steps — all call `runScan()`
   (`MediaRoutes.kt:2119`) directly, always with `freshnessFilter = null`. It only ever does file
   discovery + `Scanner.scanItem()`'s inline TMDB match (plus artwork gap-fill when `fetchImages=true`)
   — it never writes NFO, never syncs Jellyfin, never runs `detect_segments`/`sync_imdb_ratings`/
   `rescan_arr`/`detect_drift`.
2. **Scheduled/manual pipeline** — `POST /api/pipeline/run` (with a `?full=true` bypass and the Phase
   154 pre-run step-skip dialog) and the scheduler when a pipeline **is** configured — go through
   `executePipeline()` (`Main.kt:397`). It runs `scan_files` (= `runScan()` again, `signalCompletion =
   false`) as step 1, this time with a `freshnessFilter` computed at `Main.kt:430-454` — but **only**
   when `!fullRun && scanStep.recheckUnchanged`. Every subsequent configured step then dispatches
   through its own hand-written `when (step.step)` block (`Main.kt:533-742`) into
   `PipelineStepPool.runPipelineStepPool`.
3. **Realtime/event ingest** (Phase 145) — Sonarr/Radarr/Jellyfin webhooks feed
   `RealtimeIngestService.enqueue()`, which calls `scanner.scanItem()` directly, then its **own**, third
   hand-written `when (step.step)` block (`RealtimeIngestService.kt:149-194`) — which silently falls
   through to `else -> Unit` for `detect_drift`, `wait`, and `notify` (an accidental gap: `detect_drift`
   simply has no callable extracted body to dispatch to; `wait`/`notify` were never considered for the
   single-item case at all).

Consequences of the split:

- **The freshness/cooldown setting is invisible to 5 of 6 trigger call sites.** Only "run a configured
  pipeline" honors it; a plain "Scan library" click — the more discoverable, more commonly used action —
  never does, no matter how the cooldown is configured.
- **Two TMDB-fetch implementations exist and have already diverged.** `scanMovie`/`scanSeries`
  (`Scanner.kt:256-938`) inline a search→details→credits→certs→trailer fetch; `rescanMetadata`
  (`Scanner.kt:981-1248`, used by the `pull_tmdb` step) independently re-implements the same sequence
  with its own field mapping, and additionally fetches TMDB keywords for stinger detection — a real
  behavior difference the movie-scan path doesn't have.
- **Realtime ingest silently skips steps** a configured pipeline would run for the same item.
- **No admin-visible reason for "why didn't item X show up."** `runScan()` already classifies every
  Jellyfin item that produced no stored item (`scanner.classifySkip(it)`, `MediaRoutes.kt:2314`) but
  buckets `no-matching-library`/`unsupported-type`/`no-path` as "expected" and only ever counts them —
  never names them — while "unexpected" reasons get a per-item `Logger.warn`. Confirmed live: the last
  full scan in this deployment logged `490 stored, 3 skipped (no-matching-library=3)` with no way, short
  of grepping raw logs (which themselves get evicted quickly by chatty runs — see §7), to see which 3
  items those were.

## 2. Goals / non-goals

**Goals**
- One execution engine, used by every trigger, so "scan" and "pipeline" really are the same thing.
- One step-dispatch table, so a step's behavior for a bulk run and a realtime single-item run can never
  diverge again.
- The freshness/cooldown filter applies uniformly to every library-wide trigger, including the manual
  "Scan library" button — with an explicit "full rescan" bypass always available.
- One TMDB-fetch implementation, used by both the initial-match path and the `pull_tmdb` step.
- Make "expected" per-item skip reasons (starting with `no-matching-library`) visible by name, not just
  by count.

**Non-goals**
- No change to *when* the scheduler fires (Phase 166's cron parser is untouched).
- No change to detection/matching algorithms themselves (TMDB search ranking, ffprobe parsing, segment
  detection) — this is purely an execution-path unification.
- No flip of `recheck_unchanged`'s default (stays `false` — opt-in, just honored uniformly once on).
- Stage-3b-style redundant-fetch elimination (skipping `pull_tmdb`'s second TMDB call for an item
  `scan_files` just matched in the same run) is **not** attempted here — see §8.
- No change to the activity-log ring buffer's capacity/eviction policy (§7's TMDB-retry-spam finding is
  noted, not fixed, in this phase).

## 3. `RunTarget` / `effectivePipeline()` / `PipelineDeps`

New `media/PipelineEngine.kt`:

```kotlin
sealed interface RunTarget {
    data class Library(val libraryJellyfinId: String? = null) : RunTarget
    data class SingleItem(val jellyfinId: String) : RunTarget   // realtime ingest
}

class PipelineDeps(
    val store: MediaStore, val scanner: Scanner, val broadcaster: WsBroadcaster,
    val configStore: ConfigStore, val jellyfinClient: JellyfinClient,
    val scanDispatcher: CoroutineDispatcher, val artworkDownloader: ArtworkDownloader?,
    val arrRescan: ArrRescanService?, val sonarrEnrich: SonarrEnrichService?,
    val imdbClient: dev.jellystructure.imdb.ImdbClient?,
    val mediaSegmentStore: MediaSegmentStore, val mediaJobQueue: MediaJobQueue?,
)

/** The one place every trigger resolves "what steps actually run" when Settings has no pipeline
 *  configured — reproduces today's plain-scan behavior (scan_files + inline TMDB match + gap-fill
 *  artwork), now expressed as steps through the shared engine, so there's no capability regression for
 *  admins who never built a pipeline. */
fun effectivePipeline(cfg: AppConfig): List<PipelineStep> =
    cfg.scan.pipeline.filter { it.enabled }.ifEmpty {
        buildList {
            add(PipelineStep(step = "scan_files"))
            add(PipelineStep(step = "pull_tmdb", scope = "all"))
            if (cfg.behavior.fetchImages) add(PipelineStep(step = "fetch_artwork"))
        }
    }
```

## 4. `runPipeline()` — the single execution engine

```kotlin
suspend fun runPipeline(
    target: RunTarget,
    pipeline: List<PipelineStep>,   // already enabled-filtered + Phase-154 skipSteps applied by the caller
    jobId: String,
    scanTracker: ScanTracker,       // real global tracker for Library; ephemeral non-persisting one for SingleItem
    deps: PipelineDeps,
    fullRun: Boolean = false,
    signalCompletion: Boolean = true,
): List<MediaItem>
```

`scan_files` phase dispatches on `target`:
- `Library` → today's `runScan()` worker-pool body (internals unchanged), fed
  `FreshnessFilter.computeFreshnessFilter(...)` (§5).
- `SingleItem` → fetch the one Jellyfin item + `scanner.scanItem()` (today's
  `RealtimeIngestService.ingestByJellyfinId` body, moved in verbatim).

Every step after `scan_files` runs through one ordered loop calling
`runPipelineStepPool(jobId, step.step, workingSet, ...) { item, report ->
PipelineStepOps.dispatch(step.step, item, step, deps, report) }` for both `Library` and `SingleItem`
targets — this is the literal replacement for all three hand-written `when` blocks.

`executePipeline`, `runScan`'s standalone export, and `RealtimeIngestService.runConfiguredSteps` are
deleted once every trigger (§6) moves onto `runPipeline`.

## 5. Freshness filter — moved, and now consulted by every trigger

`cadenceMs()`/the skip-set builder move verbatim out of `Main.kt:430-454` into new
`media/FreshnessFilter.kt`:

```kotlin
fun cadenceMs(cadence: String): Long?
fun computeFreshnessFilter(
    scanStep: PipelineStep, store: MediaStore, fullRun: Boolean, target: RunTarget,
): ((JellyfinItem) -> Boolean)?
```

Returns `null` (no filtering — process everything) when `fullRun`, `!scanStep.recheckUnchanged`, **or
`target is RunTarget.SingleItem`** — the last is a new, explicit rule: a realtime webhook fired because
*this exact item* just changed in Jellyfin/Sonarr/Radarr, so "is it due for a periodic recheck" doesn't
apply. Called exactly once, inside `runPipeline`'s `scan_files` phase, for every trigger — this is what
makes the cooldown apply to the manual "Scan library" button for the first time.

**Already-safe invariant, unchanged:** the skip-set is built by iterating `store.allItems()` (only
already-known items) to find Jellyfin IDs to *exclude*. An item Jellyfin has that jellystructure doesn't
know about yet can never land in that skip-set — brand-new items are always in the worklist. Verified in
code; this phase does not change that mechanism, only where it's invoked from.

**Phase 95 non-destructive invariant carried forward verbatim:** `flagMissingFromSource` stays computed
from the full, unfiltered `jellyfinItems` list (`MediaRoutes.kt:2286`) and gated on
`libraryJellyfinId == null` only — never from the freshness-narrowed worklist. A filtered run must never
be mistaken for "the whole library" when deciding what's missing.

## 6. Trigger rewiring (6 call sites → one function)

| Trigger | Today | Becomes |
|---|---|---|
| `POST /api/scan` | `runScan(freshnessFilter=null)` | `runPipeline(Library(libId), effectivePipeline(cfg), ..., fullRun = "full" query param)` — **new** `?full=true`, mirroring `/pipeline/run` |
| `POST /api/scan/resume` | `runScan(freshnessFilter=null)` | same shape, `fullRun = true` (unchanged behavior — resume already means "finish the full run") |
| `SCAN_ON_START` | `runScan(freshnessFilter=null)` | `runPipeline(Library(), [scan_files, pull_tmdb(all)], ..., fullRun = true)` |
| `POST /api/pipeline/run` | `executePipeline(...)` | `runPipeline(Library(), effectivePipeline(cfg) minus Phase-154 skipSteps, ..., fullRun = full)` |
| scheduler | branches on empty-vs-configured pipeline | always `runPipeline(Library(), effectivePipeline(cfg), ..., fullRun = false)` |
| realtime ingest (`RealtimeIngestService`) | `scanItem()` + own dispatcher | `runPipeline(SingleItem(jellyfinId), effectivePipeline(cfg), ..., scanTracker = ScanTracker(db, persistToDb = false))` |

## 7. Central step dispatch + `ScanTracker` for single-item runs

`PipelineStepOps` gains one entry point:

```kotlin
enum class StepScope { LIBRARY_ONLY, SINGLE_ITEM_OK }  // wait/notify = LIBRARY_ONLY; everything else SINGLE_ITEM_OK

suspend fun dispatch(step: String, item: MediaItem, cfg: PipelineStep, deps: PipelineDeps, report: suspend (String?) -> Unit)
```

`detect_drift`'s per-item body — currently inline-only in `Main.kt:614-647` — becomes a real, callable
`PipelineStepOps.detectDrift(item, jellyfinClient, cfg): DriftOutcome`. Direct consequence: **realtime
ingest gains `detect_drift` support**, closing the accidental gap from §1. `wait`/`notify` are excluded
from `SingleItem` scope via an explicit `StepScope` check in the engine's step loop — not a silent
fallthrough — with a one-line comment explaining why (a per-webhook 5-minute `wait` or a `notify` firing
on every single realtime ingest would be actively wrong, not just unimplemented).

`runPipelineStepPool` requires a live `ScanTracker`, which today is a DB-row singleton
(`db.scanStateQueries.upsertState`/`getState`, no key param) — unsafe to share across concurrent
realtime ingests. `ScanTracker` gains a constructor flag: `class ScanTracker(private val db:
JellystructureDb, private val persistToDb: Boolean = true)`; every `db.scanStateQueries.*` call is
guarded on `persistToDb` (no-op/empty when false), while the in-memory bits (`targetWorkers`/
`activeWorkers`/`activeItemsMap`/`_stepPlan`/`_activeStep`/`cancelRequested`) are untouched. Realtime
ingest constructs a fresh `ScanTracker(db, persistToDb = false)` per call — never shared — giving it the
same `StepStarted`/`StepProgress`/`StepFinished` WS events as a bulk run without a second writer
touching the single-row `scan_state` table.

**Secondary finding, not fixed here:** the same live investigation found that one manual scan's TMDB
429-retry logging (a chatty `Logger.info` per retry attempt) was enough to evict all history for the ~10
scheduled pipeline runs around it from the 10,000-entry `activity-log.json` ring buffer. Left as a future
follow-up (either de-duplicate retry log lines or size the buffer differently) — out of scope here.

## 8. Shared TMDB-fetch function

Extract `Scanner.fetchTmdbMetadata(existingTmdbId: String?, searchTitle: String, searchYear: Int?,
langPriority: List<String>, isMovie: Boolean, acceptTitleOnly: Boolean): TmdbFetchResult?` covering the
search-or-id → localized details → credits/extIds/certs/trailer/keywords sequence common to
`scanMovie`/`scanSeries`/`scanMusicVideo` and `rescanMetadata`.

- `scanMovie`/`scanSeries`/`scanMusicVideo` build a **fresh** `MediaItem(...)` directly off the returned
  bundle — nothing to merge into yet.
- `rescanMetadata` calls the same function, then applies its **own** merge concerns on top via
  `.copy(...)`: `mergeUserGenres`, `mergeRepullTags`, stinger precedence
  (`item.segments.manuallyConfirmed`), the `tmdbMatchLocked` guard (`Scanner.kt:988-991`, stays exactly
  where it is — runs *before* calling the shared fetch, same as today), `libraryId` self-heal.

This closes the live, already-observed drift (the movie branch's TMDB-keywords/stinger fetch existing
only in `rescanMetadata` today) with one implementation instead of two independently-maintained field
lists. The near-identical per-episode TMDB-details-into-`Episode`-fields mapping (`Scanner.kt:960-972`
vs. `:1092-1104`) gets the same treatment as a low-risk cleanup within the same change.

**Explicitly deferred (not this phase):** eliminating the redundant *second* TMDB fetch when a run's
effective pipeline includes both `scan_files` and `pull_tmdb` for an already-matched item. That's an
efficiency win gated on `pipeline.any { it.step == "pull_tmdb" }` for the run's *actual* effective
pipeline — an admin with a hand-built pipeline that never included `pull_tmdb` has historically relied
on `scan_files`'s unconditional fetch as their only TMDB refresh path, so skipping it there would be a
real regression. Worth a follow-up phase once this one is live and stable, not bundled in here.

## 9. `no-matching-library` (and other "expected" skips) surfaced by name

`runScan`'s skip-summary (`MediaRoutes.kt:2312-2325`) already computes `scanner.classifySkip(it)` per
unmatched Jellyfin item but only names "unexpected" reasons. Add the same per-item detail for the
"expected" bucket — a `skippedDetail: List<Pair<JellyfinItem, String>>` surfaced in the run's Activity
summary (or at minimum a non-buried `Logger.info` line per item, same shape as the existing unexpected
one) — so an admin can see, by name, which items are being silently excluded and why, instead of only a
bucketed count. This uses data the code already computes; it changes visibility, not classification
logic.

## 10. Verification

- `compileKotlinLinuxX64` + `linuxX64Test` after each implementation stage (this repo's convention —
  review alone has previously missed real Kotlin/Native compile errors).
- New `FreshnessFilterTest.kt`: cadence-tier boundary cases (this-year/1-5y/older), `"never"` →
  always-skip, no-`lastChecked`/no-`year`/no-`jellyfinId` → never-skip (documented safe defaults),
  `fullRun`/`SingleItem` → always `null`.
- Manual, backend-only (no TV/device involved): with `recheck_unchanged = true` (already the case in
  this deployment's `scan_files` step), click "Scan library" twice in a row — the second run's processed
  count should drop sharply, and the existing "N item(s) not due for a recheck yet" log line should
  appear for the manual trigger for the first time. Verify `?full=true` still processes everything.
  Verify a realtime webhook for an already-matched item still refreshes it (proves the realtime
  empty-pipeline case wasn't regressed). Check the newly-surfaced `no-matching-library` item names
  against this deployment's real 3 currently-skipped items.
- `compileKotlinWasmJs` clean for the Stage-4 UI change.
