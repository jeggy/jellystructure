package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * DEB-4072 (DEB-3995 FR-15) — what the server last said about one title's play state: the resume position and the watched flag. The
 * outbox's conflict rule compares the server's value now with the one the phone **knew when it recorded** the offline play.
 */
@Serializable
data class ServerPlay(val resumeMs: Long = 0L, val played: Boolean = false)

fun CardPlayState?.toServerPlay(): ServerPlay = if (this == null) ServerPlay() else ServerPlay(resumeMs, played)

/**
 * One queued write. Device state only: it is a message waiting for the server, never a value any screen shows as the truth online
 * (FR-15: *offline progress is a queued write, not local truth*). [baseResumeMs] and [basePlayed] are the server's play state at the
 * moment this title's first entry was recorded.
 */
@Serializable
data class OutboxEntry(
    val itemId: String,
    /** [PROGRESS] or [WATCHED]. */
    val kind: String,
    val positionMs: Long = 0L,
    val recordedAtMs: Long = 0L,
    val baseResumeMs: Long = 0L,
    val basePlayed: Boolean = false,
) {
    companion object {
        const val PROGRESS = "progress"
        const val WATCHED = "watched"
    }
}

/** The three calls the outbox makes. [dev.jellystructure.shared.tv.TvApiClient] is the real one; tests fake it. Ravilo never writes Jellyfin user data itself (FR-15). */
interface OutboxSink {
    /** `/api/tv/playstate` — a title the server holds no user data for is **absent**, which is `ServerPlay()` (never played), not "gone". */
    suspend fun playstate(ids: List<String>): Map<String, CardPlayState>
    /** `/api/tv/playback/progress`. */
    suspend fun progress(itemId: String, positionMs: Long)
    /** `/api/tv/mark`. */
    suspend fun mark(itemId: String, watched: Boolean)
}

/** What one [ProgressOutbox.drain] did. */
data class DrainResult(
    val sent: Int = 0,
    /** Entries the server's later play (or a title it no longer offers) made moot: dropped, the server's value stands. */
    val dropped: Int = 0,
    /** The server answered 401: this profile goes to sign-in, and its outbox is kept (FR-12). */
    val rejected: Boolean = false,
    /** The server could not be reached part-way: what is left stays, oldest first, for the next time. */
    val unreachable: Boolean = false,
)

/**
 * DEB-4072 (DEB-3995 FR-15) — the persistent, per-user outbox of an offline play: its progress and its watched finish.
 *
 * - **Per viewer** (FR-12): keyed by user id like the downloads, and surviving a sign-out.
 * - **Oldest first:** titles drain in the order they were first recorded. A title keeps one progress entry (the latest position
 *   replaces the earlier) and, if it finished, one watched entry after it.
 * - **The server wins:** a title whose server play state changed since the phone last knew it was played elsewhere in the
 *   meantime, and the queued write is dropped, not sent.
 */
