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

@Serializable
data class ActivityEntry(
    val id: Int,
    val ts: Long,
    val level: String,
    val category: String,
    val message: String,
    val mediaId: String? = null,
)

@Serializable
data class ActivityLogPage(val entries: List<ActivityEntry>, val total: Int)

class ActivityLog(
    private val filePath: String,
    private val broadcaster: WsBroadcaster,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val entries = ArrayDeque<ActivityEntry>()
    private var nextId = 0
    private val json = Json { ignoreUnknownKeys = true }

    fun load() {
        val path = Path(filePath)
        if (!SystemFileSystem.exists(path)) return
        runCatching {
            val content = SystemFileSystem.source(path).buffered().readString()
            val loaded = json.decodeFromString(ListSerializer(ActivityEntry.serializer()), content)
            entries.addAll(loaded)
            nextId = (loaded.maxOfOrNull { it.id } ?: 0) + 1
        }
    }

    suspend fun log(level: String, category: String, message: String, mediaId: String? = null) {
        val entry = mutex.withLock {
            val e = ActivityEntry(id = ++nextId, ts = epochSeconds(), level = level, category = category, message = message, mediaId = mediaId)
            entries.addLast(e)
            if (entries.size > MAX_ENTRIES) entries.removeFirst()
            e
        }
        scope.launch { persistSnapshot() }
        broadcaster.broadcast(JobEvent.LogLine(entry.level, entry.category, entry.message, entry.mediaId))
    }


    suspend fun list(page: Int, pageSize: Int, category: String?, level: String?): ActivityLogPage {
        val filtered = mutex.withLock {
            entries.filter { e ->
                (category == null || e.category == category) &&
                (level == null || e.level == level)
            }
        }
        val total = filtered.size
        val paged = filtered.drop((page - 1) * pageSize).take(pageSize)
        return ActivityLogPage(paged, total)
    }

    suspend fun clear() {
        mutex.withLock { entries.clear() }
        persistSnapshot()
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
}

@OptIn(ExperimentalForeignApi::class)
private fun epochSeconds(): Long = platform.posix.time(null)
