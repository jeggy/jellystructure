package dev.jellystructure.autofetch

import dev.jellystructure.log.Logger
import dev.jellystructure.model.DashboardRow
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
    /** DEB-2478 — the investigation, once the AI rail is wired (a server without it has none, and the tab says so). */
    var investigations: Investigations? = null

    /** DEB-2479 — *Propose* for an item waiting for a number (FR-4.3), on the same AI rail. */
    var proposer: Proposer? = null

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
                    // FR-9.3 — the daily TMDB check of the queued seasons; a background job, so it waits while someone is watching.
                    if (!world.waitForViewers()) engine.queue.recheckDue(world.nowSec())
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
        val runs = store.runs(f.id, RUNS_SHOWN)
        val last = runs.firstOrNull()
        val recipe = f.recipe
        val items = store.items(f.id)
        return FetcherView(
            id = f.id, name = f.name, pageUrl = f.pageUrl, site = f.site, tmdbId = f.tmdbId, pageOnly = f.pageOnly, seriesPath = f.seriesPath,
            proven = f.proven, schedule = f.schedule, enabled = f.enabled, retention = f.retention, state = f.state, stateReason = f.stateReason,
            siteStopped = site.stopped, siteStoppedReason = site.stoppedReason, siteStoppedAt = site.stoppedAt,
            numbering = recipe?.numbering?.kind.orEmpty(), running = engine.isRunning(f.id), lastRunAt = f.lastRunAt,
            nextRunAt = if (f.enabled) FetchSchedule.nextDue(f.schedule, f.id, f.lastRunAt ?: f.createdAt, world.nowSec()) else null,
            lastRun = last?.let { RunView(it.startedAt, it.finishedAt, it.trigger, it.status, it.summary, it.lines) },
            placed = items.count { it.placedPath != null },
            runs = runs.map { RunView(it.startedAt, it.finishedAt, it.trigger, it.status, it.summary, it.lines) },
            recipe = recipe?.let { RecipeView.lines(f, it) }.orEmpty(),
            pattern = recipe?.let { RecipeView.patternText(it) }.orEmpty(),
            quality = recipe?.let { RecipeView.qualityText(it) }.orEmpty(),
            queued = store.queueOf(f.id).count { it.state != "cleared" },
            waiting = waitingItems(items),
            ignoreRobots = f.ignoreRobots,
        )
    }

    /** FR-4.3 — the items on the page now that are not downloaded because they have no number the admin approved, newest first. */
    private fun waitingItems(items: List<FetchItem>): List<WaitingItemView> {
        val lastRead = items.maxOfOrNull { it.lastSeenAt ?: 0L } ?: return emptyList()
        return items.filter { it.state == "waiting" && it.placedPath == null && (it.lastSeenAt ?: 0L) == lastRead }
            .sortedWith(compareByDescending<FetchItem> { it.published ?: "" }.thenBy { it.order })
            .map { i ->
                WaitingItemView(
                    itemId = i.itemId, title = i.title?.takeIf { it.isNotBlank() }, link = i.link, published = i.published?.takeIf { it.isNotBlank() },
                    description = i.description?.takeIf { it.isNotBlank() }, why = i.note,
                    proposal = i.proposed?.let { ProposalView(it.season, it.episode, Numbering.code(it), i.proposedNote) },
                )
            }
    }

    /**
     * DEB-2477 (FR-2.5) — the Dashboard's rows: one per stopped site, whatever the fetchers on it are doing. A stop is never retried, so the
     * row stays until the admin presses *Resume site* on the Fetchers tab, which is where the row opens.
     */
    fun dashboardRows(): List<DashboardRow> {
        val fetchers = store.fetchers()
        return store.sites().filter { it.stopped }.map { st ->
            DashboardRow(
                id = "autofetch_site:${st.site}", domain = "autofetch", severity = "warning", label = "${st.site} has stopped us",
                sentence = "${st.stoppedReason.orEmpty().replaceFirstChar { it.uppercase() }}. Nothing runs on this site, for any fetcher, until you resume it — a stop is never retried on a timer.",
                count = fetchers.count { it.site == st.site }, unit = "fetcher", fix = "open", action = "Fetchers", href = "/ai?tab=fetchers", path = "AI ▸ Fetchers › ${st.site}",
            )
        }
    }

    /** FR-9.2 — the rows of *Not on TMDB yet*, newest first. A cleared row is history and is not listed. */
    fun queueViews(): List<QueueEntryView> = store.queue().filter { it.state != "cleared" }.mapNotNull { r ->
        val f = store.fetcher(r.fetcherId) ?: return@mapNotNull null
        val item = store.item(r.fetcherId, r.itemId) ?: return@mapNotNull null
        QueueEntryView(
            id = r.id, fetcherId = f.id, series = f.name, tmdbId = f.tmdbId, season = r.season, episode = r.episode,
            code = Numbering.code(SeasonEpisode(r.season, r.episode)), pageLabel = item.title?.takeIf { it.isNotBlank() } ?: item.link,
            state = r.state, filedAt = r.filedAt, submittedAt = r.submittedAt, runtimeSec = r.runtimeSec ?: item.duration,
            title = item.title?.takeIf { it.isNotBlank() }, overview = item.description?.takeIf { it.isNotBlank() },
            airDate = Numbering.isoDate(item.published) ?: item.published, stills = r.stills,
        )
    }

    companion object { const val RUNS_SHOWN = 6 }
}

@Serializable
data class RunView(val startedAt: Long, val finishedAt: Long?, val trigger: String, val status: String, val summary: String, val lines: List<RunLine>)

