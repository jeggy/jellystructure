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
)

/**
 * The one place every trigger resolves "what steps actually run" when Settings has no pipeline
 * configured (`cfg.scan.pipeline` empty/all-disabled) — reproduces today's plain-scan behavior
 * (`scan_files` + an unconditional inline TMDB match + gap-fill artwork when enabled), now expressed as
 * steps through the shared engine, so there's no capability regression for admins who never built a
 * pipeline in Settings.
 */
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

    val workingSet: List<MediaItem> = when (target) {
        is RunTarget.Library -> {
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
                ) { item, _ -> PipelineStepOps.pullTmdb(item, scanner, store) }
            }
            "fetch_artwork" -> {
                val toProcess = if (step.scope == "all") workingSet
                    else workingSet.filter { artworkDownloader.isArtworkIncomplete(it) }
                Logger.info("fetch_artwork: ${toProcess.size} items (scope=${step.scope})")
                runPipelineStepPool(
                    jobId, step.step, toProcess, { pipelineStepConcurrency(step.step, configStore.current.behavior.scanWorkers) },
                    scanTracker, broadcaster, labelOf = { it.title },
                ) { item, _ -> PipelineStepOps.fetchArtwork(item, store, artworkDownloader) }
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
                val fresh = workingSet.mapNotNull { store.get(it.id) }
                val toSync = fresh.filter { (it.nfoWrittenAt ?: 0L) > (it.jfSyncedAt ?: 0L) && !it.jellyfinId.isNullOrBlank() }
                val jellyfinReady = cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()
                Logger.info("sync_jellyfin: ${toSync.size} of ${workingSet.size} items have unsynced NFO changes")
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
                            val result = mediaJobQueue.enqueueSegments(
                                "segments_movie", item.id, item.title,
                                dev.jellystructure.jobs.MediaJobParams(), 1, "seg:movie:${item.id}",
                            )
                            if (result.deduped) deduped++ else enqueued++
                        }
                        dev.jellystructure.model.MediaKind.TV_SHOW -> {
                            val seasons = item.episodes.filter { it.partCount == 1 }.groupBy { it.seasonNumber ?: 0 }
                            for ((season, eps) in seasons) {
                                if (step.scope != "all" && eps.none { missing(item.id, it.filename, it.episodeNumber ?: 0) }) continue
                                val result = mediaJobQueue.enqueueSegments(
                                    "segments_season", item.id, "${item.title} S${season.toString().padStart(2, '0')}",
                                    dev.jellystructure.jobs.MediaJobParams(segmentSeason = season), eps.size,
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
