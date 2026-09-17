package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
        /** Phase 201, 2026-09-13 amendment — the second defect [MkvLayout.ELEMENT_SIZE_OVERFLOW]
         *  covers: same "plays in Jellyfin, buffers forever in Ravilo" symptom, different write-time
         *  cause (a corrupted element size, not an evicted `Tracks`). Repaired by the same remux. */
        val elementSizeOverflow: List<String>,
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

    /** The set of "confirmed broken, confirmed repairable by the same remux" classifications — the
     *  only two [MkvLayout] values [repair] will act on. Kept as one place so the two call sites below
     *  (and any future one) can't drift apart on what counts as "broken." */
    private val REPAIRABLE = MkvLayout.entries.filter { it.needsRepair }.toSet()   // Phase 234: one predicate

    fun sweep(items: List<MediaItem>): SweepResult {
        val results = mkvPaths(items).map { FileResult(it, classify(it)) }
        return SweepResult(
            scanned = results.size,
            ok = results.count { it.layout == MkvLayout.OK },
            tracksAfterClusters = results.filter { it.layout == MkvLayout.TRACKS_AFTER_CLUSTER }.map { it.path },
            elementSizeOverflow = results.filter { it.layout == MkvLayout.ELEMENT_SIZE_OVERFLOW }.map { it.path },
            unknown = results.count { it.layout == MkvLayout.UNKNOWN },
        )
    }

    /** Every broken path across both repairable [MkvLayout] classifications, keyed to which one.
     *  Synchronous, one file at a time on the calling thread — fine for [sweep]'s own existing
     *  operator-triggered, request-thread callers (the standalone `/media/health/mkv-layout` report
     *  route), which is a deliberate choice already documented on that route from Phase 201, not
     *  revisited here. [MkvHealthCache] does **not** call this — see [brokenParallel]. */
    fun broken(items: List<MediaItem>): Map<String, MkvLayout> =
        mkvPaths(items).associateWith(::classify).filterValues { it in REPAIRABLE }

    /** Phase 203 (FR-203-5) — [MkvHealthCache]'s own sweep: the same classification as [broken], fanned
     *  out across [concurrency] concurrent file opens on [dispatcher] instead of one item at a time on
     *  the calling thread. A first-`Cluster` descent costs ~400 KB of reads per file (Phase 201's
     *  2026-09-13 amendment) — ~3 GB and ~88s serially across a production library — so this is what
     *  keeps the sweep off the critical path without needing the walk itself to get cheaper. */
    suspend fun brokenParallel(items: List<MediaItem>, dispatcher: CoroutineDispatcher, concurrency: Int): Map<String, MkvLayout> =
        withContext(dispatcher) {
            val gate = Semaphore(concurrency.coerceAtLeast(1))
            mkvPaths(items)
                .map { path -> async { gate.withPermit { path to classify(path) } } }
                .awaitAll()
                .toMap()
                .filterValues { it in REPAIRABLE }
        }

    /**
     * Phase 201 (FR-201-5) — repair a specific, operator-chosen set of files (normally the
     * `tracksAfterClusters` list a [sweep] just reported). An explicit action on the media library, not
     * something a scan may trigger on its own initiative — same posture Phase 188 established for any
     * bulk repair. Re-classifies each file first so a file fixed by an unrelated edit since the sweep
     * isn't remuxed again for nothing.
     *
     * 2026-09-13 amendment — this used to run inline on the request thread and be called directly from
     * the repair route. The first real production click (an 85-episode series, no progress feedback on
     * a multi-minute run) got re-clicked/reloaded several times, firing the same request repeatedly and
     * running this on the same files concurrently — [FfmpegRunner]'s remux temp file is a fixed
     * `.jstmp_<name>` per path with no run-to-run uniqueness, so overlapping repairs of one path raced on
     * that name. No data was lost that time, but the race was real. The repair route now enqueues this
     * through [MediaJobQueue] (type `mkv_layout_repair`) instead of calling it directly — the media
     * lane's existing single-worker FIFO (Phase 109) already guarantees no two ffmpeg remuxes, of any
     * kind, ever run at once, which removes the race without this object needing its own lock.
     */
    suspend fun repair(paths: List<String>): Map<String, Boolean> =
        paths.map { it to classify(it) }.filter { it.second in REPAIRABLE }
            .associate { (path, layout) ->
                // Phase 234 (FR-234-3) — a lossy repair says so.
                if (layout == MkvLayout.ELEMENT_SIZE_OVERFLOW) Logger.warn(layout.repairSentence(path), "track")
                path to FfmpegRunner.repairTracksLayout(path)
            }
}
