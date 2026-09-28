package dev.jellystructure.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 280 — audiobooks as jellystructure keeps them. Jellyfin makes one item per **file** in a `books` library; the
 * **folder is the book** here (FR-280-1, Jellyfin's own held PR #17362 does the same). Like music, not a
 * [MediaKind]: nothing here is read by a film or series path. JSON-blob rows: adding a field needs no migration.
 *
 * Phase 281 inverts the film ladder: the files' tags come first, what the admin types second, and a provider only
 * ever **suggests**. [Audiobook.origins] says, per field, which of them shaped the value.
 */

/** Where a field's value came from (FR-281-1/2). A provider's own name is also a valid origin (*iTunes*…). */
object AudiobookOrigin {
    const val FILES = "files"
    const val TYPED = "typed"
}

/** FR-280-3 — why the part numbers have a hole. */
object AudiobookGap {
    /** The filenames agree with Jellyfin's numbering: the part is not in the folder. */
    const val NOT_IN_FOLDER = "not_in_folder"
    /** The filenames and Jellyfin disagree: most likely only numbered wrong. */
    const val NUMBERED_WRONG = "numbered_wrong"
}

/** One chapter as the book player shows it (FR-281-6): embedded (a single M4B) or one per part. */
@Serializable
data class AudiobookChapter(
    val title: String,
    /** Where it starts in the **book** (all parts end to end), and how long it is. */
    @SerialName("start_ms") val startMs: Long,
    @SerialName("length_ms") val lengthMs: Long,
    /** Which part (by our order, 0-based) it is in, and where in that part it starts. */
    val part: Int = 0,
    @SerialName("part_offset_ms") val partOffsetMs: Long = 0,
)

/** FR-281-3 — one provider's answer: what it found (or one sentence on why nothing), and what it would fill. */
@Serializable
data class AudiobookSuggestion(
    val provider: String,
    val found: Boolean = false,
    /** The one sentence a card says, found or not (*It knows the author — 5 other audiobooks, none of them this one*). */
    val note: String = "",
    val title: String? = null,
    val author: String? = null,
    val year: Int? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    /** field → value it would fill (`title`, `subtitle`, `authors`, `narrators`, `series`, `series_position`, `year`,
     *  `publisher`, `language`, `genres`, `description`). Lists are joined with `; `. */
    val fields: Map<String, String> = emptyMap(),
    /** Fields the admin has applied from this card. */
    val applied: List<String> = emptyList(),
)

@Serializable
data class Audiobook(
    /** The folder's Jellyfin id (every part's `ParentId`), or `f:` + the first part's id for a file straight under the library root. */
    val id: String,
    val libraryId: String? = null,
    /** The book's folder on jellystructure's side. */
    val folderPath: String? = null,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: String? = null,
    val seriesPosition: String? = null,
    val year: Int? = null,
    val publisher: String? = null,
    val language: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    /** [MusicArt]: `cover.jpg` in the folder, art only inside the files, or nothing. */
    val coverState: String = MusicArt.NONE,
    val coverSource: String? = null,
    val durationMs: Long = 0,
    val partCount: Int = 0,
    /** field → [AudiobookOrigin] or a provider's name. A field absent here came from the files. */
    val origins: Map<String, String> = emptyMap(),
    /** FR-281-2 — a re-read never changes what the admin typed; with the lock it changes nothing at all. */
    val locked: Boolean = false,
    /** FR-280-3 — part numbers missing between the first and the last, why, and the admin's *It's just numbered wrong*. */
    val gap: List<Int> = emptyList(),
    val gapKind: String? = null,
    val gapDismissed: Boolean = false,
    /** FR-280-3 — the distinct `Album` tags when the folder's parts disagree (two books in one folder?). */
    val albumTags: List<String> = emptyList(),
    val twoInOneDismissed: Boolean = false,
    /** FR-280-3 — *Split into two books…* on a folder whose parts name two albums: the folder's book keeps the parts
     *  tagged [splitPrimary]; each other tag becomes a book of its own (`<folder id>~<tag>`). The files are not moved. */
    val splitByAlbum: Boolean = false,
    val splitPrimary: String? = null,
    /** On a book split off a folder: that folder's book id (*Join back into one book* lives there). */
    val splitFrom: String? = null,
    /** FR-281-6 — the chapters a single file carries; empty for a multi-part book (then each part is one). */
    val embeddedChapters: List<AudiobookChapter> = emptyList(),
    /** `embedded` or `files` — which the player shows; embedded only when there are any. */
    val chapterSource: String = "files",
    /** The admin's renames, by chapter index. */
    val chapterTitles: Map<String, String> = emptyMap(),
    @SerialName("suggestions_asked_at") val suggestionsAskedAt: Long? = null,
    val suggestions: List<AudiobookSuggestion> = emptyList(),
    /** FR-281-8 — when tags were last written into the parts (only with the switch on). */
    val tagsWrittenAt: Long? = null,
    val addedAt: Long? = null,
    val missingSince: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class AudiobookPart(
    /** The file's Jellyfin item id. */
    val id: String,
    val bookId: String,
    /** The file's own number (`IndexNumber`, else the filename's); kept as it is. */
    val number: Int? = null,
    /** The number the filename carries, when it has one — compared with [number] to tell a gap's kind. */
    val fileNumber: Int? = null,
    /** Our order (FR-280-1): the file's number at first, then wherever the admin drags it. Never renames a file. */
    val position: Int = 0,
    val path: String? = null,
    val durationMs: Long? = null,
    /** The file's title tag. */
    val title: String = "",
    val albumTag: String? = null,
    val codec: String? = null,
    val container: String? = null,
    val bitrate: Int? = null,
    val missingSince: Long? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class AudiobookAuthor(
    /** `a:` + the name folded to letters and digits — the same person across books, whatever the tag's spacing. */
    val id: String,
    val name: String,
    val sortName: String? = null,
    val imageState: String = MusicArt.NONE,
    val bio: String? = null,
    val bioSource: String? = null,
    val updatedAt: Long = 0,
)

/** FR-280-4 — where one listener is in one book, in **book** time. */
@Serializable
data class AudiobookProgress(
    @SerialName("book_id") val bookId: String,
    @SerialName("part_index") val partIndex: Int = 0,
    /** Position inside the current part. */
    @SerialName("position_ms") val positionMs: Long = 0,
    /** Position in the whole book (all parts end to end). */
    @SerialName("book_position_ms") val bookPositionMs: Long = 0,
    val speed: Double = 1.0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("finished_at") val finishedAt: Long? = null,
)

object AudiobookRules {
    /** FR-280-4 — finished when the last part is within its last five minutes (Jellyfin's `MaxAudiobookResume`). */
    const val FINISH_WINDOW_MS = 5 * 60_000L
    /** FR-281-5 — Jellyfin only saves a position after five minutes of a file (`MinAudiobookResume`). */
    const val JELLYFIN_MIN_RESUME_MS = 5 * 60_000L

    fun authorId(name: String): String = "a:" + name.lowercase().filter { it.isLetterOrDigit() }

    /** The book's chapters: embedded when chosen and present, else one per part in our order. */
    fun chapters(book: Audiobook, parts: List<AudiobookPart>): List<AudiobookChapter> {
        val ordered = parts.filter { it.missingSince == null }.sortedBy { it.position }
        val base = if (book.chapterSource == "embedded" && book.embeddedChapters.isNotEmpty()) book.embeddedChapters
        else {
            var start = 0L
            ordered.mapIndexed { i, p ->
                val len = p.durationMs ?: 0L
                AudiobookChapter(p.title.ifBlank { "" }, start, len, i, 0L).also { start += len }
            }
        }
        return base.mapIndexed { i, c -> book.chapterTitles[i.toString()]?.let { c.copy(title = it) } ?: c }
    }

    /** Book time of (part, position): the parts before it end to end, plus the position. */
    fun bookPosition(parts: List<AudiobookPart>, partIndex: Int, positionMs: Long): Long {
        val ordered = parts.filter { it.missingSince == null }.sortedBy { it.position }
        return ordered.take(partIndex.coerceAtLeast(0)).sumOf { it.durationMs ?: 0L } + positionMs
    }

    fun isFinished(parts: List<AudiobookPart>, partIndex: Int, positionMs: Long): Boolean {
        val ordered = parts.filter { it.missingSince == null }.sortedBy { it.position }
        val last = ordered.lastOrNull() ?: return false
        if (partIndex != ordered.lastIndex) return false
        val d = last.durationMs ?: return false
        return d - positionMs <= FINISH_WINDOW_MS
    }
}
