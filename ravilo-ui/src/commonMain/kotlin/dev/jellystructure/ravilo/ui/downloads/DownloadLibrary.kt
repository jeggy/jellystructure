package dev.jellystructure.ravilo.ui.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * DEB-4070 (DEB-3995 FR-7, FR-12) — what this phone knows about one download it was asked for. Device state: a label for what is on
 * the disk, never a catalog row, and never sent to the server.
 */
@Serializable
data class DownloadRecord(
    val itemId: String,
    /** `movie` or `episode`, as the ticket says. */
    val kind: String,
    val title: String,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val mediaSourceId: String? = null,
    val quality: String,
    val height: Int? = null,
    /** The size the sheet showed when it was asked for; the disk is the truth once it runs. */
    val bytes: Long = 0L,
    val runtimeMinutes: Int = 0,
    /** Queue order: a download runs after every one asked for before it. */
    val queuedAt: Long = 0L,
    val state: RecordState = RecordState.QUEUED,
    /** DEB-4071 (FR-9, FR-10) — watched as the server last said it (a page the viewer opened online, or slice 5's reconnect refresh). Drives *Delete watched*. */
    val watched: Boolean = false,
    /** DEB-4071 (FR-13) — the server no longer offers it to this viewer: the entry says `download.gone`, and it plays until deleted. */
    val gone: Boolean = false,
    /** DEB-4072 (FR-15) — the resume position the server last reported for this title; with [watched] it is the base the outbox's conflict rule compares to. */
    val serverResumeMs: Long = 0L,
)

enum class RecordState {
    /** Asked for; no ticket yet. A smaller copy takes its ticket only when its turn comes (FR-6: the server runs one download stream). */
    QUEUED,
    /** Handed to the engine, which owns it from here. */
    STARTED,
}

/** Where a viewer's index lives. Android: a file under `filesDir` (never `cacheDir`, FR-7); tests: memory. */
interface DownloadIndexStorage {
    fun read(userId: String): String?
    fun write(userId: String, text: String)
    fun delete(userId: String)
    /** Every user id that has an index on this phone. */
    fun users(): List<String>
}

class InMemoryDownloadIndexStorage : DownloadIndexStorage {
    private val m = LinkedHashMap<String, String>()
    override fun read(userId: String) = m[userId]
    override fun write(userId: String, text: String) { m[userId] = text }
    override fun delete(userId: String) { m.remove(userId) }
    override fun users() = m.keys.toList()
}

/**
 * FR-12 — **keyed by user id from the start**: every call takes the viewer, and another profile's records are reachable only through
 * [otherUsersBytes], which counts and never names. Sign-out does not touch this; deleting is by hand.
 */
class DownloadLibrary(
    private val storage: DownloadIndexStorage,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val ser = ListSerializer(DownloadRecord.serializer())

    fun records(userId: String): List<DownloadRecord> =
        storage.read(userId)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty()

    fun get(userId: String, itemId: String): DownloadRecord? = records(userId).firstOrNull { it.itemId == itemId }

    fun upsert(userId: String, record: DownloadRecord) {
        val all = records(userId)
        val next = if (all.any { it.itemId == record.itemId }) all.map { if (it.itemId == record.itemId) record else it } else all + record
        storage.write(userId, json.encodeToString(ser, next))
    }

    fun remove(userId: String, itemId: String) {
        val next = records(userId).filterNot { it.itemId == itemId }
        if (next.isEmpty()) storage.delete(userId) else storage.write(userId, json.encodeToString(ser, next))
    }

    /** The oldest download still waiting for its ticket, or null. */
    fun nextQueued(userId: String): DownloadRecord? =
        records(userId).filter { it.state == RecordState.QUEUED }.minByOrNull { it.queuedAt }

    /** What this viewer's downloads take, by the sizes the sheet showed. */
    fun bytes(userId: String): Long = records(userId).sumOf { it.bytes }

    /** Every user id with an index on this phone, this viewer's included. */
    fun users(): List<String> = storage.users()

    /** FR-12 — forget every record of one viewer (the media goes with the engine). */
    fun removeAll(userId: String) = storage.delete(userId)

    /** FR-12 — what every other profile on this phone holds: a number, never a title. */
    fun otherUsersBytes(userId: String): Long = storage.users().filter { it != userId }.sumOf { bytes(it) }
}
