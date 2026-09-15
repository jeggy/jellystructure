package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.Volatile

/** Phase 182 (FR-182-5) — how long [ScanTracker.cancelRun] waits for a genuinely well-behaved run to
 *  wind down after cancellation before giving up and forcibly freeing its slots. Long enough to absorb
 *  a real in-flight HTTP round-trip unwinding; short enough that "Cancel" still feels like it did
 *  something within one admin-page refresh. */
private const val CANCEL_GRACE_MS = 15_000L

@Serializable
data class ScanStatusResponse(
    val running: Boolean,
    val status: String,
    val jobId: String? = null,
    val startedAt: Long? = null,
    val processedCount: Int = 0,
    val activeWorkers: Int = 0,
    val configuredWorkers: Int = 1,
    val nextScheduledRun: Long? = null,   // 93e: epoch seconds of the next automation run (null = none)
    // Phase 135 (FR-135-2 item 5 / FR-135-4) — lets a late-joining/polling client reconstruct where the
    // run is without having seen the WS event stream: the active step + the whole ordered plan, plus the
    // run's three orthogonal descriptors (trigger/scope/type — see RunRecord).
    val activeStep: String? = null,
    val stepPlan: List<String> = emptyList(),
    val trigger: String? = null,
    val scope: String? = null,
    val type: String? = null,
    // per-worker visibility — one entry per item currently in flight across scan_files/runPipelineStepPool,
    // so the Activity page can show what each concurrent worker is doing (not just an "N/M" count).
    val activeItems: List<ActiveScanItem> = emptyList(),
    // Bug fix (2026-09-05) — Phase 178's JobEvent.Deferred/Resumed fire once, live, over the WS and are
    // never otherwise recorded; a client that loads/reconnects after the moment a run deferred (the
    // common case — nobody has the Activity page open continuously) polls this same RUNNING status
    // forever with no way to learn WHY nothing is progressing. Persisted here so any late-joining poller
    // can reconstruct the exact "Paused — TV is watching {name}" state Dashboard's WS handler already
    // knows how to render, instead of just spinning at 0 items/0 workers indefinitely.
    val deferred: Boolean = false,
    val deferredDevices: List<String> = emptyList(),
)

@Serializable
data class ActiveScanItem(val label: String, val startedAt: Long, val detail: String? = null)

/**
 * [persistToDb] — Phase 175: false for the ephemeral, per-call tracker a realtime/webhook single-item
 * ingest constructs (`ScanTracker(db, persistToDb = false)`) so it can drive [runPipelineStepPool]'s
 * `StepStarted`/`StepProgress`/`StepFinished` events (same live visibility a bulk run gets) without a
 * second concurrent writer touching the single-row, DB-backed `scan_state` table the real global tracker
 * owns — two realtime ingests running at once, or one running alongside a bulk scan, would otherwise
 * clobber each other's `scan_state` row. Every `db.scanStateQueries.*` call below is guarded on this
 * flag; the in-memory counters/maps (targetWorkers/activeWorkers/activeItemsMap/stepPlan/activeStep) are
 * unaffected and work identically either way.
 */
class ScanTracker(private val db: JellystructureDb, private val persistToDb: Boolean = true) {
    // In-memory fast-read flags; authoritative state persisted in DB
    private var _status: String = "IDLE"
    private var _jobId: String = ""
    private var _startedAt: Long = 0L

    // Worker pool counters — updated by runScan workers
    val targetWorkers = AtomicInt(1)
    val activeWorkers = AtomicInt(0)

    // 93b/93e: epoch seconds of the next scheduled automation run (0 = nothing scheduled). Set by the
    // scheduler loop in Main.kt; surfaced in ScanStatusResponse for the admin's next-run indicator.
    val nextScheduledRunSec = AtomicLong(0L)

    // Phase 135 — the current run's step plan/active-step + descriptors, for late-joining pollers.
    // Plain vars (not Atomic): read-mostly, single-writer-at-a-time (the running pipeline coroutine),
    // a stale read on one poll tick is harmless — same tradeoff already made for _status/_jobId below.
    private var _activeStep: String? = null
    private var _stepPlan: List<String> = emptyList()
    private var _trigger: String? = null
    private var _runScope: String? = null
    private var _type: String? = null

    fun setActiveStep(step: String?) { _activeStep = step }
    fun setStepPlan(steps: List<String>) { _stepPlan = steps }
    fun setDescriptors(trigger: String, runScope: String, type: String?) {
        _trigger = trigger; _runScope = runScope; _type = type
    }

