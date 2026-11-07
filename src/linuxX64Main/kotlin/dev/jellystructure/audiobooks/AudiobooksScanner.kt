package dev.jellystructure.audiobooks

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.log.Logger
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.model.AudiobookChapter
import dev.jellystructure.music.MusicJsTagWriter
import dev.jellystructure.music.MusicStore
import dev.jellystructure.music.MusicTagCarry
import dev.jellystructure.nowEpochSec
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The outcome of one `scan_audiobooks` step, as Activity's run summary says it. */
data class AudiobooksScanSummary(val libraries: Int, val books: Int, val parts: Int, val ebooks: Int, val flagged: Int, val failed: List<String>) {
    fun sentence(): String = when {
        libraries == 0 -> "no audiobook library mapped"
        else -> buildString {
            append("$parts audiobook files → $books book${if (books == 1) "" else "s"}")
            if (ebooks > 0) append(" · $ebooks ebooks ignored")
            if (flagged > 0) append(" · $flagged need you")
            if (failed.isNotEmpty()) append(" · Jellyfin didn't answer for ${failed.joinToString()}")
        }
    }
}

/**
 * Phase 280 (FR-280-2) — `scan_audiobooks`: each mapped audiobook library read from Jellyfin in one paged pass, grouped into
 * books by folder, and `ffprobe -show_chapters` run only on one-file books (dev review 2). All-or-nothing per library:
 * a failed read changes nothing.
 */
class AudiobooksScanner(
    private val configStore: ConfigStore,
    private val jellyfinClient: JellyfinClient,
    val store: AudiobooksStore,
    private val exists: (String) -> Boolean = { SystemFileSystem.exists(Path(it)) },
    private val chaptersOf: suspend (String) -> List<AudiobookChapter> = { path ->
        FfprobeRunner.chapters(path).map { c -> AudiobookChapter(c.title ?: "", c.startMs, (c.endMs - c.startMs).coerceAtLeast(0L), 0, c.startMs) }
    },
    /** DEB-3710 (DEB-3701 FR-12) — where a book's jellystructure tags live (`music_js_tag`); null where a site keeps none. */
    private val tags: MusicStore? = null,
    /** FR-12 — the carry writes one line on the book. Null where a site keeps no History. */
    private val history: dev.jellystructure.media.MediaHistory? = null,
) {
    /** Ebooks per library at the last scan (FR-280-6's *0 ebooks*). */
    var ebooks: Map<String, Int> = emptyMap()
        private set
    /** When `scan_audiobooks` last finished (null until the first one) — the Library's *not scanned yet* state. */
    var lastScanAt: Long? = null
        private set

    suspend fun scan(onlyLibraryId: String? = null): AudiobooksScanSummary {
        val cfg = configStore.current
        val libs = audiobookLibraries(cfg).filter { onlyLibraryId == null || it.jellyfinId == onlyLibraryId }
        if (libs.isEmpty() || cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank())
            return AudiobooksScanSummary(0, 0, 0, 0, 0, emptyList())
        val failed = mutableListOf<String>()
        var eb = 0
        for (lib in libs) {
            val jf = jellyfinClient.getAudiobooksLibrary(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, lib.jellyfinId)
            if (jf == null) { failed += lib.name.ifBlank { lib.jellyfinId }; continue }
            val prev = store.snapshot()
            val rows = AudiobooksIngest.build(lib, jf, prev.books, prev.parts, prev.authors, nowEpochSec(), exists, chaptersOf)
            store.replaceLibrary(rows)
            carryJsTags(prev, rows)
            ebooks = ebooks + (lib.jellyfinId to jf.ebookCount)
            eb += jf.ebookCount
            Logger.info("scan_audiobooks: ${lib.name} — ${jf.parts.size} files → ${rows.books.count { it.missingSince == null }} books", "audiobooks")
        }
        val h = store.health()
        lastScanAt = nowEpochSec()
        return AudiobooksScanSummary(libs.size, h.books, h.parts, eb, h.missingParts + h.twoInOne, failed)
    }

    /**
     * DEB-3710 (DEB-3701 FR-12) — a book's tags follow it to a new id (a folder renamed, a re-import, a split or a join).
     * [MusicTagCarry.planBooks] finds the pairs; this adds the missing tags with the one writer that may
     * ([MusicJsTagWriter.CARRY]: copy only, never a move, never a removal) and says so on the book's History. Only pairs
     * where a book is new or newly missing in *this* scan are carried, so a tag the owner took off the new book is not put
     * back by the scan after. A scan writes no other tag: the rows it replaces carry none.
     */
    internal suspend fun carryJsTags(prev: AudiobooksStore.Snapshot, rows: AudiobooksRows) {
        val tagStore = tags ?: return
        val tagSnap = tagStore.snapshot()
        if (tagSnap.jsTags.isEmpty()) return
        val changed = HashSet<String>()
        for (b in rows.books) if ((b.missingSince == null && b.id !in prev.books) || (b.missingSince != null && prev.books[b.id]?.missingSince == null)) changed += b.id
        if (changed.isEmpty()) return
        val carries = MusicTagCarry.planBooks(tagSnap, store.snapshot(), changed)
        if (carries.isEmpty()) return
        tagStore.addJsTags(MusicJsTagWriter.CARRY, carries.flatMap { it.rows })
        for (c in carries) {
            val names = c.rows.joinToString(", ") { it.tag }
            runCatching { history?.record(c.to, "music_js_tags", "tags carried from the replaced book: $names") }
        }
    }

    companion object {
        /** The libraries this product manages as audiobooks: mapped, not skipped, and named `books` by Jellyfin. */
        fun audiobookLibraries(cfg: AppConfig): List<LibraryMapping> =
            cfg.libraries.filter { it.collectionType.equals("books", ignoreCase = true) && !it.skip && it.jellyfinId.isNotBlank() }
    }
}
