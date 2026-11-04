package dev.jellystructure.autofetch

import kotlinx.serialization.Serializable

/**
 * DEB-2476 (DEB-2474, FR-3, FR-4.2, FR-12) — saving a recipe, and the settings the admin changes on a saved fetcher. A recipe
 * is entered through the route by hand in this slice; the investigation that writes one arrives in slice 4. Nothing is written
 * until the recipe has been proven against the live site.
 */
@Serializable
data class SaveRequest(
    val url: String = "",
    val tmdbId: Int? = null,
    val pageOnly: Boolean = false,
    val recipe: Recipe? = null,
    val libraryPath: String? = null,
    val name: String? = null,
    val schedule: String? = null,
    val retention: RetentionSpec? = null,
    /** `mapped` numbering (FR-4.3): the proposed table, and the ids of the items whose row the admin accepted. Only those get a number. */
    val mapping: List<MapRow> = emptyList(),
    val accept: List<String> = emptyList(),
)

sealed class NumberResult {
    data class Approved(val item: FetchItem) : NumberResult()
    data class Refused(val status: Int, val reason: String) : NumberResult()
}

sealed class SessionResult {
    data class Saved(val view: SessionView) : SessionResult()
    data class Refused(val status: Int, val reason: String) : SessionResult()
}

sealed class SaveResult {
    data class Saved(val fetcher: Fetcher, val proof: ProofResult) : SaveResult()
    /** [status] is the HTTP status the route answers; [notASeries] marks the drawn *Not a series* state. */
    data class Refused(val status: Int, val reason: String, val notASeries: Boolean = false, val proof: ProofResult? = null) : SaveResult()
}