    // Bug fix (2026-09-05) — see ScanStatusResponse.deferred's doc. Plain vars, same read-mostly/
    // single-writer tradeoff already made for _activeStep etc above.
    @Volatile private var _deferred: Boolean = false
    @Volatile private var _deferredDevices: List<String> = emptyList()
    val deferred get() = _deferred
    fun setDeferred(devices: List<String>) { _deferred = true; _deferredDevices = devices }
    fun clearDeferred() { _deferred = false; _deferredDevices = emptyList() }

    // Per-worker visibility — every concurrent worker (scan_files' pool in MediaRoutes.kt, and every
    // runPipelineStepPool step) reports the item it's working on here, keyed by an opaque token (not the
    // label) so two workers that happen to pick up same-titled items can never clobber each other's entry.
    private val activeItemsMutex = Mutex()
    private val activeItemsMap = mutableMapOf<Long, ActiveScanItem>()
    private val activeItemToken = AtomicLong(0L)

    suspend fun beginItem(label: String): Long {
        val token = activeItemToken.incrementAndGet()
        activeItemsMutex.withLock { activeItemsMap[token] = ActiveScanItem(label, epochSeconds()) }
        return token
    }

    suspend fun endItem(token: Long) {
        activeItemsMutex.withLock { activeItemsMap.remove(token) }
    }

    // Sub-item progress (e.g. "S02E03 (3/12)" while a single series' fingerprint pass works through its
    // episodes) — an item's label is set once at beginItem and would otherwise sit static for however
    // long that one item takes, which is exactly the case (detect_segments' fingerprint tier) this was
    // added for.
    suspend fun updateItemDetail(token: Long, detail: String?) {
        activeItemsMutex.withLock { activeItemsMap[token]?.let { activeItemsMap[token] = it.copy(detail = detail) } }
    }

    suspend fun activeItemsSnapshot(): List<ActiveScanItem> =
        activeItemsMutex.withLock { activeItemsMap.values.sortedBy { it.startedAt } }

    private val recordMutex = Mutex()

    val running get() = _status == "RUNNING"
    var cancelRequested: Boolean = false
        private set

    // Phase 214 (FR-214-2) — a cooperative, PER-STEP stop: sits beside [cancelRequested] (the whole-run
    // one) rather than replacing it. [runPipelineStepPool] checks both at the same two points, so the
    // mechanics are identical to the whole-run cancel; the only difference is scope. [resetStepStop] is
    // called once per step, right before it starts, so a stop requested for a PREVIOUS step can never
    // leak into the next one — an operator who stops fetch_artwork must still see write_nfo run normally.
    var stepStopRequested: Boolean = false
        private set

    fun stopCurrentStep() { stepStopRequested = true }
    fun resetStepStop() { stepStopRequested = false }

    // Phase 182 (FR-182-5) — the Job backing the currently running scan/pipeline coroutine tree, set by
    // whoever launches it (launchScanRun). Lets cancelRun() actually cancel instead of only flipping the
    // cooperative [cancelRequested] boolean, which runPipelineStepPool's worker loop only ever checked
    // BETWEEN items — an item already inside perItem (in particular a hung one, see phase-182 §2) could
    // never be interrupted that way, and pressing Cancel in the admin UI silently did nothing beyond
    // relabel the status.
    @Volatile private var runJob: Job? = null

    fun attachJob(job: Job) { runJob = job }

    val processedIdsSnapshot: Set<String>
        get() = if (persistToDb) db.scanStateQueries.getProcessedIds(_jobId).executeAsList().toSet() else emptySet()

    suspend fun load() {
        if (!persistToDb) return
        val row = db.scanStateQueries.getState().executeAsOneOrNull() ?: return
        _jobId = row.job_id
        _startedAt = row.started_at
        if (row.status == "RUNNING") {
            Logger.info("ScanTracker: previous scan was interrupted — marking as CANCELLED")
            _status = "CANCELLED"
            db.scanStateQueries.upsertState(
                status = "CANCELLED",
                job_id = row.job_id,
                started_at = row.started_at,
                updated_at = epochSeconds(),
            )
        } else {
            _status = row.status
        }
    }

    fun startNew(): String {
        val jobId = "scan-${epochSeconds()}"
        if (persistToDb) db.scanStateQueries.clearOldProcessed(jobId)
        cancelRequested = false
        stepStopRequested = false
        activeWorkers.value = 0
        _activeStep = null
        _stepPlan = emptyList()
        activeItemsMap.clear()
        _status = "RUNNING"
        _jobId = jobId
        _startedAt = epochSeconds()
        clearDeferred()
        if (persistToDb) db.scanStateQueries.upsertState(
            status = "RUNNING",
            job_id = jobId,
            started_at = _startedAt,
            updated_at = _startedAt,
        )
        return jobId
    }

