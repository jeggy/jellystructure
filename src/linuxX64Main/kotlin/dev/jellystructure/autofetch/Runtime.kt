package dev.jellystructure.autofetch

import dev.jellystructure.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * DEB-2476 (DEB-2474, FR-6.2, FR-12) — what the server holds: the store, the engine and the service, the schedule tick, and the
 * admin's views. A fetcher runs from its recipe with no model call, so nothing here talks to Anthropic.
 */
class AutofetchRuntime(
    val store: AutofetchStore,
    val engine: AutofetchEngine,
    val service: AutofetchService,
    private val lanes: SiteLanes,
    private val world: AutofetchWorld,
    private val scope: CoroutineScope,
) {
    /** A press on *Run now*: it does not wait for viewers (FR-6.2). Returns false when that fetcher is already running. */
    fun runNow(id: String): Boolean {
        if (store.fetcher(id) == null || engine.isRunning(id)) return false
        scope.launch { engine.run(id, RunTrigger.MANUAL) }
        return true
    }

    /** *Run all now* queues every enabled fetcher into its site's lane, one site's fetchers one after another. */
    fun runAll(): Int {
        val bySite = store.fetchers().filter { it.enabled }.groupBy { it.site }
        for ((_, list) in bySite) scope.launch { for (f in list) engine.run(f.id, RunTrigger.ALL) }
        return bySite.values.sumOf { it.size }
    }

    /** The schedule: once a minute, every enabled fetcher that is due. Never starts anything while off or while someone watches. */
    fun startTick() {
        runCatching { store.abandonRunning(world.nowSec()) }   // a restart mid-run leaves a `running` record behind
        scope.launch {
            while (true) {
                delay(60_000)
                runCatching {
                    if (!world.enabled()) return@runCatching
                    for (f in due(world.nowSec())) engine.run(f.id, RunTrigger.SCHEDULE)
                }.onFailure { Logger.warn("auto-fetch: tick failed: ${it.message}", "autofetch") }
            }
        }
    }

    /**
     * The fetchers whose schedule has come round. A fetcher that never ran is measured from the moment it was saved, so saving
     * one does not start a download by itself; *Run now* is the press that does (FR-6.2). Never one that is paused, running,
     * waiting for re-investigation, or on a stopped site (FR-2.5).
     */
    suspend fun due(now: Long): List<Fetcher> = store.fetchers().filter { f ->
        f.enabled && f.state == "ok" && !engine.isRunning(f.id) &&
            FetchSchedule.nextDue(f.schedule, f.id, f.lastRunAt ?: f.createdAt, now) <= now && !lanes.forSite(f.site).state().stopped
    }

    fun view(f: Fetcher): FetcherView {
        val site = store.get(f.site)
        val last = store.runs(f.id, 1).firstOrNull()
        val recipe = f.recipe
        return FetcherView(
            id = f.id, name = f.name, pageUrl = f.pageUrl, site = f.site, tmdbId = f.tmdbId, pageOnly = f.pageOnly, seriesPath = f.seriesPath,
            proven = f.proven, schedule = f.schedule, enabled = f.enabled, retention = f.retention, state = f.state, stateReason = f.stateReason,
            siteStopped = site.stopped, siteStoppedReason = site.stoppedReason, siteStoppedAt = site.stoppedAt,
            numbering = recipe?.numbering?.kind.orEmpty(), running = engine.isRunning(f.id), lastRunAt = f.lastRunAt,
            nextRunAt = if (f.enabled) FetchSchedule.nextDue(f.schedule, f.id, f.lastRunAt ?: f.createdAt, world.nowSec()) else null,
            lastRun = last?.let { RunView(it.startedAt, it.finishedAt, it.trigger, it.status, it.summary, it.lines) },
            placed = store.items(f.id).count { it.placedPath != null },
        )
    }
}

@Serializable
data class RunView(val startedAt: Long, val finishedAt: Long?, val trigger: String, val status: String, val summary: String, val lines: List<RunLine>)

@Serializable
data class FetcherView(
    val id: String, val name: String, val pageUrl: String, val site: String, val tmdbId: Int?, val pageOnly: Boolean, val seriesPath: String,
    val proven: Boolean, val schedule: String, val enabled: Boolean, val retention: RetentionSpec, val state: String, val stateReason: String,
    val siteStopped: Boolean, val siteStoppedReason: String?, val siteStoppedAt: Long?, val numbering: String, val running: Boolean,
    val lastRunAt: Long?, val nextRunAt: Long?, val lastRun: RunView?, val placed: Int,
)

@Serializable
data class SiteView(val site: String, val stopped: Boolean, val reason: String?, val stoppedAt: Long?, val backoffUntil: Long?)

@Serializable
data class AutofetchOverview(val enabled: Boolean, val fetchers: List<FetcherView>, val sites: List<SiteView>)
