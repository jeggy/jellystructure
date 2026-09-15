package dev.jellystructure.media

import dev.jellystructure.OutboundHttp
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.nowEpochSec
import dev.jellystructure.ops.ProcessGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.AtomicInt

/** Bug fix: a pipeline run that goes quiet (e.g. one item's per-episode TMDB fetches taking many
 *  minutes) used to leave zero trace in the logs/activity feed until it finally finished or errored
 *  — an incident could only be diagnosed by attaching a debugger/profiler to the live process. Any
 *  item still running past this threshold gets a WARN logged (repeating every [STUCK_ITEM_LOG_INTERVAL_SEC]
 *  while it stays stuck), so the activity log / DB shows exactly which item and how long, even if the
 *  process is otherwise unresponsive by the time anyone looks.
 *
 *  Phase 182 (FR-182-3) — that WARN alone turned out to be a post-mortem aid only: a 21-minute-long real
 *  incident produced 65 identical log lines and no action. [STUCK_ITEM_ESCALATE_SEC] adds a second rung:
 *  once, per item, a DIAGNOSTIC snapshot (this item + step, both gates' saturation, worker counts) is
 *  logged — the artefact a live debugger session would otherwise be needed to capture. [FR-182-4]'s
 *  per-item deadline is the third rung — the item is actually abandoned, not just reported on. */
private const val STUCK_ITEM_WARN_SEC = 20L
private const val STUCK_ITEM_LOG_INTERVAL_SEC = 20L
private const val STUCK_ITEM_ESCALATE_SEC = 120L

/** Phase 182 (FR-182-4) — per-step ceiling on how long a single item may occupy a worker slot inside
 *  [runPipelineStepPool] (and, via [pipelineStepItemDeadlineMs], `runScan`'s equivalent per-item call).
 *  Every step routed through this pool is I/O-bound HTTP/NFO work expected to complete in seconds; none
 *  of them do genuinely-hours-long per-item work here — `detect_segments` (the one step that legitimately
 *  runs for hours) is enqueue-only in [PipelineEngine] (Phase 164) and hands off to its own
 *  [MediaJobQueue]-owned worker/gate, never [runPipelineStepPool]'s loop — so one generous, uniform
 *  default is deliberately chosen over a per-step table. Blowing the deadline fails the ITEM (via the
 *  normal [onItemFailure] path, same as any other exception) and moves on; it does not abort the step. */
private const val DEFAULT_ITEM_DEADLINE_MS = 10 * 60_000L

/** Known limitation, stated once rather than at every call site: `withTimeout` is cooperative — it
 *  relies on the timed-out coroutine passing through a suspension point to be interrupted. If phase-182
 *  §2.4's leading hang hypothesis holds (a corrupted shared hash map spinning a real OS thread with NO
 *  suspension point), this deadline CANNOT interrupt it; FR-182-2's synchronization fix is the actual
 *  remedy for that case, and this deadline is the safety net for every I/O-shaped hang instead — a slow
 *  or wedged peer, a gate that never grants a permit, a response that never arrives. */
fun pipelineStepItemDeadlineMs(step: String): Long = DEFAULT_ITEM_DEADLINE_MS

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
 * artwork downloader's own semaphore) — this pool's worker count only controls how many items are
 * *dispatched* concurrently into that gate, not a new, separate limit.
 *
 * Bug fix: [targetWorkers] is now a supplier, re-polled every 500ms (same cadence as `runScan`'s own
 * supervisor) instead of a fixed count snapshotted once at the start — every OTHER pipeline step
 * (everything routed through this function) previously couldn't have its worker count changed once a
 * run had already started; only `scan_files` (which has its own separate, hand-written pool in
 * `runScan`) supported live rescaling. Workers launched here now drain (exit after finishing their
 * current item) when scaled down, and new workers spin up live when scaled up — identical semantics
 * to `runScan`'s worker factory, just generalized to any item type.
 *
 * Phase 182 (FR-182-4/FR-182-5) — [perItem] used to run under a plain `runCatching { … }`, which is
 * a real bug in its own right: `runCatching` catches `CancellationException` too, so a worker whose Job
 * was genuinely cancelled (an operator pressing "Cancel scan") silently swallowed that cancellation,
 * logged it as an ordinary per-item failure, and carried on to the NEXT item instead of stopping — the
 * coroutine actively resisted being cancelled. Every item now runs under [withTimeout] (FR-182-4's
 * deadline) with the three outcomes kept properly distinct: a genuine timeout is a per-item failure (the
 * item is abandoned, the step continues); a genuine outer cancellation is RE-THROWN, not swallowed, so
 * `ScanTracker`'s real Job-cancel (FR-182-5) actually stops the loop; anything else is an ordinary
 * per-item failure exactly as before.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class) // channel.isClosedForReceive — same accepted usage as runScan (MediaRoutes.kt)