    fun startResume(): String {
        cancelRequested = false
        stepStopRequested = false
        activeWorkers.value = 0
        _activeStep = null
        _stepPlan = emptyList()
        activeItemsMap.clear()
        _status = "RUNNING"
        clearDeferred()
        if (persistToDb) db.scanStateQueries.upsertState(
            status = "RUNNING",
            job_id = _jobId,
            started_at = _startedAt,
            updated_at = epochSeconds(),
        )
        return _jobId
    }

    suspend fun recordProcessed(jellyfinId: String) {
        if (!persistToDb) return
        recordMutex.withLock {
            db.scanStateQueries.insertProcessed(job_id = _jobId, jellyfin_id = jellyfinId)
        }
    }

    fun flush() {}

    /** The cooperative-only half of cancellation, kept for the one internal caller (`runScan`'s own
     *  exception handler in `MediaRoutes.kt`, which is already unwinding on its own and needs no Job to
     *  cancel). The operator-facing "Cancel scan" action must call [cancelRun] instead — see its doc. */
    fun cancel() {
        if (_status == "RUNNING") {
            cancelRequested = true
            _status = "CANCELLED"
            clearDeferred()
            if (persistToDb) db.scanStateQueries.upsertState(
                status = "CANCELLED",
                job_id = _jobId,
                started_at = _startedAt,
                updated_at = epochSeconds(),
            )
        }
    }

    /**
     * Phase 182 (FR-182-5) — the operator-facing "Cancel scan" action. Does everything [cancel] does,
     * plus actually cancels [runJob] instead of relying solely on [cancelRequested].
     *
     * Real cancellation still has a hard limit, stated here rather than re-derived at the call site: if
     * phase-182 §2.4's leading hang hypothesis holds (a corrupted shared hash map spinning a real OS
     * thread with NO suspension point), kotlinx.coroutines cancellation is cooperative and CANNOT
     * interrupt that spin — cancelling the Job unparks every well-behaved coroutine, but a truly wedged
     * thread stays wedged regardless. So this also arms a bounded grace-period watcher (launched on
     * [appScope], since this function itself is not suspend and must return immediately to the HTTP
     * handler that called it): if the run has not wound down within [CANCEL_GRACE_MS], its slots are
     * forcibly forfeited via [reset] so a NEW run is permitted to start even though the old, wedged
     * coroutines may still be alive underneath holding stale state. A genuinely wedged run therefore
     * blocks Ravilo (phase-182 §B is the real fix for that) but can no longer also block every future
     * scan forever, which — before this — it did.
     */
    fun cancelRun(appScope: CoroutineScope) {
        if (_status != "RUNNING") return
        val job = runJob
        cancel()
        job?.cancel(CancellationException("Scan cancelled by operator"))
        appScope.launch {
            val woundDownCleanly = job == null ||
                withTimeoutOrNull(CANCEL_GRACE_MS) { job.join(); true } == true
            if (!woundDownCleanly) {
                Logger.warn(
                    "ScanTracker: run did not wind down within ${CANCEL_GRACE_MS}ms of cancel — " +
                        "forcing its slots free so the next run can start",
                    "scan",
                )
                reset()
            }
        }
    }

    fun complete() {
        _status = "COMPLETE"
        activeWorkers.value = 0
        runJob = null
        clearDeferred()
        if (persistToDb) {
            db.scanStateQueries.clearProcessed(_jobId)
            db.scanStateQueries.upsertState(
                status = "COMPLETE",
                job_id = _jobId,
                started_at = _startedAt,
                updated_at = epochSeconds(),
            )
        }
    }

    fun reset() {
        _status = "IDLE"
        _jobId = ""
        _startedAt = 0L
        activeWorkers.value = 0
        runJob = null
        clearDeferred()
        if (persistToDb) db.scanStateQueries.clearOldProcessed("")
    }

    suspend fun status() = ScanStatusResponse(
        running = _status == "RUNNING",
        status = _status,
        jobId = _jobId.ifBlank { null },
        startedAt = _startedAt.takeIf { it > 0L },
        processedCount = if (persistToDb) db.scanStateQueries.countProcessed(_jobId).executeAsOne().toInt() else 0,
        activeWorkers = activeWorkers.value,
        configuredWorkers = targetWorkers.value,
        nextScheduledRun = nextScheduledRunSec.value.takeIf { it > 0L },
        activeStep = _activeStep,
        stepPlan = _stepPlan,
        deferred = _deferred,
        deferredDevices = _deferredDevices,
        trigger = _trigger,
        scope = _runScope,
        type = _type,
        activeItems = activeItemsSnapshot(),
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun epochSeconds(): Long = platform.posix.time(null)
