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

    // Phase 201, 2026-09-13 amendment — keyed by [MkvLayout] rather than a plain Set so a caller that
    // cares *which* of the two repairable defects a path has (the media-detail Fix banner's copy) can
    // ask, without every existing Set<String>-shaped consumer (Triage count, Library filter) needing to
    // change at all — they just read `.keys`.
    private var broken: Map<String, MkvLayout> = emptyMap()
    private var sweptAtSec: Long? = null

    fun sweptAt(): Long? = sweptAtSec

    /** The current broken-path set (either repairable defect), refreshing first if the cache is empty
     *  or stale. */
    suspend fun brokenPaths(items: List<MediaItem>): Set<String> {
        val now = nowEpochSec()
        val last = sweptAtSec
        if (last == null || now - last > REFRESH_INTERVAL_SEC) refresh(items)
        return broken.keys
    }

    /** Forces a fresh sweep regardless of [REFRESH_INTERVAL_SEC] — called both by the throttled
     *  [brokenPaths] above and, since the 2026-09-13 amendment, once by the scan pipeline right after
     *  `scan_files` completes, so a file broken (or fixed) during that scan is known immediately rather
     *  than waiting out the interval or an admin's next Dashboard load. */
    suspend fun refresh(items: List<MediaItem>) {
        broken = MkvLayoutAudit.broken(items)
        sweptAtSec = nowEpochSec()
    }

    fun markRepaired(paths: Collection<String>) {
        broken = broken - paths.toSet()
    }
}
