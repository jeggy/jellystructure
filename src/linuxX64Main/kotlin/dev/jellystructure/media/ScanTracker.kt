package dev.jellystructure.media

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ScanStateFile(
    val status: String = "IDLE",
    val jobId: String = "",
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val processedIds: List<String> = emptyList(),
)

@Serializable
data class ScanStatusResponse(
    val running: Boolean,
    val status: String,
    val jobId: String? = null,
    val startedAt: Long? = null,
    val processedCount: Int = 0,
)

class ScanTracker(private val stateFile: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state = ScanStateFile()
    private val _processedIds: MutableSet<String> = mutableSetOf()
    private var dirtyCount = 0

    val running get() = state.status == "RUNNING"
    val processedIdsSnapshot: Set<String> get() = _processedIds.toSet()
    var cancelRequested: Boolean = false
        private set

    fun load() {
        val path = Path(stateFile)
        if (!SystemFileSystem.exists(path)) return
        runCatching {
            val content = SystemFileSystem.source(path).buffered().readString()
            val loaded = json.decodeFromString<ScanStateFile>(content)
            _processedIds.clear()
            _processedIds.addAll(loaded.processedIds)
            state = if (loaded.status == "RUNNING") {
                println("[INFO] ScanTracker: previous scan was interrupted — marking as CANCELLED")
                loaded.copy(status = "CANCELLED", updatedAt = epochSeconds())
            } else {
                loaded
            }
            if (loaded.status == "RUNNING") persist()
        }.onFailure {
            println("[WARN] ScanTracker: failed to load state: ${it.message}")
        }
    }

    fun startNew(): String {
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        val jobId = "scan-${platform.posix.time(null)}"
        _processedIds.clear()
        cancelRequested = false
        dirtyCount = 0
        state = ScanStateFile(
            status = "RUNNING",
            jobId = jobId,
            startedAt = epochSeconds(),
            updatedAt = epochSeconds(),
        )
        persist()
        return jobId
    }

    fun startResume(): String {
        cancelRequested = false
        dirtyCount = 0
        state = state.copy(status = "RUNNING", updatedAt = epochSeconds())
        persist()
        return state.jobId
    }

    fun recordProcessed(jellyfinId: String) {
        _processedIds.add(jellyfinId)
        dirtyCount++
        if (dirtyCount >= 10) flush()
    }

    fun flush() {
        state = state.copy(processedIds = _processedIds.toList(), updatedAt = epochSeconds())
        persist()
        dirtyCount = 0
    }

    fun cancel() {
        if (state.status == "RUNNING") {
            cancelRequested = true
            state = state.copy(
                status = "CANCELLED",
                processedIds = _processedIds.toList(),
                updatedAt = epochSeconds(),
            )
            persist()
        }
    }

    fun complete() {
        state = ScanStateFile(
            status = "COMPLETE",
            jobId = state.jobId,
            startedAt = state.startedAt,
            updatedAt = epochSeconds(),
            processedIds = emptyList(),
        )
        _processedIds.clear()
        persist()
    }

    fun status() = ScanStatusResponse(
        running = state.status == "RUNNING",
        status = state.status,
        jobId = state.jobId.ifBlank { null },
        startedAt = state.startedAt.takeIf { it > 0L },
        processedCount = _processedIds.size,
    )

    private fun persist() {
        val tmp = "$stateFile.tmp"
        runCatching {
            val sink = SystemFileSystem.sink(Path(tmp)).buffered()
            sink.writeString(json.encodeToString(ScanStateFile.serializer(), state))
            sink.flush()
            sink.close()
            @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
            platform.posix.rename(tmp, stateFile)
        }.onFailure {
            println("[WARN] ScanTracker: persist failed: ${it.message}")
        }
    }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun epochSeconds(): Long = platform.posix.time(null)
}
