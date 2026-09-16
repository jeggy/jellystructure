package dev.jellystructure.server.routes

import dev.jellystructure.server.respondCachedBytes
import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.TrackerEntry
import dev.jellystructure.media.JsTag
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaKind
import dev.jellystructure.tv.TaxonomyKey
import dev.jellystructure.resolver.CertificationCatalog
import dev.jellystructure.resolver.CertificationResolver
import dev.jellystructure.torrent.SeedingSnapshot
import dev.jellystructure.torrent.TrackerResolver
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class MetadataEntry(
    val name: String,
    val count: Int,
    val tmdbId: Int? = null,
    val logoPath: String? = null,
    val hasLogo: Boolean = false,
)

@Serializable
data class LogoFetchResult(val ok: Boolean, val cached: Boolean, val detail: String? = null)

@Serializable
data class BatchLogoResult(val fetched: Int, val skipped: Int, val failed: Int)

@Serializable
data class TagsResponse(
    val jsTags: List<JsTagWithCount>,
    val otherTags: List<MetadataEntry>,
)

@Serializable
data class JsTagWithCount(
    val name: String,
    val color: String,
    val description: String,
    val count: Int,
)

@Serializable
data class CreateTagRequest(val name: String, val color: String = "#6b7280", val description: String = "")

@Serializable
data class UpdateTagRequest(val color: String? = null, val description: String? = null)

// Phase 155 — one cascade-resolved certification code, its (best-effort) source-region label, how many
// items resolve to it, and its normalized age (null = unmapped; the client defaults its stepper to 18).
@Serializable
data class AgeRatingRow(val code: String, val system: String?, val itemCount: Int, val age: Int? = null)

@Serializable
data class AgeRatingsResponse(val mapped: List<AgeRatingRow>, val unmapped: List<AgeRatingRow>, val cascadeConfigured: Boolean)

@Serializable
data class SetAgeRatingRequest(val code: String, val age: Int)

@Serializable
data class TrackerWithStats(val name: String, val private: Boolean, val hosts: List<String>, val torrentCount: Int)

@Serializable
data class CreateTrackerRequest(val name: String, val private: Boolean = false, val hosts: List<String> = emptyList())

@Serializable
data class UpdateTrackerRequest(val name: String? = null, val private: Boolean? = null, val hosts: List<String>? = null)

/** Phase 155 — groups the library by cascade-resolved certification code, splits into mapped/unmapped
 *  against [dev.jellystructure.config.MetadataConfig.ageRatingMap], and attaches a best-effort
 *  source-region label per code (the first region seen producing it — informational only, since the
 *  same code can theoretically arrive from more than one cascade region across different items). An
 *  empty cascade means every item resolves to no certification at all — reported via
 *  [AgeRatingsResponse.cascadeConfigured] so the tab can show an explicit empty state instead of a
 *  silently blank table (see the spec's FR-AGE1-2 dependency note). */
private suspend fun computeAgeRatings(store: MediaStore, cfg: AppConfig?): AgeRatingsResponse {
    val cascade = cfg?.metadata?.ageRatingCascade ?: emptyList()
    val map = cfg?.metadata?.ageRatingMap ?: emptyMap()
    if (cascade.isEmpty()) return AgeRatingsResponse(emptyList(), emptyList(), cascadeConfigured = false)

    class Agg { var count = 0; val regions = mutableSetOf<String>() }
    val byCode = mutableMapOf<String, Agg>()
    for (item in store.allItems()) {
        val resolved = CertificationResolver.resolve(cascade, item.certifications) ?: continue
        val agg = byCode.getOrPut(resolved.code) { Agg() }
        agg.count++
        agg.regions += resolved.region
    }
    fun systemLabel(regions: Set<String>): String? =
        regions.firstOrNull()?.let { CertificationCatalog.BY_CODE[it]?.system ?: it }

    val (mappedCodes, unmappedCodes) = byCode.keys.partition { it in map }
    val mapped = mappedCodes.map { code ->
        val agg = byCode.getValue(code)
        AgeRatingRow(code = code, system = systemLabel(agg.regions), itemCount = agg.count, age = map[code])
    }.sortedWith(compareBy({ it.age ?: 0 }, { it.code.lowercase() }))
    val unmapped = unmappedCodes.map { code ->
        val agg = byCode.getValue(code)
        AgeRatingRow(code = code, system = systemLabel(agg.regions), itemCount = agg.count, age = null)
    }.sortedByDescending { it.itemCount }
    return AgeRatingsResponse(mapped, unmapped, cascadeConfigured = true)
}