class ProgressOutbox(
    private val storage: DownloadIndexStorage,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val ser = ListSerializer(OutboxEntry.serializer())

    /** Oldest first, as they will be sent. */
    fun entries(userId: String): List<OutboxEntry> =
        storage.read(userId)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty()

    fun isEmpty(userId: String): Boolean = entries(userId).isEmpty()

    private fun save(userId: String, all: List<OutboxEntry>) {
        if (all.isEmpty()) storage.delete(userId) else storage.write(userId, json.encodeToString(ser, all))
    }

    /** Record where an offline play reached. [base] is what the server last said about the title; kept from the title's first entry. */
    fun recordProgress(userId: String, itemId: String, positionMs: Long, nowMs: Long, base: ServerPlay) {
        val all = entries(userId)
        val b = all.firstOrNull { it.itemId == itemId }?.let { ServerPlay(it.baseResumeMs, it.basePlayed) } ?: base
        val entry = OutboxEntry(itemId, OutboxEntry.PROGRESS, positionMs.coerceAtLeast(0L), nowMs, b.resumeMs, b.played)
        val replaced = all.any { it.itemId == itemId && it.kind == OutboxEntry.PROGRESS }
        save(userId, if (replaced) all.map { if (it.itemId == itemId && it.kind == OutboxEntry.PROGRESS) entry else it } else insertAfterItem(all, entry))
    }

    /** Record the finish (FR-15: past 90 % or past the credits marker, the same rule as online). */
    fun recordWatched(userId: String, itemId: String, nowMs: Long, base: ServerPlay) {
        val all = entries(userId)
        if (all.any { it.itemId == itemId && it.kind == OutboxEntry.WATCHED }) return
        val b = all.firstOrNull { it.itemId == itemId }?.let { ServerPlay(it.baseResumeMs, it.basePlayed) } ?: base
        save(userId, insertAfterItem(all, OutboxEntry(itemId, OutboxEntry.WATCHED, 0L, nowMs, b.resumeMs, b.played)))
    }

    /** An item's entries stay together, in the order the item first appeared; a new item goes last. */
    private fun insertAfterItem(all: List<OutboxEntry>, e: OutboxEntry): List<OutboxEntry> {
        val last = all.indexOfLast { it.itemId == e.itemId }
        return if (last < 0) all + e else all.take(last + 1) + e + all.drop(last + 1)
    }

    /**
     * FR-15 (offline resume) — the position recorded offline for [itemId], or null. It shows **only on the downloaded copy while
     * offline**; online the screen shows the server's value. A title the viewer finished offline has none (it is watched).
     */
    fun offlineResumeMs(userId: String, itemId: String): Long? {
        val mine = entries(userId).filter { it.itemId == itemId }
        if (mine.any { it.kind == OutboxEntry.WATCHED }) return null
        return mine.firstOrNull { it.kind == OutboxEntry.PROGRESS }?.positionMs
    }

    /** The item ids with something waiting, oldest first. */
    fun pendingItems(userId: String): List<String> = entries(userId).map { it.itemId }.distinct()

    /** Forget one viewer's outbox (their downloads were deleted: there is no copy left to have played offline). */
    fun clear(userId: String) = storage.delete(userId)

    fun drop(userId: String, itemId: String) = save(userId, entries(userId).filterNot { it.itemId == itemId })

    /**
     * FR-15 — send what is waiting, oldest first, through [sink]. Stops at the first sign that the server cannot be reached and leaves the
     * rest in place; a 401 stops with [DrainResult.rejected] and keeps everything.
     */
    suspend fun drain(userId: String, sink: OutboxSink): DrainResult {
        val items = pendingItems(userId)
        if (items.isEmpty()) return DrainResult()
        val server = try {
            sink.playstate(items)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return if (e is TvApiError.Http && e.status == 401) DrainResult(rejected = true) else DrainResult(unreachable = true)
        }
        var sent = 0
        var dropped = 0
        for (id in items) {
            val mine = entries(userId).filter { it.itemId == id }
            if (mine.isEmpty()) continue
            val now = server[id].toServerPlay()
            val base = ServerPlay(mine.first().baseResumeMs, mine.first().basePlayed)
            if (now != base) {   // played elsewhere since the phone last knew: the server's value stands
                drop(userId, id); dropped += mine.size
                continue
            }
            for (e in mine) {
                try {
                    if (e.kind == OutboxEntry.WATCHED) sink.mark(e.itemId, true) else sink.progress(e.itemId, e.positionMs)
                    removeOne(userId, e); sent++
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    if (t is TvApiError.Http) {
                        if (t.status == 401) return DrainResult(sent, dropped, rejected = true)
                        if (t.status in 400..499) {   // 403 / 404: the server no longer offers it; nothing to send it to
                            val left = entries(userId).count { it.itemId == id }
                            drop(userId, id); dropped += left
                            break
                        }
                    }
                    return DrainResult(sent, dropped, unreachable = true)
                }
            }
        }
        return DrainResult(sent, dropped)
    }

    private fun removeOne(userId: String, e: OutboxEntry) {
        val all = entries(userId)
        val i = all.indexOfFirst { it.itemId == e.itemId && it.kind == e.kind }
        if (i >= 0) save(userId, all.filterIndexed { n, _ -> n != i })
    }
}
