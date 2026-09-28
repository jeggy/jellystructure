package dev.jellystructure.audiobooks

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.AudiobookPart
import dev.jellystructure.model.AudiobookAuthor
import dev.jellystructure.model.AudiobookProgress
import dev.jellystructure.model.AudiobookRules
import dev.jellystructure.model.MusicArt
import dev.jellystructure.nowEpochSec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference

/** FR-280-7 — the `/api/health` audiobooks block, and the Library's status line. */
@Serializable
data class AudiobooksHealth(
    val books: Int,
    val parts: Int,
    val authors: Int,
    @SerialName("missing_parts") val missingParts: Int,
    @SerialName("two_in_one") val twoInOne: Int,
    @SerialName("covers_missing") val coversMissing: Int,
    @SerialName("no_narrator") val noNarrator: Int,
    @SerialName("duration_ms") val durationMs: Long,
)

@Serializable
data class AudiobookBookmark(
    val id: Long,
    @SerialName("book_id") val bookId: String,
    @SerialName("position_ms") val positionMs: Long,
    val note: String? = null,
    @SerialName("created_at") val createdAt: Long,
)

/**
 * Phase 280 — the books, their parts and their authors from one in-memory snapshot (like [dev.jellystructure.music.MusicStore]),
 * written through to SQLite; and each listener's place in each book (FR-280-4), which is ours and only ours.
 */
class AudiobooksStore(private val db: JellystructureDb) {
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()
    private val cache = AtomicReference<Snapshot?>(null)
    private val versionAtomic = AtomicLong(0L)
    val version: Long get() = versionAtomic.value

    data class Snapshot(val books: Map<String, Audiobook>, val parts: Map<String, AudiobookPart>, val authors: Map<String, AudiobookAuthor>) {
        /** A book's live parts in our order. */
        val partsByBook: Map<String, List<AudiobookPart>> by lazy {
            parts.values.filter { it.missingSince == null }.groupBy { it.bookId }.mapValues { (_, l) -> l.sortedBy { it.position } }
        }
    }

    fun snapshot(): Snapshot = cache.value ?: load().also { cache.value = it }

    private fun load(): Snapshot {
        val q = db.audiobooksQueries
        return Snapshot(
            q.allBooks().executeAsList().mapNotNull { runCatching { json.decodeFromString(Audiobook.serializer(), it) }.getOrNull() }.associateBy { it.id },
            q.allParts().executeAsList().mapNotNull { runCatching { json.decodeFromString(AudiobookPart.serializer(), it) }.getOrNull() }.associateBy { it.id },
            q.allAuthors().executeAsList().mapNotNull { runCatching { json.decodeFromString(AudiobookAuthor.serializer(), it) }.getOrNull() }.associateBy { it.id },
        )
    }

    fun book(id: String) = snapshot().books[id]
    fun parts(bookId: String) = snapshot().partsByBook[bookId].orEmpty()
    fun author(id: String) = snapshot().authors[id]

    suspend fun replaceLibrary(rows: AudiobooksRows) = writeLock.withLock {
        db.transaction {
            rows.books.forEach { writeBook(it) }
            rows.parts.forEach { writePart(it) }
            rows.authors.forEach { writeAuthor(it) }
        }
        val prev = snapshot()
        cache.value = Snapshot(prev.books + rows.books.associateBy { it.id }, prev.parts + rows.parts.associateBy { it.id }, prev.authors + rows.authors.associateBy { it.id })
        versionAtomic.incrementAndGet()
    }

    suspend fun putBook(b: Audiobook) = writeLock.withLock {
        db.transaction { writeBook(b) }
        val prev = snapshot(); cache.value = prev.copy(books = prev.books + (b.id to b)); versionAtomic.incrementAndGet()
    }

    suspend fun putParts(parts: List<AudiobookPart>) = writeLock.withLock {
        if (parts.isEmpty()) return@withLock
        db.transaction { parts.forEach { writePart(it) } }
        val prev = snapshot(); cache.value = prev.copy(parts = prev.parts + parts.associateBy { it.id }); versionAtomic.incrementAndGet()
    }

    suspend fun putAuthor(a: AudiobookAuthor) = writeLock.withLock {
        db.transaction { writeAuthor(a) }
        val prev = snapshot(); cache.value = prev.copy(authors = prev.authors + (a.id to a)); versionAtomic.incrementAndGet()
    }

    private fun writeBook(b: Audiobook) = db.audiobooksQueries.putBook(b.id, b.libraryId, json.encodeToString(Audiobook.serializer(), b), b.title, b.missingSince, b.updatedAt)
    private fun writePart(p: AudiobookPart) = db.audiobooksQueries.putPart(p.id, p.bookId, json.encodeToString(AudiobookPart.serializer(), p), p.missingSince, p.updatedAt)
    private fun writeAuthor(a: AudiobookAuthor) = db.audiobooksQueries.putAuthor(a.id, json.encodeToString(AudiobookAuthor.serializer(), a), a.name, a.updatedAt)

    // ── FR-280-4: where each listener is ──

