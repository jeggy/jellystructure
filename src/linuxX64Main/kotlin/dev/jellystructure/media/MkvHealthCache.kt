package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.MediaItem
import dev.jellystructure.nowEpochSec
import dev.jellystructure.ops.GateClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Phase 201 amendment (2026-09-13) — a process-lifetime cache of the last full-library
 * [MkvLayoutAudit] sweep, so folding `mkv_track_layout` into the standard Triage/Dashboard/Library
 * framework doesn't mean re-walking every `.mkv` header on every Dashboard load or Library page.
 *
 * Phase 203 (2026-09-13) — the sweep was cheap when this comment was first written (top-level headers
 * only, a few hundred bytes/file), which is why it felt safe to refresh lazily, in-line, on whichever
 * request happened to find the cache cold or stale. The same day's amendment above made the walk
 * descend into the first `Cluster` — necessary, since that's where the real corruption lives — and that
 * turned "cheap" into ~400 KB/file, ~3 GB and ~88s across the production library. The lazy refresh then
 * blocked **every** Triage issue type behind one unrelated health check on every cold start, which is
 * what this phase exists to fix: the cache is now background-warmed ([start]) and single-owner
 * ([refresh]'s mutex) rather than refreshed on-access, and a cold/stale read ([brokenPathsOrNull])
 * never blocks and never lies — see its own doc for why `null`, not an empty map, is cold.
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
    private val refreshMutex = Mutex()

    // Phase 203 (FR-203-5) — set once by [start]; every refresh after that runs on the background lane
    // this carries, not on whatever thread happened to call in. Defaults are a safe fallback for the
    // narrow window (and any test) before [start] runs.
    private var ioDispatcher: CoroutineDispatcher = Dispatchers.Default
    private var fileConcurrency: Int = 8

    fun sweptAt(): Long? = sweptAtSec

    /**
     * Phase 203 (FR-203-1/FR-203-2) — never triggers a sweep, never blocks. `null` means no sweep has
     * ever completed for this process (a cold cache) — every caller must treat that as *unknown*, not
     * *zero broken*: a `0` on the Dashboard's attention breakdown must always mean "checked, and clean,"
     * never "haven't looked yet." [start]'s background warm keeps this window short in practice; it does
     * not remove it (a fresh restart is still cold until its first sweep completes).
     */
    fun brokenPathsOrNull(): Map<String, MkvLayout>? = if (sweptAtSec == null) null else broken

    /**
     * Phase 203 (FR-203-3) — call once from `Main.kt` at boot. Warms the cache immediately in the
     * background, then keeps it fresh every [REFRESH_INTERVAL_SEC] for the life of the process, so by
     * the time anyone opens the Dashboard the answer normally already exists. [dispatcher] and
     * [concurrency] are recorded here and reused by every later [refresh] — including the one the
     * post-`scan_files` pipeline hook triggers directly — so they only need configuring in this one
     * place. Failure is logged and never fatal, same posture as `sonarrEnrich` and the scan hook.
     */
    fun start(scope: CoroutineScope, dispatcher: CoroutineDispatcher, concurrency: Int, itemsProvider: suspend () -> List<MediaItem>) {
        ioDispatcher = dispatcher
        fileConcurrency = concurrency.coerceAtLeast(1)
        scope.launch(GateClass.BACKGROUND) {
            while (true) {
                runCatching { refresh(itemsProvider()) }
                    .onFailure { Logger.warn("MKV health sweep failed: ${it.message}", "triage") }
                delay(REFRESH_INTERVAL_SEC * 1000)
            }
        }
    }

    /**
     * Forces a fresh sweep regardless of [REFRESH_INTERVAL_SEC] — called by [start]'s own loop and,
     * since the 2026-09-13 amendment, once by the scan pipeline right after `scan_files` completes, so a
     * file broken (or fixed) during that scan is known immediately rather than waiting out the interval
     * or an admin's next Dashboard load.
     *
     * Phase 203 (FR-203-4) — single-owner: if a sweep is already running (the background loop, the scan
     * hook, or a concurrent call to this from either), this call is skipped rather than queued behind it
     * or run alongside it — the in-flight sweep will produce an answer within moments regardless. Same
     * reasoning as Phase 201's own concurrent-repair incident: a shared resource gets exactly one owner
     * at a time, not a queue and not an ad-hoc lock per caller.
     */
    suspend fun refresh(items: List<MediaItem>) {
        if (!refreshMutex.tryLock()) {
            Logger.info("MKV health sweep already in progress — skipping this trigger", "triage")
            return
        }
        try {
            broken = MkvLayoutAudit.brokenParallel(items, ioDispatcher, fileConcurrency)
            sweptAtSec = nowEpochSec()
        } finally {
            refreshMutex.unlock()
        }
    }

    fun markRepaired(paths: Collection<String>) {
        broken = broken - paths.toSet()
    }
}
