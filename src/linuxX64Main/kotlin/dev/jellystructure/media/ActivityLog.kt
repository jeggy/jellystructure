package dev.jellystructure.media

import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val MAX_ENTRIES = 10_000
private const val MAX_RUNS = 200

@Serializable
data class ActivityEntry(
    val id: Int,
    val ts: Long,
    val level: String,
    val category: String,
    val message: String,
    val mediaId: String? = null,
    val runId: String? = null,   // 93g: the scan/pipeline run this line belongs to
    val step: String? = null,    // 93g: the pipeline step (scan_files/pull_tmdb/…) when applicable
)

@Serializable
data class ActivityLogPage(val entries: List<ActivityEntry>, val total: Int)

/** 93g: one scan/pipeline run, for the Activity run picker. [trigger] = scheduled/manual/scan. */
@Serializable
data class RunRecord(val runId: String, val trigger: String, val startedAt: Long, val finishedAt: Long? = null)

/** 93g: a run plus the event/error counts derived from the (retained) activity entries. */
@Serializable
data class RunSummary(
    val runId: String, val trigger: String, val startedAt: Long, val finishedAt: Long? = null,
    val events: Int = 0, val errors: Int = 0,
)

class ActivityLog(
    private val filePath: String,
    private val broadcaster: WsBroadcaster,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val entries = ArrayDeque<ActivityEntry>()
    private val runs = ArrayDeque<RunRecord>()       // 93g: recent runs (oldest → newest)
    private var nextId = 0
    private val json = Json { ignoreUnknownKeys = true }
    private val runsFile get() = "$filePath.runs"

    fun load() {
        val path = Path(filePath)
        if (SystemFileSystem.exists(path)) runCatching {
            val content = SystemFileSystem.source(path).buffered().readString()
            val loaded = json.decodeFromString(ListSerializer(ActivityEntry.serializer()), content)
            entries.addAll(loaded)
            nextId = (loaded.maxOfOrNull { it.id } ?: 0) + 1
        }
        val rp = Path(runsFile)
        if (SystemFileSystem.exists(rp)) runCatching {
            val content = SystemFileSystem.source(rp).buffered().readString()
            runs.addAll(json.decodeFromString(ListSerializer(RunRecord.serializer()), content))
        }
    }

    suspend fun log(level: String, category: String, message: String, mediaId: String? = null, runId: String? = null, step: String? = null) {
        val entry = mutex.withLock {
            val e = ActivityEntry(id = ++nextId, ts = epochSeconds(), level = level, category = category, message = message, mediaId = mediaId, runId = runId, step = step)
            entries.addLast(e)
            if (entries.size > MAX_ENTRIES) entries.removeFirst()
            e
        }
        scope.launch { persistSnapshot() }
        broadcaster.broadcast(JobEvent.LogLine(entry.level, entry.category, entry.message, entry.mediaId, entry.runId))
    }

    // 93g — runs index for the Activity run picker.
    suspend fun startRun(runId: String, trigger: String) {
        mutex.withLock {
            runs.addLast(RunRecord(runId, trigger, epochSeconds()))
            if (runs.size > MAX_RUNS) runs.removeFirst()
        }
        scope.launch { persistRuns() }
    }

    suspend fun finishRun(runId: String) {
        mutex.withLock {
            val i = runs.indexOfLast { it.runId == runId }
            if (i >= 0) runs[i] = runs[i].copy(finishedAt = epochSeconds())
        }
        scope.launch { persistRuns() }
    }

    /** Recent runs (newest first) with event/error counts derived from the retained entries. */
    suspend fun runSummaries(): List<RunSummary> = mutex.withLock {
        val byRun = entries.groupBy { it.runId }
        runs.reversed().map { r ->
            val es = byRun[r.runId].orEmpty()
            RunSummary(r.runId, r.trigger, r.startedAt, r.finishedAt, es.size, es.count { it.level == "ERROR" })
        }
    }

    suspend fun list(page: Int, pageSize: Int, category: String?, level: String?, run: String? = null): ActivityLogPage {
        val filtered = mutex.withLock {
            entries.filter { e ->
                (category == null || e.category == category) &&
                (level == null || e.level == level) &&
                (run == null || e.runId == run)
            }
        }
        val total = filtered.size
        val paged = filtered.drop((page - 1) * pageSize).take(pageSize)
        return ActivityLogPage(paged, total)
    }

    suspend fun clear() {
        mutex.withLock { entries.clear(); runs.clear() }
        persistSnapshot(); persistRuns()
    }

    private suspend fun persistSnapshot() {
        val snapshot = mutex.withLock { entries.toList() }
        val tmp = "$filePath.tmp"
        runCatching {
            val content = json.encodeToString(ListSerializer(ActivityEntry.serializer()), snapshot)
            val sink = SystemFileSystem.sink(Path(tmp)).buffered()
            sink.writeString(content)
            sink.flush()
            sink.close()
            @OptIn(ExperimentalForeignApi::class)
            platform.posix.rename(tmp, filePath)
        }
    }

    private suspend fun persistRuns() {
        val snapshot = mutex.withLock { runs.toList() }
        val tmp = "$runsFile.tmp"
        runCatching {
            val content = json.encodeToString(ListSerializer(RunRecord.serializer()), snapshot)
            val sink = SystemFileSystem.sink(Path(tmp)).buffered()
            sink.writeString(content)
            sink.flush()
            sink.close()
            @OptIn(ExperimentalForeignApi::class)
            platform.posix.rename(tmp, runsFile)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun epochSeconds(): Long = platform.posix.time(null)
