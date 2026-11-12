package dev.jellystructure.autofetch

import dev.jellystructure.cron.ScheduleCheck
import dev.jellystructure.cron.ScheduleEvaluator
import dev.jellystructure.cron.ScheduleParse
import dev.jellystructure.cron.ScheduleZone
import dev.jellystructure.cron.parseSchedule
import dev.jellystructure.cron.validateSchedule
import dev.jellystructure.db.JellystructureDb
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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
    /** FR-15.3 — the programme's own description and image address, read from the page at save (page-only only). */
    val seriesDescription: String?,
    val seriesImage: String?,
    val retention: RetentionSpec,
    val schedule: String,
    val enabled: Boolean,
    val state: String,
    val stateReason: String,
    val createdAt: Long,
    val lastRunAt: Long?,
    val lastListOkAt: Long?,
    /** DEB-3592 (FR-2.8) — the admin's tick: this fetcher's requests ignore the site's `robots.txt` Disallow rules. Off for every fetcher saved before it. */
    val ignoreRobots: Boolean = false,
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
    /** When the listing last held this item (epoch seconds): the items with the newest value are what the page lists now. */
    val lastSeenAt: Long? = null,
    /** DEB-2479 (FR-4.3) — the number the admin approved; the only number a `mapped` recipe downloads under. */
    val approvedSeason: Int? = null,
    val approvedEpisode: Int? = null,
    /** The model's answer to *Propose*, kept until the admin accepts or replaces it. Nothing is downloaded for it. */
    val proposedSeason: Int? = null,
    val proposedEpisode: Int? = null,
    val proposedNote: String = "",
    /** DEB-3407 (FR-4.1) — the date the item's own yt-dlp answer gave, once; [ownDateRead] says it was asked for, so a null [ownDate] is *no date*. */
    val ownDate: String? = null,
    val ownDateRead: Boolean = false,
) {
    val approved: SeasonEpisode? get() = if (approvedSeason != null && approvedEpisode != null) SeasonEpisode(approvedSeason, approvedEpisode) else null
    val proposed: SeasonEpisode? get() = if (proposedSeason != null && proposedEpisode != null) SeasonEpisode(proposedSeason, proposedEpisode) else null
}

/**
 * DEB-2477 (FR-8.4, FR-9) — an episode filed under *Not on TMDB yet*. [titleFromPage] and [overviewFromPage] are the per-episode
 * marker of FR-8.4, field by field; [stills] are the moments, in seconds, the three stills were cut at (FR-9.4).
 */
data class QueueRow(
    val id: Long,
    val fetcherId: String,
    val itemId: String,
    val season: Int,
    val episode: Int,
    /** `queued` · `submitted` (the admin's note, FR-9.3) · `cleared` (TMDB returned the episode; the marker may live on). */
    val state: String,
    val filedAt: Long,
    val submittedAt: Long?,
    val clearedAt: Long?,
    val lastCheckAt: Long?,
    val titleFromPage: Boolean,
    val overviewFromPage: Boolean,
    val stills: List<Int>,
    /** DEB-3319 (FR-9.2) — the runtime ffprobe read from the placed file when the row was filed; null on a row filed before it was kept. */
    val runtimeSec: Int? = null,
) {
    val markerOn: Boolean get() = titleFromPage || overviewFromPage
    /** Checked daily while TMDB lacks the episode, or has it but still leaves a field blank that the page fills. */
    val needsCheck: Boolean get() = state != "cleared" || markerOn
}

@Serializable
data class RunLine(val kind: String, val text: String)

data class RunRecord(val id: Long, val fetcherId: String, val startedAt: Long, val finishedAt: Long?, val trigger: String, val status: String, val summary: String, val lines: List<RunLine>)

/** The `autofetch_*` tables. Lives on the shared database; every write is one statement. */
class AutofetchStore(private val db: JellystructureDb) : SiteStore, SessionStore {
    private val q get() = db.autofetchQueries
    private val json = Json { ignoreUnknownKeys = true }
    private val retentionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- fetchers ----------------------------------------------------------------------------------------------------

