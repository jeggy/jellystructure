package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Phase 201 (FR-201-6) — "a detector the operator can run and read." A read-only sweep of every
 * `.mkv` file in the library, classifying each by [MkvLayout]. Cheap: the walk stops at the first
 * `Cluster` of each file and never reads a payload, so a full 7 885-file production sweep ran in well
 * under a minute. Deliberately **never auto-repairs** — that is a separate, explicit operator action
 * ([MkvLayoutAudit.repair]), because a repair is a write to a media file and this is a report.
 */
object MkvLayoutAudit {

    data class FileResult(val path: String, val layout: MkvLayout)

    data class SweepResult(
        val scanned: Int,
        val ok: Int,
        val tracksAfterClusters: List<String>,
        val unknown: Int,
    )

    /** Every `.mkv` path in the library — the item's own path for a movie/music video, every
     *  episode's path for a series. Order-preserving, no de-duplication (a shared multi-episode file
     *  path is intentionally checked once per episode row it backs — cheap, and simpler than the
     *  Phase 149 combined-row logic this doesn't need to know about). */
    fun mkvPaths(items: List<MediaItem>): List<String> = items.flatMap { item ->
        if (item.episodes.isNotEmpty()) item.episodes.map { it.path } else listOf(item.path)
    }.filter { it.substringAfterLast('.').lowercase() == "mkv" }.distinct()

    private fun classify(path: String): MkvLayout = runCatching {
        SystemFileSystem.source(Path(path)).buffered().use { scanMkvLayout(it) }
    }.getOrDefault(MkvLayout.UNKNOWN)

    fun sweep(items: List<MediaItem>): SweepResult {
        val results = mkvPaths(items).map { FileResult(it, classify(it)) }
        return SweepResult(
            scanned = results.size,
            ok = results.count { it.layout == MkvLayout.OK },
            tracksAfterClusters = results.filter { it.layout == MkvLayout.TRACKS_AFTER_CLUSTER }.map { it.path },
            unknown = results.count { it.layout == MkvLayout.UNKNOWN },
        )
    }

    /**
     * Phase 201 (FR-201-5) — repair a specific, operator-chosen set of files (normally the
     * `tracksAfterClusters` list a [sweep] just reported). An explicit action on the media library, not
     * something a scan may trigger on its own initiative — same posture Phase 188 established for any
     * bulk repair. Re-classifies each file first so a file fixed by an unrelated edit since the sweep
     * isn't remuxed again for nothing.
     */
    suspend fun repair(paths: List<String>): Map<String, Boolean> =
        paths.filter { classify(it) == MkvLayout.TRACKS_AFTER_CLUSTER }
            .associateWith { FfmpegRunner.repairTracksLayout(it) }
}
