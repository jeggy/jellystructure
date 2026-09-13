package dev.jellystructure.media

import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.arr.SonarrEnrichService
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.imdb.ImdbClient
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.log.RunContext
import dev.jellystructure.model.MediaItem
import dev.jellystructure.nfo.NfoWriter
import dev.jellystructure.nowEpochSec
import dev.jellystructure.server.routes.fireWebhook
import dev.jellystructure.server.routes.runScan
import kotlin.concurrent.AtomicInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Phase 175 — what a [runPipeline] call is processing: the whole library (optionally scoped to one
 * Jellyfin library, optionally resuming a cancelled run), or exactly one already-scanned item (the
 * realtime/webhook ingest path — the caller has already done the equivalent of `scan_files`/`pull_tmdb`
 * for it via [Scanner.scanItem], so [runPipeline] only runs the steps after those two for it). The
 * freshness/cooldown filter only ever applies to [Library] — a [SingleItem] run exists specifically
 * because that one item just changed, so "is it due for a periodic recheck" doesn't apply (see
 * FreshnessFilter.kt).
 */
sealed interface RunTarget {
    data class Library(val libraryJellyfinId: String? = null, val resumeSkipIds: Set<String> = emptySet()) : RunTarget
    data class SingleItem(val item: MediaItem) : RunTarget
}

/** Bundles every collaborator [runPipeline] needs, replacing the long positional parameter list
 *  `executePipeline`/`runScan` used to carry individually. [arrRescan] is nullable — not every server
 *  wiring (e.g. a *arr-less install) has one configured. */
class PipelineDeps(
    val store: MediaStore,
    val scanner: Scanner,
    val broadcaster: WsBroadcaster,
    val configStore: ConfigStore,
    val jellyfinClient: JellyfinClient,
    val scanDispatcher: CoroutineDispatcher,
    val artworkDownloader: ArtworkDownloader,
    val arrRescan: ArrRescanService? = null,
    val sonarrEnrich: SonarrEnrichService? = null,
    val imdbClient: ImdbClient? = null,
    val mediaSegmentStore: MediaSegmentStore,
    val mediaJobQueue: MediaJobQueue,
    // Phase 181 — every RunTarget.Library run converges on Jellyfin's actual catalog (the set-difference
    // sweep, FR-181-1) before the (predictive) freshness filter runs, and retries anything a prior
    // ingest attempt gave up on (the persistent dirty-set, FR-181-5). Required, not defaulted, so a new
    // PipelineDeps construction site can't silently opt out of the backstop this phase exists to add.
    val realtimeIngest: RealtimeIngestService,
    val mediaHistory: MediaHistory,
    val dirtyItemStore: DirtyItemStore,
)

/**
 * The one place every trigger resolves "what steps actually run" when Settings has no pipeline
 * configured (`cfg.scan.pipeline` empty/all-disabled) — reproduces today's plain-scan behavior
 * (`scan_files` + an unconditional inline TMDB match + gap-fill artwork when enabled), now expressed as
 * steps through the shared engine, so there's no capability regression for admins who never built a
 * pipeline in Settings.
 */
/**
 * Phase 178 §FR-178-4 — "Run anyway": a one-run, not-written-to-config override for a run currently
 * sitting in [awaitPlaybackClear] (matching Phase 154's established "pre-run choice, not a config
 * change" pattern, just applied to a run already waiting rather than one about to start). Deliberately
 * an in-memory set, not persisted — a jobId is only ever meaningful while its run is live.
 */
object PipelineDeferOverride {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val forced = mutableSetOf<String>()

    suspend fun runAnyway(jobId: String) = mutex.withLock { forced.add(jobId) }

    /** Consumes the override (at most once per request) — called from [awaitPlaybackClear]'s poll loop. */
    suspend fun consume(jobId: String): Boolean = mutex.withLock { forced.remove(jobId) }
}

/**
 * Phase 178 §FR-178-2 — waits out an active playback before letting a deferrable step/run proceed,
 * broadcasting [JobEvent.Deferred] once when it starts waiting (not on every poll) and [JobEvent.Resumed]
 * once it clears, so the dashboard's ambient dock (FR-178-4) can show "Paused — TV is watching {name}"
 * instead of looking stalled. Governed by `[pipeline] defer_while_playing` (checked by the caller via
 * [deferEligible] — this function doesn't re-read config so a mid-wait config flip takes effect on the
 * NEXT deferral check, not by aborting one already in progress). A no-op (returns immediately) when
 * [deferEligible] is false or nothing is playing.
 */