    fun fetchers(): List<Fetcher> = q.fetcherAll().executeAsList().map { map(it) }
    fun fetcher(id: String): Fetcher? = q.fetcherById(id).executeAsOneOrNull()?.let { map(it) }

    fun insert(f: Fetcher) = q.fetcherInsert(f.id, f.name, f.pageUrl, f.site, f.tmdbId?.toLong(), if (f.pageOnly) 1 else 0, f.libraryPath, f.seriesPath,
        f.recipeJson, if (f.proven) 1 else 0, f.seriesDescription, f.seriesImage, retentionJson.encodeToString(RetentionSpec.serializer(), f.retention), f.schedule, if (f.enabled) 1 else 0, f.createdAt, if (f.ignoreRobots) 1 else 0)

    fun updateSettings(id: String, retention: RetentionSpec, schedule: String, enabled: Boolean) =
        q.fetcherUpdateSettings(retentionJson.encodeToString(RetentionSpec.serializer(), retention), schedule, if (enabled) 1 else 0, id)

    fun setIgnoreRobots(id: String, ignore: Boolean) = q.fetcherSetIgnoreRobots(if (ignore) 1 else 0, id)

    fun setState(id: String, state: String, reason: String) = q.fetcherSetState(state, reason, id)
    /** DEB-2478 — *Re-investigate page* saved: the fetcher's recipe, and what it says of the series, replaced. */
    fun setRecipe(id: String, recipeJson: String, proven: Boolean, seriesDescription: String?, seriesImage: String?) =
        q.fetcherSetRecipe(recipeJson, if (proven) 1 else 0, seriesDescription, seriesImage, id)
    fun setRan(id: String, at: Long) = q.fetcherSetRan(at, id)
    fun setListOk(id: String, at: Long) = q.fetcherSetListOk(at, id)

    /** FR-6.8 — deletes the fetcher, its recipe, its items, its run records and its mark; never a file. */
    fun delete(id: String) {
        q.queueDeleteByFetcher(id); q.itemsDeleteByFetcher(id); q.runsDeleteByFetcher(id); q.markDeleteByFetcher(id); q.fetcherDelete(id)
    }

    private fun map(r: dev.jellystructure.db.Autofetch_fetcher) = Fetcher(
        id = r.id, name = r.name, pageUrl = r.page_url, site = r.site, tmdbId = r.tmdb_id?.toInt(), pageOnly = r.page_only != 0L,
        libraryPath = r.library_path, seriesPath = r.series_path, recipeJson = r.recipe_json, proven = r.proven != 0L, seriesDescription = r.series_description, seriesImage = r.series_image,
        retention = runCatching { json.decodeFromString(RetentionSpec.serializer(), r.retention) }.getOrDefault(RetentionSpec()),
        schedule = r.schedule, enabled = r.enabled != 0L, state = r.state, stateReason = r.state_reason, createdAt = r.created_at,
        lastRunAt = r.last_run_at, lastListOkAt = r.last_list_ok_at, ignoreRobots = r.ignore_robots != 0L,
    )

    // ---- sites -------------------------------------------------------------------------------------------------------

    override fun get(site: String): SiteState = q.siteGet(site).executeAsOneOrNull()?.let {
        SiteState(it.site, it.stopped_reason, it.stopped_at, it.robots_text, it.robots_fetched_at, it.backoff_until, it.backoff_level.toInt(),
            it.rate_limited_day, it.rate_limited_count.toInt(), it.ever_ok != 0L)
    } ?: SiteState(site)

    override fun put(state: SiteState) = q.sitePut(state.site, state.stoppedReason, state.stoppedAt, state.robotsText, state.robotsFetchedAt,
        state.backoffUntil, state.backoffLevel.toLong(), state.rateLimitedDay, state.rateLimitedCount.toLong(), if (state.everOk) 1 else 0)

    fun sites(): List<SiteState> = q.siteAll().executeAsList().map { get(it.site) }

    // ---- sessions (DEB-3613, FR-3.6): the value goes out through [session] only, to the code that makes the engine's own requests ----------

