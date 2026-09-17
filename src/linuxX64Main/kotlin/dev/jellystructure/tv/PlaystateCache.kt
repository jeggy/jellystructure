package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.ops.GateClass
import dev.jellystructure.shared.tv.CardPlayState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.time

// Phase 205 (FR-205-2) — matches PLAYSTATE_TTL_MS's old freshness expectation (HomeFeedService), now
// the refresh cadence rather than a per-reader TTL.
private const val REFRESH_INTERVAL_MS = 20_000L
// A household's historical pairings can run to a dozen devices; refreshing every one forever would grow
// unbounded outbound Jellyfin traffic with the size of the household's *history*, not its current use.
private const val RECENTLY_SEEN_WINDOW_MS = 30L * 24 * 3_600_000L
private const val FETCH_TIMEOUT_MS = 5_000L
// Phase 230 (FR-230-3) — a user with no map yet: nothing good to protect, a slow answer beats none.
private const val COLD_FETCH_TIMEOUT_MS = 20_000L
// Phase 230 (FR-230-1) — episodes are re-read on a rotation: one slice per cycle, every episode once per
// EPISODE_SWEEP_CYCLES cycles (15 × 20 s = 5 min). Measured on production 2026-09-17: the whole id set
// was 9 369 ids = 94 requests = 3.4 s of a 5 s budget, per user, every 20 s (≈ 19 req/s at Jellyfin,
// forever) — and it timed out whenever Jellyfin had anything else to do.
internal const val EPISODE_SWEEP_CYCLES = 15
// Spread refreshes out rather than firing a household's users at Jellyfin in one instant.
private const val STAGGER_MS = 500L

/**
 * Phase 205 (FR-205-1/FR-205-2) — the ONE per-user whole-catalog playstate map, background-refreshed,
 * shared by every read site that used to fetch it live: `HomeFeedService`'s Home/channel feeds,
 * `BrowseService`'s browse/search/related hydration, and `DetailService.getPlaystate` — the worst of the
 * seven, with **no cache and no timeout at all** before this phase, hit on every single detail/episode
 * open.
 *
 * [get] never triggers a Jellyfin call and never blocks; it returns whatever the background loop last
 * wrote for that user, or an empty map if nothing has been fetched yet. An absent ✓/resume overlay is an
 * established, harmless degraded state everywhere [withPlaystate] is already used — unlike Continue
 * Watching's row, there is no confident-wrong-answer risk here (a missing ✓ reads as "not watched yet",
 * not as "nothing to show"), so no FR-203-2-style omission marker is needed for this cache.
 */
object PlaystateCache {
    private var data: Map<String, Map<String, CardPlayState>> = emptyMap()
    // Phase 219 (FR-219-4) — the age of the last successful cycle per user, for /api/health.
    private var lastSuccessAt: Map<String, Long> = emptyMap()
    fun refresherAges(): Map<String, Long> { val now = nowMs(); return lastSuccessAt.mapValues { now - it.value } }
    private val json = Json { encodeDefaults = true }
    private var cycle = 0
    // Phase 230 (FR-230-5) — what the cycle cost, for its log line.
    private var cycleIds = 0

    fun get(userId: String): Map<String, CardPlayState> = data[userId].orEmpty()