    fun progress(userId: String, bookId: String): AudiobookProgress? = db.audiobooksQueries.progressFor(userId, bookId).executeAsOneOrNull()?.let { r ->
        AudiobookProgress(r.book_id, r.part_index.toInt(), r.position_ms, AudiobookRules.bookPosition(parts(r.book_id), r.part_index.toInt(), r.position_ms), r.speed, r.updated_at, r.finished_at)
    }

    fun progressOfUser(userId: String): List<AudiobookProgress> = db.audiobooksQueries.progressOfUser(userId).executeAsList().map { r ->
        AudiobookProgress(r.book_id, r.part_index.toInt(), r.position_ms, AudiobookRules.bookPosition(parts(r.book_id), r.part_index.toInt(), r.position_ms), r.speed, r.updated_at, r.finished_at)
    }

    /** Every listener's place in one book (the admin's read-only Listeners tab, 281 FR-281-7). */
    fun progressOfBook(bookId: String): List<Pair<String, AudiobookProgress>> = db.audiobooksQueries.progressOfBook(bookId).executeAsList().map { r ->
        r.user_id to AudiobookProgress(r.book_id, r.part_index.toInt(), r.position_ms, AudiobookRules.bookPosition(parts(r.book_id), r.part_index.toInt(), r.position_ms), r.speed, r.updated_at, r.finished_at)
    }

    /** A heartbeat: the part and where in it. Finished once the last part is within its last five minutes; a heartbeat
     *  from an earlier part (a restart) takes *finished* away again. The speed is kept as it was. */
    suspend fun saveProgress(userId: String, bookId: String, partIndex: Int, positionMs: Long): AudiobookProgress = writeLock.withLock {
        val prev = db.audiobooksQueries.progressFor(userId, bookId).executeAsOneOrNull()
        val now = nowEpochSec()
        val finished = if (AudiobookRules.isFinished(parts(bookId), partIndex, positionMs)) (prev?.finished_at ?: now) else null
        db.audiobooksQueries.putProgress(userId, bookId, partIndex.toLong(), positionMs.coerceAtLeast(0L), prev?.speed ?: 1.0, now, finished)
        AudiobookProgress(bookId, partIndex, positionMs, AudiobookRules.bookPosition(parts(bookId), partIndex, positionMs), prev?.speed ?: 1.0, now, finished)
    }

    suspend fun saveSpeed(userId: String, bookId: String, speed: Double) = writeLock.withLock {
        val prev = db.audiobooksQueries.progressFor(userId, bookId).executeAsOneOrNull()
        db.audiobooksQueries.putProgress(userId, bookId, prev?.part_index ?: 0L, prev?.position_ms ?: 0L, speed.coerceIn(0.5, 3.0), nowEpochSec(), prev?.finished_at)
    }

    /** *Mark as finished* / *Start over*. */
    suspend fun setFinished(userId: String, bookId: String, finished: Boolean) = writeLock.withLock {
        val prev = db.audiobooksQueries.progressFor(userId, bookId).executeAsOneOrNull()
        val now = nowEpochSec()
        if (finished) {
            val ps = parts(bookId)
            db.audiobooksQueries.putProgress(userId, bookId, (ps.size - 1).coerceAtLeast(0).toLong(), ps.lastOrNull()?.durationMs ?: 0L, prev?.speed ?: 1.0, now, now)
        } else db.audiobooksQueries.putProgress(userId, bookId, 0L, 0L, prev?.speed ?: 1.0, now, null)
    }

    fun bookmarks(userId: String, bookId: String): List<AudiobookBookmark> =
        db.audiobooksQueries.bookmarksFor(userId, bookId).executeAsList().map { AudiobookBookmark(it.id, it.book_id, it.position_ms, it.note, it.created_at) }

    suspend fun addBookmark(userId: String, bookId: String, positionMs: Long, note: String?): AudiobookBookmark = writeLock.withLock {
        val now = nowEpochSec()
        db.transactionWithResult {
            db.audiobooksQueries.addBookmark(userId, bookId, positionMs.coerceAtLeast(0L), note?.trim()?.takeIf { it.isNotEmpty() }?.take(200), now)
            val id = db.audiobooksQueries.lastInsertedBookmark().executeAsOne()
            AudiobookBookmark(id, bookId, positionMs, note, now)
        }
    }

    suspend fun deleteBookmark(userId: String, id: Long) = writeLock.withLock { db.audiobooksQueries.deleteBookmark(id, userId) }

    fun health(): AudiobooksHealth {
        val s = snapshot()
        val books = s.books.values.filter { it.missingSince == null }
        return AudiobooksHealth(
            books = books.size, parts = s.parts.values.count { it.missingSince == null }, authors = s.authors.size,
            missingParts = books.count { it.gap.isNotEmpty() && !it.gapDismissed },
            twoInOne = books.count { it.albumTags.size > 1 && !it.twoInOneDismissed },
            coversMissing = books.count { it.coverState == MusicArt.NONE },
            noNarrator = books.count { it.narrators.isEmpty() },
            durationMs = books.sumOf { it.durationMs },
        )
    }
}