    override fun session(site: String): StoredSession? = q.sessionGet(site).executeAsOneOrNull()?.let { StoredSession(it.cookies, it.saved_at, it.expires_at) }
    override fun putSession(site: String, cookiesTxt: String, savedAt: Long, expiresAt: Long?) = q.sessionPut(site, cookiesTxt, savedAt, expiresAt)
    override fun deleteSession(site: String) = q.sessionDelete(site)
    /** The sites that have a session, so one saved before any request to the site is still listed (it has no `autofetch_site` row yet). */
    fun sessionSites(): List<String> = q.sessionSites().executeAsList()

    // ---- items -------------------------------------------------------------------------------------------------------

    fun items(fetcherId: String): List<FetchItem> = q.itemsByFetcher(fetcherId).executeAsList().map { map(it) }
    fun item(fetcherId: String, itemId: String): FetchItem? = q.itemGet(fetcherId, itemId).executeAsOneOrNull()?.let { map(it) }
    fun itemByPlacedPath(path: String): FetchItem? = q.itemByPath(path).executeAsOneOrNull()?.let { map(it) }

    fun upsertListed(fetcherId: String, itemId: String, it: ListedItem, se: SeasonEpisode?, published: String?, now: Long) {
        val dur = it.duration?.trim()?.toDoubleOrNull()?.toLong()
        q.itemInsertListed(fetcherId, itemId, it.link.orEmpty(), it.title, it.description, published, it.order.toLong(), se?.season?.toLong(), se?.episode?.toLong(), dur, now)
        q.itemUpdateListed(it.link.orEmpty(), it.title, it.description, published, it.order.toLong(), se?.season?.toLong(), se?.episode?.toLong(), dur, now, fetcherId, itemId)
    }

    /** The date the item's own yt-dlp answer gave, or null for *no date*; either way it is not asked for again. */
    fun setOwnDate(fetcherId: String, itemId: String, date: String?) = q.itemSetOwnDate(date, fetcherId, itemId)
    /** *Re-investigate page*: every *no date* answer may be asked for again. */
    fun clearOwnDates(fetcherId: String) = q.itemClearOwnDates(fetcherId)
    fun setMediaValue(fetcherId: String, itemId: String, v: String) = q.itemSetMediaValue(v, fetcherId, itemId)
    fun setItemState(fetcherId: String, itemId: String, state: String, note: String, failures: Int) = q.itemSetState(state, note, failures.toLong(), fetcherId, itemId)
    fun setPlaced(fetcherId: String, itemId: String, path: String, at: Long) = q.itemSetPlaced(path, at, fetcherId, itemId)
    fun release(fetcherId: String, itemId: String) = q.itemRelease(fetcherId, itemId)
    fun setDeleted(fetcherId: String, itemId: String, note: String) = q.itemSetDeleted(note, fetcherId, itemId)
    /** DEB-3695 (FR-4.5) — a placed file followed its item's date; the row records the new path. */
    fun setPath(fetcherId: String, itemId: String, path: String) = q.itemSetPath(path, fetcherId, itemId)
    /** DEB-3695 (FR-4.5) — the row keeps the number its file carries, when the page's new number is not free. */
    fun setNumber(fetcherId: String, itemId: String, se: SeasonEpisode) = q.itemSetNumber(se.season.toLong(), se.episode.toLong(), fetcherId, itemId)
    /** DEB-3695 (FR-16.2) — a re-upload replaced this row's episode: no path, and retention never counts it again. */
    fun setReplaced(fetcherId: String, itemId: String, note: String) = q.itemSetReplaced(note, fetcherId, itemId)
    fun markMissing(fetcherId: String, itemId: String, since: Long) = q.itemMarkMissing(since, fetcherId, itemId)

    /** DEB-2479 (FR-4.3) — the admin's number for an item; the proposal it answers goes, and a waiting item is waiting no longer. */
    fun setApproved(fetcherId: String, itemId: String, se: SeasonEpisode) = q.itemSetApproved(se.season.toLong(), se.episode.toLong(), fetcherId, itemId)
    fun setProposal(fetcherId: String, itemId: String, se: SeasonEpisode, note: String) = q.itemSetProposal(se.season.toLong(), se.episode.toLong(), note, fetcherId, itemId)
    /** A recipe that no longer maps numbers by hand drops the approvals of the items it has not placed (a placed item keeps its number). */
    fun clearApprovedUnplaced(fetcherId: String) = q.itemClearApprovedUnplaced(fetcherId)

