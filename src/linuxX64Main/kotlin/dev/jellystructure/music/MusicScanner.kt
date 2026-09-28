package dev.jellystructure.music

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.log.Logger
import dev.jellystructure.nowEpochSec
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The outcome of one `scan_music` step, as Activity's run summary says it. */
data class MusicScanSummary(
    val libraries: Int,
    val artists: Int,
    val albums: Int,
    val tracks: Int,
    val missing: Int,
    val failed: List<String>,
) {
    fun sentence(): String = when {
        libraries == 0 -> "no music library mapped"
        else -> buildString {
            append("$albums albums · $tracks songs · $artists artists")
            if (missing > 0) append(" · $missing no longer in Jellyfin")
            if (failed.isNotEmpty()) append(" · Jellyfin didn't answer for ${failed.joinToString()}")
        }
    }
}

/**
 * Phase 275 (FR-275-2) — `scan_music`: read each mapped music library from Jellyfin and store it. No ffprobe per
 * track — Jellyfin's `MediaStreams` already carry codec, bitrate and sample rate, and an audio file has one stream.
 */
class MusicScanner(
    private val configStore: ConfigStore,
    private val jellyfinClient: JellyfinClient,
    private val store: MusicStore,
    private val exists: (String) -> Boolean = { SystemFileSystem.exists(Path(it)) },
) {
    /** Phase 278 — when a scan last read a library (the Library's *mapped, not yet scanned* state). Since boot. */
    var lastScanAt: Long? = null
        private set

    /** [onlyLibraryId] — a run scoped to one library scans music only when that library is a music one. */
    suspend fun scan(onlyLibraryId: String? = null): MusicScanSummary {
        val cfg = configStore.current
        val libs = musicLibraries(cfg).filter { onlyLibraryId == null || it.jellyfinId == onlyLibraryId }
        if (libs.isEmpty() || cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank())
            return MusicScanSummary(0, 0, 0, 0, 0, emptyList())
        val failed = mutableListOf<String>()
        for (lib in libs) {
            val jf = jellyfinClient.getMusicLibrary(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, lib.jellyfinId)
            if (jf == null) {
                // Nothing is marked missing on a failed fetch — the next run tries again.
                failed += lib.name.ifBlank { lib.jellyfinId }
                continue
            }
            val prev = store.snapshot()
            val rows = MusicIngest.build(lib, jf, prev.artists, prev.albums, prev.tracks, nowEpochSec(), exists)
            store.replaceLibrary(rows)
            lastScanAt = nowEpochSec()
            Logger.info("scan_music: ${lib.name} — ${jf.albums.size} albums, ${jf.tracks.size} tracks, ${rows.artists.size} artists", "music")
        }
        val h = store.health()
        val missing = store.snapshot().let { s ->
            val ids = libs.map { it.jellyfinId }.toSet()
            s.albums.values.count { it.libraryId in ids && it.missingSince != null } +
                s.tracks.values.count { it.libraryId in ids && it.missingSince != null }
        }
        return MusicScanSummary(libs.size, h.artists, h.albums, h.tracks, missing, failed)
    }

    companion object {
        /** The libraries this product manages as music: mapped, not skipped, and named `music` by Jellyfin. */
        fun musicLibraries(cfg: AppConfig): List<LibraryMapping> =
            cfg.libraries.filter { it.collectionType.equals("music", ignoreCase = true) && !it.skip && it.jellyfinId.isNotBlank() }
    }
}
