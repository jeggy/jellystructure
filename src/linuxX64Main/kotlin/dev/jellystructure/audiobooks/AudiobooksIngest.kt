package dev.jellystructure.audiobooks

import dev.jellystructure.auth.JellyfinAudiobooksLibrary
import dev.jellystructure.auth.JellyfinMusicItem
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.AudiobookPart
import dev.jellystructure.model.AudiobookAuthor
import dev.jellystructure.model.AudiobookChapter
import dev.jellystructure.model.AudiobookGap
import dev.jellystructure.model.AudiobookOrigin
import dev.jellystructure.model.AudiobookRules
import dev.jellystructure.model.MusicArt
import dev.jellystructure.music.MusicIngest
import dev.jellystructure.util.isoToEpochSeconds

/** One books library's rows, present and missing. */
data class AudiobooksRows(val libraryId: String, val books: List<Audiobook>, val parts: List<AudiobookPart>, val authors: List<AudiobookAuthor>, val ebooks: Int)

/**
 * Phase 280 (FR-280-1/2/3) — Jellyfin's per-file items grouped into books. The group is Jellyfin's own parent folder
 * (dev review 3: every part's `ParentId` is the folder item); a file straight under the library root is a book on its
 * own. Nothing here guesses: a hole in the numbering and a folder whose parts name two albums are **flagged**, and
 * the admin decides.
 *
 * A re-read replaces only what the files say, and only for a field the files still own (281 FR-281-1: what the admin
 * typed or accepted is theirs; with the lock, nothing changes at all).
 */
object AudiobooksIngest {
    private val COVER_FILES = listOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png")
    /** Fields the files can fill (FR-281-1's first rung). */
    val FILE_FIELDS = listOf("title", "authors", "narrators", "year", "genres", "description")
    private val TICKS_PER_MS = 10_000L

    /** The number a filename carries — the last group of digits in its base name (`Book - 06.mp3` → 6). */
    fun fileNumber(path: String?): Int? {
        val name = path?.substringAfterLast('/')?.substringBeforeLast('.') ?: return null
        return Regex("(\\d{1,4})").findAll(name).lastOrNull()?.value?.toIntOrNull()
    }

    suspend fun build(
        lib: LibraryMapping,
        jf: JellyfinAudiobooksLibrary,
        previousBooks: Map<String, Audiobook>,
        previousParts: Map<String, AudiobookPart>,
        previousAuthors: Map<String, AudiobookAuthor>,
        now: Long,
        exists: (String) -> Boolean,
        chaptersOf: suspend (String) -> List<AudiobookChapter>,
    ): AudiobooksRows {
        val libId = lib.jellyfinId
        // FR-280-3 — a folder the admin split: each `Album` tag but the one the folder keeps becomes its own book.
        val split = previousBooks.values.filter { it.splitByAlbum }.associate { it.id to it.splitPrimary }
        val groups = jf.parts.groupBy { item ->
            val parent = item.parentId
            val key = if (parent.isNullOrBlank() || parent.equals(libId, ignoreCase = true)) "f:" + item.id else parent
            val album = item.album?.trim()?.takeIf { it.isNotEmpty() }
            if (key in split && album != null && album != split[key]) splitId(key, album) else key
        }
        val books = mutableListOf<Audiobook>()
        val parts = mutableListOf<AudiobookPart>()
        for ((bookId, items) in groups) {
            val prev = previousBooks[bookId]
            val rootFile = bookId.startsWith("f:") && SPLIT !in bookId
            val splitFrom = bookId.substringBefore(SPLIT, "").takeIf { it.isNotEmpty() }
            val folder = if (rootFile) null else MusicIngest.localPath(lib, items.first().path)?.substringBeforeLast('/')
            val ordered = items.sortedWith(compareBy<JellyfinMusicItem>({ it.indexNumber ?: fileNumber(it.path) ?: Int.MAX_VALUE }, { it.path.orEmpty() }))
            // Our order: kept for a part already known (the admin may have dragged it), new ones after.
            var nextPos = (previousParts.values.filter { it.bookId == bookId }.maxOfOrNull { it.position } ?: -1) + 1
            val bookParts = ordered.map { it ->
                val p = previousParts[it.id]
                val stream = it.mediaStreams.firstOrNull { s -> s.type.equals("Audio", true) }
                AudiobookPart(
                    id = it.id, bookId = bookId, number = it.indexNumber ?: fileNumber(it.path), fileNumber = fileNumber(it.path),
                    // A part that moved book (a split or a join) starts after this book's own parts.
                    position = p?.takeIf { old -> old.bookId == bookId }?.position ?: nextPos++, path = MusicIngest.localPath(lib, it.path),
                    durationMs = it.runTimeTicks?.let { t -> t / TICKS_PER_MS }, title = it.name,
                    albumTag = it.album?.takeIf { a -> a.isNotBlank() }, codec = stream?.codec, container = it.container, bitrate = stream?.bitRate,
                    missingSince = null, updatedAt = now,
                )
            }
            parts += bookParts
            val numbers = bookParts.mapNotNull { it.number }.distinct().sorted()
            val gap = if (numbers.size >= 2) (numbers.first()..numbers.last()).filter { n -> n !in numbers } else emptyList()
            val gapKind = when {
                gap.isEmpty() -> null
                ordered.any { i -> i.indexNumber != null && fileNumber(i.path) != null && i.indexNumber != fileNumber(i.path) } -> AudiobookGap.NUMBERED_WRONG
                else -> AudiobookGap.NOT_IN_FOLDER
            }
            val albumTags = ordered.mapNotNull { it.album?.trim()?.takeIf { a -> a.isNotEmpty() } }.distinct()
            val folderName = folder?.substringAfterLast('/') ?: items.first().name
            val title = albumTags.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: folderName
            val authors = (ordered.flatMap { it.albumArtists } + ordered.flatMap { it.artistItems }).map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()
            val narrators = ordered.flatMap { it.people }.filter { it.type.equals("Composer", true) }.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()
            val duration = bookParts.sumOf { it.durationMs ?: 0L }
            val cover = when {
                folder != null && splitFrom != null && exists("$folder/${splitCoverName(bookId)}") -> MusicArt.FILE
                folder != null && splitFrom == null && COVER_FILES.any { exists("$folder/$it") } -> MusicArt.FILE
                ordered.any { !it.imageTags["Primary"].isNullOrBlank() } -> MusicArt.JELLYFIN
                else -> MusicArt.NONE
            }
            // FR-280-2 — ffprobe's chapters only for a one-file book, and only once per file length.
            val singlePath = bookParts.singleOrNull()?.path
            val chapters = when {
                singlePath == null -> emptyList()
                prev != null && prev.durationMs == duration && prev.partCount == 1 -> prev.embeddedChapters
                else -> chaptersOf(singlePath)
            }
            val fromFiles = Audiobook(
                id = bookId, libraryId = libId, folderPath = folder, title = title, authors = authors, narrators = narrators,
                year = ordered.mapNotNull { it.year }.minOrNull(), description = ordered.firstNotNullOfOrNull { it.overview?.takeIf { o -> o.isNotBlank() } },
                genres = ordered.flatMap { it.genres }.filter { it.isNotBlank() }.distinct(), coverState = cover,
                durationMs = duration, partCount = bookParts.size, gap = gap, gapKind = gapKind,
                albumTags = if (albumTags.size > 1) albumTags else emptyList(), embeddedChapters = chapters,
                chapterSource = if (prev == null && chapters.isNotEmpty()) "embedded" else prev?.chapterSource ?: "files",
                addedAt = ordered.mapNotNull { it.dateCreated?.let { d -> isoToEpochSeconds(d) } }.minOrNull(),
                splitFrom = splitFrom,
            )
            books += carry(fromFiles, prev, now)
        }
        // Rows Jellyfin no longer has are kept and marked (the films' rule).
        val seenBooks = books.map { it.id }.toSet(); val seenParts = parts.map { it.id }.toSet()
        books += previousBooks.values.filter { it.libraryId == libId && it.id !in seenBooks && it.missingSince == null }.map { it.copy(missingSince = now, updatedAt = now) }
        parts += previousParts.values.filter { p -> p.id !in seenParts && p.missingSince == null && previousBooks[p.bookId]?.libraryId == libId }.map { it.copy(missingSince = now, updatedAt = now) }

        val authorNames = books.filter { it.missingSince == null }.flatMap { it.authors }.distinctBy { AudiobookRules.authorId(it) }
        val authors = authorNames.map { name ->
            val id = AudiobookRules.authorId(name)
            previousAuthors[id]?.copy(name = name, updatedAt = now) ?: AudiobookAuthor(id = id, name = name, sortName = sortName(name), updatedAt = now)
        }
        return AudiobooksRows(libId, books, parts, authors, jf.ebookCount)
    }

