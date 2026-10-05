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

    // ─── Phase 270 — the AI seam (dev review item 9): this service stays the only author of a list ───

    /** Theme tags per title from 270's theme job, one more feature for similarity. Set by Main. */
    var themes: () -> Map<String, List<String>> = { emptyMap() }

    /** Called with each built viewer's shortlist after a full build, after a viewer's FIRST build (270's re-rank
     *  runs then, not on every finish-triggered rebuild) and after the admin's *Rebuild now* — with why and by
     *  whom, for the AI queue (Phase 272). Set by Main; null = no AI. */
    var afterBuild: (suspend (List<dev.jellystructure.ai.AiJobs.RerankInput>, String, String) -> Unit)? = null
    private val aiAskedAt = HashMap<String, Long>()

    fun start(scope: CoroutineScope) {
        scope.launch(GateClass.BACKGROUND) {
            // Nothing built yet (a fresh install, or the first boot with this phase): build once now, so a
            // first Home is personal as soon as possible rather than after the next scheduled run.
            if (lastBuiltAt() == null) runCatching { buildAll(REASON_FIRST) }.onFailure { Logger.warn("Recommendations: first build failed: ${it.message}", "tv") }
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

    /** One viewer, now (the admin's *Rebuild now*, with [by] the admin's name, and the stale path). */
    suspend fun rebuildViewer(device: DeviceData, by: String? = null): Boolean {
        val input = buildLock.withLock {
            val library = mediaStore.liveItems()
            val history = historyOf(device) ?: return false
            val scope = RecommendationEngine.scopeKey(device)
            var starter = storedStarter(scope)
            if (starter.isEmpty()) {
                starter = RecommendationEngine.starter(library.filter { it.visibleTo(device) }, emptyMap(), nowSec())
                storeStarter(scope, starter)
            }
            buildViewer(device, history, library, starter, RecommendationEngine.Vectors(library, themes()))
        }
        // Phase 270 (FR-270-7) — a viewer's FIRST build is re-ranked too: no AI order yet for them, and not
        // asked in the last day (so an answer that failed validation is not paid for on every finish).
        val hook = afterBuild
        if (hook != null && by != null) {
            // Phase 272 (FR-272-6) — the admin asked: always queue a re-rank, even over an existing AI order.
            runCatching { hook(listOf(input), REASON_BY_HAND, by) }.onFailure { Logger.warn("Recommendations: AI re-rank not queued: ${it.message}", "tv") }
        } else if (hook != null && db.aiQueries.orderFor(input.userId, input.scope).executeAsList().isEmpty()) {
            val now = nowSec()
            val ask = staleLock.withLock { (now - (aiAskedAt[input.userId] ?: 0L) > AI_RETRY_SEC).also { if (it) aiAskedAt[input.userId] = now } }
            if (ask) runCatching { hook(listOf(input), REASON_FIRST, dev.jellystructure.ai.AiJobs.BY_SYSTEM) }.onFailure { Logger.warn("Recommendations: AI re-rank not queued: ${it.message}", "tv") }
        }
        return true
    }

    /**
     * FR-269-8 (1) — every recently seen viewer, and every scope's starter list. Histories are fetched
     * once and used twice: the anonymous household counts for the starter lists (FR-269-10: only within
     * one scope), then each viewer's own list. Returns a one-line summary for the pipeline step.
     */
    suspend fun buildAll(reason: String = REASON_WEEKLY): String {
        val inputs = ArrayList<dev.jellystructure.ai.AiJobs.RerankInput>()
        val summary = buildAllLocked(inputs)
        // Phase 270 — the weekly build is when the AI re-ranks every viewer (and tags untagged titles).
        afterBuild?.let { hook -> runCatching { hook(inputs, reason, dev.jellystructure.ai.AiJobs.BY_SYSTEM) }.onFailure { Logger.warn("Recommendations: AI jobs not queued: ${it.message}", "tv") } }
        return summary
    }

    private suspend fun buildAllLocked(inputs: MutableList<dev.jellystructure.ai.AiJobs.RerankInput>): String = buildLock.withLock {
        val now = nowSec()
        val library = mediaStore.liveItems()
        val vectors = RecommendationEngine.Vectors(library, themes())
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
            for ((device, history) in members) { inputs += buildViewer(device, history, library, starter, vectors); built++ }
        }
        "$built viewer${if (built == 1) "" else "s"}, ${scopes.size} scope${if (scopes.size == 1) "" else "s"}" +
            (if (devices.size > histories.size) " (${devices.size - histories.size} unreachable)" else "")
    }

    private fun buildViewer(device: DeviceData, history: History, library: List<MediaItem>, starter: List<RecommendationEngine.Pick>, vectors: RecommendationEngine.Vectors): dev.jellystructure.ai.AiJobs.RerankInput {
        val now = nowSec()
        val byJf = library.mapNotNull { it.jellyfinId?.let { id -> id to it } }.toMap()
        val signals = RecommendationEngine.signals(history.played, history.resume, history.favorites, byJf, now)
        val visible = library.filter { it.visibleTo(device) }
        val picks = RecommendationEngine.build(signals, visible, library, starter, now, vectors)
        // Phase 270 — the AI's shortlist: the same scoring, kept to 100.
        val shortlist = RecommendationEngine.build(signals, visible, library, starter, now, vectors, size = dev.jellystructure.ai.AiRequests.RERANK_SHORTLIST)
        val scope = RecommendationEngine.scopeKey(device)
        // Phase 270 (FR-270-7) — a rebuild between two AI runs keeps the AI's last order, filtered to what is
        // still on the shortlist and topped up from this build's own list.
        val aiOrder = db.aiQueries.orderFor(device.jellyfinUserId, scope).executeAsList()
        val rows: List<Pair<RecommendationEngine.Pick, String>> = if (aiOrder.isEmpty()) picks.map { it to SOURCE_STANDARD } else {
            val onShortlist = shortlist.associateBy { it.jellyfinId }
            val kept = aiOrder.mapNotNull { o -> onShortlist[o.item_id]?.let { it to SOURCE_AI } }
            val ids = kept.mapTo(HashSet()) { it.first.jellyfinId }
            (kept + picks.filter { it.jellyfinId !in ids }.map { it to SOURCE_STANDARD }).take(RecommendationEngine.LIST_SIZE)
        }
        db.transaction {
            db.recommendationQueries.deleteViewer(device.jellyfinUserId, scope)
            rows.forEachIndexed { rank, (p, source) ->
                db.recommendationQueries.putRecommendation(device.jellyfinUserId, scope, rank.toLong(), p.jellyfinId, p.score, p.reason, p.reasonItem, source, now)
            }
        }
        versionLock.withLock { versions[device.jellyfinUserId] = (versions[device.jellyfinUserId] ?: 0L) + 1 }
        val watched = history.played.mapNotNull { p -> (byJf[p.seriesId ?: p.id] ?: byJf[p.id])?.jellyfinId }.distinct()
        return dev.jellystructure.ai.AiJobs.RerankInput(device.jellyfinUserId, scope, shortlist.map { it.jellyfinId }, watched, label = device.jellyfinUsername)
    }

    /**
     * Phase 270 (dev review item 9) — an accepted AI re-rank for one viewer and scope: stored as the AI's
     * order (kept by later rebuilds until the next AI run), and written as the list at once, each title keeping
     * the reason code 269 gave it. A title 269 no longer lists is skipped; the AI never adds one of its own.
     */
    fun applyAiOrder(userId: String, scope: String, picks: List<dev.jellystructure.ai.AiRequests.Pick>) {
        val now = nowSec()
        val current = db.recommendationQueries.forViewer(userId, scope).executeAsList().associateBy { it.item_id }
        db.transaction {
            db.aiQueries.deleteOrder(userId, scope)
            picks.forEachIndexed { rank, p -> db.aiQueries.putOrder(userId, scope, rank.toLong(), p.jellyfinId, p.reason, now) }
            if (current.isNotEmpty()) {
                val ordered = picks.mapNotNull { current[it.jellyfinId] }
                val ids = ordered.mapTo(HashSet()) { it.item_id }
                val rest = current.values.sortedBy { it.rank }.filter { it.item_id !in ids }
                db.recommendationQueries.deleteViewer(userId, scope)
                (ordered.map { it to SOURCE_AI } + rest.map { it to it.source }).take(RecommendationEngine.LIST_SIZE).forEachIndexed { rank, (r, source) ->
                    db.recommendationQueries.putRecommendation(userId, scope, rank.toLong(), r.item_id, r.score, r.reason_code, r.reason_item_id, source, now)
                }
            }
        }
        versionLock.withLock { versions[userId] = (versions[userId] ?: 0L) + 1 }
    }

    /** Phase 270 — the AI's reasons for [userId]'s titles in [scope], for the admin's view. */
    fun aiReasonsFor(userId: String, scope: String): Map<String, String> =
        db.aiQueries.orderFor(userId, scope).executeAsList().filter { it.reason.isNotBlank() }.associate { it.item_id to it.reason }

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
    /** Phase 270 — how many viewers a full build covers (the AI tab's estimate). */
    fun viewerCount(): Int = viewers().size

    /** Phase 274 reuses the same viewers (dev review 4). */
    internal fun viewers(): List<DeviceData> {
        val now = nowSec() * 1000L
        return deviceService.allDevices()
            .filter { now - it.lastSeen < RECENTLY_SEEN_MS }
            .sortedByDescending { it.lastSeen }
            .distinctBy { it.jellyfinUserId }
            .sortedBy { it.jellyfinUserId }
    }

    internal suspend fun historyOf(device: DeviceData): History? {
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
     * [device]'s list as stored, in its stored order, minus only what is no longer visible (FR-269-7 amended
     * 2026-10-04: a title finished, marked watched or started since the build stays where it is, with its tick or
     * progress bar; the weekly build leaves it out next time). A viewer with no list
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
        return ids.mapNotNull { byJf[it] }.take(RecommendationEngine.LIST_SIZE)
    }

    /** FR-269-9 — the admin's view of one user's stored list(s), newest build first. */
    fun storedFor(userId: String): List<dev.jellystructure.db.Recommendation> =
        db.recommendationQueries.forUser(userId).executeAsList()

    companion object {
        /** The process's one instance, for the pipeline step (whose deps are built in three places). Set by Main. */
        var current: RecommendationService? = null
        const val SOURCE_STANDARD = "standard"
        const val SOURCE_AI = "ai"
        /** Phase 272 — why a re-rank was queued, as the Activity card says it. */
        const val REASON_WEEKLY = "weekly build"
        const val REASON_RUN_BY_HAND = "run started by hand"
        const val REASON_FIRST = "first build"
        const val REASON_BY_HAND = "Rebuild now"
        /** Phase 270 — a viewer without an AI order is asked again at most once a day. */
        private const val AI_RETRY_SEC = 24L * 3_600
        private const val STALE_POLL_MS = 60_000L
        /** A finish, then a quiet two minutes, then the rebuild: one rebuild for an evening's binge, not one per episode. */
        private const val STALE_DEBOUNCE_SEC = 120L
        private const val RECENTLY_SEEN_MS = 30L * 24 * 3_600_000L
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        fun nowSec(): Long = time(null)
    }
}