private suspend fun awaitPlaybackClear(deferEligible: Boolean, jobId: String, broadcaster: WsBroadcaster, scanTracker: ScanTracker) {
    if (!deferEligible) return
    if (!dev.jellystructure.tv.isPlaybackActive()) return
    var announced = false
    while (dev.jellystructure.tv.isPlaybackActive()) {
        if (PipelineDeferOverride.consume(jobId)) break  // FR-178-4 "Run anyway"
        if (!announced) {
            val devices = dev.jellystructure.tv.activePlaybackDeviceNames()
            Logger.info("Pipeline deferred — TV playing (${devices.joinToString(", ")})", "pipeline")
            broadcaster.broadcast(JobEvent.Deferred(jobId, devices))
            // Bug fix (2026-09-05) — JobEvent.Deferred fires once, live, over the WS. A client that
            // loads/reconnects after this moment (the common case, per phase-178's live incident: this
            // household's kids-show marathons hold a TV "playing" for hours) polled GET /scan/status and
            // saw only a generic RUNNING/0-items state forever, with no way to discover it was waiting on
            // a TV or that "Run anyway" existed. Persisted here so status() can reconstruct it.
            scanTracker.setDeferred(devices)
            announced = true
        }
        delay(2_000L)  // short enough that "Run anyway" feels immediate
    }
    if (announced) {
        scanTracker.clearDeferred()
        broadcaster.broadcast(JobEvent.Resumed(jobId))
    }
}

fun effectivePipeline(cfg: AppConfig): List<PipelineStep> =
    cfg.scan.pipeline.filter { it.enabled }.ifEmpty {
        buildList {
            add(PipelineStep(step = "scan_files"))
            add(PipelineStep(step = "pull_tmdb", scope = "all"))
            if (cfg.behavior.fetchImages) add(PipelineStep(step = "fetch_artwork"))
        }
    }

/**
 * Phase 175 — the single execution engine, replacing the old `executePipeline` (Main.kt),
 * `runScan`'s standalone route-level use, and `RealtimeIngestService.runConfiguredSteps` (three
 * independently hand-written step dispatchers). Every trigger — the manual "Scan library"/"Run pipeline"
 * buttons, scan resume, `SCAN_ON_START`, the scheduler, and realtime webhook ingest — now funnels through
 * this one function and one step loop, so a step's behavior can never silently diverge between a bulk
 * run and a single-item run again, and the freshness/cooldown filter (§ FreshnessFilter.kt) is honored
 * uniformly by every [RunTarget.Library] trigger instead of only a configured pipeline run.
 *
 * For [RunTarget.Library], `scan_files` runs (via the existing [runScan] worker-pool implementation,
 * unchanged) to discover/match items, then every other step in [pipeline] runs over the resulting
 * working set through [runPipelineStepPool]. For [RunTarget.SingleItem], the caller ([RealtimeIngestService])
 * has already done the `scan_files`+TMDB-match equivalent via [Scanner.scanItem] — `scan_files` and
 * `pull_tmdb` are always skipped here for that target (re-running `pull_tmdb` would be a wasted second
 * TMDB fetch for the same item in the same run), and `wait`/`notify` are skipped too — a per-webhook
 * 5-minute wait or a notify firing on every single realtime ingest would be actively wrong, not just
 * unimplemented, so this is an explicit rule, not a silent fallthrough.
 */
