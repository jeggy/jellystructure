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
)

sealed class SaveResult {
    data class Saved(val fetcher: Fetcher, val proof: ProofResult) : SaveResult()
    /** [status] is the HTTP status the route answers; [notASeries] marks the drawn *Not a series* state. */
    data class Refused(val status: Int, val reason: String, val notASeries: Boolean = false, val proof: ProofResult? = null) : SaveResult()
}

class AutofetchService(
    private val store: AutofetchStore,
    private val engine: AutofetchEngine,
    private val lanes: SiteLanes,
    tools: MediaTools,
    private val world: AutofetchWorld,
    private val newId: () -> String,
) {
    private val proof = RecipeProof(tools, world)

    suspend fun save(req: SaveRequest): SaveResult {
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
        val lane = lanes.forSite(site)
        val pr = proof.prove(recipe, req.pageOnly, req.tmdbId, lane)
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
            lastRunAt = null, lastListOkAt = world.nowSec(),
        )
        store.insert(f)
        if (req.pageOnly) store.mark(seriesPath, f.id)
        val now = world.nowSec()
        for ((item, se) in pr.numbered) {
            val itemId = item.id?.takeIf { it.isNotBlank() } ?: item.link ?: continue
            store.upsertListed(f.id, itemId, item, se, Numbering.isoDate(item.published), now)
        }
        return SaveResult.Saved(f, pr)
    }

    /** `PATCH /api/autofetch/{id}` — retention, schedule and the paused switch; none of them needs *Re-investigate page* (FR-16.1). */
    fun update(id: String, retention: RetentionSpec?, schedule: String?, enabled: Boolean?): String? {
        val f = store.fetcher(id) ?: return "no such fetcher"
        retention?.let {
            if (it.mode != "all" && it.mode != "newest") return "retention must be all or newest"
            if (it.mode == "newest" && it.keep !in 1..999) return "Keep the newest N takes a number from 1 to 999"
        }
        if (schedule != null && !FetchSchedule.valid(schedule)) return "the schedule must be every6h, daily@HH:MM or weekly@D@HH:MM; no custom schedule below 6 hours"
        store.updateSettings(id, retention ?: f.retention, schedule ?: f.schedule, enabled ?: f.enabled)
        return null
    }

    /** `DELETE /api/autofetch/{id}` — the fetcher, its recipe and its history; never a file (FR-6.8). */
    fun delete(id: String): Boolean {
        if (store.fetcher(id) == null) return false
        store.delete(id)
        return true
    }

    /** `POST /api/autofetch/sites/{site}/resume` — one listing request; back to stopped if that is refused too (FR-2.5). */
    suspend fun resume(site: String): String? {
        val lane = lanes.forSite(site)
        if (!lane.state().stopped) return "that site is not stopped"
        lane.resume()
        val probe = store.fetchers().firstOrNull { it.site == site }?.recipe ?: return null
        val read = ListingFetcher.read(probe.copy(list = probe.list.copy(next = null)), lane)
        return if (read is ListingRead.Blocked && lane.state().stopped) "the site refused again: ${lane.state().stoppedReason}" else null
    }

    fun preview(id: String, retention: RetentionSpec): Int? = store.fetcher(id)?.let { engine.previewRetention(it, retention) }
}
