package dev.jellystructure.autofetch

import dev.jellystructure.cron.CronParse
import dev.jellystructure.cron.nextFireEpochSec
import dev.jellystructure.cron.parseCron
import dev.jellystructure.db.JellystructureDb
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** DEB-2476 (DEB-2474) — a saved fetcher. [retention] and [recipe] are the stored JSON, decoded on read. */
data class Fetcher(
    val id: String,
    val name: String,
    val pageUrl: String,
    val site: String,
    val tmdbId: Int?,
    val pageOnly: Boolean,
    val libraryPath: String,
    val seriesPath: String,
    val recipeJson: String,
    val proven: Boolean,
    val retention: RetentionSpec,
    val schedule: String,
    val enabled: Boolean,
    val state: String,
    val stateReason: String,
    val createdAt: Long,
    val lastRunAt: Long?,
    val lastListOkAt: Long?,
) {
    val recipe: Recipe? get() = RecipeCodec.decode(recipeJson)
}

/** One item of a fetcher: what the page said (verbatim, FR-8.2), and where it went. */
data class FetchItem(
    val fetcherId: String,
    val itemId: String,
    val link: String,
    val title: String?,
    val description: String?,
    val published: String?,
    val order: Int,
    val season: Int?,
    val episode: Int?,
    val duration: Int?,
    val mediaValue: String?,
    val state: String,
    val failures: Int,
    val note: String,
    val placedPath: String?,
    val placedAt: Long?,
    val missingSince: Long?,
)

@Serializable
data class RunLine(val kind: String, val text: String)

data class RunRecord(val id: Long, val fetcherId: String, val startedAt: Long, val finishedAt: Long?, val trigger: String, val status: String, val summary: String, val lines: List<RunLine>)

/** The `autofetch_*` tables. Lives on the shared database; every write is one statement. */
class AutofetchStore(private val db: JellystructureDb) : SiteStore {
    private val q get() = db.autofetchQueries
    private val json = Json { ignoreUnknownKeys = true }
    private val retentionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- fetchers ----------------------------------------------------------------------------------------------------

    fun fetchers(): List<Fetcher> = q.fetcherAll().executeAsList().map { map(it) }
    fun fetcher(id: String): Fetcher? = q.fetcherById(id).executeAsOneOrNull()?.let { map(it) }

    fun insert(f: Fetcher) = q.fetcherInsert(f.id, f.name, f.pageUrl, f.site, f.tmdbId?.toLong(), if (f.pageOnly) 1 else 0, f.libraryPath, f.seriesPath,
        f.recipeJson, if (f.proven) 1 else 0, retentionJson.encodeToString(RetentionSpec.serializer(), f.retention), f.schedule, if (f.enabled) 1 else 0, f.createdAt)

    fun updateSettings(id: String, retention: RetentionSpec, schedule: String, enabled: Boolean) =
        q.fetcherUpdateSettings(retentionJson.encodeToString(RetentionSpec.serializer(), retention), schedule, if (enabled) 1 else 0, id)

    fun setState(id: String, state: String, reason: String) = q.fetcherSetState(state, reason, id)
    fun setRan(id: String, at: Long) = q.fetcherSetRan(at, id)
    fun setListOk(id: String, at: Long) = q.fetcherSetListOk(at, id)

    /** FR-6.8 — deletes the fetcher, its recipe, its items, its run records and its mark; never a file. */
    fun delete(id: String) {
        q.itemsDeleteByFetcher(id); q.runsDeleteByFetcher(id); q.markDeleteByFetcher(id); q.fetcherDelete(id)
    }

    private fun map(r: dev.jellystructure.db.Autofetch_fetcher) = Fetcher(
        id = r.id, name = r.name, pageUrl = r.page_url, site = r.site, tmdbId = r.tmdb_id?.toInt(), pageOnly = r.page_only != 0L,
        libraryPath = r.library_path, seriesPath = r.series_path, recipeJson = r.recipe_json, proven = r.proven != 0L,
        retention = runCatching { json.decodeFromString(RetentionSpec.serializer(), r.retention) }.getOrDefault(RetentionSpec()),
        schedule = r.schedule, enabled = r.enabled != 0L, state = r.state, stateReason = r.state_reason, createdAt = r.created_at,
        lastRunAt = r.last_run_at, lastListOkAt = r.last_list_ok_at,
    )

    // ---- sites -------------------------------------------------------------------------------------------------------

    override fun get(site: String): SiteState = q.siteGet(site).executeAsOneOrNull()?.let {
        SiteState(it.site, it.stopped_reason, it.stopped_at, it.robots_text, it.robots_fetched_at, it.backoff_until, it.backoff_level.toInt(),
            it.rate_limited_day, it.rate_limited_count.toInt(), it.ever_ok != 0L)
    } ?: SiteState(site)

    override fun put(state: SiteState) = q.sitePut(state.site, state.stoppedReason, state.stoppedAt, state.robotsText, state.robotsFetchedAt,
        state.backoffUntil, state.backoffLevel.toLong(), state.rateLimitedDay, state.rateLimitedCount.toLong(), if (state.everOk) 1 else 0)

    fun sites(): List<SiteState> = q.siteAll().executeAsList().map { get(it.site) }

    // ---- items -------------------------------------------------------------------------------------------------------

    fun items(fetcherId: String): List<FetchItem> = q.itemsByFetcher(fetcherId).executeAsList().map { map(it) }
    fun item(fetcherId: String, itemId: String): FetchItem? = q.itemGet(fetcherId, itemId).executeAsOneOrNull()?.let { map(it) }
    fun itemByPlacedPath(path: String): FetchItem? = q.itemByPath(path).executeAsOneOrNull()?.let { map(it) }