class AutofetchService(
    private val store: AutofetchStore,
    private val engine: AutofetchEngine,
    private val lanes: SiteLanes,
    private val tools: MediaTools,
    private val world: AutofetchWorld,
    private val newId: () -> String,
) {
    private val proof = RecipeProof(tools, world)

    /** [ignoreRobots] is the investigation's tick (FR-2.8), kept on the fetcher; it is not part of the request body. */
    suspend fun save(req: SaveRequest, ignoreRobots: Boolean = false): SaveResult {
        if (!world.enabled()) return SaveResult.Refused(409, "Auto-fetch is switched off ([autofetch] enabled)")
        // FR-15.1 — a page-only fetcher is chosen, never inferred.
        if (req.tmdbId == null && !req.pageOnly) return SaveResult.Refused(400, RecipeRefusal.NO_TMDB_ID)
        if (req.tmdbId != null && req.pageOnly) return SaveResult.Refused(400, "A page-only fetcher has no TMDB id")
        val recipe0 = req.recipe ?: return SaveResult.Refused(400, "the request carries no recipe")
        val site = Urls.siteOf(req.url) ?: return SaveResult.Refused(400, "the page URL is not an http or https address")
        val withUrl = recipe0.copy(source = recipe0.source.copy(url = req.url, hosts = (recipe0.source.hosts + listOfNotNull(Urls.host(req.url))).distinct()))
        val retention = req.retention ?: if (req.pageOnly) RetentionSpec("newest", 12, true) else RetentionSpec("all")
        val recipe = withUrl.copy(retention = retention)
        val schedule = req.schedule ?: "daily@04:00"
        if (!FetchSchedule.valid(schedule)) return SaveResult.Refused(400, "the schedule must be every6h, daily@HH:MM or weekly@D@HH:MM; no custom schedule below 6 hours")
        RecipeValidator.validate(recipe, req.pageOnly).firstOrNull()?.let { return SaveResult.Refused(422, it) }

        // FR-3.1 — a series only.
        var tmdbName: String? = null
        if (req.tmdbId != null) {
            when (world.tmdbKind(req.tmdbId)) {
                TmdbKind.FILM -> return SaveResult.Refused(422, "this id is a film — Auto-fetch needs a series")
                TmdbKind.UNKNOWN -> return SaveResult.Refused(422, "TMDB has no series with this id")
                TmdbKind.UNREACHABLE -> return SaveResult.Refused(503, "TMDB could not be reached to check the id")
                TmdbKind.SERIES -> tmdbName = world.tmdbSeriesName(req.tmdbId)
            }
        }
        // FR-3.5 — one target library.
        val libs = world.libraries().filter { !it.skip && it.collectionType.equals("tvshows", true) && it.localPath.isNotBlank() }
        val lib = when {
            req.libraryPath != null -> libs.firstOrNull { it.localPath.trimEnd('/') == req.libraryPath.trimEnd('/') }
                ?: return SaveResult.Refused(400, "that is not one of the mapped series libraries")
            libs.size == 1 -> libs.single()
            libs.isEmpty() -> return SaveResult.Refused(422, "no series library is mapped")
            else -> return SaveResult.Refused(400, "choose which series library this saves into")
        }

        // The proof (FR-4.2 / FR-15.8). Whatever it finds is the failed state with its reason; nothing is written.
        val lane = lanes.forSite(site, ignoreRobots, signedIn = true, proving = true)
        val pr = proof.prove(recipe, req.pageOnly, req.tmdbId, lane, req.mapping)
        if (!pr.ok) {
            val blocked = pr.blocked
            return SaveResult.Refused(if (blocked != null) 502 else 422, pr.failure ?: "the recipe failed its proof", notASeries = blocked == null, proof = pr)
        }

        val chosen = (req.name ?: pr.series?.name ?: tmdbName)?.let { Numbering.cleanName(it) }?.takeIf { it.isNotEmpty() }
            ?: return SaveResult.Refused(422, "the series has no name; give one")
        val seriesPath = lib.localPath.trimEnd('/') + "/" + chosen
        // FR-3.4 — not where another tool downloads.
        world.sonarrReason(req.tmdbId, seriesPath)?.let { return SaveResult.Refused(409, it) }

        val f = Fetcher(
            id = newId(), name = chosen, pageUrl = req.url, site = site, tmdbId = req.tmdbId, pageOnly = req.pageOnly,
            libraryPath = lib.localPath.trimEnd('/'), seriesPath = seriesPath, recipeJson = RecipeCodec.encode(recipe), proven = pr.proven,
            seriesDescription = pr.series?.description, seriesImage = pr.series?.image,
            retention = retention, schedule = schedule, enabled = true, state = "ok", stateReason = "", createdAt = world.nowSec(),
            lastRunAt = null, lastListOkAt = world.nowSec(), ignoreRobots = ignoreRobots,
        )
        store.insert(f)
        if (req.pageOnly) store.mark(seriesPath, f.id)
        storeListed(f.id, recipe, pr, req.accept.toSet())
        return SaveResult.Saved(f, pr)
    }

    /**
     * DEB-2478 (FR-4.4) — *Re-investigate page* saved: the new recipe takes the old one's place, after the same checks as a first save
     * (shape, then the proof against the live site). The fetcher's retention, schedule and files stay as they were, and it runs again
     * from the next check. The old recipe ran until this moment.
     */
    suspend fun replaceRecipe(id: String, recipe0: Recipe, mapping: List<MapRow> = emptyList(), accept: List<String> = emptyList()): SaveResult {
        val f = store.fetcher(id) ?: return SaveResult.Refused(404, "no such fetcher")
        val recipe = recipe0.copy(source = recipe0.source.copy(url = f.pageUrl, hosts = (recipe0.source.hosts + listOfNotNull(Urls.host(f.pageUrl))).distinct()), retention = f.retention)
        RecipeValidator.validate(recipe, f.pageOnly).firstOrNull()?.let { return SaveResult.Refused(422, it) }
        val pr = proof.prove(recipe, f.pageOnly, f.tmdbId, lanes.forSite(f.site, f.ignoreRobots, signedIn = true, proving = true), mapping)
        if (!pr.ok) return SaveResult.Refused(if (pr.blocked != null) 502 else 422, pr.failure ?: "the recipe failed its proof", notASeries = pr.blocked == null, proof = pr)
        // A recipe that no longer maps by hand drops the numbers the admin approved for items it has not placed; a placed one keeps its own.
        if (f.recipe?.numbering?.kind == "mapped" && recipe.numbering.kind != "mapped") store.clearApprovedUnplaced(id)
        store.setRecipe(id, RecipeCodec.encode(recipe), pr.proven, pr.series?.description ?: f.seriesDescription, pr.series?.image ?: f.seriesImage)
        store.setState(id, "ok", "")
        store.clearOwnDates(id)
        storeListed(id, recipe, pr, accept.toSet())
        return SaveResult.Saved(store.fetcher(id)!!, pr)
    }

    /**
     * What a save writes of the listing it proved. With `mapped` numbering only the rows the admin ticked get a number (FR-4.3); every other
     * item is stored with none and waits for one. Otherwise every item takes the number its recipe gave it.
     */
    private fun storeListed(fetcherId: String, recipe: Recipe, pr: ProofResult, accepted: Set<String>) {
        val now = world.nowSec()
        if (recipe.numbering.kind != "mapped") {
            for ((item, se) in pr.numbered) {
                val itemId = itemKeyOf(item)
                if (itemId.isEmpty()) continue
                store.upsertListed(fetcherId, itemId, item, se, PageDates.dateOf(recipe, item), now)
            }
            storeDated(fetcherId, pr, now)
            return
        }
        val proposed = pr.numbered.associate { (item, se) -> itemKeyOf(item) to se }
        for (item in pr.items) {
            val itemId = itemKeyOf(item)
            if (itemId.isEmpty()) continue
            val tick = proposed[itemId]?.takeIf { itemId in accepted }
            val prior = store.item(fetcherId, itemId)?.approved
            store.upsertListed(fetcherId, itemId, item, tick ?: prior, Numbering.isoDate(item.published), now)
            if (tick != null) store.setApproved(fetcherId, itemId, tick)
            else if (prior == null) store.setItemState(fetcherId, itemId, "waiting", "no number approved yet", 0)
        }
        storeDated(fetcherId, pr, now)
    }

    /** DEB-3407 (FR-4.1) — what the proof learned of an item's own date is kept on its row, so the first run does not ask again; *no date* is kept too. */
    private fun storeDated(fetcherId: String, pr: ProofResult, now: Long) {
        val byId = pr.items.associateBy { itemKeyOf(it) }
        for ((itemId, date) in pr.dated) {
            val item = byId[itemId] ?: continue
            if (itemId.isEmpty()) continue
            if (store.item(fetcherId, itemId) == null) store.upsertListed(fetcherId, itemId, item, null, Numbering.isoDate(item.published), now)
            store.setOwnDate(fetcherId, itemId, date)
        }
    }

    /**
     * `POST /api/autofetch/{id}/items/{itemId}/number` (FR-4.3, FR-12) — the admin's number for an item that waits for one: typed, or a proposal
     * accepted. It writes nothing but the database; the next run downloads the item. The number may not be another item's, and may not be a file
     * the series folder already holds.
     */
    suspend fun approveNumber(fetcherId: String, itemId: String, season: Int, episode: Int): NumberResult {
        val f = store.fetcher(fetcherId) ?: return NumberResult.Refused(404, "no such fetcher")
        val item = store.item(fetcherId, itemId) ?: return NumberResult.Refused(404, "no such item")
        if (season !in 0..9999 || episode !in 0..9999) return NumberResult.Refused(422, "a season and an episode are numbers from 0 to 9999")
        if (item.placedPath != null) return NumberResult.Refused(409, "that item is already in the library")
        val se = SeasonEpisode(season, episode)
        takenReason(f, item.itemId, se)?.let { return NumberResult.Refused(409, it) }
        store.setApproved(fetcherId, itemId, se)
        return NumberResult.Approved(store.item(fetcherId, itemId)!!)
    }

    /** Why [se] is not free for [itemId] of [f]: another of its items has it, or the series folder holds a file under it. Null when it is free. */
    internal suspend fun takenReason(f: Fetcher, itemId: String, se: SeasonEpisode): String? {
        val other = store.items(f.id).firstOrNull { o ->
            o.itemId != itemId && o.state !in setOf("deleted", "released") && (o.approved ?: if (o.season != null && o.episode != null) SeasonEpisode(o.season, o.episode) else null) == se
        }
        if (other != null) return "${Numbering.code(se)} is already the number of '${other.title?.takeIf { it.isNotBlank() } ?: other.link}'"
        if (se in engine.existingCodes(f.seriesPath)) return "${Numbering.code(se)} already has a file in the series folder"
        return null
    }

    /** `PATCH /api/autofetch/{id}` — retention, schedule, the paused switch and the robots.txt tick (FR-2.8); none of them needs *Re-investigate page* (FR-16.1). */
    fun update(id: String, retention: RetentionSpec?, schedule: String?, enabled: Boolean?, ignoreRobots: Boolean? = null): String? {
        val f = store.fetcher(id) ?: return "no such fetcher"
        retention?.let {
            if (it.mode != "all" && it.mode != "newest") return "retention must be all or newest"
            if (it.mode == "newest" && it.keep !in 1..999) return "Keep the newest N takes a number from 1 to 999"
        }
        if (schedule != null && !FetchSchedule.valid(schedule)) return "the schedule must be every6h, daily@HH:MM or weekly@D@HH:MM; no custom schedule below 6 hours"
        store.updateSettings(id, retention ?: f.retention, schedule ?: f.schedule, enabled ?: f.enabled)
        if (ignoreRobots != null) store.setIgnoreRobots(id, ignoreRobots)
        return null
    }

    /** `DELETE /api/autofetch/{id}` — the fetcher, its recipe and its history; never a file (FR-6.8). */
    suspend fun delete(id: String): Boolean {
        if (store.fetcher(id) == null) return false
        engine.queue.forgetFetcher(id)
        store.delete(id)
        return true
    }

    /** `POST /api/autofetch/sites/{site}/resume` — one listing request; back to stopped if that is refused too (FR-2.5). */
    suspend fun resume(site: String): String? {
        val lane = lanes.forSite(site, signedIn = true)
        if (!lane.state().stopped) return "that site is not stopped"
        lane.resume()
        val fetcher = store.fetchers().firstOrNull { it.site == site } ?: return null
        val probe = fetcher.recipe ?: return null
        val read = ListingFetcher.read(probe.copy(list = probe.list.copy(next = null)), lane.forFetcher(fetcher.ignoreRobots), tools)
        return if (read is ListingRead.Blocked && lane.state().stopped) "the site refused again: ${lane.state().stoppedReason}" else null
    }

    /**
     * DEB-3613 (FR-3.6, FR-12) — `PUT /api/autofetch/sites/{site}/session`: the admin's own pasted session for [site], kept once for every fetcher on it.
     * A paste that cannot be read, or holds no cookie for the site, is refused (`422`) and nothing is kept. A site stopped because its session ran out
     * resumes. The answer never holds the session.
     */
    fun setSession(site0: String, text: String): SessionResult {
        val site = site0.trim().lowercase().removePrefix("www.")
        if (site.isEmpty() || !site.contains('.') || site.any { it == '/' || it.isWhitespace() }) return SessionResult.Refused(422, "That is not a site.")
        if (text.length > SiteSessions.MAX_PASTE) return SessionResult.Refused(422, SiteSessions.TOO_LARGE)
        val paste = SiteSessions.parse(text, site)
        if (paste is SessionPaste.Refused) return SessionResult.Refused(422, paste.reason)
        paste as SessionPaste.Parsed
        val now = world.nowSec()
        store.putSession(site, SiteSessions.toNetscape(paste.cookies), now, paste.expiresAt)
        // A site stopped because the old session ran out takes the new one; any other stop is the admin's *Resume site* to press.
        if (SiteSessions.isExpiredReason(site, store.get(site).stoppedReason)) store.put(store.get(site).copy(stoppedReason = null, stoppedAt = null, backoffUntil = null, backoffLevel = 0))
        return SessionResult.Saved(SessionView(true, now, paste.expiresAt))
    }

    /** `DELETE …/session` — forgets it. Fetchers that need it stop at their next run with FR-3.6's reason. True when there was one. */
    fun forgetSession(site0: String): Boolean {
        val site = site0.trim().lowercase().removePrefix("www.")
        if (store.session(site) == null) return false
        store.deleteSession(site)
        return true
    }

    /** Where a queued episode's still [n] is kept (FR-9.4). */
    fun stillPath(rowId: Long, n: Int): String = engine.queue.stillFile(rowId, n)

    fun preview(id: String, retention: RetentionSpec): RetentionPreview? = store.fetcher(id)?.let { engine.previewRetention(it, retention) }
}
