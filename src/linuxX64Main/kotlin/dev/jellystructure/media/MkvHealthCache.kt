package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.nowEpochSec

/**
 * Phase 201 amendment (2026-09-13) — a process-lifetime cache of the last full-library
 * [MkvLayoutAudit] sweep, so folding `mkv_track_layout` into the standard Triage/Dashboard/Library
 * framework doesn't mean re-walking every `.mkv` header on every Dashboard load or Library page. The
 * sweep itself is cheap (FR-201-6: full production library under a minute) but "cheap once" is not
 * "free on every request" — this is lazily refreshed at most every [REFRESH_INTERVAL_SEC].
 *
 * A successful repair calls [markRepaired] directly rather than waiting out the interval, so a title
 * that was just fixed doesn't keep reporting broken for up to 15 more minutes.
 */
object MkvHealthCache {
    private const val REFRESH_INTERVAL_SEC = 15 * 60L

    private var brokenPaths: Set<String> = emptySet()
    private var sweptAtSec: Long? = null

    fun sweptAt(): Long? = sweptAtSec

    /** The current broken-path set, refreshing first if the cache is empty or stale. */
    suspend fun brokenPaths(items: List<MediaItem>): Set<String> {
        val now = nowEpochSec()
        val last = sweptAtSec
        if (last == null || now - last > REFRESH_INTERVAL_SEC) refresh(items)
        return brokenPaths
    }

    suspend fun refresh(items: List<MediaItem>) {
        brokenPaths = MkvLayoutAudit.sweep(items).tracksAfterClusters.toSet()
        sweptAtSec = nowEpochSec()
    }

    fun markRepaired(paths: Collection<String>) {
        brokenPaths = brokenPaths - paths.toSet()
    }
}