@Serializable
data class FetcherView(
    val id: String, val name: String, val pageUrl: String, val site: String, val tmdbId: Int?, val pageOnly: Boolean, val seriesPath: String,
    val proven: Boolean, val schedule: String, val enabled: Boolean, val retention: RetentionSpec, val state: String, val stateReason: String,
    val siteStopped: Boolean, val siteStoppedReason: String?, val siteStoppedAt: Long?, val numbering: String, val running: Boolean,
    val lastRunAt: Long?, val nextRunAt: Long?, val lastRun: RunView?, val placed: Int,
    /** DEB-2477 — the open row: the last runs (FR-10.6), the recipe read-only (FR-4.4), and the list row's words. */
    val runs: List<RunView> = emptyList(), val recipe: List<RecipeLineView> = emptyList(), val pattern: String = "", val quality: String = "",
    /** Episodes of this fetcher waiting under *Not on TMDB yet*. */
    val queued: Int = 0,
    /** DEB-2479 (FR-4.3, FR-10.2) — items waiting for a number the admin approves; the row says *n items waiting for a number*. */
    val waiting: List<WaitingItemView> = emptyList(),
    /** DEB-3592 (FR-2.8, FR-12) — this fetcher's requests ignore the site's `robots.txt` Disallow rules. */
    val ignoreRobots: Boolean = false,
)

/** An item the page lists that is not downloaded until the admin gives it a number: the page's own words, verbatim, and the model's proposal if asked for. */
@Serializable
data class WaitingItemView(
    val itemId: String, val title: String?, val link: String, val published: String?, val description: String?, val why: String = "",
    val proposal: ProposalView? = null,
)

/** The model's answer to *Propose*, kept on the item (FR-4.3). The admin accepts it by sending its numbers to `…/number`. */
@Serializable
data class ProposalView(val season: Int, val episode: Int, val code: String, val note: String)

/** One line of the read-only recipe: the key, its text, and an optional trailing comment. */
@Serializable
data class RecipeLineView(val key: String, val text: String, val comment: String = "")

/** FR-9.2 — what *Not on TMDB yet* holds for one episode. The words are the page's own, verbatim, or null when the page has none (FR-8.2). */
@Serializable
data class QueueEntryView(
    val id: Long, val fetcherId: String, val series: String, val tmdbId: Int?, val season: Int, val episode: Int, val code: String,
    val pageLabel: String, val state: String, val filedAt: Long, val submittedAt: Long?, val runtimeSec: Int?,
    val title: String?, val overview: String?, val airDate: String?,
    /** The moments, in seconds, the stills were cut at; `GET /api/autofetch/queue/{id}/still/{n}` answers each. */
    val stills: List<Int>,
)

@Serializable
data class SiteView(val site: String, val stopped: Boolean, val reason: String?, val stoppedAt: Long?, val backoffUntil: Long?, val fetchers: Int = 0)

@Serializable
data class AutofetchOverview(
    val enabled: Boolean, val fetchers: List<FetcherView>, val sites: List<SiteView>, val queue: List<QueueEntryView> = emptyList(),
    /** DEB-2478 (FR-1.2) — whether *Investigate* is on and, if not, why; and the investigation the server holds, if one. */
    val investigate: InvestigateGate = InvestigateGate(false, "The investigation is not running"),
    val investigation: InvestigationView? = null,
)

/** The recipe in the words of the open row (FR-4.4): data, never executed. */
object RecipeView {
    fun lines(f: Fetcher, r: Recipe): List<RecipeLineView> = buildList {
        add(RecipeLineView("#", "recipe v${r.version} · ${f.name} · ${if (f.pageOnly) "page only, no TMDB id" else "tmdb ${f.tmdbId}"}${if (r.writtenBy.isNotBlank()) " · written by ${r.writtenBy}${if (r.writtenAt.isNotBlank()) " on ${r.writtenAt}" else ""}" else ""} · read-only here"))
        add(RecipeLineView("source", r.source.url, "hosts fixed at save; nothing else may be named"))
        add(RecipeLineView("list", when (r.list.kind) {
            "json" -> "GET ${r.list.url} → ${r.list.fields.keys.joinToString(", ")}"
            else -> "${r.list.kind} ${r.list.itemSelector} → ${r.list.fields.keys.joinToString(", ")}"
        }))
        add(RecipeLineView("media", when {
            r.media.itemPage != null -> "${r.media.manifest}  (the media id is read from each item's own page)"
            r.media.manifest.isNotBlank() -> r.media.manifest
            else -> "the listing's own manifest field"
        }, qualityText(r)))
        add(RecipeLineView("numbering", r.numbering.kind, when (r.numbering.kind) {
            "date" -> "season = the published year, episode = its day of the year"
            "datetime" -> "season = the year and week, episode = the day and time"
            "mapped" -> "an explicit item → SxxEyy table, only the rows you accepted"
            else -> ""
        }))
        if (r.numbering.kind == "source") add(RecipeLineView("pattern", patternText(r)))
        if (f.pageOnly && r.series != null) add(RecipeLineView("series", "the programme page's own name, description and image", "read at save and on Re-investigate, never on a run"))
        add(RecipeLineView("retention", if (r.retention.keepsAll) "all episodes" else "newest ${r.retention.keep}${if (r.retention.leavesPage) " · also delete once it leaves the page" else ""}"))
        add(RecipeLineView("quality", "highest variant, always"))
        add(RecipeLineView("#", "nothing in a recipe is executed. The engine runs it, not the model."))
    }

    fun patternText(r: Recipe): String = when (r.numbering.kind) {
        "date" -> "By date"
        "datetime" -> "By date and time"
        "mapped" -> "Mapped"
        else -> r.pattern.joinToString(" · ") { it.regex }.ifBlank { "—" }
    }

    fun qualityText(r: Recipe): String = "highest ${r.media.kind.uppercase()}"
}
