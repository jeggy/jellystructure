package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.Serializable

@Serializable
data class ScanStatusResponse(
    val running: Boolean,
    val status: String,
    val jobId: String? = null,
    val startedAt: Long? = null,
    val processedCount: Int = 0,
)

class ScanTracker(private val db: JellystructureDb) {
    // In-memory fast-read flags; authoritative state persisted in DB
    private var _status: String = "IDLE"
    private var _jobId: String = ""
    private var _startedAt: Long = 0L

    val running get() = _status == "RUNNING"
    var cancelRequested: Boolean = false
        private set

    val processedIdsSnapshot: Set<String>
        get() = db.scanStateQueries.getProcessedIds(_jobId).executeAsList().toSet()

    fun load() {
        val row = db.scanStateQueries.getState().executeAsOneOrNull() ?: return
        _jobId = row.job_id
        _startedAt = row.started_at
        if (row.status == "RUNNING") {
            println("[INFO] ScanTracker: previous scan was interrupted — marking as CANCELLED")
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
        // Clear processed IDs from all prior runs
        db.scanStateQueries.clearOldProcessed(jobId)
        cancelRequested = false
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
        _status = "RUNNING"
        db.scanStateQueries.upsertState(
            status = "RUNNING",
            job_id = _jobId,
            started_at = _startedAt,
            updated_at = epochSeconds(),
        )
        return _jobId
    }

    fun recordProcessed(jellyfinId: String) {
        db.scanStateQueries.insertProcessed(job_id = _jobId, jellyfin_id = jellyfinId)
    }

    // No-op with DB persistence — every recordProcessed is already written immediately
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
        db.scanStateQueries.clearProcessed(_jobId)
        db.scanStateQueries.upsertState(
            status = "COMPLETE",
            job_id = _jobId,
            started_at = _startedAt,
            updated_at = epochSeconds(),
        )
    }

    fun status() = ScanStatusResponse(
        running = _status == "RUNNING",
        status = _status,
        jobId = _jobId.ifBlank { null },
        startedAt = _startedAt.takeIf { it > 0L },
        processedCount = db.scanStateQueries.countProcessed(_jobId).executeAsOne().toInt(),
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun epochSeconds(): Long = platform.posix.time(null)
