package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.visibleTo
import dev.jellystructure.model.MediaItem
import dev.jellystructure.ops.GateClass
import dev.jellystructure.ops.SpinLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.time

/**
 * Phase 269 — each viewer's *Recommended* list: fetched signals in, [RecommendationEngine] in the middle,
 * rows in `recommendation` / `starter_list` out. Never computed on a request (FR-269-7): a request reads
 * what the last build stored and skips what has become ineligible since.
 *
 * Built three ways (FR-269-8): the `build_recommendations` pipeline step ([buildAll], weekly by default),
 * a debounced rebuild of ONE viewer after [PlaystateCache] sees them finish something ([markStale]), and
 * at boot when nothing has ever been built. Every build runs on the BACKGROUND gate class and one at a
 * time ([buildLock]).
 */
class RecommendationService(
    private val db: JellystructureDb,
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
    private val deviceService: RaviloDeviceService,
    /** Test seam: the history source. Default = Jellyfin, through the viewer's own device token. */
    private val historySource: (suspend (DeviceData) -> History?)? = null,
) {
    /** What a viewer did, as Jellyfin reports it (every client, not only Ravilo). */
    data class History(
        val played: List<dev.jellystructure.auth.JellyfinPlayItem>,
        val resume: List<dev.jellystructure.auth.JellyfinPlayItem>,
        val favorites: Set<String>,
    )

    private val buildLock = Mutex()
    private val versions = HashMap<String, Long>()
    private val versionLock = SpinLock()

    /** How many times [userId]'s list has been rebuilt — part of Home's cache check (a rebuilt list must
     *  not wait out a cached feed). */
    fun versionFor(userId: String): Long = versionLock.withLock { versions[userId] ?: 0L }
    private val staleLock = SpinLock()
    private val stale = HashMap<String, Pair<DeviceData, Long>>()

    fun start(scope: CoroutineScope) {
        scope.launch(GateClass.BACKGROUND) {
            // Nothing built yet (a fresh install, or the first boot with this phase): build once now, so a
            // first Home is personal as soon as possible rather than after the next scheduled run.
            if (lastBuiltAt() == null) runCatching { buildAll() }.onFailure { Logger.warn("Recommendations: first build failed: ${it.message}", "tv") }
            while (true) {
                delay(STALE_POLL_MS)
                runCatching { rebuildStale() }.onFailure { Logger.warn("Recommendations: rebuild failed: ${it.message}", "tv") }
            }
        }
    }

    /** Epoch seconds of the newest build (dev review item 6: every full build writes `starter_list`). */
    fun lastBuiltAt(): Long? = db.recommendationQueries.lastStarterBuild().executeAsOneOrNull()?.built

    /** FR-269-8 (2) — [device]'s viewer finished something: rebuild them, debounced. */
    fun markStale(device: DeviceData) {
        staleLock.withLock { if (device.jellyfinUserId !in stale) stale[device.jellyfinUserId] = device to nowSec() }
    }

    private suspend fun rebuildStale() {
        val now = nowSec()
        val due = staleLock.withLock {
            val ready = stale.filterValues { (_, at) -> now - at >= STALE_DEBOUNCE_SEC }
            ready.keys.forEach { stale.remove(it) }
            ready.values.map { it.first }
        }
        for (device in due) rebuildViewer(device)
    }

    /** One viewer, now (the admin's *Rebuild now*, and the stale path). */
    suspend fun rebuildViewer(device: DeviceData): Boolean = buildLock.withLock {
        val library = mediaStore.liveItems()
        val history = historyOf(device) ?: return@withLock false
        val scope = RecommendationEngine.scopeKey(device)
        var starter = storedStarter(scope)
        if (starter.isEmpty()) {
            starter = RecommendationEngine.starter(library.filter { it.visibleTo(device) }, emptyMap(), nowSec())
            storeStarter(scope, starter)
        }
        buildViewer(device, history, library, starter, RecommendationEngine.Vectors(library))
        true
    }

    /**
     * FR-269-8 (1) — every recently seen viewer, and every scope's starter list. Histories are fetched
     * once and used twice: the anonymous household counts for the starter lists (FR-269-10: only within
     * one scope), then each viewer's own list. Returns a one-line summary for the pipeline step.
     */
    suspend fun buildAll(): String = buildLock.withLock {
        val now = nowSec()
        val library = mediaStore.liveItems()
        val vectors = RecommendationEngine.Vectors(library)
        val devices = viewers()
        val histories = LinkedHashMap<String, Pair<DeviceData, History>>()
        for (d in devices) historyOf(d)?.let { histories[d.jellyfinUserId] = d to it }
        val byJf = library.mapNotNull { it.jellyfinId?.let { id -> id to it } }.toMap()
        val scopes = histories.values.groupBy { RecommendationEngine.scopeKey(it.first) }
        var built = 0
        for (scope in scopes.keys.sorted()) {
            val members = scopes.getValue(scope)
            val counts = HashMap<String, Int>()
            for ((_, h) in members) {
                val s = RecommendationEngine.signals(h.played, emptyList(), emptySet(), byJf, now)
                s.weights.filterValues { it > 0 }.keys.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            }
            val starter = RecommendationEngine.starter(library.filter { it.visibleTo(members.first().first) }, counts, now)
            storeStarter(scope, starter)
            for ((device, history) in members) { buildViewer(device, history, library, starter, vectors); built++ }
        }
        "$built viewer${if (built == 1) "" else "s"}, ${scopes.size} scope${if (scopes.size == 1) "" else "s"}" +
            (if (devices.size > histories.size) " (${devices.size - histories.size} unreachable)" else "")
    }

    private fun buildViewer(device: DeviceData, history: History, library: List<MediaItem>, starter: List<RecommendationEngine.Pick>, vectors: RecommendationEngine.Vectors) {
        val now = nowSec()
        val byJf = library.mapNotNull { it.jellyfinId?.let { id -> id to it } }.toMap()
        val signals = RecommendationEngine.signals(history.played, history.resume, history.favorites, byJf, now)
        val picks = RecommendationEngine.build(signals, library.filter { it.visibleTo(device) }, library, starter, now, vectors)
        val scope = RecommendationEngine.scopeKey(device)
        db.transaction {
            db.recommendationQueries.deleteViewer(device.jellyfinUserId, scope)
            picks.forEachIndexed { rank, p ->
                db.recommendationQueries.putRecommendation(device.jellyfinUserId, scope, rank.toLong(), p.jellyfinId, p.score, p.reason, p.reasonItem, SOURCE_STANDARD, now)
            }
        }
        versionLock.withLock { versions[device.jellyfinUserId] = (versions[device.jellyfinUserId] ?: 0L) + 1 }
    }

    private fun storeStarter(scope: String, starter: List<RecommendationEngine.Pick>) {
        val now = nowSec()
        db.transaction {
            db.recommendationQueries.deleteStarter(scope)
            starter.forEachIndexed { rank, p -> db.recommendationQueries.putStarter(scope, rank.toLong(), p.jellyfinId, p.reason, now) }
        }
    }

    private fun storedStarter(scope: String): List<RecommendationEngine.Pick> =
        db.recommendationQueries.starterFor(scope).executeAsList().map { RecommendationEngine.Pick(it.item_id, 0.0, it.reason_code) }

    /** One recently seen device per Jellyfin user (dev review item 4): a user not seen in 30 days never
     *  opens a Home to see a list, so none is built for them. */
    private fun viewers(): List<DeviceData> {
        val now = nowSec() * 1000L
        return deviceService.allDevices()
            .filter { now - it.lastSeen < RECENTLY_SEEN_MS }
            .sortedByDescending { it.lastSeen }
            .distinctBy { it.jellyfinUserId }
            .sortedBy { it.jellyfinUserId }
    }

    private suspend fun historyOf(device: DeviceData): History? {
        historySource?.let { return it(device) }
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        if (base.isBlank()) return null
        return runCatching {
            val token = jellyfinClient.tvToken(base, device, configStore.current.apiKeys.jellyfinToken)
            History(
                played = jellyfinClient.getRecentlyPlayedAll(base, token, device.jellyfinUserId),
                resume = jellyfinClient.getResumeItemsAll(base, token, device.jellyfinUserId),
                favorites = jellyfinClient.getFavoriteItemIds(base, token, device.jellyfinUserId),
            )
        }.onFailure { Logger.warn("Recommendations: history for ${device.jellyfinUsername} unavailable: ${it.message}", "tv") }.getOrNull()
    }

    // ─── Serving (FR-269-7) ───────────────────────────────────────────────────

    /**
     * [device]'s list as stored, minus whatever became ineligible since the build: no longer visible,
     * finished or started since ([PlaystateCache], a filter and not a recompute). A viewer with no list
     * yet gets their scope's starter list at once, and a build is queued (FR-269-8 (3)). [visible] is the
     * caller's already-fetched `liveItems(device)`.
     */
    fun servedFor(device: DeviceData, visible: List<MediaItem>): List<MediaItem> {
        val scope = RecommendationEngine.scopeKey(device)
        var ids = db.recommendationQueries.forViewer(device.jellyfinUserId, scope).executeAsList().map { it.item_id }
        if (ids.isEmpty()) {
            ids = db.recommendationQueries.starterFor(scope).executeAsList().map { it.item_id }
            markStale(device)
        }
        if (ids.isEmpty()) return emptyList()
        val byJf = visible.mapNotNull { it.jellyfinId?.let { id -> id to it } }.toMap()
        val ps = PlaystateCache.get(device.jellyfinUserId)
        return ids.mapNotNull { id ->
            val state = ps[id]
            if (state != null && (state.played || state.resumeMs > 0)) null else byJf[id]
        }.take(RecommendationEngine.LIST_SIZE)
    }

    /** FR-269-9 — the admin's view of one user's stored list(s), newest build first. */
    fun storedFor(userId: String): List<dev.jellystructure.db.Recommendation> =
        db.recommendationQueries.forUser(userId).executeAsList()

    companion object {
        /** The process's one instance, for the pipeline step (whose deps are built in three places). Set by Main. */
        var current: RecommendationService? = null
        const val SOURCE_STANDARD = "standard"
        private const val STALE_POLL_MS = 60_000L
        /** A finish, then a quiet two minutes, then the rebuild: one rebuild for an evening's binge, not one per episode. */
        private const val STALE_DEBOUNCE_SEC = 120L
        private const val RECENTLY_SEEN_MS = 30L * 24 * 3_600_000L
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        fun nowSec(): Long = time(null)
    }
}