    private fun map(r: dev.jellystructure.db.Autofetch_item) = FetchItem(
        r.fetcher_id, r.item_id, r.link, r.title, r.description, r.published, r.list_order.toInt(), r.season?.toInt(), r.episode?.toInt(),
        r.duration?.toInt(), r.media_value, r.state, r.failures.toInt(), r.note, r.placed_path, r.placed_at, r.missing_since, r.last_seen_at,
        r.approved_season?.toInt(), r.approved_episode?.toInt(), r.proposed_season?.toInt(), r.proposed_episode?.toInt(), r.proposed_note,
        r.own_date, r.own_date_read != 0L,
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

    // ---- Not on TMDB yet (FR-8.4, FR-9) --------------------------------------------------------------------------------

    fun queue(): List<QueueRow> = q.queueAll().executeAsList().map { map(it) }
    fun queueRow(id: Long): QueueRow? = q.queueById(id).executeAsOneOrNull()?.let { map(it) }
    fun queueRow(fetcherId: String, itemId: String): QueueRow? = q.queueGet(fetcherId, itemId).executeAsOneOrNull()?.let { map(it) }
    fun queueOf(fetcherId: String): List<QueueRow> = q.queueByFetcher(fetcherId).executeAsList().map { map(it) }

    fun fileQueue(fetcherId: String, itemId: String, season: Int, episode: Int, at: Long, titleFromPage: Boolean, overviewFromPage: Boolean, stills: List<Int>, runtimeSec: Int? = null) =
        q.queueInsert(fetcherId, itemId, season.toLong(), episode.toLong(), at, if (titleFromPage) 1 else 0, if (overviewFromPage) 1 else 0, stillsJson(stills), runtimeSec?.toLong())

    fun queueSubmitted(id: Long, at: Long) = q.queueSetSubmitted(at, id)
    fun queueCleared(id: Long, at: Long) = q.queueSetCleared(at, id)
    fun queueChecked(id: Long, at: Long) = q.queueSetChecked(at, id)
    fun queueMarkers(id: Long, title: Boolean, overview: Boolean) = q.queueSetMarkers(if (title) 1 else 0, if (overview) 1 else 0, id)
    fun queueStills(id: Long, stills: List<Int>) = q.queueSetStills(stillsJson(stills), id)
    fun dropQueue(fetcherId: String, itemId: String) = q.queueDelete(fetcherId, itemId)

    private fun stillsJson(stills: List<Int>) = json.encodeToString(ListSerializer(Int.serializer()), stills)

    private fun map(r: dev.jellystructure.db.Autofetch_queue) = QueueRow(
        r.id, r.fetcher_id, r.item_id, r.season.toInt(), r.episode.toInt(), r.state, r.filed_at, r.submitted_at, r.cleared_at, r.last_check_at,
        r.title_from_page != 0L, r.overview_from_page != 0L,
        runCatching { json.decodeFromString(ListSerializer(Int.serializer()), r.stills_json) }.getOrDefault(emptyList()),
        r.runtime_sec?.toInt(),
    )

    // ---- the page-only mark (FR-15.5) --------------------------------------------------------------------------------

    fun mark(seriesPath: String, fetcherId: String) = q.markPut(seriesPath, fetcherId)
    fun isPageOnlySeries(seriesPath: String): Boolean = q.markGet(seriesPath.trimEnd('/')).executeAsOneOrNull() != null
    fun pageOnlySeriesPaths(): Set<String> = q.markAll().executeAsList().map { it.series_path }.toSet()

    /** DEB-2916 (FR-1) — the series paths, trailing slash trimmed, fed by an Auto-fetch whose recipe numbers by air date. */
    fun dateNumberedSeriesPaths(): Set<String> {
        val dateFetchers = fetchers().filter { it.recipe?.numbering?.kind.let { k -> k == "date" || k == "datetime" } }.map { it.id }.toSet()
        if (dateFetchers.isEmpty()) return emptySet()
        return q.markAll().executeAsList().filter { it.fetcher_id in dateFetchers }.map { it.series_path.trimEnd('/') }.toSet()
    }
}

/**
 * DEB-4387 (FR-5) — an auto-fetcher's *Check* is the shared schedule (a cron or an `every:` recurrence), refused below
 * [FLOOR_MINUTES] (6 h, FR-2.3) and spread by the fetcher's id. The three words the column held before —
 * `every6h` · `daily@HH:MM` · `weekly@D@HH:MM` (D = 0 Sunday … 6) — keep being read for good, with no migration, and a fetcher is
 * written in the new form at its next save ([canonical]). The minute is spread by the fetcher's id so two fetchers on
 * *Daily · 04:00* do not start on the same second.
 */
object FetchSchedule {
    private val DAILY = Regex("""^daily@(\d{1,2}):(\d{2})$""")
    private val WEEKLY = Regex("""^weekly@([0-6])@(\d{1,2}):(\d{2})$""")

