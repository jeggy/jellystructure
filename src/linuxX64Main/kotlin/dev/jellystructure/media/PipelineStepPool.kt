package dev.jellystructure.media

import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.nowEpochSec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.concurrent.AtomicInt

/** Bug fix: a pipeline run that goes quiet (e.g. one item's per-episode TMDB fetches taking many
 *  minutes) used to leave zero trace in the logs/activity feed until it finally finished or errored
 *  — an incident could only be diagnosed by attaching a debugger/profiler to the live process. Any
 *  item still running past this threshold gets a WARN logged (repeating every [STUCK_ITEM_LOG_INTERVAL_SEC]
 *  while it stays stuck), so the activity log / DB shows exactly which item and how long, even if the
 *  process is otherwise unresponsive by the time anyone looks. */
private const val STUCK_ITEM_WARN_SEC = 20L
private const val STUCK_ITEM_LOG_INTERVAL_SEC = 20L

/**
 * Phase 135 (FR-135-1/FR-135-2) — run [items] through a bounded worker pool (mirrors `runScan`'s
 * pattern in `MediaRoutes.kt`), so a pipeline step's per-item work is concurrent (faster) AND
 * [ScanTracker.activeWorkers]/[ScanTracker.targetWorkers] reflect *this* step's live pool while it
 * runs — replacing a plain sequential `for` loop. Broadcasts step-scoped progress
 * ([JobEvent.StepStarted]/[JobEvent.StepProgress]/[JobEvent.StepFinished]) so the Activity page's
 * Overall/Now-processing/Workers stay live through every step, not just `scan_files`.
 *
 * A per-item failure is caught and logged (via [onItemFailure]) and does not abort the step — matches
 * the pre-135 sequential steps' `runCatching`-per-item semantics. Real per-item concurrency is still
 * bounded by whatever gate the underlying call already sits behind (`OutboundHttp`, `ProcessGate`, the
 * artwork downloader's own semaphore) — this pool's [concurrency] only controls how many items are
 * *dispatched* concurrently into that gate, not a new, separate limit.
 */
suspend fun <T> runPipelineStepPool(
    jobId: String,
    step: String,
    items: List<T>,
    concurrency: Int,
    scanTracker: ScanTracker,
    broadcaster: WsBroadcaster,
    labelOf: (T) -> String,
    onItemFailure: (T, Throwable) -> Unit = { item, e -> },
    // reportDetail: an item can push a live sub-status (e.g. "S02E03 (3/12)") while it's still in
    // flight — its own [ScanTracker.ActiveScanItem.label] is set once at dispatch and would otherwise sit
    // static for however long that one item takes (detect_segments' fingerprint tier is the motivating
    // case: one series can run for hours across hundreds of episodes).
    perItem: suspend (T, reportDetail: suspend (String?) -> Unit) -> Unit,
) {
    scanTracker.setActiveStep(step)
    val total = items.size
    broadcaster.broadcast(JobEvent.StepStarted(jobId, step, total))
    if (total == 0) {
        scanTracker.targetWorkers.value = 0
        scanTracker.activeWorkers.value = 0
        broadcaster.broadcast(JobEvent.StepFinished(jobId, step, "0 items"))
        return
    }

    val processed = AtomicInt(0)
    scanTracker.targetWorkers.value = concurrency.coerceAtLeast(1)
    scanTracker.activeWorkers.value = 0

    coroutineScope {
        val channel = Channel<T>(Channel.UNLIMITED)
        launch {
            for (item in items) {
                if (scanTracker.cancelRequested) break
                channel.send(item)
            }
            channel.close()
        }
        val watchdog = launch {
            while (true) {
                delay(STUCK_ITEM_LOG_INTERVAL_SEC * 1000)
                val now = nowEpochSec()
                val stuck = scanTracker.activeItemsSnapshot().filter { now - it.startedAt >= STUCK_ITEM_WARN_SEC }
                for (item in stuck) {
                    Logger.warn("$step: '${item.label}' still running after ${now - item.startedAt}s", "pipeline")
                }
            }
        }
        val workerJobs = List(scanTracker.targetWorkers.value) {
            scanTracker.activeWorkers.incrementAndGet()
            launch {
                try {
                    for (item in channel) {
                        if (scanTracker.cancelRequested) break
                        val label = labelOf(item)
                        val token = scanTracker.beginItem(label)
                        runCatching { perItem(item) { detail -> scanTracker.updateItemDetail(token, detail) } }
                            .onFailure { e ->
                                Logger.warn("$step failed for '$label': ${e.message}")
                                onItemFailure(item, e)
                            }
                        scanTracker.endItem(token)
                        val done = processed.incrementAndGet().coerceAtMost(total)
                        broadcaster.broadcast(JobEvent.StepProgress(jobId, step, label, done, total))
                    }
                } finally {
                    scanTracker.activeWorkers.decrementAndGet()
                }
            }
        }
        workerJobs.joinAll()
        watchdog.cancel()
    }
    broadcaster.broadcast(JobEvent.StepFinished(jobId, step, "$total item${if (total != 1) "s" else ""} processed"))
}

/** Phase 135 (FR-135-1 item 2) — the concurrency ceiling per step. Most steps are safe at the
 *  operator's configured `scanWorkers`: their real bottleneck is an existing app-wide gate
 *  (`OutboundHttp`, `ProcessGate`, the artwork downloader's own semaphore) that just queues excess
 *  dispatched workers safely. `sync_imdb_ratings` is the one exception — imdbapi.dev has no verified
 *  batch endpoint and each call keeps its own inter-call delay, so a *small* pool preserves the
 *  intended throttle instead of multiplying it by the worker count. */
fun pipelineStepConcurrency(step: String, scanWorkers: Int): Int = when (step) {
    "sync_imdb_ratings" -> scanWorkers.coerceIn(1, 2)
    else -> scanWorkers.coerceIn(1, 100)
}