fun Route.metadataRoutes(store: MediaStore, tagStore: JsTagStore, logoDownloader: LogoDownloader, seedingSnapshot: SeedingSnapshot, configStore: ConfigStore? = null) {
    route("/metadata") {
        // Phase 216 (FR-216-9) — grouped through TaxonomyKey, the same normaliser the viewer-side
        // BrowseService.facets() counts with, so this page and Ravilo's Discover wall state the same
        // number for the same value (acceptance test 7). Was an exact-match groupBy, which counted
        // `HBO Nordic` and `HBO  nordic` as two studios.
        get("/studios") {
            val sort = call.request.queryParameters["sort"] ?: "count"
            val all = store.allItems()
            val counter = TaxonomyKey.Counter()
            val firstByKey = HashMap<String, Pair<Int?, String?>>()
            for (it in all) {
                val s = it.studio?.takeIf { s -> s.isNotBlank() } ?: continue
                counter.add(s)
                firstByKey.getOrPut(TaxonomyKey.key(s)) { it.studioTmdbId to it.studioLogoPath }
            }
            val entries = counter.entries().map { e ->
                val first = firstByKey[TaxonomyKey.key(e.name)]
                MetadataEntry(
                    name = e.name, count = e.count,
                    tmdbId = first?.first, logoPath = first?.second,
                    hasLogo = logoDownloader.hasLogo("studios", e.name),
                )
            }
            val sorted = if (sort == "name") entries.sortedBy { it.name.lowercase() } else entries.sortedByDescending { it.count }
            call.respond(sorted)
        }

        get("/networks") {
            val sort = call.request.queryParameters["sort"] ?: "count"
            val all = store.allItems().filter { it.kind == MediaKind.TV_SHOW }
            val counter = TaxonomyKey.Counter()
            val firstByKey = HashMap<String, Pair<Int?, String?>>()
            for (it in all) {
                val n = it.network?.takeIf { n -> n.isNotBlank() } ?: continue
                counter.add(n)
                firstByKey.getOrPut(TaxonomyKey.key(n)) { it.networkTmdbId to it.networkLogoPath }
            }
            val entries = counter.entries().map { e ->
                val first = firstByKey[TaxonomyKey.key(e.name)]
                MetadataEntry(
                    name = e.name, count = e.count,
                    tmdbId = first?.first, logoPath = first?.second,
                    hasLogo = logoDownloader.hasLogo("networks", e.name),
                )
            }
            val sorted = if (sort == "name") entries.sortedBy { it.name.lowercase() } else entries.sortedByDescending { it.count }
            call.respond(sorted)
        }

        // --- Studio artwork ---
        route("/studios") {
            post("/artwork/batch") {
                val all = store.allItems()
                val studios = all.mapNotNull { it.studio?.takeIf { s -> s.isNotBlank() }?.let { s ->
                    Triple(s, it.studioTmdbId, it.studioLogoPath)
                }}.distinctBy { it.first }
                val result = logoDownloader.batchFetchStudios(studios)
                call.respond(BatchLogoResult(result.fetched, result.skipped, result.failed))
            }
            route("/{name}") {
                get("/artwork") {
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val bytes = logoDownloader.serveLogo("studios", name)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondCachedBytes(bytes, ContentType.Image.PNG)
                }
                post("/artwork") {
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val all = store.allItems()
                    val item = all.firstOrNull { it.studio == name }
                    val cached = logoDownloader.hasLogo("studios", name)
                    if (cached) { call.respond(LogoFetchResult(ok = true, cached = true)); return@post }
                    val ok = logoDownloader.fetchStudioLogo(name, item?.studioTmdbId, item?.studioLogoPath)
                    call.respond(LogoFetchResult(ok = ok, cached = false, detail = if (!ok) "no logo available" else null))
                }
            }
        }

        // --- Network artwork ---
        route("/networks") {
            post("/artwork/batch") {
                val all = store.allItems().filter { it.kind == MediaKind.TV_SHOW }
                val networks = all.mapNotNull { it.network?.takeIf { n -> n.isNotBlank() }?.let { n ->
                    Pair(n, it.networkLogoPath)
                }}.distinctBy { it.first }
                val result = logoDownloader.batchFetchNetworks(networks)
                call.respond(BatchLogoResult(result.fetched, result.skipped, result.failed))
            }
            route("/{name}") {
                get("/artwork") {
                    val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val bytes = logoDownloader.serveLogo("networks", name)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondCachedBytes(bytes, ContentType.Image.PNG)
                }
                post("/artwork") {
                    val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val all = store.allItems().filter { it.kind == MediaKind.TV_SHOW }
                    val item = all.firstOrNull { it.network == name }
                    val cached = logoDownloader.hasLogo("networks", name)
                    if (cached) { call.respond(LogoFetchResult(ok = true, cached = true)); return@post }
                    val logoPath = item?.networkLogoPath
                    if (logoPath.isNullOrBlank()) {
                        call.respond(LogoFetchResult(ok = false, cached = false, detail = "no logo available for this network"))
                        return@post
                    }
                    val ok = logoDownloader.fetchNetworkLogo(name, logoPath)
                    call.respond(LogoFetchResult(ok = ok, cached = false, detail = if (!ok) "download failed" else null))
                }
            }
        }

        get("/genres") {
            val sort = call.request.queryParameters["sort"] ?: "count"
            // Phase 216 (FR-216-9) — same TaxonomyKey grouping as studios/networks above.
            val counter = TaxonomyKey.Counter()
            for (item in store.allItems()) {
                item.genres.distinctBy { TaxonomyKey.key(it) }.forEach { counter.add(it) }
            }
            val entries = counter.entries().map { MetadataEntry(name = it.name, count = it.count) }
            val sorted = if (sort == "name") entries.sortedBy { it.name.lowercase() } else entries.sortedByDescending { it.count }
            call.respond(sorted)
        }

        get("/tags") {
            val sort = call.request.queryParameters["sort"] ?: "count"
            val jsTagNames = tagStore.nameSet()
            val tagCounts = mutableMapOf<String, Int>()
            for (item in store.allItems()) {
                for (tag in item.tags) {
                    if (tag.isNotBlank()) tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
                }
            }
            val jsTags = tagStore.all().map { jsTag ->
                JsTagWithCount(name = jsTag.name, color = jsTag.color, description = jsTag.description, count = tagCounts[jsTag.name] ?: 0)
            }.let { list -> if (sort == "name") list.sortedBy { it.name.lowercase() } else list.sortedByDescending { it.count } }
            val otherTags = tagCounts.filter { it.key !in jsTagNames }
                .map { (name, count) -> MetadataEntry(name = name, count = count) }
                .let { list -> if (sort == "name") list.sortedBy { it.name.lowercase() } else list.sortedByDescending { it.count } }
            call.respond(TagsResponse(jsTags = jsTags, otherTags = otherTags))
        }

        // Phase 98 — tracker registry CRUD at /api/metadata/trackers
        route("/trackers") {
            get {
                val trackers = configStore?.current?.trackers ?: emptyList()
                val snap = runCatching { seedingSnapshot.get() }.getOrNull()
                call.respond(trackers.map { t ->
                    val count = snap?.torrents?.count { tor ->
                        TrackerResolver.hostOf(tor.tracker.takeIf { u -> u.isNotBlank() } ?: "") in t.hosts
                    } ?: 0
                    TrackerWithStats(t.name, t.isPrivate, t.hosts, count)
                }.sortedByDescending { it.torrentCount })
            }
            post {
                val cs = configStore ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
                val req = call.receive<CreateTrackerRequest>()
                if (req.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name required"))
                val config = cs.current
                if (config.trackers.any { it.name == req.name }) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "tracker '${req.name}' already exists"))
                val updated = config.copy(trackers = config.trackers + TrackerEntry(req.name, req.private, req.hosts))
                cs.update(updated)
                call.respond(HttpStatusCode.Created, TrackerWithStats(req.name, req.private, req.hosts, 0))
            }
            get("/unmapped") {
                val snap = seedingSnapshot.get()
                val trackers = configStore?.current?.trackers ?: emptyList()
                val groups = TrackerResolver.detectUnmappedGroups(snap.torrents, trackers)
                call.respond(groups)
            }
            route("/{name}") {
                put {
                    val cs = configStore ?: return@put call.respond(HttpStatusCode.ServiceUnavailable)
                    val name = call.parameters["name"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val req = call.receive<UpdateTrackerRequest>()
                    val config = cs.current
                    val idx = config.trackers.indexOfFirst { it.name == name }
                    if (idx < 0) return@put call.respond(HttpStatusCode.NotFound)
                    val existing = config.trackers[idx]
                    val updated = existing.copy(
                        name = req.name ?: existing.name,
                        isPrivate = req.private ?: existing.isPrivate,
                        hosts = req.hosts ?: existing.hosts,
                    )
                    val newList = config.trackers.toMutableList().also { it[idx] = updated }
                    cs.update(config.copy(trackers = newList))
                    call.respond(TrackerWithStats(updated.name, updated.isPrivate, updated.hosts, 0))
                }
                delete {
                    val cs = configStore ?: return@delete call.respond(HttpStatusCode.ServiceUnavailable)
                    val name = call.parameters["name"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    val config = cs.current
                    if (config.trackers.none { it.name == name }) return@delete call.respond(HttpStatusCode.NotFound)
                    cs.update(config.copy(trackers = config.trackers.filter { it.name != name }))
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        // Phase 155 — cascade-resolved certification -> normalized age 0-18. Keyed on the code
        // CertificationResolver.resolve() already produces (this install's cascade, not per-region raw
        // strings) — see the spec's Backend review addendum for why.
        route("/age-ratings") {
            get {
                call.respond(computeAgeRatings(store, configStore?.current))
            }
            post {
                val cs = configStore ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
                val req = call.receive<SetAgeRatingRequest>()
                if (req.code.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "code required"))
                val age = req.age.coerceIn(0, 18)
                val config = cs.current
                // Bug fix (live report, 2026-08-14) — cs.update()'s write-to-disk result is now checked;
                // a failed persist (the ktoml age_rating_map bug above was exactly this) used to respond
                // success anyway, so the write-through pulse looked fine but nothing was actually saved.
                val ok = cs.update(config.copy(metadata = config.metadata.copy(ageRatingMap = config.metadata.ageRatingMap + (req.code to age))))
                if (ok) call.respond(AgeRatingRow(code = req.code, system = null, itemCount = 0, age = age))
                else call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Couldn't save — check the server log"))
            }
            // FR-AGE1-3 "Suggest mappings" — fills every still-unmapped code from
            // CertificationResolver.AGE_SEED; never overwrites an operator's existing explicit choice.
            post("/suggest") {
                val cs = configStore ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
                val config = cs.current
                val seeded = CertificationResolver.AGE_SEED.filterKeys { it !in config.metadata.ageRatingMap }
                val ok = cs.update(config.copy(metadata = config.metadata.copy(ageRatingMap = config.metadata.ageRatingMap + seeded)))
                if (ok) call.respond(computeAgeRatings(store, cs.current))
                else call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Couldn't save — check the server log"))
            }
        }
    }

    route("/tags") {
        get {
            call.respond(tagStore.all())
        }
        post {
            val req = call.receive<CreateTagRequest>()
            val tag = JsTag(name = req.name.trim(), color = req.color, description = req.description)
            if (tag.name.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name is required"))
            val ok = tagStore.create(tag)
            if (!ok) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "tag '${tag.name}' already exists"))
            call.respond(HttpStatusCode.Created, tag)
        }
        route("/{name}") {
            patch {
                val name = call.parameters["name"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val req = call.receive<UpdateTagRequest>()
                val ok = tagStore.update(name, req.color, req.description)
                if (!ok) return@patch call.respond(HttpStatusCode.NotFound)
                call.respond(tagStore.get(name)!!)
            }
            delete {
                val name = call.parameters["name"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val ok = tagStore.delete(name)
                if (!ok) return@delete call.respond(HttpStatusCode.NotFound)
                // Phase 199 (FR-199-7): strip immediately rather than leave it — once a name is gone
                // from `js_tags`, `preserveJsTags` no longer recognizes it as JS-owned, so the *next*
                // scan/re-pull to touch each item would silently drop it anyway, at a time depending on
                // that item's freshness tier. Stripping here now instead makes the deletion's effect on
                // items honest and immediate rather than a ticking, tier-dependent side effect.
                for (item in store.allItems()) {
                    if (name in item.tags) {
                        store.updateOne(item.copy(tags = item.tags - name), respectJsTags = false)
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
