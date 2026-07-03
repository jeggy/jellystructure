package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong

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
)

class ScanTracker(private val db: JellystructureDb) {
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

    private val recordMutex = Mutex()

    val running get() = _status == "RUNNING"
    var cancelRequested: Boolean = false
        private set

    val processedIdsSnapshot: Set<String>
        get() = db.scanStateQueries.getProcessedIds(_jobId).executeAsList().toSet()

    suspend fun load() {
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
        db.scanStateQueries.clearOldProcessed(jobId)
        cancelRequested = false
        activeWorkers.value = 0
        _activeStep = null
        _stepPlan = emptyList()
        _status = "RUNNING"
        _jobId = jobId
        _startedAt = epochSeconds()
        db.scanStateQueries.upsertState(
            status = "RUNNING",
            job_id = jobId,
            started_at = _startedAt,
            updated_at = _startedAt,
        )
        return jobId
    }

    fun startResume(): String {
        cancelRequested = false
        activeWorkers.value = 0
        _activeStep = null
        _stepPlan = emptyList()
        _status = "RUNNING"
        db.scanStateQueries.upsertState(
            status = "RUNNING",
            job_id = _jobId,
            started_at = _startedAt,
            updated_at = epochSeconds(),
        )
        return _jobId
    }

    suspend fun recordProcessed(jellyfinId: String) {
        recordMutex.withLock {
            db.scanStateQueries.insertProcessed(job_id = _jobId, jellyfin_id = jellyfinId)
        }
    }

    fun flush() {}

    fun cancel() {
        if (_status == "RUNNING") {
            cancelRequested = true
            _status = "CANCELLED"
            db.scanStateQueries.upsertState(
                status = "CANCELLED",
                job_id = _jobId,
                started_at = _startedAt,
                updated_at = epochSeconds(),
            )
        }
    }

    fun complete() {
        _status = "COMPLETE"
        activeWorkers.value = 0
        db.scanStateQueries.clearProcessed(_jobId)
        db.scanStateQueries.upsertState(
            status = "COMPLETE",
            job_id = _jobId,
            started_at = _startedAt,
            updated_at = epochSeconds(),
        )
    }

    fun reset() {
        _status = "IDLE"
        _jobId = ""
        _startedAt = 0L
        activeWorkers.value = 0
        db.scanStateQueries.clearOldProcessed("")
    }

    fun status() = ScanStatusResponse(
        running = _status == "RUNNING",
        status = _status,
        jobId = _jobId.ifBlank { null },
        startedAt = _startedAt.takeIf { it > 0L },
        processedCount = db.scanStateQueries.countProcessed(_jobId).executeAsOne().toInt(),
        activeWorkers = activeWorkers.value,
        configuredWorkers = targetWorkers.value,
        nextScheduledRun = nextScheduledRunSec.value.takeIf { it > 0L },
        activeStep = _activeStep,
        stepPlan = _stepPlan,
        trigger = _trigger,
        scope = _runScope,
        type = _type,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun epochSeconds(): Long = platform.posix.time(null)