    fun upsertListed(fetcherId: String, itemId: String, it: ListedItem, se: SeasonEpisode?, published: String?, now: Long) {
        val dur = it.duration?.trim()?.toDoubleOrNull()?.toLong()
        q.itemInsertListed(fetcherId, itemId, it.link.orEmpty(), it.title, it.description, published, it.order.toLong(), se?.season?.toLong(), se?.episode?.toLong(), dur, now)
        q.itemUpdateListed(it.link.orEmpty(), it.title, it.description, published, it.order.toLong(), se?.season?.toLong(), se?.episode?.toLong(), dur, now, fetcherId, itemId)
    }

    fun setMediaValue(fetcherId: String, itemId: String, v: String) = q.itemSetMediaValue(v, fetcherId, itemId)
    fun setItemState(fetcherId: String, itemId: String, state: String, note: String, failures: Int) = q.itemSetState(state, note, failures.toLong(), fetcherId, itemId)
    fun setPlaced(fetcherId: String, itemId: String, path: String, at: Long) = q.itemSetPlaced(path, at, fetcherId, itemId)
    fun release(fetcherId: String, itemId: String) = q.itemRelease(fetcherId, itemId)
    fun setDeleted(fetcherId: String, itemId: String, note: String) = q.itemSetDeleted(note, fetcherId, itemId)
    fun markMissing(fetcherId: String, itemId: String, since: Long) = q.itemMarkMissing(since, fetcherId, itemId)

    private fun map(r: dev.jellystructure.db.Autofetch_item) = FetchItem(
        r.fetcher_id, r.item_id, r.link, r.title, r.description, r.published, r.list_order.toInt(), r.season?.toInt(), r.episode?.toInt(),
        r.duration?.toInt(), r.media_value, r.state, r.failures.toInt(), r.note, r.placed_path, r.placed_at, r.missing_since,
    )

    // ---- runs --------------------------------------------------------------------------------------------------------

    fun startRun(fetcherId: String, trigger: String, at: Long): Long {
        // last_insert_rowid() is per connection; the insert and the read share the writer's transaction.
        return db.transactionWithResult {
            q.runInsert(fetcherId, at, trigger)
            q.lastInsertRowId().executeAsOne()
        }
    }

    fun finishRun(id: Long, at: Long, status: String, summary: String, lines: List<RunLine>) =
        q.runFinish(at, status, summary, json.encodeToString(ListSerializer(RunLine.serializer()), lines), id)

    fun runs(fetcherId: String, limit: Int = 20): List<RunRecord> = q.runsByFetcher(fetcherId, limit.toLong()).executeAsList().map {
        RunRecord(it.id, it.fetcher_id, it.started_at, it.finished_at, it.run_trigger, it.status, it.summary,
            runCatching { json.decodeFromString(ListSerializer(RunLine.serializer()), it.lines_json) }.getOrDefault(emptyList()))
    }

    /** A process that stopped mid-run leaves a `running` record; the next start closes them. */
    fun abandonRunning(at: Long) = q.runsAbandon(at)

    // ---- the page-only mark (FR-15.5) --------------------------------------------------------------------------------

    fun mark(seriesPath: String, fetcherId: String) = q.markPut(seriesPath, fetcherId)
    fun isPageOnlySeries(seriesPath: String): Boolean = q.markGet(seriesPath.trimEnd('/')).executeAsOneOrNull() != null
    fun pageOnlySeriesPaths(): Set<String> = q.markAll().executeAsList().map { it.series_path }.toSet()
}

/**
 * The schedule presets of `processing-runs-as-nine-features.md` FR-3.2, no custom below 6 hours (FR-2.3): `every6h` ·
 * `daily@HH:MM` · `weekly@D@HH:MM` (D = 0 Sunday … 6). The minute is spread by the fetcher's id so two fetchers on
 * *Daily · 04:00* do not start on the same second.
 */
object FetchSchedule {
    private val DAILY = Regex("""^daily@(\d{1,2}):(\d{2})$""")
    private val WEEKLY = Regex("""^weekly@([0-6])@(\d{1,2}):(\d{2})$""")

    fun valid(s: String): Boolean = s == "every6h" || DAILY.matches(s) && inClock(DAILY.find(s)!!) || WEEKLY.matches(s) && inClock(WEEKLY.find(s)!!, 2)

    private fun inClock(m: MatchResult, at: Int = 1): Boolean = m.groupValues[at].toInt() in 0..23 && m.groupValues[at + 1].toInt() in 0..59

    /** The spread: 0..14 minutes after the hour's minute, from the id. */
    fun spreadMinutes(id: String): Int = (id.fold(0) { a, c -> (a * 31 + c.code) and 0x7fffffff }) % 15

    private fun cron(s: String, id: String): String? {
        val spread = spreadMinutes(id)
        DAILY.find(s)?.let { return "${(it.groupValues[2].toInt() + spread) % 60} ${it.groupValues[1].toInt()} * * *" }
        WEEKLY.find(s)?.let { return "${(it.groupValues[3].toInt() + spread) % 60} ${it.groupValues[2].toInt()} * * ${it.groupValues[1]}" }
        return null
    }

    /** The next time this fetcher is due after [lastRunAt] (null = never ran: due now). Never sooner than the 6 h floor. */
    fun nextDue(schedule: String, id: String, lastRunAt: Long?, now: Long): Long {
        if (lastRunAt == null) return now
        val floor = lastRunAt + LaneRules.RUN_FLOOR_SEC
        if (schedule == "every6h") return floor
        val expr = (parseCron(cron(schedule, id) ?: return floor) as? CronParse.Ok)?.expr ?: return floor
        val slot = nextFireEpochSec(expr, lastRunAt) ?: return floor
        return maxOf(slot, floor)
    }
}