    /** Called once from `Main.kt` at boot. */
    fun start(
        scope: CoroutineScope,
        deviceService: RaviloDeviceService,
        mediaStore: MediaStore,
        jellyfinClient: JellyfinClient,
        configStore: ConfigStore,
        tvEventBus: TvEventBus,
    ) {
        scope.launch(GateClass.BACKGROUND) {
            while (true) {
                runCatching { refreshAll(deviceService, mediaStore, jellyfinClient, configStore, tvEventBus) }
                    .onFailure { Logger.warn("Playstate refresh failed: ${it.message}", "tv") }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshAll(
        deviceService: RaviloDeviceService,
        mediaStore: MediaStore,
        jellyfinClient: JellyfinClient,
        configStore: ConfigStore,
        tvEventBus: TvEventBus,
    ) {
        if (configStore.current.apiKeys.jellyfinUrl.isBlank()) return
        val now = nowMs()
        // One representative device per Jellyfin user — allowedLibraries/allowedTags are properties of
        // the Jellyfin user's own policy (Phase 142), not of a particular device pairing, so any of a
        // user's recently-seen devices carries the same visibility scope.
        val users = deviceService.allDevices()
            .filter { now - it.lastSeen < RECENTLY_SEEN_WINDOW_MS }
            .distinctBy { it.jellyfinUserId }
        var refreshed = 0
        var failed = 0
        cycleIds = 0
        val slice = cycle++ % EPISODE_SWEEP_CYCLES
        for (device in users) {
            if (refreshOne(device, mediaStore, jellyfinClient, configStore, tvEventBus, scope = RefreshScope.Cycle(slice))) refreshed++ else failed++
            delay(STAGGER_MS)
        }
        if (users.isNotEmpty()) Logger.info("Playstate refresh: $refreshed/${users.size} users refreshed, $failed timed out or failed — $cycleIds ids in ${(cycleIds + 99) / 100} requests", "tv")
    }

    /** Also called directly by [HomeFeedService.invalidatePlaystate] (FR-205-9) for an immediate,
     *  best-effort correction after a reported playback stop, rather than leaving the just-stopped
     *  title's ✓ to wait out the next background cycle. Returns whether the refresh produced a result —
     *  R231's rule applies here too: a failed/timed-out fetch never overwrites the last good value. */
    suspend fun refreshOne(
        device: DeviceData,
        mediaStore: MediaStore,
        jellyfinClient: JellyfinClient,
        configStore: ConfigStore,
        tvEventBus: TvEventBus? = null,
        scope: RefreshScope = RefreshScope.Touched(null),
    ): Boolean {
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        if (base.isBlank()) return false
        val items = mediaStore.liveItems(device)
        // Phase 230 (FR-230-3) — no map yet ⇒ one whole, patient sweep; otherwise only what [scope] names.
        val cold = data[device.jellyfinUserId] == null
        val ids = when {
            cold -> idsToRefresh(items)
            scope is RefreshScope.Cycle -> idsForCycle(items, scope.slice)
            else -> idsForStop(items, (scope as RefreshScope.Touched).jellyfinId)
        }
        if (ids.isEmpty()) return true  // nothing to fetch is not a failure
        val timeoutMs = if (cold) COLD_FETCH_TIMEOUT_MS else FETCH_TIMEOUT_MS
        if (scope is RefreshScope.Cycle) cycleIds += ids.size
        // Phase 219 (FR-219-4) — time the permit wait and the Jellyfin round trip separately, so a cycle
        // skipped for want of a permit is INFO ("pool busy") and only a real Jellyfin timeout stays WARN.
        val recorder = dev.jellystructure.ops.GateWaitRecorder()
        val ps = withTimeoutOrNull(timeoutMs) {
            withContext(recorder) {
                val token = jellyfinClient.tvToken(base, device, configStore.current.apiKeys.jellyfinToken)
                fetchPlaystate(jellyfinClient, base, token, device.jellyfinUserId, ids)
            }
        }
        if (ps == null) {
            if (recorder.acquisitions == 0 || recorder.waitedMs >= timeoutMs / 2) {
                Logger.info("Playstate refresh for ${device.jellyfinUsername} skipped — outbound pool busy (waited ${recorder.waitedMs} ms for a permit), will retry in ${REFRESH_INTERVAL_MS / 1000} s", "tv")
            } else {
                Logger.warn("Playstate refresh for ${device.jellyfinUsername} timed out after ${timeoutMs} ms at Jellyfin (${ids.size} ids) (permit wait ${recorder.waitedMs} ms)", "tv")
            }
            return false
        }
        lastSuccessAt = lastSuccessAt + (device.jellyfinUserId to nowMs())
        // Phase 230 (FR-230-1) — MERGE: a cycle now carries a slice, not the whole catalog.
        data = data + (device.jellyfinUserId to (data[device.jellyfinUserId].orEmpty() + ps))
        // R176 — patch any already-open Home/Browse/Search screen on another of this user's devices,
        // same push HomeFeedService's own playstateFor used to fire on a fresh live fetch.
        if (ps.isNotEmpty()) tvEventBus?.notifyPlaystateChanged(device.jellyfinUserId, json.encodeToString(ps))
        return true
    }

    /** Phase 211 — a series' own top-level id carries no per-episode state; `DetailService.getPlaystate`
     *  is called with episode ids (season open), so this map must hold episode-keyed entries too, or
     *  every one of those lookups misses unconditionally, not just intermittently. Pulled out of
     *  [refreshOne] so the id set is unit-testable without a live/faked Jellyfin call. */
    internal fun idsToRefresh(items: List<dev.jellystructure.model.MediaItem>): List<String> =
        items.flatMap { item -> listOfNotNull(item.jellyfinId) + item.episodes.mapNotNull { it.jellyfinId } }

    /** Phase 230 — what one refresh is about: a background [Cycle] (titles + episode slice [Cycle.slice]),
     *  or what a playback stop [Touched] (null id ⇒ titles only). */
    sealed interface RefreshScope {
        data class Cycle(val slice: Int) : RefreshScope
        data class Touched(val jellyfinId: String?) : RefreshScope
    }

    internal fun topLevelIds(items: List<dev.jellystructure.model.MediaItem>): List<String> = items.mapNotNull { it.jellyfinId }

    /** FR-230-1 — every title, plus the episodes whose position falls in [slice] of [EPISODE_SWEEP_CYCLES].
     *  Position-based (not hash-based) so slices are even and every episode is covered exactly once per sweep. */
    internal fun idsForCycle(items: List<dev.jellystructure.model.MediaItem>, slice: Int): List<String> {
        val episodes = items.flatMap { item -> item.episodes.mapNotNull { it.jellyfinId } }
        return topLevelIds(items) + episodes.filterIndexed { i, _ -> i % EPISODE_SWEEP_CYCLES == slice % EPISODE_SWEEP_CYCLES }
    }

    /** FR-230-2 — every title, plus the episodes of the title that owns [jellyfinId] (an episode's or the
     *  series' own id). A movie, an unknown id or `null` adds nothing. */
    internal fun idsForStop(items: List<dev.jellystructure.model.MediaItem>, jellyfinId: String?): List<String> {
        val owner = jellyfinId?.let { id -> items.firstOrNull { it.jellyfinId == id || it.episodes.any { e -> e.jellyfinId == id } } }
        return topLevelIds(items) + owner?.episodes.orEmpty().mapNotNull { it.jellyfinId }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nowMs(): Long = time(null) * 1000L
