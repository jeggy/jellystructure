package dev.jellystructure.audiobooks

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.AudiobookOrigin
import dev.jellystructure.model.AudiobookSuggestion
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtCandidate
import dev.jellystructure.nowEpochSec
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Phase 281 — everything the admin does to a book: the editor (FR-281-2), the suggestions and what is taken from
 * them (FR-281-3), the lock, our part order (FR-281-5), the chapter source and titles (FR-281-6), the cover
 * (FR-281-7) and Save — `cover.jpg` always, and the tags inside the parts only with the switch on (FR-281-8).
 * Every change is recorded in the book's History.
 */
class AudiobooksMediaService(
    private val store: AudiobooksStore,
    private val providers: AudiobookProviders,
    private val artwork: ArtworkDownloader,
    private val configStore: ConfigStore,
    private val jellyfin: JellyfinClient,
    private val seeding: SeedingGuard?,
    private val history: MediaHistory?,
    /** Re-reads one library (a split or a join takes effect through the same grouping a scan does). */
    private val rescan: suspend (libraryId: String?) -> Unit = {},
) {
    companion object {
        /** The fields the Details tab edits, in its order. */
        val FIELDS = listOf("title", "subtitle", "authors", "narrators", "series", "series_position", "year", "publisher", "language", "genres", "description")
        private val LIST_FIELDS = setOf("authors", "narrators", "genres")
        private fun split(v: String?): List<String> = v.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /** A field's value as the editor shows it (lists joined with `; `). */
        fun valueOf(b: Audiobook, field: String): String? = when (field) {
            "title" -> b.title; "subtitle" -> b.subtitle; "authors" -> b.authors.joinToString("; ").ifEmpty { null }
            "narrators" -> b.narrators.joinToString("; ").ifEmpty { null }; "series" -> b.series; "series_position" -> b.seriesPosition
            "year" -> b.year?.toString(); "publisher" -> b.publisher; "language" -> b.language
            "genres" -> b.genres.joinToString("; ").ifEmpty { null }; "description" -> b.description; else -> null
        }

        fun withField(b: Audiobook, field: String, value: String?): Audiobook {
            val v = value?.trim()?.takeIf { it.isNotEmpty() }
            return when (field) {
                "title" -> b.copy(title = v ?: b.title)
                "subtitle" -> b.copy(subtitle = v)
                "authors" -> b.copy(authors = split(v))
                "narrators" -> b.copy(narrators = split(v))
                "series" -> b.copy(series = v)
                "series_position" -> b.copy(seriesPosition = v)
                "year" -> b.copy(year = v?.take(4)?.toIntOrNull())
                "publisher" -> b.copy(publisher = v)
                "language" -> b.copy(language = v)
                "genres" -> b.copy(genres = split(v))
                "description" -> b.copy(description = v)
                else -> b
            }
        }

        /** The script that writes tags into one file (dev review 1: a tagger, not a remux — an M4B's chapter atoms stay).
         *  Arguments: the path, then `key=value` pairs; an empty value removes the tag. */
        private const val TAG_SCRIPT = """
import sys, mutagen
from mutagen.id3 import ID3, TIT2, TALB, TPE1, TPE2, TCOM, COMM, TPUB, TCON, TRCK, ID3NoHeaderError
from mutagen.mp4 import MP4
p = sys.argv[1]
kv = dict(a.split('=', 1) for a in sys.argv[2:])
low = p.lower()
if low.endswith('.mp3'):
    try: t = ID3(p)
    except ID3NoHeaderError: t = ID3()
    frames = {'title': TIT2, 'album': TALB, 'artist': TPE1, 'albumartist': TPE2, 'composer': TCOM, 'publisher': TPUB, 'genre': TCON, 'tracknumber': TRCK}
    for k, v in kv.items():
        if k == 'comment':
            t.delall('COMM')
            if v: t.add(COMM(encoding=3, lang='und', desc='', text=v))
        elif k in frames:
            t.delall(frames[k].__name__)
            if v: t.add(frames[k](encoding=3, text=v))
    t.save(p)
elif low.endswith(('.m4b', '.m4a', '.mp4')):
    f = MP4(p)
    keys = {'title': '\xa9nam', 'album': '\xa9alb', 'artist': '\xa9ART', 'albumartist': 'aART', 'composer': '\xa9wrt', 'comment': '\xa9cmt', 'genre': '\xa9gen'}
    for k, v in kv.items():
        if k == 'tracknumber':
            if v: f['trkn'] = [(int(v), 0)]
        elif k in keys:
            if v: f[keys[k]] = [v]
            elif keys[k] in f: del f[keys[k]]
    f.save()
else:
    f = mutagen.File(p)
    if f is None: sys.exit(2)
    if f.tags is None: f.add_tags()
    names = {'title': 'TITLE', 'album': 'ALBUM', 'artist': 'ARTIST', 'albumartist': 'ALBUMARTIST', 'composer': 'COMPOSER', 'comment': 'COMMENT', 'publisher': 'ORGANIZATION', 'genre': 'GENRE', 'tracknumber': 'TRACKNUMBER'}
    for k, v in kv.items():
        n = names.get(k)
        if not n: continue
        if v: f.tags[n] = [v]
        elif n in f.tags: del f.tags[n]
    f.save()
"""
        private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }

    private var taggerChecked: Boolean? = null

    /** Whether this server can write tags at all (python3 with mutagen in the image). */
    @OptIn(ExperimentalForeignApi::class)
    fun taggerAvailable(): Boolean = taggerChecked ?: (platform.posix.system("python3 -c 'import mutagen' >/dev/null 2>&1") == 0).also { taggerChecked = it }

    private fun note(id: String, action: String, detail: String) { runCatching { history?.record(id, action, detail) } }

    // ── the editor (FR-281-2) ──

    suspend fun edit(bookId: String, field: String, value: String?): Audiobook? {
        val b = store.book(bookId) ?: return null
        if (field !in FIELDS) return b
        val next = withField(b, field, value).let { it.copy(origins = it.origins + (field to AudiobookOrigin.TYPED), updatedAt = nowEpochSec()) }
        if (next == b) return b
        store.putBook(next)
        note(bookId, "audiobook_edit", "${field.replace('_', ' ')} typed here")
        return next
    }

    suspend fun setLocked(bookId: String, locked: Boolean): Audiobook? {
        val b = store.book(bookId) ?: return null
        note(bookId, "audiobook_lock", if (locked) "Locked — a re-read changes nothing" else "Unlocked — a re-read may fill what the files say")
        return b.copy(locked = locked, updatedAt = nowEpochSec()).also { store.putBook(it) }
    }

    // ── suggestions (FR-281-3) ──

    suspend fun ask(bookId: String, asin: String?): Audiobook? {
        val b = store.book(bookId) ?: return null
        val cards = providers.ask(b, asin)
        // What the admin already took from a card stays marked on it.
        val merged = cards.map { c -> b.suggestions.firstOrNull { it.provider == c.provider }?.let { old -> c.copy(applied = old.applied.filter { f -> c.fields[f] == old.fields[f] }) } ?: c }
        val next = b.copy(suggestions = merged, suggestionsAskedAt = nowEpochSec(), updatedAt = nowEpochSec())
        store.putBook(next)
        note(bookId, "audiobook_suggest", "Asked ${cards.joinToString(", ") { it.provider }} — ${cards.count { it.found }} found it")
        return next
    }

    /** M6·2's lean — per field: only the lines taken change, and each field's origin becomes the provider. */
    suspend fun apply(bookId: String, provider: String, fields: List<String>): Audiobook? {
        var b = store.book(bookId) ?: return null
        val card: AudiobookSuggestion = b.suggestions.firstOrNull { it.provider == provider } ?: return b
        val taken = fields.filter { it in card.fields && it in FIELDS }
        for (f in taken) b = withField(b, f, card.fields[f]).let { it.copy(origins = it.origins + (f to provider)) }
        b = b.copy(suggestions = b.suggestions.map { if (it.provider == provider) it.copy(applied = (it.applied + taken).distinct()) else it }, updatedAt = nowEpochSec())
        store.putBook(b)
        note(bookId, "audiobook_apply", "From $provider: ${taken.joinToString(", ") { it.replace('_', ' ') }}")
        return b
    }

    // ── parts, chapters, flags ──

    /** FR-281-5 — our order, never a rename. */
    suspend fun order(bookId: String, partIds: List<String>): Boolean {
        val parts = store.parts(bookId)
        if (parts.isEmpty() || partIds.toSet() != parts.map { it.id }.toSet()) return false
        val byId = parts.associateBy { it.id }
        store.putParts(partIds.mapIndexed { i, id -> byId.getValue(id).copy(position = i, updatedAt = nowEpochSec()) })
        note(bookId, "audiobook_order", "Parts reordered here · the files are not renamed")
        return true
    }

    suspend fun chapters(bookId: String, source: String?, titles: Map<String, String>?): Audiobook? {
        val b = store.book(bookId) ?: return null
        val next = b.copy(
            chapterSource = source?.takeIf { it == "embedded" || it == "files" } ?: b.chapterSource,
            chapterTitles = titles?.let { t -> (b.chapterTitles + t).filterValues { it.isNotBlank() } } ?: b.chapterTitles,
            updatedAt = nowEpochSec(),
        )
        store.putBook(next)
        return next
    }

    /** FR-280-3 — *It's just numbered wrong* / *It's one book*: stays dismissed for that folder. */
    suspend fun dismiss(bookId: String, what: String): Audiobook? {
        val b = store.book(bookId) ?: return null
        val next = when (what) { "gap" -> b.copy(gapDismissed = true); "two_in_one" -> b.copy(twoInOneDismissed = true); else -> return b }
        store.putBook(next.copy(updatedAt = nowEpochSec()))
        note(bookId, "audiobook_flag", if (what == "gap") "Marked as just numbered wrong" else "Marked as one book")
        return next
    }

    // ── Split into two books… / Join back (FR-280-3) ──

    /** One group per `Album` tag, in part order; the first keeps the folder's page. */
    fun splitPreview(b: Audiobook): List<dev.jellystructure.model.AudiobookSplitGroup> {
        if (b.albumTags.size < 2 || b.twoInOneDismissed) return emptyList()
        val parts = store.parts(b.id).filter { it.missingSince == null }.sortedBy { it.position }
        val tags = parts.mapNotNull { it.albumTag?.trim()?.takeIf { t -> t.isNotEmpty() } }.distinct()
        return tags.mapIndexed { i, t ->
            dev.jellystructure.model.AudiobookSplitGroup(t, parts.filter { it.albumTag?.trim() == t || (i == 0 && it.albumTag.isNullOrBlank()) }.map { p -> p.number?.let { "$it · ${p.title}" } ?: p.title }, i == 0)
        }
    }

    suspend fun split(bookId: String): Audiobook? {
        val b = store.book(bookId) ?: return null
        val first = splitPreview(b).firstOrNull()?.title ?: return b
        val next = b.copy(splitByAlbum = true, splitPrimary = first, updatedAt = nowEpochSec())
        store.putBook(next)
        note(bookId, "audiobook_split", "Split into ${b.albumTags.size} books by the files' album tags · the files are not moved")
        rescan(b.libraryId)
        return store.book(bookId)
    }

    /** On the folder's own book, or on a book split off it: the folder becomes one book again. */
    suspend fun join(bookId: String): Audiobook? {
        val b = store.book(bookId) ?: return null
        val folder = store.book(b.splitFrom ?: b.id) ?: return null
        store.putBook(folder.copy(splitByAlbum = false, splitPrimary = null, twoInOneDismissed = true, updatedAt = nowEpochSec()))
        note(folder.id, "audiobook_split", "Joined back into one book")
        rescan(folder.libraryId)
        return store.book(folder.id)
    }

    /** ⋯ → *Re-read the files*: tags, part order and lengths from Jellyfin (the book's library only). */
    suspend fun reread(bookId: String): Audiobook? {
        val b = store.book(bookId) ?: return null
        rescan(b.libraryId)
        note(bookId, "audiobook_reread", "Re-read from Jellyfin")
        return store.book(bookId)
    }

    /** *Sync Jellyfin* — ask Jellyfin to re-read the folder, nothing written. */
    suspend fun sync(bookId: String): Boolean {
        val b = store.book(bookId) ?: return false
        val cfg = configStore.current
        if (cfg.apiKeys.jellyfinUrl.isBlank()) return false
        val target = b.splitFrom ?: b.id
        if (target.startsWith("f:")) return store.parts(b.id).all { jellyfin.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, it.id, full = true) }
        return jellyfin.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, target, full = true, recursive = true)
    }

    /** FR-281-9 — an author's biography, typed here (a provider only ever suggests one). */
    suspend fun editAuthorBio(authorId: String, bio: String?): dev.jellystructure.model.AudiobookAuthor? {
        val a = store.author(authorId) ?: return null
        val text = bio?.trim()?.takeIf { it.isNotEmpty() }
        val next = a.copy(bio = text, bioSource = if (text == null) null else AudiobookOrigin.TYPED, updatedAt = nowEpochSec())
        store.putAuthor(next)
        note(authorId, "audiobook_author", if (text == null) "Biography removed" else "Biography typed here")
        return next
    }

    // ── the cover (FR-281-7) ──

    /** `cover.jpg` in the book's folder — or, for a book split off a folder, `cover-<tag>.jpg` beside it. */
    private fun coverPath(b: Audiobook) = b.folderPath?.let { "${it.trimEnd('/')}/${if (b.splitFrom != null) AudiobooksIngest.splitCoverName(b.id) else "cover.jpg"}" }
    fun existingCover(b: Audiobook): String? = b.folderPath?.let { f ->
        val names = if (b.splitFrom != null) listOf(AudiobooksIngest.splitCoverName(b.id)) else listOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png")
        names.map { "${f.trimEnd('/')}/$it" }.firstOrNull { SystemFileSystem.exists(Path(it)) }
    }
    fun coverLocked(b: Audiobook): Boolean = coverPath(b)?.let { artwork.isManual(it) } == true

    fun coverCandidates(b: Audiobook): List<MusicArtCandidate> = providers.coverCandidates(b)

    suspend fun useCover(bookId: String, url: String, source: String): Boolean {
        val b = store.book(bookId) ?: return false
        val dest = coverPath(b) ?: return false
        if (!artwork.downloadTo(url, dest)) return false
        artwork.markManual(dest)
        store.putBook(b.copy(coverState = MusicArt.FILE, coverSource = source, updatedAt = nowEpochSec()))
        note(bookId, "audiobook_cover", "cover.jpg from $source")
        return true
    }

    suspend fun uploadCover(bookId: String, bytes: ByteArray): Boolean {
        val b = store.book(bookId) ?: return false
        val dest = coverPath(b) ?: return false
        val ok = runCatching { dev.jellystructure.io.FileIo.writeBytes(Path("$dest.tmp"), bytes); platform.posix.rename("$dest.tmp", dest) == 0 }.getOrDefault(false)
        if (!ok) return false
        artwork.markManual(dest)
        store.putBook(b.copy(coverState = MusicArt.FILE, coverSource = "upload", updatedAt = nowEpochSec()))
        note(bookId, "audiobook_cover", "cover.jpg uploaded")
        return true
    }

    suspend fun clearCover(bookId: String): Boolean {
        val b = store.book(bookId) ?: return false
        val path = existingCover(b) ?: return false
        artwork.removeImage(path)
        store.putBook(b.copy(coverState = MusicArt.NONE, coverSource = null, updatedAt = nowEpochSec()))
        note(bookId, "audiobook_cover", "cover removed")
        return true
    }

    fun setCoverLock(bookId: String, locked: Boolean) {
        val b = store.book(bookId) ?: return
        val p = coverPath(b) ?: return
        if (locked) artwork.markManual(p) else artwork.unmarkManual(p)
    }

    // ── Save (FR-281-8) ──

    /** What Save will do, as its menu says it. */
    fun saveSays(b: Audiobook): String {
        val tags = configStore.current.audiobooks.writeTags && taggerAvailable()
        val n = store.parts(b.id).size
        return if (tags) "cover.jpg + tags in $n file${if (n == 1) "" else "s"}" else "cover.jpg"
    }

    /** The cover is already on disk by the time Save runs; with the switch on, the parts' tags are written too
     *  (seeding files skipped). Then Jellyfin re-reads the folder. Returns one sentence. */
    @OptIn(ExperimentalForeignApi::class)
    suspend fun save(bookId: String, sync: Boolean): String {
        val b = store.book(bookId) ?: return "not found"
        val cfg = configStore.current
        // Our order, so each part's track number is the one the phone plays it at.
        val parts = store.parts(bookId).filter { it.missingSince == null }.sortedBy { it.position }
        var written = 0; var seeded = 0; var failed = 0
        if (cfg.audiobooks.writeTags && taggerAvailable()) {
            for ((i, p) in parts.withIndex()) {
                val path = p.path ?: continue
                val guard = seeding?.check(path, cfg)
                if (guard is SeedingCheckResult.Blocked || guard is SeedingCheckResult.Unreachable) { seeded++; continue }
                val kv = mapOf(
                    "title" to p.title.ifBlank { b.title }, "album" to b.title, "artist" to b.authors.joinToString(", "), "albumartist" to b.authors.joinToString(", "),
                    "composer" to b.narrators.joinToString(", "), "comment" to b.description.orEmpty(), "publisher" to b.publisher.orEmpty(),
                    "genre" to b.genres.joinToString(", "), "tracknumber" to (i + 1).toString(),
                )
                val cmd = "python3 -c ${q(TAG_SCRIPT)} ${q(path)} " + kv.entries.joinToString(" ") { (k, v) -> q("$k=$v") } + " >/dev/null 2>&1"
                // A child process per file — through the shared ProcessGate, off the request thread.
                if (dev.jellystructure.ops.ProcessGate.withPermit { platform.posix.system(cmd) } == 0) written++ else failed++
            }
            if (written > 0) store.putBook(b.copy(tagsWrittenAt = nowEpochSec(), updatedAt = nowEpochSec()))
        }
        if (sync) sync(bookId)
        val sentence = buildString {
            append(if (written > 0) "Tags written into $written file${if (written == 1) "" else "s"}" else "cover.jpg kept")
            if (seeded > 0) append(" · $seeded seeding, skipped")
            if (failed > 0) append(" · $failed could not be written")
            if (sync) append(" · Jellyfin re-reading")
        }
        note(bookId, "audiobook_save", sentence)
        if (failed > 0) Logger.warn("book tags: $failed of ${parts.size} failed for $bookId", "audiobooks")
        return sentence
    }
}