suspend fun runPipeline(
    target: RunTarget,
    pipeline: List<PipelineStep>,
    jobId: String,
    scanTracker: ScanTracker,
    deps: PipelineDeps,
    fullRun: Boolean = false,
    signalCompletion: Boolean = true,
    // Phase 178 §FR-178-2 — true for a scheduled or event-driven (realtime ingest) run; false for an
    // operator-initiated one (a manual click, SCAN_ON_START), which per the phase's invariant is never
    // silently deferred. Gates scan_files' probe-heavy work (the whole run start, for RunTarget.Library)
    // and the fetch_artwork step; detect_segments defers separately at its own queue (see
    // MediaJobParams.deferWhilePlaying / MediaJobQueue.segmentsWorkerLoop).
    deferEligible: Boolean = false,
): List<MediaItem> {
    val store = deps.store
    val scanner = deps.scanner
    val broadcaster = deps.broadcaster
    val configStore = deps.configStore
    val jellyfinClient = deps.jellyfinClient
    val scanDispatcher = deps.scanDispatcher
    val artworkDownloader = deps.artworkDownloader
    val arrRescan = deps.arrRescan
    val sonarrEnrich = deps.sonarrEnrich
    val imdbClient = deps.imdbClient
    val mediaSegmentStore = deps.mediaSegmentStore
    val mediaJobQueue = deps.mediaJobQueue
    val realtimeIngest = deps.realtimeIngest
    val mediaHistory = deps.mediaHistory
    val dirtyItemStore = deps.dirtyItemStore

    val scanStep = pipeline.firstOrNull { it.step == "scan_files" } ?: PipelineStep(step = "scan_files")

    Logger.info("Pipeline starting: ${pipeline.joinToString(" → ") { it.step }}${if (fullRun) " (full — no freshness filter)" else ""}")

    // Bug fix (2026-07-03, preserved verbatim from executePipeline): runScan's own completion
    // (scanTracker.complete(), which resets activeWorkers to 0 and flips running=false) must NOT fire
    // after just the scan_files sub-step — it previously did, so a second click could race to a false
    // "already running" 409 and the Activity page's worker count froze at 0/N for the rest of the run
    // while later steps were still actually going. This function signals completion itself, once, after
    // every step has truly finished (or on the early "nothing to do" return right below).
    suspend fun signalPipelineComplete(items: List<MediaItem>) {
        if (scanTracker.cancelRequested) {
            broadcaster.broadcast(JobEvent.Finished(jobId, items.size, 0))
            return
        }
        scanTracker.complete()
        broadcaster.broadcast(JobEvent.Finished(jobId, items.size, 0))
        val cfg = configStore.current
        if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()) {
            jellyfinClient.triggerLibraryRefresh(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken)
        }
        if (cfg.behavior.notifyOnScanDone)
            fireWebhook(cfg, """{"event":"scan_complete","jobId":"$jobId","items":${items.size}}""")
        if (cfg.behavior.notifyOnNoMatch) {
            val unmatched = items.count { it.tmdbId == null && it.kind != dev.jellystructure.model.MediaKind.MUSIC_VIDEO }
            if (unmatched > 0)
                fireWebhook(cfg, """{"event":"no_tmdb_match","jobId":"$jobId","unmatched":$unmatched}""")
        }
    }

    val orderedSteps = listOf("scan_files") + pipeline.filter { it.step != "scan_files" }.map { it.step }
    scanTracker.setStepPlan(orderedSteps)
    broadcaster.broadcast(JobEvent.PipelinePlan(jobId, orderedSteps))
    scanTracker.setActiveStep("scan_files")

    // Phase 178 §FR-178-2 — defer the whole run's start (covers scan_files' probe-heavy work, which
    // isn't itself a skippable step — see RunTarget.Library below) rather than starting it only to have
    // it compete with a TV for disk I/O the moment it begins.
    awaitPlaybackClear(deferEligible, jobId, broadcaster, scanTracker)

    val workingSet: List<MediaItem> = when (target) {
        is RunTarget.Library -> {
            // Phase 181 (FR-181-1/FR-181-1a/FR-181-5) — converge on Jellyfin's actual catalog before the
            // (predictive) freshness filter runs, so a title stuck in a slow recheck tier can never fully
            // hide a file Jellyfin already has (the Fjollerne incident this phase exists for). Cheap enough
            // (~0.5s / ~7MB for this library's ~8k items) to run on every Library trigger uniformly,
            // rather than trying to give it its own, separately-reasoned-about cadence.
            runCatching {
                val sweep = sweepJellyfinLibrary(configStore, jellyfinClient, store)
                if (sweep != null) {
                    if (sweep.missingIds.isNotEmpty() || sweep.staleTopLevelIds.isNotEmpty()) {
                        Logger.info(
                            "Library sweep: ${sweep.scannedCount} Jellyfin items scanned, " +
                                "${sweep.missingIds.size} missing, ${sweep.staleTopLevelIds.size} stale",
                            "ingest",
                        )
                    }
                    sweep.missingIds.forEach { realtimeIngest.enqueue(it) }
                    // FR-181-1a — surfaced for review on the item's own History tab, never auto-removed
                    // (Phase 95's non-destructive invariant).
                    for (staleId in sweep.staleTopLevelIds) {
                        store.resolveByJellyfinId(staleId)?.let { item ->
                            mediaHistory.record(
                                item.id, "jellyfin_missing",
                                "Jellyfin no longer reports this item (jellyfinId=$staleId) — check for a removal or re-import",
                            )
                        }
                    }
                }
                // FR-181-5 — anything a prior ingest attempt exhausted its retries on gets one more try
                // every Library cycle, instead of being forgotten the moment the in-memory retry gave up.
                //
                // Phase 195 (FR-195-1/FR-195-5) — bounded, and deliberately AFTER the sweep's own
                // enqueues above so a genuinely new episode never queues behind the backlog. This used
                // to be `dirtyItemStore.all().forEach { enqueue(it) }`: 117 ids dispatched at once, each
                // fanning out one ffprobe waiter per episode file, into a gate with 12 background
                // permits and a 30 s deadline. Nothing completed, everything was re-marked dirty, and
                // the next cycle did it again — the set had not drained in 12 hours.
                val drainLimit = configStore.current.behavior.scanWorkers.coerceIn(1, 100) * 2
                val now = store.nowMs()
                val dueCount = dirtyItemStore.countDue(now)
                val batch = dirtyItemStore.due(now, drainLimit)
                if (batch.isNotEmpty()) {
                    // No silent caps (Phase 183's own rule): say what was attempted AND what was left,
                    // or a capped pass reads as "we tried everything" when it did not.
                    Logger.info(
                        "Retrying ${batch.size} of $dueCount due previously-failed ingest(s) " +
                            "(${dirtyItemStore.count()} outstanding in total)",
                        "ingest",
                    )
                    batch.forEach { realtimeIngest.enqueue(it) }
                }
            }.onFailure { Logger.warn("Library sweep failed: ${it.message}", "ingest") }

            val freshnessFilter = computeFreshnessFilter(scanStep, store, fullRun, target)
            val result = withContext(RunContext(jobId, "scan_files")) {
                runScan(
                    jobId, target.resumeSkipIds, store, scanner, scanTracker, broadcaster,
                    configStore, jellyfinClient, scanDispatcher, target.libraryJellyfinId,
                    freshnessFilter = freshnessFilter, artworkDownloader = null, signalCompletion = false,
                )
            }
            sonarrEnrich?.enrichAll()
            result
        }
        // scan_files/pull_tmdb equivalent already done by the caller's Scanner.scanItem() call.
        is RunTarget.SingleItem -> listOf(target.item)
    }

    if (workingSet.isEmpty()) {
        Logger.info("Pipeline scan_files: no items in working set, skipping action steps")
        signalPipelineComplete(workingSet)
        return workingSet
    }

    Logger.info("Pipeline scan_files complete: ${workingSet.size} items in working set")

    // Phase 201, 2026-09-13 amendment — the operator asked for MKV structure corruption to be found by
    // the scan that already walks every file, not discovered by a viewer hitting play. Deliberately
    // `store.allItems()`, not `workingSet` — an incremental/freshness-filtered run's working set can be
    // a small subset of the library, and MkvHealthCache.refresh *replaces* its cached map wholesale, so
    // refreshing against only the items just scanned would wipe cache entries for every broken file this
    // run didn't touch. The full-library sweep is the same one the on-demand routes already run and is
    // cheap (FR-201-6: well under a minute for the whole production library). Same
    // runCatching-swallow-and-log posture as sonarrEnrich above it — this must never fail the scan.
    runCatching { MkvHealthCache.refresh(store.allItems()) }
        .onFailure { Logger.warn("MKV structure sweep failed: ${it.message}", "scan") }

    val cfg = configStore.current
    try {
    for (step in pipeline) {
        if (step.step == "scan_files") continue
        // A realtime single-item run already matched TMDB via scanItem() — running pull_tmdb again
        // would be a wasted second fetch for the same item in the same run.
        if (target is RunTarget.SingleItem && step.step == "pull_tmdb") continue
        // A per-webhook 5-minute wait, or a notify firing on every single realtime ingest, would be
        // actively wrong — not just unimplemented — so these are explicitly excluded for SingleItem.
        if (target is RunTarget.SingleItem && (step.step == "wait" || step.step == "notify")) continue
        withContext(RunContext(jobId, step.step)) {
        Logger.info("Pipeline step: ${step.step}")
        when (step.step) {
            "pull_tmdb" -> {
                val toProcess = if (step.scope == "all") workingSet
                    else workingSet.filter { it.tmdbId == null }
                Logger.info("pull_tmdb: ${toProcess.size} items (scope=${step.scope})")
                runPipelineStepPool(
                    jobId, step.step, toProcess, { pipelineStepConcurrency(step.step, configStore.current.behavior.scanWorkers) },
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item, _ -> PipelineStepOps.pullTmdb(item, scanner, store, dirtyItemStore) }
            }
            "fetch_artwork" -> {
                // Phase 178 §FR-178-2 — re-checked here (not just at the run's start above): playback
                // may have started after this run began but before its turn came.
                awaitPlaybackClear(deferEligible, jobId, broadcaster, scanTracker)
                val toProcess = if (step.scope == "all") workingSet
                    else workingSet.filter { artworkDownloader.isArtworkIncomplete(it) }
                Logger.info("fetch_artwork: ${toProcess.size} items (scope=${step.scope})")
                runPipelineStepPool(
                    jobId, step.step, toProcess, { pipelineStepConcurrency(step.step, configStore.current.behavior.scanWorkers) },
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item, _ -> PipelineStepOps.fetchArtwork(item, store, artworkDownloader) }
            }
            "prewarm_subtitles" -> {
                // Phase 178 §FR-178-2 — same re-check fetch_artwork already does: this step hits
                // Jellyfin's own ffmpeg extraction, real disk/CPU work on the same media files a TV
                // might now be reading.
                awaitPlaybackClear(deferEligible, jobId, broadcaster, scanTracker)
                val warmed = AtomicInt(0)
                Logger.info("prewarm_subtitles: ${workingSet.size} items")
                runPipelineStepPool(
                    jobId, step.step, workingSet, { pipelineStepConcurrency(step.step, configStore.current.behavior.scanWorkers) },
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item, _ -> repeat(PipelineStepOps.prewarmSubtitles(item, jellyfinClient, cfg)) { warmed.incrementAndGet() } }
                Logger.info("prewarm_subtitles: ${warmed.value} subtitle stream(s) warmed")
            }
            "write_nfo" -> {
                val serverUrl = cfg.apiKeys.jellyfinUrl
                val written = AtomicInt(0)
                val unchanged = AtomicInt(0)
                val foreignSkipped = AtomicInt(0)
                // A realtime single-item run also (re)writes each of a fresh series' episode NFOs —
                // matches the write-through pushToJellyfin path so an event-ingested series' new
                // episodes get theirs. The bulk run leaves this false (series-only, plus its own
                // unresolved-episode repair pass inside writeNfo), preserving its existing behavior.
                val includeEpisodes = target is RunTarget.SingleItem
                runPipelineStepPool(
                    jobId, step.step, workingSet, { pipelineStepConcurrency(step.step, configStore.current.behavior.scanWorkers) },
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item, _ ->
                    when (PipelineStepOps.writeNfo(
                        item, store, serverUrl, cfg.metadata.ageRatingCascade,
                        allowForeign = step.overwrite || cfg.behavior.overwriteNfo,
                        includeEpisodes = includeEpisodes,
                    )) {
                        PipelineStepOps.NfoResult.WRITTEN -> written.incrementAndGet()
                        PipelineStepOps.NfoResult.UNCHANGED -> unchanged.incrementAndGet()
                        PipelineStepOps.NfoResult.FOREIGN_SKIPPED -> foreignSkipped.incrementAndGet()
                    }
                }
                Logger.info("write_nfo: ${written.value} written, ${unchanged.value} unchanged" +
                    if (foreignSkipped.value > 0) ", ${foreignSkipped.value} foreign NFO(s) skipped (set overwrite to replace)" else "")
            }
            "sync_jellyfin" -> {
                // Phase 193 (FR-193-2) — was scoped to workingSet ∩ unsynced, but workingSet is the
                // freshness-filtered scan_files output (bounding the EXPENSIVE work: probing files,
                // calling TMDB). An item this pipeline already wrote an NFO for and never synced isn't
                // speculative extra work — it's the completion of work already begun, and shouldn't wait
                // up to 6 months for a freshness window that has nothing to do with it. Bounded by
                // construction: this set can only contain items a previous run's write_nfo already
                // touched, and each item leaves it the moment its refresh succeeds.
                val toSync = store.allItems().filter { (it.nfoWrittenAt ?: 0L) > (it.jfSyncedAt ?: 0L) && !it.jellyfinId.isNullOrBlank() }
                val jellyfinReady = cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()
                Logger.info("sync_jellyfin: ${toSync.size} item(s) have unsynced NFO changes (library-wide, not just this run's working set)")
                runPipelineStepPool(
                    jobId, step.step, if (jellyfinReady) toSync else emptyList(),
                    { pipelineStepConcurrency(step.step, configStore.current.behavior.scanWorkers) }, scanTracker, broadcaster, labelOf = { it.title },
                ) { item, _ -> PipelineStepOps.syncJellyfin(item, store, jellyfinClient, cfg) }
            }
            "rescan_arr" -> {
                Logger.info("rescan_arr: ${workingSet.size} items")
                if (arrRescan != null) {
                    runPipelineStepPool(
                        jobId, step.step, workingSet, { pipelineStepConcurrency(step.step, configStore.current.behavior.scanWorkers) },
                        scanTracker, broadcaster, labelOf = { it.title },
                    ) { item, _ -> arrRescan.nudge(item) }
                } else {
                    scanTracker.setActiveStep(step.step)
                    broadcaster.broadcast(JobEvent.StepStarted(jobId, step.step, 0))
                    broadcaster.broadcast(JobEvent.StepFinished(jobId, step.step, "no arr configured"))
                }
            }
            "detect_drift" -> {
                val converged = AtomicInt(0); val nfoStale = AtomicInt(0)
                val jfBehind = AtomicInt(0); val external = AtomicInt(0)
                runPipelineStepPool(
                    jobId, step.step, workingSet, { pipelineStepConcurrency(step.step, configStore.current.behavior.scanWorkers) },
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item, _ ->
                    when (PipelineStepOps.detectDrift(item, store, jellyfinClient, cfg, step.autoReassert)) {
                        PipelineStepOps.DriftOutcome.NFO_STALE -> nfoStale.incrementAndGet()
                        PipelineStepOps.DriftOutcome.JELLYFIN_BEHIND -> jfBehind.incrementAndGet()
                        PipelineStepOps.DriftOutcome.EXTERNAL_DRIFT -> external.incrementAndGet()
                        PipelineStepOps.DriftOutcome.CONVERGED -> converged.incrementAndGet()
                    }
                }
                Logger.info("detect_drift: ${converged.value} converged, ${nfoStale.value} NFO stale, ${jfBehind.value} Jellyfin behind" +
                    (if (step.autoReassert) " (auto-reassert attempted)" else "") + ", ${external.value} external drift")
                if (external.value > 0 && cfg.behavior.notifyOnDrift) {
                    runCatching { fireWebhook(cfg, """{"event":"drift_detected","pipeline":true,"items":${external.value}}""") }
                        .onFailure { Logger.warn("detect_drift notify webhook failed: ${it.message}") }
                }
            }
            "detect_segments" -> {
                // Phase 150 → 163 → 164: enqueue-only — detection itself runs on the segments lane
                // (MediaJobQueue), off this run's critical path.
                fun missing(itemId: String, episodeKey: String, episodeNumber: Int) =
                    mediaSegmentStore.getSegment(itemId, episodeKey, episodeNumber, SegmentKind.INTRO) == null ||
                    mediaSegmentStore.getSegment(itemId, episodeKey, episodeNumber, SegmentKind.CREDITS) == null
                fun needsDetection(item: MediaItem): Boolean = when (item.kind) {
                    dev.jellystructure.model.MediaKind.MOVIE -> missing(item.id, "", 0)
                    dev.jellystructure.model.MediaKind.TV_SHOW -> item.episodes.any { it.partCount == 1 && missing(item.id, it.filename, it.episodeNumber ?: 0) }
                    dev.jellystructure.model.MediaKind.MUSIC_VIDEO -> false
                }
                val toProcess = if (step.scope == "all") workingSet else workingSet.filter(::needsDetection)
                scanTracker.setActiveStep(step.step)
                broadcaster.broadcast(JobEvent.StepStarted(jobId, step.step, toProcess.size))

                var enqueued = 0
                var deduped = 0
                for (item in toProcess) {
                    when (item.kind) {
                        dev.jellystructure.model.MediaKind.MOVIE -> {
                            // Phase 178 §FR-178-2 — deferWhilePlaying mirrors this run's own deferEligible:
                            // a scheduled/event-driven pipeline's detect_segments jobs defer while a TV
                            // plays; an operator's own pipeline run (or the segment editor's "detect
                            // again", which never goes through this enqueue path at all) never does.
                            val result = mediaJobQueue.enqueueSegments(
                                "segments_movie", item.id, item.title,
                                dev.jellystructure.jobs.MediaJobParams(deferWhilePlaying = deferEligible), 1, "seg:movie:${item.id}",
                            )
                            if (result.deduped) deduped++ else enqueued++
                        }
                        dev.jellystructure.model.MediaKind.TV_SHOW -> {
                            val seasons = item.episodes.filter { it.partCount == 1 }.groupBy { it.seasonNumber ?: 0 }
                            for ((season, eps) in seasons) {
                                if (step.scope != "all" && eps.none { missing(item.id, it.filename, it.episodeNumber ?: 0) }) continue
                                val result = mediaJobQueue.enqueueSegments(
                                    "segments_season", item.id, "${item.title} S${season.toString().padStart(2, '0')}",
                                    dev.jellystructure.jobs.MediaJobParams(segmentSeason = season, deferWhilePlaying = deferEligible), eps.size,
                                    "seg:season:${item.id}:$season",
                                )
                                if (result.deduped) deduped++ else enqueued++
                            }
                        }
                        dev.jellystructure.model.MediaKind.MUSIC_VIDEO -> {}
                    }
                }
                val summary = "enqueued $enqueued detection job${if (enqueued == 1) "" else "s"}" +
                    if (deduped > 0) " ($deduped already queued)" else ""
                Logger.info("detect_segments: $summary", "pipeline")
                broadcaster.broadcast(JobEvent.StepFinished(jobId, step.step, summary))
            }
            "sync_imdb_ratings" -> {
                val toSync = workingSet.filter { !it.imdbId.isNullOrBlank() }
                Logger.info("sync_imdb_ratings: ${toSync.size} of ${workingSet.size} items have an IMDb id")
                val updated = AtomicInt(0)
                runPipelineStepPool(
                    jobId, step.step, toSync, { pipelineStepConcurrency(step.step, configStore.current.behavior.scanWorkers) },
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item, _ ->
                    if (PipelineStepOps.syncImdb(item, store, imdbClient)) updated.incrementAndGet()
                    delay(250)
                }
                Logger.info("sync_imdb_ratings: ${updated.value} of ${toSync.size} ratings updated")
            }
            "wait" -> {
                Logger.info("wait: ${step.minutes} min")
                scanTracker.setActiveStep(step.step)
                broadcaster.broadcast(JobEvent.StepStarted(jobId, step.step, 1))
                delay(step.minutes * 60_000L)
                broadcaster.broadcast(JobEvent.StepFinished(jobId, step.step, "${step.minutes} min elapsed"))
            }
            "notify" -> {
                scanTracker.setActiveStep(step.step)
                broadcaster.broadcast(JobEvent.StepStarted(jobId, step.step, 1))
                val payload = """{"event":"pipeline_complete","items":${workingSet.size},"on":"${step.on}"}"""
                runCatching { fireWebhook(cfg, payload) }
                    .onFailure { Logger.warn("notify webhook failed: ${it.message}") }
                broadcaster.broadcast(JobEvent.StepFinished(jobId, step.step, "notified"))
            }
        }
        }
    }
    Logger.info("Pipeline complete — ${workingSet.size} items processed")
    } finally {
        signalPipelineComplete(workingSet)
    }
    return workingSet
}