    /** FR-2.3 of the auto-fetch spec: no check more often than every six hours. */
    const val FLOOR_MINUTES = 360

    /** Every refusal starts with this, so a route can tell a schedule's 422 from any other problem. */
    const val REFUSAL_PREFIX = "the schedule"

    private fun inClock(m: MatchResult, at: Int = 1): Boolean = m.groupValues[at].toInt() in 0..23 && m.groupValues[at + 1].toInt() in 0..59

    /** The old words as the shared schedule's cron, or null when [s] is not one of them (or names an hour that does not exist). */
    private fun legacyCron(s: String): String? {
        if (s == "every6h") return "0 */6 * * *"
        DAILY.find(s)?.takeIf { inClock(it) }?.let { return "${it.groupValues[2].toInt()} ${it.groupValues[1].toInt()} * * *" }
        WEEKLY.find(s)?.takeIf { inClock(it, 2) }?.let { return "${it.groupValues[3].toInt()} ${it.groupValues[2].toInt()} * * ${it.groupValues[1]}" }
        return null
    }

    /** Null when [s] is a schedule a fetcher may have; else why not. A blank one is refused: a fetcher always has a Check. */
    fun refusal(s: String, nowSec: Long, zone: TimeZone): String? {
        if (legacyCron(s) != null) return null
        return when (val check = validateSchedule(s, nowSec, zone, FLOOR_MINUTES)) {
            is ScheduleCheck.Ok -> null
            is ScheduleCheck.Invalid -> "$REFUSAL_PREFIX: ${check.message}"
        }
    }

    /** The string to store: the old words become their cron (`weekly@3@04:00` is `0 4 * * 3`), anything else its canonical form. */
    fun canonical(s: String): String {
        legacyCron(s)?.let { return it }
        return (parseSchedule(s) as? ScheduleParse.Ok)?.schedule?.canonical ?: s
    }

    /** The spread: 0..14 minutes after the schedule's own minute, from the id. */
    fun spreadMinutes(id: String): Int = (id.fold(0) { a, c -> (a * 31 + c.code) and 0x7fffffff }) % 15

    /** The schedule in the plain words of the shared sentence, for a view. */
    fun sentence(s: String): String = (parseSchedule(legacyCron(s) ?: s) as? ScheduleParse.Ok)?.schedule?.sentence ?: s

    /**
     * The next time this fetcher is due after [lastRunAt] (null = never ran: due now), from the one evaluator: the first fire after
     * the last run, moved by the fetcher's spread, and never sooner than the 6 h floor after the last run.
     */
    fun nextDue(schedule: String, id: String, lastRunAt: Long?, now: Long, zone: TimeZone = ScheduleZone.resolve("")): Long {
        if (lastRunAt == null) return now
        val floor = lastRunAt + LaneRules.RUN_FLOOR_SEC
        if (schedule == "every6h") return floor
        val parsed = (parseSchedule(legacyCron(schedule) ?: schedule) as? ScheduleParse.Ok)?.schedule ?: return floor
        val spread = spreadMinutes(id) * 60L
        // Looking from the last run less the spread finds the fire whose spread-shifted moment is the first one after the run.
        val slot = (ScheduleEvaluator.nextFire(parsed, zone, lastRunAt - spread) ?: return floor) + spread
        return maxOf(slot, floor)
    }
}