suspend fun <T> runPipelineStepPool(
    jobId: String,
    step: String,
    items: List<T>,
    targetWorkers: () -> Int,
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
    scanTracker.targetWorkers.value = targetWorkers().coerceAtLeast(1)
    scanTracker.activeWorkers.value = 0
    val itemDeadlineMs = pipelineStepItemDeadlineMs(step)

    coroutineScope {
        val channel = Channel<T>(Channel.UNLIMITED)
        launch {
            for (item in items) {
                // Phase 214 (FR-214-2) — stepStopRequested is the per-step sibling of cancelRequested
                // (the whole-run one): same cooperative check, narrower scope. Both stop dispatch of
                // further items; only cancelRequested also propagates a real CancellationException below.
                if (scanTracker.cancelRequested || scanTracker.stepStopRequested) break
                channel.send(item)
            }
            channel.close()
        }
        // Phase 182 (FR-182-3): escalated items tracked locally — this run's own diagnostic state, not
        // shared across runs. `ActiveScanItem` exposes no durable per-item token, so escalation keys on
        // (label, startedAt) — unique enough for one run's diagnostic purposes. Each item escalates at
        // most once; the plain WARN at STUCK_ITEM_WARN_SEC keeps repeating every tick exactly as before.
        val escalated = mutableSetOf<Pair<String, Long>>()
        val watchdog = launch {
            while (true) {
                delay(STUCK_ITEM_LOG_INTERVAL_SEC * 1000)
                val now = nowEpochSec()
                for (item in scanTracker.activeItemsSnapshot()) {
                    val elapsed = now - item.startedAt
                    if (elapsed < STUCK_ITEM_WARN_SEC) continue
                    Logger.warn("$step: '${item.label}' still running after ${elapsed}s", "pipeline")
                    val key = item.label to item.startedAt
                    if (elapsed >= STUCK_ITEM_ESCALATE_SEC && escalated.add(key)) {
                        val http = OutboundHttp.stats()
                        val proc = ProcessGate.stats()
                        Logger.warn(
                            "$step: '${item.label}' stuck ${elapsed}s — diagnostic snapshot: " +
                                "workers=${scanTracker.activeWorkers.value}/${scanTracker.targetWorkers.value} " +
                                "outboundHttp(shared=${http.sharedInFlight}/${http.sharedCapacity} " +
                                "reserved=${http.reservedInFlight}/${http.interactiveReserved} " +
                                "waiting=i${http.interactiveWaiting}+b${http.backgroundWaiting}) " +
                                "processGate(shared=${proc.sharedInFlight}/${proc.sharedCapacity} " +
                                "reserved=${proc.reservedInFlight}/${proc.interactiveReserved} " +
                                "waiting=i${proc.interactiveWaiting}+b${proc.backgroundWaiting})",
                            "pipeline",
                        )
                    }
                }
            }
        }

        fun launchWorker() {
            scanTracker.activeWorkers.incrementAndGet()
            launch {
                try {
                    for (item in channel) {
                        if (scanTracker.cancelRequested || scanTracker.stepStopRequested) break
                        val label = labelOf(item)
                        val token = scanTracker.beginItem(label)
                        try {
                            withTimeout(itemDeadlineMs) {
                                perItem(item) { detail -> scanTracker.updateItemDetail(token, detail) }
                            }
                        } catch (e: TimeoutCancellationException) {
                            Logger.warn("$step: '$label' exceeded its ${itemDeadlineMs}ms deadline — abandoning this item", "pipeline")
                            onItemFailure(item, e)
                        } catch (e: CancellationException) {
                            scanTracker.endItem(token)
                            throw e   // real outer cancellation (ScanTracker.cancelRun) — must propagate
                        } catch (e: Throwable) {
                            Logger.warn("$step failed for '$label': ${e.message}")
                            onItemFailure(item, e)
                        }
                        scanTracker.endItem(token)
                        val done = processed.incrementAndGet().coerceAtMost(total)
                        broadcaster.broadcast(JobEvent.StepProgress(jobId, step, label, done, total))
                        // Scale-down drain: exit if we are excess, matching runScan's worker factory.
                        if (scanTracker.activeWorkers.value > scanTracker.targetWorkers.value) {
                            Logger.info("$step: worker draining (scale-down)", "pipeline")
                            break
                        }
                    }
                } finally {
                    scanTracker.activeWorkers.decrementAndGet()
                }
            }
        }

        repeat(scanTracker.targetWorkers.value) { launchWorker() }

        val supervisor = launch {
            while (!channel.isClosedForReceive || scanTracker.activeWorkers.value > 0) {
                delay(500)
                val newTarget = targetWorkers().coerceAtLeast(1)
                if (newTarget != scanTracker.targetWorkers.value) {
                    Logger.info("$step: workers ${scanTracker.targetWorkers.value} → $newTarget", "pipeline")
                    scanTracker.targetWorkers.value = newTarget
                }
                val active = scanTracker.activeWorkers.value
                val target = scanTracker.targetWorkers.value
                if (target > active && !channel.isClosedForReceive) {
                    repeat(target - active) { launchWorker() }
                }
            }
        }

        // Joining only the workers launched here would race a mid-drain scale-up (the supervisor can
        // launch more after this point). The supervisor's own while-condition already means "producer
        // done AND every worker wound down" — joining it (not cancelling) waits for that same
        // condition to go false and the loop to exit naturally, matching runScan's completion signal.
        supervisor.join()
        watchdog.cancel()
    }
    // Phase 214 (FR-214-2) — an operator-requested per-step stop ends the step honestly ("stopped early
    // at N of M") rather than claiming every item was processed; the pipeline's own for-loop still moves
    // on to the NEXT step afterward (open question 3 in the phase-214 spec: continuing is more useful
    // than ending the whole run over one slow/unwanted step).
    val summary = if (scanTracker.stepStopRequested && processed.value < total)
        "stopped early at ${processed.value} of $total item${if (total != 1) "s" else ""}"
    else "$total item${if (total != 1) "s" else ""} processed"
    broadcaster.broadcast(JobEvent.StepFinished(jobId, step, summary))
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
