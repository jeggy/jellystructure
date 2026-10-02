package dev.jellystructure.audiobooks

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.AudiobookPart
import dev.jellystructure.model.AudiobookProgress
import dev.jellystructure.model.AudiobookRules
import dev.jellystructure.model.MusicArt
import dev.jellystructure.music.musicVisible
import dev.jellystructure.shared.tv.AudiobookAuthorDetail
import dev.jellystructure.shared.tv.AudiobookAuthorRef
import dev.jellystructure.shared.tv.AudiobookBookmarkItem
import dev.jellystructure.shared.tv.AudiobookCard
import dev.jellystructure.shared.tv.AudiobookChapterItem
import dev.jellystructure.shared.tv.AudiobookDetail
import dev.jellystructure.shared.tv.AudiobookPartItem
import dev.jellystructure.shared.tv.AudiobookPosition
import dev.jellystructure.shared.tv.AudiobookShelf
import io.ktor.http.encodeURLPathPart

/**
 * Phase 281 (FR-281-10) — the phone's audiobooks, scoped to the viewer (a library the viewer may not open is not
 * there at all). The position, the speed and the bookmarks are **ours** (280 FR-280-4); Jellyfin gets a mirror of
 * the position on the part being played ([mirror]) and the earlier parts marked played ([markPlayed]), and is never
 * read back — it keeps no position in a part under five minutes.
 */
