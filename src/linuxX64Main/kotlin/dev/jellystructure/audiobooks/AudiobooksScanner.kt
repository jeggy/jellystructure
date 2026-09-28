package dev.jellystructure.audiobooks

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.log.Logger
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.model.AudiobookChapter
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
            ebooks = ebooks + (lib.jellyfinId to jf.ebookCount)
            eb += jf.ebookCount
            Logger.info("scan_audiobooks: ${lib.name} — ${jf.parts.size} files → ${rows.books.count { it.missingSince == null }} books", "audiobooks")
        }
        val h = store.health()
        lastScanAt = nowEpochSec()
        return AudiobooksScanSummary(libs.size, h.books, h.parts, eb, h.missingParts + h.twoInOne, failed)
    }

    companion object {
        /** The libraries this product manages as audiobooks: mapped, not skipped, and named `books` by Jellyfin. */
        fun audiobookLibraries(cfg: AppConfig): List<LibraryMapping> =
            cfg.libraries.filter { it.collectionType.equals("books", ignoreCase = true) && !it.skip && it.jellyfinId.isNotBlank() }
    }
}