    /** The separator between a folder's id and the tag of a book split off it. */
    const val SPLIT = "~"

    /** A split-off book's id: the folder's id, then the `Album` tag folded to letters and digits. */
    fun splitId(folderId: String, album: String): String = folderId + SPLIT + album.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "x" }

    /** A split-off book's cover sits beside the folder's own `cover.jpg`, named for its tag. */
    fun splitCoverName(bookId: String): String = "cover-" + bookId.substringAfter(SPLIT) + ".jpg"

    /** `Ingrid Lykke` → `Lykke, Ingrid` — the shelf's author sort. */
    fun sortName(name: String): String {
        val words = name.trim().split(Regex("\\s+"))
        return if (words.size < 2) name else words.last() + ", " + words.dropLast(1).joinToString(" ")
    }

    private fun carry(fresh: Audiobook, prev: Audiobook?, now: Long): Audiobook {
        if (prev == null) return fresh.copy(createdAt = now, updatedAt = now, origins = FILE_FIELDS.associateWith { AudiobookOrigin.FILES })
        fun owned(field: String) = !prev.locked && (prev.origins[field] ?: AudiobookOrigin.FILES) == AudiobookOrigin.FILES
        val merged = prev.copy(
            libraryId = fresh.libraryId, folderPath = fresh.folderPath, coverState = fresh.coverState,
            coverSource = if (fresh.coverState == MusicArt.NONE) null else prev.coverSource,
            durationMs = fresh.durationMs, partCount = fresh.partCount, gap = fresh.gap, gapKind = fresh.gapKind,
            albumTags = fresh.albumTags, embeddedChapters = fresh.embeddedChapters,
            chapterSource = if (fresh.embeddedChapters.isEmpty()) "files" else prev.chapterSource,
            addedAt = fresh.addedAt ?: prev.addedAt, missingSince = null, splitFrom = fresh.splitFrom,
            title = if (owned("title")) fresh.title else prev.title,
            authors = if (owned("authors")) fresh.authors else prev.authors,
            narrators = if (owned("narrators")) fresh.narrators else prev.narrators,
            year = if (owned("year")) fresh.year else prev.year,
            genres = if (owned("genres")) fresh.genres else prev.genres,
            description = if (owned("description")) fresh.description else prev.description,
        )
        return if (merged == prev) prev else merged.copy(updatedAt = now)
    }
}
