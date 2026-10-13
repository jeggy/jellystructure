package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.nfo.NfoWriter
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Phase 314 (FR-314-13) — a film's own files that are not on disk, named as they are on disk: a film in its own folder
 * misses `poster.jpg`, one loose in a library root misses `<basename>-poster.jpg` (FR-316-1), and the NFO's name comes
 * from [NfoWriter.nfoPath] rather than a literal, so a music video's `<basename>.nfo` reads correctly too.
 */
internal fun missingFilmAssets(item: MediaItem, exists: (String) -> Boolean): List<String> {
    val out = ArrayList<String>(4)
    for (name in listOf("poster.jpg", "fanart.jpg", "clearlogo.png")) {
        val path = assetFilePath(item, name)
        if (!exists(path)) out += path.substringAfterLast('/')
    }
    // A film loose in a library root has no NFO of its own to miss: `NfoWriter.nfoPath` would be the ROOT's
    // `movie.nfo`, which is no more ours to write than the root's `poster.jpg` is (FR-316-2 refuses that one).
    if (!(item.kind == MediaKind.MOVIE && inSharedFolder(item.path)) && !exists(NfoWriter.nfoPath(item)))
        out += NfoWriter.nfoPath(item).substringAfterLast('/')
    return out
}

/**
 * Phase 314 (FR-314-13, amendment 2026-10-13) — a rename or a version write never leaves a film without its artwork.
 *
 * Measured on prod (DEB-869): an in-place rename makes Radarr drop its `moviefile` row for the vanished path, and
 * Radarr takes the poster, fanart, clearlogo and `movie.nfo` it had adopted as that movie's extras with it. The
 * hourly pass wrote them back, so the measured run lost the artwork for five minutes — by luck of the clock, the
 * rename fell five minutes before the half hour. A rename at `:31` is 59 minutes of a film with no poster, no
 * backdrop and no logo, which a viewer browsing the library sees, times however many films a pass covers.
 *
 * So the write path reads the folder back itself and writes whatever is gone, instead of leaving it to the clock.
 * Two things make this cheap enough to call unconditionally:
 *
 *  - **Only what is missing is written.** A full folder costs four `stat` calls and nothing else; a present file is
 *    never rewritten, so the `.manual` markers, the `.src` sidecars and an operator's own picks keep their meaning.
 *  - **The NFO is decided by the file, not by the hash.** `PipelineStepOps.writeNfo` compares the would-be content
 *    hash against the stored one and writes nothing when they agree — correct for drift, useless here, where the
 *    content is unchanged and the *file* is what disappeared. This writes it whenever it is not on disk.
 *
 * Radarr deletes asynchronously (its rescan is a queued command, not a call), so the check runs more than once:
 * [PASSES] passes [SETTLE_MS] apart, which covers a rescan that lands after our first write. A pass that finds the
 * folder whole does nothing at all, so the common case is three `stat` rounds spread over half a minute.
 */
class FilmAssets(
    private val store: MediaStore,
    private val artwork: ArtworkDownloader,
    private val config: () -> AppConfig,
) {
    /** The film's own files that are not in its folder right now (empty when it is whole, or not a film). */
    fun missing(item: MediaItem): List<String> =
        if (item.kind == MediaKind.TV_SHOW) emptyList()
        else missingFilmAssets(item) { SystemFileSystem.exists(Path(it)) }

    /**
     * Writes back whatever of [mediaId]'s images and NFO is not on disk, and returns what came back (by filename).
     *
     * [videoPath] is the film's path as the caller has just made it — the mover calls this before its own
     * `updateMedia` step, so the stored row can still carry the old name. Everything here is derived from the path,
     * nothing is written to it: only `nfoWrittenAt`/`nfoHash` go back to the store, on the row as it stands.
     *
     * [settle] false is the one immediate pass (the caller wants the folder whole now); true is the repeat that
     * catches Radarr deleting after that pass. Failures are logged and never thrown: this runs after the file work
     * is already done, and an unreachable TMDB must not turn a finished rename into a failed one.
     */
    suspend fun reassert(mediaId: String, videoPath: String? = null, settle: Boolean = true): List<String> {
        val restored = LinkedHashSet<String>()
        repeat(if (settle) PASSES else 1) { pass ->
            if (pass > 0) delay(SETTLE_MS)
            val stored = store.get(mediaId) ?: return restored.toList()
            val item = if (videoPath != null && videoPath != stored.path) stored.copy(path = videoPath) else stored
            if (item.kind == MediaKind.TV_SHOW) return restored.toList()
            val gone = missing(item)
            if (gone.isEmpty()) return@repeat
            runCatching { artwork.fetch(item) }
                .onFailure { Logger.warn("film assets: artwork for '${item.title}' couldn't be written back: ${it.message} (314)", "media") }
            if (gone.any { it.endsWith(".nfo") }) writeNfo(item)
            restored += gone - missing(item).toSet()
        }
        if (restored.isNotEmpty())
            Logger.info("film assets: put ${restored.joinToString(", ")} back without waiting for the hourly pass (314)", "media")
        return restored.toList()
    }

    private suspend fun writeNfo(item: MediaItem) {
        val cfg = config()
        val written = runCatching { NfoWriter.writeTracked(item, cfg.apiKeys.jellyfinUrl, cfg.metadata.ageRatingCascade).getOrThrow() }
            .onFailure { Logger.warn("film assets: the NFO for '${item.title}' couldn't be written back: ${it.message} (314)", "media") }
            .getOrNull() ?: return
        val stored = store.get(item.id) ?: return
        runCatching { store.updateOne(stored.copy(nfoWrittenAt = written.writtenAt, nfoHash = written.hash)) }
    }

    companion object {
        /** One pass now, two more behind Radarr's asynchronous delete. */
        const val PASSES = 3
        const val SETTLE_MS = 15_000L
    }
}