class AudiobooksTvService(
    private val store: AudiobooksStore,
    /** A heartbeat relayed to Jellyfin on the part's own item (the session the part's play started). */
    /** R357 (FR-R357-2) — with the player's volume (0–100) and mute when the heartbeat carried them. */
    private val mirror: suspend (device: DeviceData, partId: String, positionMs: Long, paused: Boolean, volumePercent: Int?, muted: Boolean?) -> Unit = { _, _, _, _, _, _ -> },
    /** Parts the listener has moved past, marked played in Jellyfin. */
    private val markPlayed: suspend (device: DeviceData, partIds: List<String>) -> Unit = { _, _ -> },
) {
    companion object {
        val SORTS = setOf("added", "title", "author", "series")
        fun coverUrl(b: Audiobook): String? = if (b.coverState != MusicArt.NONE) "/api/tv/image/audiobook/${b.id.encodeURLPathPart()}?v=${b.updatedAt}" else null
    }

    private fun visibleBooks(device: DeviceData): List<Audiobook> =
        store.snapshot().books.values.filter { it.missingSince == null && it.partCount > 0 && musicVisible(it.libraryId, device.allowedLibraries) }

    private fun visibleBook(device: DeviceData, id: String): Audiobook? =
        store.book(id)?.takeIf { it.missingSince == null && musicVisible(it.libraryId, device.allowedLibraries) }

    /** Our order, present parts only — index = the phone's part number. */
    fun parts(bookId: String): List<AudiobookPart> = store.parts(bookId).filter { it.missingSince == null }.sortedBy { it.position }

    private fun authors(b: Audiobook) = b.authors.map { AudiobookAuthorRef(AudiobookRules.authorId(it), it) }

    private fun card(b: Audiobook, p: AudiobookProgress?): AudiobookCard {
        val ps = parts(b.id)
        val chapters = AudiobookRules.chapters(b, ps)
        val started = p != null && (p.bookPositionMs > 0 || p.finishedAt != null)
        val chapter = if (started) chapters.indexOfLast { it.startMs <= p.bookPositionMs }.coerceAtLeast(0) + 1 else null
        return AudiobookCard(
            id = b.id, title = b.title, authors = authors(b), coverUrl = coverUrl(b), durationMs = b.durationMs,
            positionMs = if (started) p.bookPositionMs else null,
            leftMs = if (started && p.finishedAt == null) (b.durationMs - p.bookPositionMs).coerceAtLeast(0) else null,
            finished = p?.finishedAt != null, chapter = chapter, chapters = chapters.size,
            part = if (started) p.partIndex + 1 else null, parts = ps.size, series = b.series, seriesPosition = b.seriesPosition,
        )
    }

    private fun sorted(books: List<Audiobook>, sort: String): List<Audiobook> = when (sort) {
        "title" -> books.sortedBy { it.title.lowercase() }
        "author" -> books.sortedWith(compareBy({ it.authors.firstOrNull()?.let { a -> AudiobooksIngest.sortName(a) }?.lowercase() ?: "" }, { it.title.lowercase() }))
        "series" -> books.sortedWith(compareBy({ it.series?.lowercase() ?: "￿" }, { it.seriesPosition?.toDoubleOrNull() ?: 0.0 }, { it.title.lowercase() }))
        else -> books.sortedWith(compareByDescending<Audiobook> { it.addedAt ?: it.createdAt }.thenBy { it.title.lowercase() })
    }

    /** FR-R323-1 — Continue listening (in progress, newest listen first), then every book in [sort]'s order. */
    fun shelf(device: DeviceData, sort: String?, author: String? = null, series: String? = null): AudiobookShelf {
        val all = visibleBooks(device)
        val mine = store.progressOfUser(device.jellyfinUserId).associateBy { it.bookId }
        val s = sort?.takeIf { it in SORTS } ?: "added"
        val filtered = all.filter { b -> (author == null || b.authors.any { AudiobookRules.authorId(it) == author }) && (series == null || b.series == series) }
        val cont = all.mapNotNull { b -> mine[b.id]?.takeIf { it.finishedAt == null && it.bookPositionMs > 0 }?.let { b to it } }
            .sortedByDescending { it.second.updatedAt }.map { (b, p) -> card(b, p) }
        val authorRefs = all.flatMap { authors(it) }.distinctBy { it.id }.sortedBy { AudiobooksIngest.sortName(it.name).lowercase() }
        return AudiobookShelf(
            continueListening = if (author == null && series == null) cont else emptyList(),
            books = sorted(filtered, s).map { card(it, mine[it.id]) }, sort = s,
            authors = if (authorRefs.size > 1) authorRefs else emptyList(),
            series = all.mapNotNull { it.series?.takeIf { n -> n.isNotBlank() } }.distinct().sortedBy { it.lowercase() },
        )
    }

    private fun position(p: AudiobookProgress) = AudiobookPosition(p.partIndex, p.positionMs, p.bookPositionMs, p.finishedAt != null, p.updatedAt)

    fun detail(device: DeviceData, id: String): AudiobookDetail? {
        val b = visibleBook(device, id) ?: return null
        val ps = parts(b.id)
        val p = store.progress(device.jellyfinUserId, b.id)
        return AudiobookDetail(
            id = b.id, title = b.title, subtitle = b.subtitle, authors = authors(b), narrators = b.narrators, year = b.year,
            description = b.description, series = b.series, seriesPosition = b.seriesPosition, coverUrl = coverUrl(b), durationMs = b.durationMs,
            chapters = AudiobookRules.chapters(b, ps).mapIndexed { i, c -> AudiobookChapterItem(c.title.ifBlank { "" }, c.startMs, c.lengthMs, c.part, c.partOffsetMs) },
            parts = ps.mapIndexed { i, x -> AudiobookPartItem(x.id, i, x.durationMs ?: 0) },
            position = p?.let { position(it) }, speed = p?.speed ?: 1.0,
            bookmarks = store.bookmarks(device.jellyfinUserId, b.id).map { AudiobookBookmarkItem(it.id, it.positionMs, it.note, it.createdAt) },
        )
    }

    fun author(device: DeviceData, id: String): AudiobookAuthorDetail? {
        val a = store.author(id) ?: return null
        val books = visibleBooks(device).filter { b -> b.authors.any { AudiobookRules.authorId(it) == id } }
        if (books.isEmpty()) return null
        val mine = store.progressOfUser(device.jellyfinUserId).associateBy { it.bookId }
        return AudiobookAuthorDetail(a.id, a.name, a.bio, sorted(books, "series").map { card(it, mine[it.id]) })
    }

    /** The Jellyfin item to play for (book, part) — null when the book or the part is not this viewer's to play. */
    fun partId(device: DeviceData, bookId: String, part: Int): String? {
        visibleBook(device, bookId) ?: return null
        return parts(bookId).getOrNull(part)?.id
    }

    /** A heartbeat (dev review of 280, item 1): ours first, then the mirror. */
    suspend fun progress(device: DeviceData, bookId: String, part: Int, positionMs: Long, paused: Boolean,
                         volumePercent: Int? = null, muted: Boolean? = null): AudiobookPosition? {
        visibleBook(device, bookId) ?: return null
        val ps = parts(bookId)
        val here = ps.getOrNull(part) ?: return null
        val before = store.progress(device.jellyfinUserId, bookId)
        val saved = store.saveProgress(device.jellyfinUserId, bookId, part, positionMs.coerceIn(0L, here.durationMs ?: Long.MAX_VALUE))
        runCatching { mirror(device, here.id, saved.positionMs, paused, volumePercent, muted) }
        val from = before?.partIndex ?: 0
        if (part > from) runCatching { markPlayed(device, ps.subList(from.coerceAtLeast(0), part).map { it.id }) }
        return position(saved)
    }

    suspend fun speed(device: DeviceData, bookId: String, speed: Double): Boolean {
        visibleBook(device, bookId) ?: return false
        store.saveSpeed(device.jellyfinUserId, bookId, speed)
        return true
    }

    suspend fun finished(device: DeviceData, bookId: String, finished: Boolean): AudiobookPosition? {
        visibleBook(device, bookId) ?: return null
        store.setFinished(device.jellyfinUserId, bookId, finished)
        return store.progress(device.jellyfinUserId, bookId)?.let { position(it) }
    }

    suspend fun addBookmark(device: DeviceData, bookId: String, positionMs: Long, note: String?): AudiobookBookmarkItem? {
        val b = visibleBook(device, bookId) ?: return null
        val bm = store.addBookmark(device.jellyfinUserId, bookId, positionMs.coerceIn(0L, b.durationMs.coerceAtLeast(0L)), note)
        return AudiobookBookmarkItem(bm.id, bm.positionMs, bm.note, bm.createdAt)
    }

    suspend fun deleteBookmark(device: DeviceData, bookmarkId: Long) = store.deleteBookmark(device.jellyfinUserId, bookmarkId)

    /** Whether this viewer has any audiobook at all (the phone's listening mode and shelf chip). */
    fun any(device: DeviceData): Boolean = visibleBooks(device).isNotEmpty()
}
