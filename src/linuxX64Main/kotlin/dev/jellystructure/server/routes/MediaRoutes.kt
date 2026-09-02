package dev.jellystructure.server.routes

import dev.jellystructure.server.respondCachedBytes
import dev.jellystructure.arr.ArrRescanService
import dev.jellystructure.nextRunDelayMs
import dev.jellystructure.nowEpochSec
import dev.jellystructure.runTagged
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.io.FileIo
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.log.WorkerId
import dev.jellystructure.media.ArtworkAsset
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.assetFilePath
import dev.jellystructure.media.clearTmdbMatch
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.ProbeDiagnosis
import dev.jellystructure.media.LogoDownloader
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.MkvpropeditRunner
import dev.jellystructure.media.PipelineDeps
import dev.jellystructure.media.PipelineStepOps
import dev.jellystructure.media.RunTarget
import dev.jellystructure.media.effectivePipeline
import dev.jellystructure.media.runPipeline
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MediaTrailer
import dev.jellystructure.model.NfoFileNode
import dev.jellystructure.model.NfoFileTree
import dev.jellystructure.model.Person
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.resolver.primaryAudioLanguage
import dev.jellystructure.nfo.NfoWriter
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.tmdb.TmdbImage
import dev.jellystructure.tv.RaviloConfigService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.AtomicInt
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import dev.jellystructure.torrent.SeedingSnapshot
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.MatchMode
import dev.jellystructure.shared.tv.anyCondition
import dev.jellystructure.shared.tv.migrateFlatQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class JellyfinLocksResponse(val lockData: Boolean, val lockedFields: List<String>)

@Serializable
private data class PersonSearchResult(
    val tmdbId: Int,
    val name: String,
    val profilePath: String? = null,
    val knownForDepartment: String = "",
)

// --- Phase 47: artwork candidate gallery DTOs ---
@Serializable
private data class ArtworkCandidate(
    val filePath: String,
    val lang: String?,       // null = no-language / textless (surfaced as "xx" in the UI)
    val voteAverage: Double,
    val width: Int,
    val height: Int,
    val onDisk: Boolean,     // R124: the artwork is actually on disk AND (best-effort) this candidate
                             // matches the scanned file_path. Never true when the asset isn't on disk.
)

@Serializable
private data class ArtworkCandidatesResponse(
    val asset: String,
    val onDiskExists: Boolean,
    val resolvedLanguage: String?,
    val candidates: List<ArtworkCandidate>,
)

@Serializable
private data class SaveCandidateRequest(val asset: String = "", val source: String)

/** Phase 154 — per-run step skip from the pre-run dialog (`POST /api/pipeline/run`). Defaulted so a
 *  bodyless call behaves exactly as before this phase. Never persisted; see FR-PIPE1-6. */
@Serializable
private data class PipelineRunRequest(val skipSteps: List<String> = emptyList())

// Wire shape for GET /api/media/{id}/episodes/stills — mirrors the frontend's EpisodeStillStatus.
// Phase 149: episodeNumber disambiguates entries sharing a filename (a multi-episode file) — the
// frontend used to key these by filename alone, collapsing a group's N statuses down to one.
@Serializable
private data class EpisodeStillStatusDto(val filename: String, val stillExists: Boolean, val stillPath: String, val source: String? = null, val episodeNumber: Int? = null, val manual: Boolean = false)

/**
 * Phase 149: resolves the target episode for a still-related route. Several episodes can share
 * [epFilename] (a multi-episode file) — [epNum], when present, disambiguates which one via an EXACT
 * match (never silently falls back to "first match" once the caller has told us which episode it
 * means, since that could misdirect an edit to the wrong episode). Omitted [epNum] (an older client, or
 * the overwhelmingly common non-ambiguous case) falls back to plain filename matching, unchanged from
 * pre-149 behavior.
 */
private fun resolveStillEpisode(item: MediaItem, epFilename: String, epNum: Int?): Episode? =
    if (epNum != null) item.episodes.firstOrNull { it.filename == epFilename && it.episodeNumber == epNum }
    else item.episodes.firstOrNull { it.filename == epFilename }

// Wire shape for the batch fire-and-forget endpoints ("…started", item count).
@Serializable
private data class BatchStartedResponse(val status: String, val total: Int)

// Wire shape for GET /api/media/{id}/seasons — mirrors the frontend's SeasonStatus.
@Serializable
private data class SeasonStatusDto(val season: Int, val posterExists: Boolean, val posterManual: Boolean = false)

private fun mapCandidates(images: List<TmdbImage>, onDiskSource: String?): List<ArtworkCandidate> =
    images.map { img ->
        ArtworkCandidate(
            filePath = img.filePath,
            lang = img.languageCode,
            voteAverage = img.voteAverage,
            width = img.width,
            height = img.height,
            onDisk = onDiskSource != null && img.filePath == onDiskSource,
        )
    }

// Shared lenient parser — reused across handlers so a Json format isn't rebuilt per request
// (kotlinx.serialization advises against per-call `Json { }` creation).
private val lenientJson = Json { ignoreUnknownKeys = true }

fun Route.mediaRoutes(
    store: MediaStore,
    scanner: Scanner,
    artwork: ArtworkDownloader,
    tmdbClient: TmdbClient,
    appScope: CoroutineScope,
    scanTracker: ScanTracker,
    broadcaster: WsBroadcaster,
    jellyfinClient: JellyfinClient,
    configStore: ConfigStore,
    mediaHistory: MediaHistory,
    scanDispatcher: CoroutineDispatcher,
    seedingGuard: SeedingGuard,
    seedingSnapshot: SeedingSnapshot,
    raviloConfigService: RaviloConfigService,
    logoDownloader: LogoDownloader,
    arrRescan: ArrRescanService? = null,
    sonarrEnrich: dev.jellystructure.arr.SonarrEnrichService? = null,
    mediaJobQueue: dev.jellystructure.media.MediaJobQueue,
    imdbClient: dev.jellystructure.imdb.ImdbClient,
    fingerprintService: dev.jellystructure.media.FingerprintService,
    mediaSegmentStore: dev.jellystructure.media.MediaSegmentStore,
    realtimeIngest: dev.jellystructure.media.RealtimeIngestService,
    dirtyItemStore: dev.jellystructure.media.DirtyItemStore,
) {
    // Phase 175 — shared collaborator bundle for every runPipeline() call these routes make.
    val pipelineDeps = PipelineDeps(
        store = store, scanner = scanner, broadcaster = broadcaster, configStore = configStore,
        jellyfinClient = jellyfinClient, scanDispatcher = scanDispatcher, artworkDownloader = artwork,
        arrRescan = arrRescan, sonarrEnrich = sonarrEnrich, imdbClient = imdbClient,
        mediaSegmentStore = mediaSegmentStore, mediaJobQueue = mediaJobQueue,
        realtimeIngest = realtimeIngest, mediaHistory = mediaHistory, dirtyItemStore = dirtyItemStore,
    )
    route("/media") {
        get {
            val kindStr = call.request.queryParameters["kind"]
            val kind = kindStr?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
            val filter = call.request.queryParameters["filter"]
            val search = call.request.queryParameters["search"]?.takeIf { it.length >= 2 }
            val sort = call.request.queryParameters["sort"]
            val pageNum = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()
                ?.coerceIn(1, 100) ?: 20
            val studios = call.request.queryParameters["studios"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val networks = call.request.queryParameters["networks"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val genres = call.request.queryParameters["genres"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val audioLangs = call.request.queryParameters["audioLang"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val trackTitle = call.request.queryParameters["trackTitle"]?.takeIf { it.isNotBlank() }
            val audioCodec = call.request.queryParameters["audioCodec"]?.takeIf { it.isNotBlank() }
            val untaggedAudio = call.request.queryParameters["untaggedAudio"] == "true"
            val tags = call.request.queryParameters["tags"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            // R32 hero-item facet: filter by membership in a viewer's hero carousel.
            val heroMode = call.request.queryParameters["heroItem"]?.takeIf { it == "featured" || it == "not_featured" }
            val viewer = call.request.queryParameters["viewer"]?.takeIf { it.isNotBlank() }
            // Phase 140: query= (JSON tree) is the primary carrier; the legacy R74 conditions=/match=
            // pair is still accepted and migrated, for old deep-linked API callers.
            val queryJson = call.request.queryParameters["query"]
            val conditionsJson = call.request.queryParameters["conditions"]
            val match = call.request.queryParameters["match"]?.let { runCatching { MatchMode.valueOf(it) }.getOrNull() } ?: MatchMode.ALL
            val query: ConditionGroup? = when {
                !queryJson.isNullOrBlank() -> runCatching { lenientJson.decodeFromString(ConditionGroup.serializer(), queryJson) }.getOrNull()
                !conditionsJson.isNullOrBlank() -> {
                    val conditions = runCatching { lenientJson.decodeFromString(ListSerializer(Condition.serializer()), conditionsJson) }.getOrElse { emptyList() }
                    if (conditions.isEmpty()) null else migrateFlatQuery(match, conditions)
                }
                else -> null
            }
            val heroIds = if ((heroMode != null || query?.anyCondition { it.facet == "hero_item" } == true) && viewer != null)
                raviloConfigService.getConfig(viewer).heroes.map { it.itemId }.toSet() else emptySet()
            // Phase 98 — tracker filter: "any" = seeded anywhere, else a named tracker from the registry.
            val trackerFilter = call.request.queryParameters["tracker"]?.takeIf { it.isNotBlank() }
            val seededIds = if (trackerFilter != null) {
                seedingSnapshot.seededItemIds(trackerFilter, store.allItems(), configStore.current)
            } else null
            val result = store.list(kind, filter, search, sort, pageNum, pageSize, studios, networks, genres, audioLangs, trackTitle, audioCodec, untaggedAudio, tags, heroIds, heroMode, query, seededIds, excludeMissing = viewer != null)
            // Phase 89: strip heavy fields not needed for grid cards (cast/crew/tracks/titlesByLang/episodes)
            // to reduce response size from ~61KB/item mean to ~1KB/item.
            val stripped = result.copy(items = result.items.map { it.copy(
                episodes = emptyList(), cast = emptyList(), crew = emptyList(),
                tracks = emptyList(), titlesByLang = emptyMap(),
            )})
            call.respond(stripped)
        }

        // POST /api/media/batch-count — evaluate N condition stacks in one library scan.
        // Used by RaviloConfig channel count badges: N channels → 1 call instead of N.
        post("/batch-count") {
            // Phase 140 — query (the blocks tree) is the primary carrier; match/conditions (R74) still
            // accepted and migrated per-request, for old callers.
            @Serializable data class BatchCountReq(
                val index: Int,
                val match: String = "ALL",
                val conditions: List<Condition> = emptyList(),
                val query: ConditionGroup? = null,
            )
            @Serializable data class BatchCountRes(val index: Int, val total: Int)
            val requests = call.receive<List<BatchCountReq>>()
            val trees = requests.map { req ->
                req.query ?: migrateFlatQuery(runCatching { MatchMode.valueOf(req.match) }.getOrElse { MatchMode.ALL }, req.conditions)
            }
            val counts = store.countBatch(trees)
            call.respond(requests.mapIndexed { i, req -> BatchCountRes(req.index, counts[i]) })
        }

        get("/meta-facets") {
            @Serializable data class FacetItem(val value: String, val count: Int, val color: String? = null, val label: String? = null)
            @Serializable data class MetaFacetsResponse(
                val studios: List<FacetItem>,
                val networks: List<FacetItem>,
                val genres: List<FacetItem>,
                val tags: List<FacetItem>,
                val ageRatings: List<FacetItem>,
                val castCrew: List<FacetItem> = emptyList(),
                val metadataLanguages: List<FacetItem> = emptyList(),
            )
            val f = store.metaFacets()
            call.respond(MetaFacetsResponse(
                studios  = f.studios.map  { FacetItem(it.value, it.count) },
                networks = f.networks.map { FacetItem(it.value, it.count) },
                genres   = f.genres.map   { FacetItem(it.value, it.count) },
                tags     = f.tags.map     { FacetItem(it.value, it.count, it.color) },
                ageRatings = f.ageRatings.map { FacetItem(it.value, it.count) },
                castCrew = f.castCrew.map { FacetItem(it.value, it.count, label = it.label) },
                metadataLanguages = f.metadataLanguages.map { FacetItem(it.value, it.count) },
            ))
        }

        get("/track-facets") {
            @Serializable data class FacetItem(val value: String, val count: Int)
            @Serializable data class FacetsResponse(
                val audioLanguages: List<FacetItem>,
                val audioCodecs: List<FacetItem>,
                val trackTitles: List<FacetItem>,
            )
            val f = store.trackFacets()
            call.respond(FacetsResponse(
                audioLanguages = f.audioLanguages.map { FacetItem(it.value, it.count) },
                audioCodecs = f.audioCodecs.map { FacetItem(it.value, it.count) },
                trackTitles = f.trackTitles.map { FacetItem(it.value, it.count) },
            ))
        }

        // R127: facet value counts narrowed to a condition set (e.g. a channel's filter) — meta + track in
        // one pass. Used by the Workbench in a channel content-row scope; the global GET facets above stay
        // for the library-wide workbench.
        post("/facets") {
            // Phase 140 — query (the blocks tree) is the primary carrier; match/conditions (R74) still
            // accepted and migrated, for old callers.
            @Serializable data class FacetReq(val match: String = "ALL", val conditions: List<Condition> = emptyList(), val query: ConditionGroup? = null)
            @Serializable data class FItem(val value: String, val count: Int, val color: String? = null, val label: String? = null)
            @Serializable data class NarrowedFacetsResponse(
                val studios: List<FItem>, val networks: List<FItem>, val genres: List<FItem>, val tags: List<FItem>,
                val ageRatings: List<FItem>,
                val audioLanguages: List<FItem>, val audioCodecs: List<FItem>, val trackTitles: List<FItem>,
                val castCrew: List<FItem> = emptyList(),
                val metadataLanguages: List<FItem> = emptyList(),
            )
            val req = call.receive<FacetReq>()
            val tree = req.query ?: migrateFlatQuery(runCatching { MatchMode.valueOf(req.match) }.getOrElse { MatchMode.ALL }, req.conditions)
            val (meta, track) = store.facetsNarrowed(tree)
            call.respond(NarrowedFacetsResponse(
                studios  = meta.studios.map  { FItem(it.value, it.count, it.color) },
                networks = meta.networks.map { FItem(it.value, it.count, it.color) },
                genres   = meta.genres.map   { FItem(it.value, it.count, it.color) },
                tags     = meta.tags.map     { FItem(it.value, it.count, it.color) },
                ageRatings = meta.ageRatings.map { FItem(it.value, it.count, it.color) },
                audioLanguages = track.audioLanguages.map { FItem(it.value, it.count) },
                audioCodecs    = track.audioCodecs.map    { FItem(it.value, it.count) },
                trackTitles    = track.trackTitles.map    { FItem(it.value, it.count) },
                castCrew = meta.castCrew.map { FItem(it.value, it.count, label = it.label) },
                metadataLanguages = meta.metadataLanguages.map { FItem(it.value, it.count) },
            ))
        }

        delete("/all") {
            if (scanTracker.status().running) {
                return@delete call.respond(HttpStatusCode.Conflict, mapOf("error" to "A scan is currently running — stop it before clearing data."))
            }
            store.update(emptyList())
            scanTracker.reset()
            Logger.info("All scanned data cleared by user request", "system")
            call.respond(HttpStatusCode.NoContent)
        }

        route("/{id}") {
            get {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(item)
            }

            // DELETE /api/media/{id} — permanently remove an item that's no longer in Jellyfin (the
            // Phase 95 "missing_from_source" triage's "review & remove if intended" action). Refuses
            // (409) if the item is still present in Jellyfin — a deliberate safety valve, not a
            // general-purpose delete route.
            delete {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
                when (store.deleteItem(id)) {
                    true -> {
                        Logger.info("Removed '${item.title}' (${item.id}) — no longer in Jellyfin", "system")
                        call.respond(HttpStatusCode.NoContent)
                    }
                    false -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Still present in Jellyfin — can't remove."))
                    null -> call.respond(HttpStatusCode.NotFound)
                }
            }

            get("/history") {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                call.respond(mediaHistory.forItem(id))
            }

            // POST /api/media/{id}/history/{entryId}/revert — restore state captured in before_snapshot
            post("/history/{entryId}/revert") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val entryId = call.parameters["entryId"]?.toLongOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
                val entry = mediaHistory.findById(entryId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                if (!entry.revertable || entry.beforeSnapshot.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "entry not revertable"))
                    return@post
                }
                val json = lenientJson
                val reverted: MediaItem = when (entry.action) {
                    "set_tmdb_id" -> {
                        @Serializable data class TmdbSnap(val tmdbId: Int? = null)
                        val snap = runCatching { json.decodeFromString<TmdbSnap>(entry.beforeSnapshot) }.getOrNull()
                            ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "corrupt snapshot"))
                        item.copy(tmdbId = snap.tmdbId)
                    }
                    "metadata_edit" -> {
                        @Serializable data class MetaSnap(val title: String, val overview: String? = null, val year: Int? = null, val originalTitle: String? = null, val director: String? = null, val studio: String? = null, val network: String? = null, val tags: List<String> = emptyList(), val genres: List<String> = emptyList())
                        val snap = runCatching { json.decodeFromString<MetaSnap>(entry.beforeSnapshot) }.getOrNull()
                            ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "corrupt snapshot"))
                        item.copy(title = snap.title, overview = snap.overview, year = snap.year, originalTitle = snap.originalTitle, director = snap.director, studio = snap.studio, network = snap.network, tags = snap.tags, genres = snap.genres.ifEmpty { item.genres })
                    }
                    "language_override" -> {
                        @Serializable data class LangSnap(val language: String? = null)
                        val snap = runCatching { json.decodeFromString<LangSnap>(entry.beforeSnapshot) }.getOrNull()
                            ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "corrupt snapshot"))
                        item.copy(resolvedLanguage = snap.language?.ifBlank { null })
                    }
                    // Phase 184 (FR-184-8) — unlike tmdb_match_clear's revert (which restores a named
                    // field set), this snapshot is the FULL pre-change item, episodes included: a
                    // metadataLanguage change re-pulls title/overview/artwork/genres/cast/… (and, for a
                    // series, every episode's own title/overview/still) through the ordinary rescan
                    // path, so undoing it needs the whole prior state back, not just the field that
                    // triggered it. This is also what "restores all three" (the language, the NFO, the
                    // artwork) means in practice — the snapshot already reflects last write's outcome.
                    "metadata_language_set" -> {
                        runCatching { json.decodeFromString(MediaItem.serializer(), entry.beforeSnapshot) }.getOrNull()
                            ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "corrupt snapshot"))
                    }
                    // Phase 174 (FR-174-5): restore ONLY the fields `clearTmdbMatch` owns, out of a
                    // whole-item snapshot — a wholesale item restore would also resurrect unrelated
                    // fields edited since the clear. The deleted poster/backdrop FILES aren't restored;
                    // posterPath/backdropPath are TMDB file_paths, so the next fetch_artwork
                    // re-downloads them from the paths restored here.
                    "tmdb_match_clear" -> {
                        val snap = runCatching { json.decodeFromString(MediaItem.serializer(), entry.beforeSnapshot) }.getOrNull()
                            ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "corrupt snapshot"))
                        item.copy(
                            tmdbId = snap.tmdbId,
                            posterPath = snap.posterPath,
                            backdropPath = snap.backdropPath,
                            overview = snap.overview,
                            genres = snap.genres,
                            tmdbGenres = snap.tmdbGenres,
                            studio = snap.studio,
                            studioTmdbId = snap.studioTmdbId,
                            studioLogoPath = snap.studioLogoPath,
                            secondaryStudios = snap.secondaryStudios,
                            originalTitle = snap.originalTitle,
                            originalLanguage = snap.originalLanguage,
                            cast = snap.cast,
                            crew = snap.crew,
                            imdbId = snap.imdbId,
                            runtime = snap.runtime,
                            certifications = snap.certifications,
                            trailer = snap.trailer,
                            imdbRating = snap.imdbRating,
                            lockedArtwork = snap.lockedArtwork,
                            tmdbMatchLocked = false,
                        )
                    }
                    else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown action type"))
                }
                // respectTmdbMatchLock = false: reverting a `tmdb_match_clear` is an explicit operator
                // decision to take the match back, so the guard must not re-strip what it just restored.
                // respectMetadataLanguageLock = false: same reasoning for a `metadata_language_set`
                // revert — the guard would otherwise re-force the currently-stored (about-to-be-undone)
                // language straight back over whatever the snapshot restored.
                store.updateOne(reverted, respectTmdbMatchLock = false, respectMetadataLanguageLock = false)
                mediaHistory.record(id, "revert", "reverted entry $entryId (${entry.action})")
                call.respond(reverted)
            }

            route("/nfo") {
                get {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val raw = NfoWriter.readRaw(item)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondText(raw, ContentType.Text.Xml)
                }

                // GET /api/media/{id}/nfo/files — the tree of NFO files this item could have, each with a
                // server-built read URL. Reuses the loaded item + episode list (one stat per file).
                get("/files") {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val nodes = if (item.kind == MediaKind.TV_SHOW) {
                        buildList {
                            add(NfoFileNode("tvshow.nfo", "/api/media/$id/nfo", NfoWriter.exists(item), NfoWriter.nfoPath(item)))
                            item.episodes
                                .sortedWith(compareBy({ it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }))
                                .forEach { ep ->
                                    add(NfoFileNode(
                                        label = episodeNfoLabel(ep),
                                        readUrl = "/api/media/$id/episodes/${ep.filename.encodeURLPathPart()}/nfo",
                                        exists = NfoWriter.episodeNfoExists(ep),
                                        path = NfoWriter.episodeNfoPath(ep),
                                        season = ep.seasonNumber,
                                        episode = ep.episodeNumber,
                                    ))
                                }
                        }
                    } else {
                        listOf(NfoFileNode("movie.nfo", "/api/media/$id/nfo", NfoWriter.exists(item), NfoWriter.nfoPath(item)))
                    }
                    call.respond(NfoFileTree(item.kind, nodes))
                }

                post {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    NfoWriter.writeTracked(item, configStore.current.apiKeys.jellyfinUrl, configStore.current.metadata.ageRatingCascade)
                        .onSuccess { result ->
                            mediaHistory.record(id, "nfo_write", result.path)
                            store.updateOne(item.copy(nfoWrittenAt = result.writtenAt, nfoHash = result.hash))
                            // For TV shows, also write episodedetails.nfo for each episode (Phase 76: pass main cast)
                            if (item.kind == MediaKind.TV_SHOW) {
                                var epWritten = 0
                                for ((group, result) in NfoWriter.writeEpisodeNfos(item.episodes, item.cast)) {
                                    result.onSuccess { epWritten += group.size }
                                        .onFailure { Logger.warn("Episode NFO write failed for ${group.first().filename}: ${it.message}") }
                                }
                                if (epWritten > 0) Logger.info("Wrote $epWritten episode NFO(s) for '$id'")
                            }
                            // Phase 153 — plan.md documents this route as "Write NFO … trigger Jellyfin
                            // refresh"; the trigger was missing, so a manual write never actually got
                            // Jellyfin to re-read it until some other action (e.g. Sync) happened to.
                            val cfg = configStore.current
                            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)
                            }
                            call.respond(mapOf("path" to result.path))
                        }
                        .onFailure { e ->
                            Logger.error("NFO write failed for $id: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "write failed")))
                        }
                }

                get("/writable") {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val dir = when (item.kind) {
                        MediaKind.MOVIE, MediaKind.MUSIC_VIDEO -> item.path.substringBeforeLast('/')
                        MediaKind.TV_SHOW -> item.path
                    }
                    val testFile = "$dir/.jellystructure-write-test.tmp"
                    @Serializable data class WritableResult(val writable: Boolean, val path: String, val error: String? = null)
                    val error = runCatching {
                        // Phase 129 (FR-OPS1 §C) — use{} so a mid-write throw still closes the sink.
                        SystemFileSystem.sink(Path(testFile)).buffered().use { }
                        runCatching { SystemFileSystem.delete(Path(testFile)) }
                        null as String?
                    }.getOrElse { it.message ?: "write failed" }
                    call.respond(WritableResult(writable = error == null, path = dir, error = error))
                }
            }

            route("/artwork") {
                get {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(artwork.check(item))
                }

                post {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val status = artwork.fetch(item)   // R125: fetch() now also downloads missing episode stills
                    // Bug fix: fetch() may just have created episode stills (TMDB or a screengrab fallback —
                    // either is a real, valid still); Episode.hasStill is a persisted snapshot, so it must be
                    // re-stamped + saved now, or triage/Library/Dashboard keep reporting these episodes
                    // "missing" indefinitely (stampHasStill no-ops for movies).
                    store.updateOne(artwork.stampHasStill(item))
                    if (status.posterExists || status.fanartExists) {
                        mediaHistory.record(id, "artwork_fetch", "poster=${status.posterExists} fanart=${status.fanartExists}")
                        val cfg = configStore.current
                        if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                            jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                        }
                    }
                    call.respond(status)
                }

                // POST /api/media/{id}/artwork/upload — upload poster.jpg or fanart.jpg from client
                post("/upload") {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)

                    val multipart = call.receiveMultipart()
                    var type = ""
                    var fileBytes: ByteArray? = null

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> if (part.name == "type") type = part.value
                            is PartData.FileItem -> if (part.name == "file") {
                                fileBytes = part.provider().readRemaining().readByteArray()
                            }
                            else -> {}
                        }
                        part.release()
                    }

                    val filename = when (type) {
                        "poster" -> "poster.jpg"
                        "fanart", "backdrop" -> "fanart.jpg"
                        "logo", "clearlogo" -> "clearlogo.png"
                        else -> ""
                    }
                    if (filename.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "type must be poster, backdrop, or clearlogo"))
                        return@post
                    }
                    val bytes = fileBytes
                    if (bytes == null || bytes.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file data received"))
                        return@post
                    }

                    // Phase 168 follow-up fix: a music video shares its folder with siblings, so it
                    // needs the same per-basename convention as everything else in ArtworkDownloader —
                    // see assetFilePath's doc comment.
                    val destPath = assetFilePath(item, filename)
                    val tmpPath = "$destPath.tmp"
                    // Phase 129 (FR-OPS1 §C) — use{} so a mid-write throw still closes the sink.
                    SystemFileSystem.sink(Path(tmpPath)).buffered().use { sink ->
                        sink.write(bytes, 0, bytes.size)
                        sink.flush()
                    }
                    @OptIn(ExperimentalForeignApi::class)
                    platform.posix.rename(tmpPath, destPath)
                    Logger.info("Artwork uploaded: $destPath (${bytes.size} bytes)")

                    // Phase 133: mark poster/backdrop as manually chosen (survives the next TMDB sync)
                    // and point the admin at the file we just wrote — an upload has no TMDB file_path,
                    // so posterPath/backdropPath would otherwise stay stale and the upload would be
                    // invisible in the admin (Ravilo already renders on-disk artwork and would show it).
                    val asset = when (type) {
                        "poster" -> ArtworkAsset.POSTER
                        "fanart", "backdrop" -> ArtworkAsset.BACKDROP
                        "logo", "clearlogo" -> ArtworkAsset.CLEARLOGO
                        else -> null
                    }
                    // Phase 151: lock the FILE too, for every uploaded asset (clearlogo included — it has
                    // no MediaItem field to lock, so the on-disk marker is its only protection).
                    if (asset != null) artwork.markAssetManual(item, asset)
                    val updated = when (asset) {
                        ArtworkAsset.POSTER -> item.copy(posterPath = "/tv/image/${item.id}/poster", lockedArtwork = (item.lockedArtwork + asset).distinct())
                        ArtworkAsset.BACKDROP -> item.copy(backdropPath = "/tv/image/${item.id}/backdrop", lockedArtwork = (item.lockedArtwork + asset).distinct())
                        ArtworkAsset.CLEARLOGO -> item.copy(lockedArtwork = (item.lockedArtwork + asset).distinct())
                        else -> item
                    }
                    // respectArtworkLock = false: this IS the explicit operator action that sets the lock,
                    // so it must be allowed to change the locked value (Phase 151).
                    if (updated !== item) store.updateOne(updated, respectArtworkLock = false)

                    val cfg = configStore.current
                    if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                        jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                    }

                    call.respond(artwork.check(updated))
                }

                // POST /api/media/{id}/artwork/{asset}/clear — Phase 174 (FR-174-4): remove an on-disk
                // asset entirely (file + its .manual/.src sidecars). No removal path existed before this
                // — reported live: a wrong TMDB match downloaded a backdrop with no way to get rid of it.
                post("/{asset}/clear") {
                    val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val assetName = call.parameters["asset"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    // Validate like /upload does, rather than letting an unknown name fall through
                    // clearAsset's null assetPath and report success for a no-op.
                    if (!ArtworkAsset.isLockable(assetName)) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "asset must be poster, backdrop, or clearlogo"))
                        return@post
                    }
                    val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val removed = artwork.clearAsset(item, assetName)
                    // Phase 151 interaction: releasing the lock is not optional. `preserveLockedArtwork`
                    // restores the STORED posterPath/backdropPath for every locked asset on each
                    // scan-derived write — leave the lock in place and the now-null path is pinned null
                    // forever, so fetch_artwork can re-download the file but nothing can ever point at
                    // it again (and the UI keeps badging a lock on an image that no longer exists).
                    val unlocked = item.lockedArtwork.filterNot { it == assetName }
                    val updated = when (assetName) {
                        ArtworkAsset.POSTER -> item.copy(posterPath = null, lockedArtwork = unlocked)
                        ArtworkAsset.BACKDROP -> item.copy(backdropPath = null, lockedArtwork = unlocked)
                        else -> item.copy(lockedArtwork = unlocked)  // clearlogo has no MediaItem field
                    }
                    store.updateOne(updated, respectArtworkLock = false)
                    mediaHistory.record(id, "artwork_clear", "asset=$assetName${if (removed) "" else " (nothing on disk)"}")
                    // Same reason /upload refreshes: Jellyfin otherwise keeps serving its cached copy of
                    // an image that is no longer on disk.
                    val cfg = configStore.current
                    if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                        jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                    }
                    call.respond(artwork.check(updated))
                }

                // GET /api/media/{id}/artwork/candidates?asset=poster|backdrop|clearlogo
                // — full TMDB candidate list (every language incl. textless) for the gallery.
                get("/candidates") {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val asset = call.request.queryParameters["asset"] ?: "poster"
                    val item = store.resolve(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val status = artwork.check(item)
                    val onDiskExists = when (asset) {
                        "poster" -> status.posterExists
                        "backdrop" -> status.fanartExists
                        "clearlogo" -> status.logoExists
                        else -> false
                    }
                    val images = item.tmdbId?.let { tid ->
                        if (item.kind == MediaKind.MOVIE) tmdbClient.getMovieImages(tid) else tmdbClient.getTvImages(tid)
                    }
                    val list = when (asset) {
                        "poster" -> images?.posters
                        "backdrop" -> images?.backdrops
                        "clearlogo" -> images?.logos
                        else -> null
                    } ?: emptyList()
                    val onDiskSource = when (asset) {
                        "poster" -> item.posterPath
                        "backdrop" -> item.backdropPath
                        "clearlogo" -> artwork.readAssetSrc(item, "clearlogo")
                        else -> null
                    }
                    // R124: only badge a candidate "ON DISK" when the asset is genuinely on disk — otherwise
                    // a scanned-but-never-downloaded poster shows "ON DISK" while the rail says "missing".
                    call.respond(ArtworkCandidatesResponse(asset, onDiskExists, item.resolvedLanguage, mapCandidates(list, onDiskSource.takeIf { onDiskExists })))
                }

                // POST /api/media/{id}/artwork/candidates/save  { asset, source }
                // — source is a TMDB file_path ("/abc.jpg") or a full http(s) URL.
                post("/candidates/save") {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val req = call.receive<SaveCandidateRequest>()
                    if (req.source.isBlank() || artwork.assetPath(item, req.asset) == null) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid asset or source"))
                    }
                    val ok = artwork.saveAsset(item, req.asset, req.source)
                    if (!ok) return@post call.respond(HttpStatusCode.BadGateway, mapOf("error" to "download failed"))
                    // Persist which TMDB image is now on disk. The gallery marks a candidate "on disk"
                    // by comparing its TMDB file_path against item.posterPath/backdropPath, so without
                    // this the old image stays flagged and the new one can never take over. Only TMDB
                    // paths ("/x.jpg") map to those fields; a custom http(s) URL is written to disk but
                    // isn't a TMDB path, so leave the field untouched (it's rendered via the TMDB CDN).
                    //
                    // Phase 176: this sidecar is also what lets fetch()/isArtworkIncomplete() detect a
                    // poster/backdrop left over from a superseded match — previously only clearlogo wrote
                    // it here, so an explicit poster/backdrop pick had no provenance to compare against
                    // either. A non-TMDB source still gets a sentinel, so a later automatic re-match
                    // doesn't mistake this operator pick's `.src`-less state for "safe to leave alone"
                    // forever — though `saveAsset`'s `.manual` marker (below) already protects the file
                    // itself regardless.
                    artwork.writeAssetSrc(item, req.asset, if (req.source.startsWith("/")) req.source else "upload")
                    var updated = if (req.source.startsWith("/")) when (req.asset) {
                        "poster" -> item.copy(posterPath = req.source)
                        "backdrop" -> item.copy(backdropPath = req.source)
                        else -> item
                    } else item
                    // Phase 133/151: an explicit pick locks the asset so it survives the next TMDB
                    // sync/re-pull — the metadata via lockedArtwork, the file via the `.manual` marker
                    // written by saveAsset above.
                    if (ArtworkAsset.isLockable(req.asset)) {
                        updated = updated.copy(lockedArtwork = (updated.lockedArtwork + req.asset).distinct())
                    }
                    // respectArtworkLock = false — see the upload route: an explicit re-pick must win.
                    if (updated !== item) store.updateOne(updated, respectArtworkLock = false)
                    mediaHistory.record(id, "artwork_save", "asset=${req.asset}")
                    val cfg = configStore.current
                    if (!updated.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                        jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, updated.jellyfinId)
                    }
                    call.respond(artwork.check(updated))
                }
            }
        }

        // Season-poster sub-routes (Phase 47) — series only
        route("/{id}/seasons") {
            // GET /api/media/{id}/seasons — distinct seasons + poster-on-disk status
            get {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
                val seasons = item.episodes.mapNotNull { it.seasonNumber }.distinct().sorted()
                call.respond(seasons.map { SeasonStatusDto(it, artwork.checkSeasonPoster(item, it), artwork.isSeasonPosterManual(item, it)) })
            }

            // GET /api/media/{id}/seasons/{season}/poster/candidates
            get("/{season}/poster/candidates") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val season = call.parameters["season"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
                val images = item.tmdbId?.let { tmdbClient.getSeasonImages(it, season) }
                call.respond(ArtworkCandidatesResponse("poster", artwork.checkSeasonPoster(item, season), item.resolvedLanguage, mapCandidates(images?.posters ?: emptyList(), null)))
            }

            // POST /api/media/{id}/seasons/{season}/poster/save  { source }
            post("/{season}/poster/save") {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val season = call.parameters["season"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
                val req = call.receive<SaveCandidateRequest>()
                if (req.source.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "source required"))
                val ok = artwork.saveSeasonPoster(item, season, req.source)
                if (!ok) return@post call.respond(HttpStatusCode.BadGateway, mapOf("error" to "download failed"))
                mediaHistory.record(id, "season_poster_save", "season=$season")
                val cfg = configStore.current
                if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                    jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                }
                call.respond(mapOf("ok" to true))
            }
        }

        // Episode sub-routes — all scoped under /media/{id}/episodes
        route("/{id}/episodes") {
            // GET /api/media/{id}/episodes/stills — check still status for all episodes
            get("/stills") {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val statuses = item.episodes.map { ep ->
                    val status = artwork.checkEpisodeStill(ep)
                    EpisodeStillStatusDto(ep.filename, status.stillExists, status.stillPath, status.source, ep.episodeNumber, status.manual)
                }
                call.respond(statuses)
            }

            // POST /api/media/{id}/episodes/stills — fetch all missing episode stills from TMDB
            post("/stills") {
                val id = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                var fetched = 0
                for (ep in item.episodes) {
                    if (ep.stillPath != null) {
                        val result = artwork.fetchEpisodeStill(ep)
                        if (result.stillExists) fetched++
                    }
                }
                // Bug fix: fetchEpisodeStill() may just have created stills on disk (TMDB or a screengrab
                // fallback — either is a real, valid still); Episode.hasStill is a persisted snapshot, so
                // it must be re-stamped + saved now, or triage/Library/Dashboard keep reporting these
                // episodes "missing" indefinitely.
                if (fetched > 0) store.updateOne(artwork.stampHasStill(store.get(id) ?: item))
                mediaHistory.record(id, "episode_stills_fetch", "fetched=$fetched")
                call.respond(mapOf("fetched" to fetched))
            }

            // POST /api/media/{id}/episodes/nfo — write episodedetails.nfo for all episodes
            post("/nfo") {
                val id = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                var written = 0
                var failed = 0
                for ((group, result) in NfoWriter.writeEpisodeNfos(item.episodes, item.cast)) {
                    result.onSuccess { written += group.size }
                        .onFailure { failed += group.size }
                }
                mediaHistory.record(id, "episode_nfo_write", "written=$written failed=$failed")
                call.respond(mapOf("written" to written, "failed" to failed))
            }

            // POST /api/media/{id}/episodes/{epFilename}/still/upload — upload episode still
            post("/{epFilename}/still/upload") {
                val id = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val epFilename = call.parameters["epFilename"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val epNum = call.request.queryParameters["ep"]?.toIntOrNull()
                val ep = resolveStillEpisode(item, epFilename, epNum)
                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))

                val multipart = call.receiveMultipart()
                var fileBytes: ByteArray? = null
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem && part.name == "file") {
                        fileBytes = part.provider().readRemaining().readByteArray()
                    }
                    part.release()
                }
                val bytes = fileBytes
                if (bytes == null || bytes.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file data received"))
                    return@post
                }

                val stillStatus = artwork.checkEpisodeStill(ep)
                val destPath = stillStatus.stillPath
                val tmpPath = "$destPath.tmp"
                // Phase 129 (FR-OPS1 §C) — use{} so a mid-write throw still closes the sink.
                SystemFileSystem.sink(Path(tmpPath)).buffered().use { sink ->
                    sink.write(bytes, 0, bytes.size)
                    sink.flush()
                }
                @OptIn(ExperimentalForeignApi::class)
                platform.posix.rename(tmpPath, destPath)
                Logger.info("Episode still uploaded: $destPath (${bytes.size} bytes)")

                val cfg = configStore.current
                if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                    jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                }
                call.respond(mapOf("path" to destPath))
            }

            // Episode track routes — {epFilename} identifies the episode by filename
            route("/{epFilename}") {
                // GET /api/media/{id}/episodes/{epFilename}/nfo — exact on-disk episodedetails.nfo bytes.
                // The episode is server-resolved by filename; an unknown/`..` name matches none ⇒ 404.
                get("/nfo") {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val ep = item.episodes.firstOrNull { it.filename == epFilename }
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val raw = NfoWriter.readRawEpisode(ep)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondText(raw, ContentType.Text.Xml)
                }

                // GET /api/media/{id}/episodes/{epFilename}/still/candidates — TMDB still
                // candidates for one episode (Phase 47). Episodes resolve their own language.
                get("/still/candidates") {
                    val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val epNum = call.request.queryParameters["ep"]?.toIntOrNull()
                    val ep = resolveStillEpisode(item, epFilename, epNum)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val tid = item.tmdbId
                    val s = ep.seasonNumber
                    val e = ep.episodeNumber
                    val images = if (tid != null && s != null && e != null)
                        tmdbClient.getEpisodeImages(tid, s, e) else null
                    val onDisk = artwork.checkEpisodeStill(ep).stillExists
                    // R124: only badge a still candidate "ON DISK" when the still is genuinely on disk —
                    // matching ep.stillPath (TMDB metadata) alone falsely badged never-downloaded stills.
                    call.respond(ArtworkCandidatesResponse("still", onDisk, ep.resolvedLanguage, mapCandidates(images?.stills ?: emptyList(), ep.stillPath.takeIf { onDisk })))
                }

                // R131: serve the current on-disk still (the screen-grab / TMDB still) for the picker preview.
                get("/still/file") {
                    val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val epNum = call.request.queryParameters["ep"]?.toIntOrNull()
                    val ep = resolveStillEpisode(item, epFilename, epNum) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val st = artwork.checkEpisodeStill(ep)
                    if (!st.stillExists) return@get call.respond(HttpStatusCode.NotFound)
                    val bytes = runCatching { FileIo.readBytes(Path(st.stillPath)) }.getOrNull()
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    // Short max-age (not the usual 86400s) — this preview can be regenerated in place by
                    // the admin; the content-hash ETag alone already busts a stale cache correctly, but a
                    // short max-age also avoids a picker showing a *just*-regenerated still as "same" for
                    // a full day on a client that skips revalidation.
                    call.respondCachedBytes(bytes, ContentType.Image.JPEG, maxAgeSeconds = 60)
                }

                // R131: generate / regenerate a screen-grab still from the episode's video frame (lowest priority).
                post("/still/screengrab") {
                    val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val epNum = call.request.queryParameters["ep"]?.toIntOrNull()
                    val ep = resolveStillEpisode(item, epFilename, epNum) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val st = artwork.screengrabEpisodeStill(ep)
                    if (!st.stillExists) return@post call.respond(HttpStatusCode.BadGateway, mapOf("error" to "frame extraction failed"))
                    // Bug fix: a screengrab is a real, valid still — Episode.hasStill is a persisted
                    // snapshot, so it must be re-stamped + saved now, or triage/Library/Dashboard keep
                    // reporting this episode "missing" indefinitely.
                    store.updateOne(artwork.stampHasStill(item))
                    mediaHistory.record(id, "still_screengrab", "ep=$epFilename")
                    val cfg = configStore.current
                    if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                        jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                    }
                    call.respond(EpisodeStillStatusDto(ep.filename, st.stillExists, st.stillPath, st.source, ep.episodeNumber, st.manual))
                }

                // POST /api/media/{id}/episodes/{epFilename}/still/save  { source }
                post("/still/save") {
                    val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val epNum = call.request.queryParameters["ep"]?.toIntOrNull()
                    val ep = resolveStillEpisode(item, epFilename, epNum)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val req = call.receive<SaveCandidateRequest>()
                    if (req.source.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "source required"))
                    val ok = artwork.saveEpisodeStill(ep, req.source)
                    if (!ok) return@post call.respond(HttpStatusCode.BadGateway, mapOf("error" to "download failed"))
                    // Bug fix: Episode.hasStill is a persisted snapshot, so it must be re-stamped + saved
                    // now, or triage/Library/Dashboard keep reporting this episode "missing" indefinitely.
                    store.updateOne(artwork.stampHasStill(item))
                    mediaHistory.record(id, "episode_still_save", "ep=$epFilename")
                    val cfg = configStore.current
                    if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                        jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                    }
                    call.respond(mapOf("ok" to true))
                }

                // GET /api/media/{id}/episodes/{epFilename}/tracks/plan?specifier=...
                get("/tracks/plan") {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val ep = item.episodes.firstOrNull { it.filename == epFilename }
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))

                    val specifier = call.request.queryParameters["specifier"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "specifier required"))
                    val targetTrack = ep.tracks.firstOrNull { it.specifier == specifier }
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

                    val ext = ep.path.substringAfterLast('.').lowercase()
                    val sameType = ep.tracks.filter { it.kind == targetTrack.kind }
                    val kindStr = targetTrack.kind.name.lowercase()
                    val beforeSnaps = sameType.map { t -> TrackSnap(t.specifier, t.language, t.codec, t.title, t.default, kindStr) }
                    val afterSnaps = sameType.map { t -> TrackSnap(t.specifier, t.language, t.codec, t.title, t.streamIndex == targetTrack.streamIndex, kindStr) }

                    if (ext == "mkv") {
                        val escaped = ep.path.replace("'", "'\\''")
                        val parts = sameType.joinToString(" \\\n  ") { t ->
                            val flag = if (t.streamIndex == targetTrack.streamIndex) 1 else 0
                            "--edit track:@${t.streamIndex + 1} --set flag-default=$flag"
                        }
                        call.respond(TrackPlan("mkvpropedit '$escaped' \\\n  $parts", "mkvpropedit", 40, specifier, beforeSnaps, afterSnaps))
                    } else {
                        call.respond(TrackPlan(FfmpegRunner.planSetDefault(ep.path, targetTrack.streamIndex, sameType.map { it.streamIndex }, targetTrack.kind), "ffmpeg", 5000, specifier, beforeSnaps, afterSnaps))
                    }
                }

                // Phase 128 — GET .../episodes/{epFilename}/tracks/diagnose: see TrackRoutes.kt's movie
                // twin for the full rationale (corrupt/unreadable/no-audio classification + *arr-managed).
                get("/tracks/diagnose") {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val ep = item.episodes.firstOrNull { it.filename == epFilename }
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
                    val diagnosis = FfprobeRunner.diagnose(ep.path).copy(managed = arrRescan?.isManaged(item) ?: false)
                    call.respond(diagnosis)
                }

                // Phase 128 — POST .../episodes/{epFilename}/tracks/reprobe: see TrackRoutes.kt's movie
                // twin. Episode-level resolvedLanguage is honest (null) when the fresh probe still shows
                // no audio, independent of the series' own resolved language.
                post("/tracks/reprobe") {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val epIdx = item.episodes.indexOfFirst { it.filename == epFilename }
                    if (epIdx < 0) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
                    val ep = item.episodes[epIdx]
                    val newTracks = FfprobeRunner.probe(ep.path)
                    val newIssue = newTracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                    val hasAudio = newTracks.any { it.kind == TrackKind.AUDIO }
                    val updatedEpisodes = item.episodes.toMutableList()
                    updatedEpisodes[epIdx] = ep.copy(
                        tracks = newTracks,
                        issueCount = newIssue,
                        resolvedLanguage = primaryAudioLanguage(configStore.current, ep.path, newTracks).takeIf { hasAudio },
                    )
                    store.updateOne(item.copy(episodes = updatedEpisodes))
                    mediaHistory.record(id, "tracks_reprobe", "ep=${ep.filename} streams=${newTracks.size} hasAudio=$hasAudio")
                    call.respond(newTracks)
                }

                // Phase 128 — POST .../episodes/{epFilename}/reacquire: see TrackRoutes.kt's movie twin.
                // *arr manages at the SERIES level, so this nudges the whole series' Sonarr entry.
                post("/reacquire") {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val (managed, ok, detail) = arrRescan?.reacquire(item) ?: Triple(false, false, "No *arr configured")
                    call.respond(ReacquireResponse(managed, ok, detail))
                }

                // POST /api/media/{id}/episodes/{epFilename}/tracks/default
                post("/tracks/default") {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val epIdx = item.episodes.indexOfFirst { it.filename == epFilename }
                    if (epIdx < 0) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
                    val ep = item.episodes[epIdx]

                    @Serializable data class Req(val specifier: String)
                    val req = call.receive<Req>()
                    val targetTrack = ep.tracks.firstOrNull { it.specifier == req.specifier }
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))
                    val ext = ep.path.substringAfterLast('.').lowercase()
                    val sameType = ep.tracks.filter { it.kind == targetTrack.kind }

                    when (val guard = seedingGuard.check(ep.path, configStore.current)) {
                        is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                        is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                        else -> Unit
                    }

                    val ok = if (ext == "mkv") MkvpropeditRunner.setDefault(ep.path, targetTrack.streamIndex, sameType.map { it.streamIndex })
                             else FfmpegRunner.setDefault(ep.path, targetTrack.streamIndex, sameType.map { it.streamIndex }, targetTrack.kind)

                    if (!ok) { call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "tool failed")); return@post }

                    val newTracks = FfprobeRunner.probe(ep.path)
                    val newIssue = newTracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                    val updatedEpisodes = item.episodes.toMutableList()
                    updatedEpisodes[epIdx] = ep.copy(tracks = newTracks, issueCount = newIssue)
                    store.updateOne(item.copy(episodes = updatedEpisodes))
                    mediaHistory.record(id, "set_default", "ep=${ep.filename} specifier=${req.specifier}")
                    arrRescan?.nudge(item)  // Phase 54 — refresh Sonarr MediaInfo after an episode track edit
                    call.respond(mapOf("ok" to true))
                }

                // POST /api/media/{id}/episodes/{epFilename}/tracks/language
                post("/tracks/language") {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val epIdx = item.episodes.indexOfFirst { it.filename == epFilename }
                    if (epIdx < 0) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
                    val ep = item.episodes[epIdx]

                    @Serializable data class LangReq(val specifier: String, val language: String)
                    val req = call.receive<LangReq>()
                    if (!req.language.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*"))) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid language code"))
                        return@post
                    }
                    if (LanguageResolver.toIso6392(req.language) == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no ISO-639-2 mapping for '${req.language}'"))
                        return@post
                    }
                    val targetTrack = ep.tracks.firstOrNull { it.specifier == req.specifier }
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))
                    val ext = ep.path.substringAfterLast('.').lowercase()

                    when (val guard = seedingGuard.check(ep.path, configStore.current)) {
                        is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                        is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                        else -> Unit
                    }

                    val ok = if (ext == "mkv") MkvpropeditRunner.setLanguage(ep.path, targetTrack.streamIndex, req.language)
                             else FfmpegRunner.setLanguage(ep.path, targetTrack.streamIndex, req.language)

                    if (!ok) { call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "${if (ext == "mkv") "mkvpropedit" else "ffmpeg"} failed")); return@post }

                    val newTracks = FfprobeRunner.probe(ep.path)
                    val newIssue = newTracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                    val updatedEpisodes = item.episodes.toMutableList()
                    // Re-derive episode resolvedLanguage if the changed track is audio (same as reorder path).
                    val epResolved = if (targetTrack.kind == TrackKind.AUDIO)
                        primaryAudioLanguage(configStore.current, ep.path, newTracks)
                    else ep.resolvedLanguage
                    updatedEpisodes[epIdx] = ep.copy(tracks = newTracks, issueCount = newIssue, resolvedLanguage = epResolved)
                    // Re-derive series-level resolvedLanguage from episodes' majority primary audio language.
                    val seriesResolved = if (targetTrack.kind == TrackKind.AUDIO) {
                        val votes = mutableMapOf<String, Int>()
                        for (e in updatedEpisodes) {
                            e.tracks.firstOrNull { it.kind == TrackKind.AUDIO }?.language
                                ?.let { LanguageResolver.normalize(it) }
                                ?.let { votes[it] = (votes[it] ?: 0) + 1 }
                        }
                        votes.maxByOrNull { it.value }?.key ?: item.resolvedLanguage
                    } else item.resolvedLanguage
                    // Keep item.tracks in sync with the first episode's tracks so repull language resolution is correct
                    val updatedItemTracks = if (epIdx == 0) newTracks else item.tracks
                    store.updateOne(item.copy(episodes = updatedEpisodes, tracks = updatedItemTracks, resolvedLanguage = seriesResolved))

                    // Verify the tag actually persisted (B/T-agnostic); report disk truth, not the request.
                    val probed = newTracks.firstOrNull { it.specifier == req.specifier }?.language
                    val persisted = probed != null && LanguageResolver.normalize(probed) == LanguageResolver.normalize(req.language)
                    if (!persisted) {
                        mediaHistory.record(id, "set_language", "ep=${ep.filename} specifier=${req.specifier} FAILED to persist (on disk: ${probed ?: "none"})")
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "the language tag did not persist (file shows '${probed ?: "none"}') — the container may not support per-stream language"))
                        return@post
                    }
                    mediaHistory.record(id, "set_language", "ep=${ep.filename} specifier=${req.specifier} language=$probed")
                    arrRescan?.nudge(item)  // Phase 54 — refresh Sonarr MediaInfo after an episode track edit
                    call.respond(LangWriteResponse(ok = true, language = probed))
                }

                // POST /api/media/{id}/episodes/{epFilename}/tracks/forced — forced flag (MKV only)
                post("/tracks/forced") {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val epIdx = item.episodes.indexOfFirst { it.filename == epFilename }
                    if (epIdx < 0) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
                    val ep = item.episodes[epIdx]

                    @Serializable data class ForcedReq(val specifier: String, val forced: Boolean)
                    val req = call.receive<ForcedReq>()
                    val targetTrack = ep.tracks.firstOrNull { it.specifier == req.specifier }
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))
                    if (targetTrack.kind != TrackKind.SUBTITLE)
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "forced flag only applies to subtitle tracks"))
                    val ext = ep.path.substringAfterLast('.').lowercase()
                    if (ext != "mkv")
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "forced flag editing requires MKV container"))

                    when (val guard = seedingGuard.check(ep.path, configStore.current)) {
                        is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                        is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                        else -> Unit
                    }

                    val sameType = ep.tracks.filter { it.kind == TrackKind.SUBTITLE }
                    val forcedIdx = if (req.forced) targetTrack.streamIndex else -1
                    val ok = MkvpropeditRunner.setForced(ep.path, forcedIdx, sameType.map { it.streamIndex })
                    if (!ok) { call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "mkvpropedit failed")); return@post }

                    val newTracks = FfprobeRunner.probe(ep.path)
                    val newIssue = newTracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                    val updatedEpisodes = item.episodes.toMutableList()
                    updatedEpisodes[epIdx] = ep.copy(tracks = newTracks, issueCount = newIssue)
                    store.updateOne(item.copy(episodes = updatedEpisodes))
                    mediaHistory.record(id, "set_forced", "ep=${ep.filename} specifier=${req.specifier} forced=${req.forced}")
                    arrRescan?.nudge(item)  // Phase 54 — refresh Sonarr MediaInfo after an episode track edit
                    call.respond(mapOf("ok" to true))
                }

                // POST /api/media/{id}/episodes/{epFilename}/tracks/reorder — Phase 109: enqueues an
                // ffmpeg remux job and returns immediately (202 + job id) instead of blocking the request
                // for the whole remux — see MediaJobQueue.
                post("/tracks/reorder") {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val ep = item.episodes.firstOrNull { it.filename == epFilename }
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))

                    @Serializable data class ReorderReq(val kind: String, val order: List<String>)
                    val req = call.receive<ReorderReq>()
                    when (req.kind.lowercase()) {
                        "audio" -> TrackKind.AUDIO
                        "subtitle" -> TrackKind.SUBTITLE
                        else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be audio or subtitle"))
                    }
                    val orderedTracks = req.order.mapNotNull { spec -> ep.tracks.firstOrNull { it.specifier == spec } }
                    if (orderedTracks.size != req.order.size)
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "one or more specifiers not found"))

                    // Fail-fast guard at enqueue time (Phase 109 FR A.3) — the job re-checks at start too,
                    // since the seeding state can change while it waits in the queue.
                    when (val guard = seedingGuard.check(ep.path, configStore.current)) {
                        is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                        is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                        else -> Unit
                    }

                    val epCode = if (ep.seasonNumber != null && ep.episodeNumber != null)
                        "S${ep.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
                    else ep.filename
                    val job = mediaJobQueue.enqueue(
                        "reorder", id, "${item.title} — $epCode",
                        dev.jellystructure.jobs.MediaJobParams(kind = req.kind.lowercase(), order = req.order, episodeFilename = epFilename),
                    )
                    call.respond(HttpStatusCode.Accepted, mapOf("jobId" to job.id))
                }

                // DELETE /api/media/{id}/episodes/{epFilename}/tracks/{specifier} — Phase 144: drop one
                // track from an episode file via the ffmpeg remux "remove" job (the movie twin lives in
                // TrackRoutes.kt). Used by "Fix cover track" to drop a cover-image-muxed-as-video stream.
                delete("/tracks/{specifier}") {
                    val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    val specifier = call.parameters["specifier"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
                    val ep = item.episodes.firstOrNull { it.filename == epFilename }
                        ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
                    ep.tracks.firstOrNull { it.specifier == specifier }
                        ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

                    when (val guard = seedingGuard.check(ep.path, configStore.current)) {
                        is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@delete }
                        is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@delete }
                        else -> Unit
                    }

                    val epCode = if (ep.seasonNumber != null && ep.episodeNumber != null)
                        "S${ep.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
                    else ep.filename
                    val job = mediaJobQueue.enqueue(
                        "remove", id, "${item.title} — $epCode",
                        dev.jellystructure.jobs.MediaJobParams(specifier = specifier, episodeFilename = epFilename),
                    )
                    call.respond(HttpStatusCode.Accepted, mapOf("jobId" to job.id))
                }

                // PATCH /api/media/{id}/episodes/{epFilename}/metadata — edit episode title/overview
                patch("/metadata") {
                    val id = call.parameters["id"]
                        ?: return@patch call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@patch call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@patch call.respond(HttpStatusCode.NotFound)
                    val epIdx = item.episodes.indexOfFirst { it.filename == epFilename }
                    if (epIdx < 0) return@patch call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
                    val ep = item.episodes[epIdx]

                    @Serializable data class EpMetaReq(val title: String? = null, val overview: String? = null)
                    val req = call.receive<EpMetaReq>()
                    val updatedEp = ep.copy(
                        title = if (req.title != null) req.title.ifBlank { null } else ep.title,
                        overview = if (req.overview != null) req.overview.ifBlank { null } else ep.overview,
                    )
                    val updatedEpisodes = item.episodes.toMutableList()
                    updatedEpisodes[epIdx] = updatedEp
                    val updatedItem = item.copy(episodes = updatedEpisodes)
                    store.updateOne(updatedItem)
                    mediaHistory.record(id, "episode_meta_edit", "ep=${ep.filename}")
                    appScope.launch { pushToJellyfin(updatedItem, artwork, configStore, jellyfinClient, appScope, store, arrRescan) }
                    call.respond(updatedEp)
                }
            }
        }

        // PATCH /api/media/{id}/language — override the resolved language for a series
        patch("/{id}/language") {
            val id = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@patch call.respond(HttpStatusCode.NotFound)

            @Serializable data class LangOverrideReq(val language: String)
            val req = call.receive<LangOverrideReq>()
            val lang = req.language.trim()
            if (lang.isBlank() || !lang.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*"))) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid language code"))
                return@patch
            }
            val snapshot = """{"language":"${item.resolvedLanguage?.replace("\"", "\\\"") ?: ""}"}"""
            val updated = item.copy(resolvedLanguage = lang)
            store.updateOne(updated)
            mediaHistory.record(id, "language_override", lang, revertable = true, beforeSnapshot = snapshot)
            call.respond(updated)
        }

        // PATCH /api/media/{id}/metadata — edit title, overview, year, tags, director, studio, network
        patch("/{id}/metadata") {
            val id = call.parameters["id"]
                ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@patch call.respond(HttpStatusCode.NotFound)

            @Serializable data class MetadataEditReq(
                val title: String? = null,
                val overview: String? = null,
                val year: Int? = null,
                val originalTitle: String? = null,
                val tags: List<String>? = null,
                val genres: List<String>? = null,
                val director: String? = null,
                val studio: String? = null,
                val network: String? = null,
            )
            val req = call.receive<MetadataEditReq>()
            val newTitle = req.title?.takeIf { it.isNotBlank() } ?: item.title
            // If the user changed the title, record it in titlesByLang under the resolved language
            // so the manual title remains searchable. Never remove other languages' entries.
            val updatedTitlesByLang = if (!req.title.isNullOrBlank() && item.resolvedLanguage != null) {
                item.titlesByLang + mapOf(item.resolvedLanguage to newTitle)
            } else item.titlesByLang
            val updated = item.copy(
                title = newTitle,
                overview = req.overview ?: item.overview,
                // Bug fix: this is the ONE caller of this route (MediaDetail.kt's `saveMetaNow`), and it
                // always sends the form's actual current year — including an explicit `null` when the
                // operator clears the Year field. `req.year ?: item.year` could never distinguish
                // "cleared on purpose" from "not sent", so the field could never be cleared at all.
                // `year` (unlike `director`/`studio`/`network`) has no kind-conditional "not applicable"
                // case that needs the old preserve-if-null behavior.
                year = req.year,
                originalTitle = if (req.originalTitle != null) req.originalTitle.ifBlank { null } else item.originalTitle,
                tags = req.tags ?: item.tags,
                genres = req.genres ?: item.genres,
                director = if (req.director != null) req.director.ifBlank { null } else item.director,
                studio = if (req.studio != null) req.studio.ifBlank { null } else item.studio,
                network = if (req.network != null) req.network.ifBlank { null } else item.network,
                titlesByLang = updatedTitlesByLang,
            )
            @Serializable data class MetaSnap(val title: String, val overview: String?, val year: Int?, val originalTitle: String?, val director: String?, val studio: String?, val network: String?, val tags: List<String>, val genres: List<String> = emptyList())
            val snap = MetaSnap(item.title, item.overview, item.year, item.originalTitle, item.director, item.studio, item.network, item.tags, item.genres)
            store.updateOne(updated)
            mediaHistory.record(id, "metadata_edit", "title=${updated.title}", revertable = true, beforeSnapshot = Json.encodeToString(snap))
            // Phase 74: write-through persists to the DB ONLY. The NFO write + Jellyfin refresh happen
            // on the explicit "Save → NFO" / "Save & sync to Jellyfin" action, not on every field edit.
            call.respond(updated)
        }

        // PATCH /api/media/{id}/tmdb-id — set or clear the TMDB id; does not trigger a resync
        patch("/{id}/tmdb-id") {
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
            @Serializable data class TmdbIdReq(val tmdbId: Int? = null)
            val req = call.receive<TmdbIdReq>()
            val tmdbSnap = """{"tmdbId":${item.tmdbId ?: "null"}}"""
            // Phase 174 (FR-174-3): setting a real id IS the explicit re-match that lifts a cleared
            // match's lock — and the guard has to stand down for this one write, or it would strip the
            // id straight back out. Setting the id to null is NOT an unlock: clearing an id says
            // nothing about whether a correct match exists.
            val updated = item.copy(tmdbId = req.tmdbId, tmdbMatchLocked = item.tmdbMatchLocked && req.tmdbId == null)
            store.updateOne(updated, respectTmdbMatchLock = req.tmdbId == null)
            mediaHistory.record(id, "set_tmdb_id", "tmdbId=${req.tmdbId}", revertable = true, beforeSnapshot = tmdbSnap)
            call.respond(updated)
        }

        // POST /api/media/{id}/tmdb-match/clear — Phase 174 (FR-174-1): fully undo a TMDB match (wrong
        // or simply unwanted). PATCH /tmdb-id above only ever touches the id itself — every OTHER field
        // a match populated (posterPath/backdropPath and the downloaded files themselves, overview,
        // genres, cast/crew, studio, originalTitle/originalLanguage, imdbId, runtime, certifications,
        // trailer, imdbRating) had no removal path at all, so clearing just the id left a wrong match's
        // residue permanently stuck with nothing on screen to fix it. Reported live: a music video
        // ("Tina Varde - Lowtide") got matched to an unrelated 1923 film of the same one-word title,
        // with no way to fully undo it and no way to stop `pull_tmdb` from re-attempting (and
        // re-producing) the same wrong match on the next scheduled scan.
        //
        // The field set lives in `clearTmdbMatch` (TmdbMatchLock.kt), not here, so this route and the
        // store-write guard that keeps a full scan from re-applying the match can't drift apart. Fields
        // it deliberately leaves alone — `title`, `year`, `tags`, `titlesByLang` — are documented there.
        post("/{id}/tmdb-match/clear") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            artwork.clearAsset(item, ArtworkAsset.POSTER)
            artwork.clearAsset(item, ArtworkAsset.BACKDROP)
            // Snapshot minus `episodes` — they're the bulk of a series' JSON and hold no TMDB-owned
            // item field, and the revert below restores named fields only, never the whole item.
            val snap = Json.encodeToString(MediaItem.serializer(), item.copy(episodes = emptyList()))
            // Release the poster/backdrop locks along with the files (see the artwork clear route's
            // comment — a lock on a deleted image pins its path null forever).
            val updated = clearTmdbMatch(item).copy(
                lockedArtwork = item.lockedArtwork.filterNot { it == ArtworkAsset.POSTER || it == ArtworkAsset.BACKDROP },
            )
            store.updateOne(updated, respectArtworkLock = false)
            mediaHistory.record(id, "tmdb_match_clear", "cleared TMDB match (was tmdbId=${item.tmdbId ?: "none"})", revertable = true, beforeSnapshot = snap)
            val cfg = configStore.current
            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
            }
            call.respond(updated)
        }

        // GET /api/media/{id}/jellyfin-locks — live lock/field status from Jellyfin
        get("/{id}/jellyfin-locks") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val jid = item.jellyfinId
            if (jid.isNullOrBlank()) {
                call.respond(JellyfinLocksResponse(lockData = false, lockedFields = emptyList()))
                return@get
            }
            val cfg = configStore.current
            if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) {
                call.respond(JellyfinLocksResponse(lockData = false, lockedFields = emptyList()))
                return@get
            }
            val jItem = jellyfinClient.getItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jid)
            val lockData = jItem?.lockData ?: false
            val lockedFields = jItem?.lockedFields ?: emptyList()
            val updated = item.copy(jellyfinLockData = lockData, jellyfinLockedFields = lockedFields)
            store.updateOne(updated)
            call.respond(JellyfinLocksResponse(lockData = lockData, lockedFields = lockedFields))
        }

        // GET /api/media/{id}/tmdb-search?q=&year= — search TMDB for alternative matches
        get("/{id}/tmdb-search") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val q = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
                ?: item.title
            val year = call.request.queryParameters["year"]?.toIntOrNull()
            @Serializable data class TmdbMatch(val id: Int, val title: String, val year: String, val posterPath: String? = null, val overview: String = "")
            if (item.kind == MediaKind.MOVIE) {
                val results = scanner.searchMovieTmdb(q, year).map { r ->
                    TmdbMatch(r.id, r.title, r.releaseDate.take(4), r.posterPath, r.overview)
                }
                call.respond(results)
            } else {
                val results = scanner.searchTvTmdb(q, year).map { r ->
                    TmdbMatch(r.id, r.name, r.firstAirDate.take(4))
                }
                call.respond(results)
            }
        }

        // GET /api/media/{id}/tmdb-languages — every language TMDB holds something for this title,
        // each with enough to judge it (title? overview? how many posters?) — Phase 184's picker
        // (FR-184-4) reads this directly; the resolver-trace visualization (buildResolverTrace) reads
        // just the codes off it, same as before this phase enriched the response.
        get("/{id}/tmdb-languages") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val tmdbId = item.tmdbId
            if (tmdbId == null) {
                call.respond(emptyList<dev.jellystructure.tmdb.TmdbLanguageCoverage>())
                return@get
            }
            val coverage = scanner.translationCoverage(tmdbId, item.kind == MediaKind.MOVIE)
            call.respond(coverage)
        }

        // PATCH /api/media/{id}/metadata-language — Phase 184: an operator's manual choice of which
        // language to fetch TMDB metadata in. `language: null` means "Back to automatic" — the same
        // route handles both directions, matching PATCH /{id}/tmdb-id's null-clears convention.
        //
        // FR-184-2: no new fetch/fallback logic — this is the ORDINARY rescanMetadata path, with
        // metadataLanguage set first so Scanner's own overrideLang logic picks it up. FR-184-6: for a
        // series this naturally re-pulls every episode too, since rescanMetadata's TV_SHOW branch
        // already does that for any language change.
        patch("/{id}/metadata-language") {
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
            @Serializable data class MetaLangReq(val language: String? = null)
            val req = runCatching { call.receive<MetaLangReq>() }.getOrElse {
                return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request"))
            }
            val chosen = req.language?.trim()?.lowercase()?.ifBlank { null }
            if (item.tmdbId == null) {
                return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "item has no TMDB match"))
            }

            // FR-184-8 — the full pre-change item (episodes included), so the revert below can restore
            // everything the rescan is about to touch, not just this one field. See the revert handler's
            // own comment for why a named-field restore (tmdb_match_clear's shape) isn't enough here.
            val snap = Json.encodeToString(MediaItem.serializer(), item)
            val now = dev.jellystructure.nowEpochSec()
            val withChoice = item.copy(
                metadataLanguage = chosen,
                metadataLanguageSetAt = if (chosen != null) now else null,
            )
            store.updateOne(withChoice, respectMetadataLanguageLock = false)
            mediaHistory.record(
                id, "metadata_language_set",
                if (chosen != null) "language=$chosen (was ${item.metadataLanguage ?: "automatic"})"
                else "back to automatic (was ${item.metadataLanguage})",
                revertable = true, beforeSnapshot = snap,
            )

            val rescanned = scanner.rescanMetadata(withChoice)
            if (rescanned == null) {
                Logger.warn("metadata-language: re-pull failed for $id (no TMDB match or API error)", "scan")
                call.respond(withChoice)
                return@patch
            }
            val enriched = sonarrEnrich?.enrichOne(rescanned) ?: rescanned
            store.updateOne(enriched, respectMetadataLanguageLock = false)

            NfoWriter.writeTracked(enriched, configStore.current.apiKeys.jellyfinUrl, configStore.current.metadata.ageRatingCascade)
                .onSuccess { result ->
                    mediaHistory.record(id, "nfo_write", result.path)
                    store.updateOne(enriched.copy(nfoWrittenAt = result.writtenAt, nfoHash = result.hash), respectMetadataLanguageLock = false)
                }
                .onFailure { Logger.warn("metadata-language: NFO write failed for $id: ${it.message}", "scan") }

            val artStatus = artwork.fetch(enriched)
            store.updateOne(artwork.stampHasStill(enriched), respectMetadataLanguageLock = false)
            if (artStatus.posterExists || artStatus.fanartExists) {
                mediaHistory.record(id, "artwork_fetch", "poster=${artStatus.posterExists} fanart=${artStatus.fanartExists}")
            }

            val cfg = configStore.current
            if (!enriched.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, enriched.jellyfinId, full = true)
            }
            call.respond(enriched)
        }

        // GET /api/media/{id}/seeding — full SeedingReport for the seeding surface (Phase 97)
        get("/{id}/seeding") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val report = seedingSnapshot.reportForItem(item, configStore.current)
            call.respond(report)
        }

        // POST /api/media/{id}/seeding/refresh — force-refresh the shared snapshot
        post("/{id}/seeding/refresh") {
            call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            seedingSnapshot.get(forceRefresh = true)
            call.respond(mapOf("ok" to true))
        }


        // GET /api/media/{id}/drift — Phase 115: three-state sync evaluation (NFO stale / Jellyfin
        // behind / external drift), replacing the old blunt DB-vs-Jellyfin field compare.
        get("/{id}/drift") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val result = dev.jellystructure.nfo.DriftEvaluator.evaluate(item, jellyfinClient, configStore.current)
            call.respond(result)
            if (result.state == dev.jellystructure.nfo.DriftState.EXTERNAL_DRIFT.name.lowercase()) {
                val cfg = configStore.current
                if (cfg.behavior.notifyOnDrift)
                    fireWebhook(cfg, """{"event":"drift_detected","mediaId":"$id","fields":${result.fields.size}}""")
            }
        }

        // POST /api/media/{id}/repull-jellyfin — re-fetch item from Jellyfin + full rescan
        post("/{id}/repull-jellyfin") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            if (item.jellyfinId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "item has no Jellyfin ID"))
                return@post
            }
            if (scanTracker.running) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "scan already running"))
                return@post
            }
            val updated = scanner.rescanFromJellyfin(item)?.let { artwork.stampHasStill(it) }
            if (updated == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "re-pull failed — item not found in Jellyfin or config missing"))
                return@post
            }
            val enriched = sonarrEnrich?.enrichOne(updated) ?: updated
            store.updateOne(enriched)
            broadcaster.broadcast(JobEvent.ItemScanned("repull-jellyfin-$id", enriched))
            mediaHistory.record(id, "repull_jellyfin", "jellyfinId=${item.jellyfinId}")
            pushToJellyfin(enriched, artwork, configStore, jellyfinClient, appScope, store, arrRescan)
            call.respond(enriched)
        }

        // POST /api/media/{id}/sync — targeted full rescan for one item (no ScanTracker transitions)
        post("/{id}/sync") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            if (scanTracker.running) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "scan already running"))
                return@post
            }
            @Serializable data class SyncReq(val scope: String = "episodes")
            val req = runCatching { call.receive<SyncReq>() }.getOrDefault(SyncReq())
            val updated = when (item.kind) {
                MediaKind.MOVIE -> scanner.syncMovie(item)
                MediaKind.TV_SHOW -> when (req.scope) {
                    "series" -> scanner.rescanMetadata(item)
                    else -> scanner.syncSeriesEpisodes(item)
                }
                // Phase 171 (reverses Phase 168): a music video can have a real TMDB match now, so
                // route this through the same metadata-only rescan TV_SHOW's "series" scope uses —
                // no per-file re-probe path exists for music videos (out of scope; filename parsing
                // doesn't need a fresh ffprobe to redo), but a manually-set or freshly-searched tmdbId
                // should actually get fetched, not silently dropped.
                MediaKind.MUSIC_VIDEO -> scanner.rescanMetadata(item)
            }?.let { artwork.stampHasStill(it) }
            if (updated == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "sync failed — file not found or no TMDB match"))
                return@post
            }
            val enriched = sonarrEnrich?.enrichOne(updated) ?: updated
            store.updateOne(enriched)
            broadcaster.broadcast(JobEvent.ItemScanned("sync-$id", enriched))
            mediaHistory.record(id, "sync", "kind=${item.kind.name.lowercase()} scope=${req.scope}")
            pushToJellyfin(enriched, artwork, configStore, jellyfinClient, appScope, store, arrRescan)
            call.respond(enriched)
        }

        // POST /api/media/{id}/trailer/refetch — Phase 130: re-run TMDB /videos + selection for this
        // title only (no probe/re-scan of anything else). No usable video ⇒ trailer becomes null.
        post("/{id}/trailer/refetch") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            val tmdbId = item.tmdbId
            if (tmdbId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "item has no TMDB id"))
                return@post
            }
            val video = if (item.kind == MediaKind.MOVIE) tmdbClient.getMovieVideos(tmdbId, item.originalLanguage.orEmpty())
                else tmdbClient.getTvVideos(tmdbId, item.originalLanguage.orEmpty())
            val trailer = video?.let { v ->
                val site = if (v.site.equals("Vimeo", ignoreCase = true)) "vimeo" else "youtube"
                val thumb = if (site == "vimeo") tmdbClient.resolveVimeoThumb(v.key) else null
                MediaTrailer(site = site, key = v.key, name = v.name, thumb = thumb)
            }
            val updated = item.copy(trailer = trailer)
            store.updateOne(updated)
            mediaHistory.record(id, "trailer_refetch", if (trailer != null) "site=${trailer.site} key=${trailer.key}" else "no usable video")
            call.respond(updated)
        }

        // DELETE /api/media/{id}/trailer — Phase 130: Clear — an explicit operator action, not
        // preserved/reintroduced by the next automatic sync (which would just refetch and overwrite it).
        delete("/{id}/trailer") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
            val updated = item.copy(trailer = null)
            store.updateOne(updated)
            mediaHistory.record(id, "trailer_clear", "")
            call.respond(updated)
        }

        // POST /api/media/{id}/imdb-rating/sync — Phase 131: manual per-title re-sync against
        // imdbapi.dev. A failed/absent lookup leaves the previous stored rating intact.
        post("/{id}/imdb-rating/sync") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            val imdbId = item.imdbId
            if (imdbId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "item has no IMDb id"))
                return@post
            }
            val fetched = imdbClient.getRating(imdbId)
            val updated = if (fetched != null)
                item.copy(imdbRating = dev.jellystructure.model.ImdbRating(fetched.aggregateRating, fetched.voteCount, dev.jellystructure.nowEpochSec()))
            else item
            if (fetched != null) store.updateOne(updated)
            mediaHistory.record(id, "imdb_rating_sync", if (fetched != null) "rating=${fetched.aggregateRating} votes=${fetched.voteCount}" else "no rating returned")
            call.respond(updated)
        }

        // POST /api/media/{id}/seasons/{seasonNumber}/sync — per-season resync
        post("/{id}/seasons/{seasonNumber}/sync") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val seasonNumber = call.parameters["seasonNumber"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid season number"))
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            if (item.kind != MediaKind.TV_SHOW) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "only available for TV shows"))
                return@post
            }
            if (scanTracker.running) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "scan already running"))
                return@post
            }
            @Serializable data class SeasonSyncReq(val scope: String = "episodes")
            val req = runCatching { call.receive<SeasonSyncReq>() }.getOrDefault(SeasonSyncReq())
            val (syncedItem, synced) = scanner.syncSeason(item, seasonNumber, probeFiles = req.scope != "season")
            val updatedItem = artwork.stampHasStill(syncedItem)
            store.updateOne(updatedItem)
            broadcaster.broadcast(JobEvent.ItemScanned("sync-$id-s$seasonNumber", updatedItem))
            mediaHistory.record(id, "season_sync", "season=$seasonNumber scope=${req.scope} synced=$synced")
            pushToJellyfin(updatedItem, artwork, configStore, jellyfinClient, appScope, store, arrRescan)
            call.respond(mapOf("synced" to synced))
        }

        // POST /api/media/{id}/repull — re-fetch TMDB metadata without re-probing the file
        route("/{id}/repull") {
            post {
                val id = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val item = store.resolve(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val updated = scanner.rescanMetadata(item)
                if (updated == null) {
                    Logger.warn("Re-pull TMDB failed for $id (no TMDB match or API error)")
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "TMDB fetch failed — no match found"))
                    return@post
                }

                val enriched = sonarrEnrich?.enrichOne(updated) ?: updated
                store.updateOne(enriched)
                Logger.info("Re-pulled TMDB for '$id': title='${enriched.title}' tmdbId=${enriched.tmdbId} episodes=${enriched.episodes.size}")
                pushToJellyfin(enriched, artwork, configStore, jellyfinClient, appScope, store, arrRescan)
                call.respond(enriched)
            }
        }

        // Phase 75 — Cast & crew endpoints

        // PATCH /api/media/{id}/cast — replace the cast list (write-through)
        patch("/{id}/cast") {
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
            val cast = runCatching { call.receive<List<Person>>() }.getOrElse {
                return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid cast payload"))
            }
            val updated = item.copy(cast = cast)
            store.updateOne(updated)
            broadcaster.broadcast(JobEvent.ItemScanned("cast-edit-$id", updated))
            call.respond(updated)
        }

        // PATCH /api/media/{id}/crew — replace the crew list (write-through)
        patch("/{id}/crew") {
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
            val crew = runCatching { call.receive<List<Person>>() }.getOrElse {
                return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid crew payload"))
            }
            val updated = item.copy(crew = crew)
            store.updateOne(updated)
            broadcaster.broadcast(JobEvent.ItemScanned("crew-edit-$id", updated))
            call.respond(updated)
        }

        // POST /api/media/{id}/cast/fetch — re-fetch cast+crew from TMDB, store, return updated item
        post("/{id}/cast/fetch") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            val tmdbId = item.tmdbId
            if (tmdbId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "item has no TMDB id"))
                return@post
            }
            val (cast, crew) = scanner.fetchCredits(tmdbId, item.kind == MediaKind.MOVIE, seasons = item.episodes.mapNotNull { it.seasonNumber }.distinct())
            val updated = item.copy(cast = cast, crew = crew)
            store.updateOne(updated)
            broadcaster.broadcast(JobEvent.ItemScanned("cast-fetch-$id", updated))
            call.respond(updated)
        }

        // Phase 76: PATCH /api/media/{id}/episodes/{filename}/cast — update episode guest stars
        patch("/{id}/episodes/{filename}/cast") {
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val filename = call.parameters["filename"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
            val guests = runCatching { call.receive<List<Person>>() }.getOrElse {
                return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid cast payload"))
            }
            val updated = item.copy(episodes = item.episodes.map { ep ->
                if (ep.filename == filename) ep.copy(guestStars = guests) else ep
            })
            store.updateOne(updated)
            broadcaster.broadcast(JobEvent.ItemScanned("ep-cast-edit-$id", updated))
            call.respond(updated)
        }

        // Phase 76: PATCH /api/media/{id}/episodes/{filename}/crew — update episode crew
        patch("/{id}/episodes/{filename}/crew") {
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val filename = call.parameters["filename"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
            val crew = runCatching { call.receive<List<Person>>() }.getOrElse {
                return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid crew payload"))
            }
            val updated = item.copy(episodes = item.episodes.map { ep ->
                if (ep.filename == filename) ep.copy(crew = crew) else ep
            })
            store.updateOne(updated)
            broadcaster.broadcast(JobEvent.ItemScanned("ep-crew-edit-$id", updated))
            call.respond(updated)
        }

        // Phase 76: POST /api/media/{id}/episodes/{filename}/cast/fetch — fetch episode guest stars + crew from TMDB
        post("/{id}/episodes/{filename}/cast/fetch") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val filename = call.parameters["filename"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            val ep = item.episodes.firstOrNull { it.filename == filename }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
            val tmdbId = item.tmdbId
            val season = ep.seasonNumber
            val epNum = ep.episodeNumber
            if (tmdbId == null || season == null || epNum == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing TMDB id or season/episode number"))
                return@post
            }
            val (guests, crew) = scanner.fetchEpisodeCredits(tmdbId, season, epNum)
            val updated = item.copy(episodes = item.episodes.map { e ->
                if (e.filename == filename) e.copy(guestStars = guests, crew = crew) else e
            })
            store.updateOne(updated)
            broadcaster.broadcast(JobEvent.ItemScanned("ep-cast-fetch-$id", updated))
            call.respond(updated)
        }

        // Phase 150's segment-marker routes lived here (PATCH/POST .../segments[/rescan]) — removed
        // Phase 163: intro/credits/etc. moved out of the SegmentMarkers blob into the media_segment
        // table, and these routes directly manipulated that blob. Replaced by
        // dev.jellystructure.server.routes.SegmentRoutes.
    }

    // GET /api/people/{tmdbId}/image — serve cached person photo (download on-demand)
    route("/people") {
        get("/{tmdbId}/image") {
            val tmdbId = call.parameters["tmdbId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            // Serve from cache if present
            val cached = logoDownloader.servePersonImage(tmdbId)
            if (cached != null) {
                call.respondCachedBytes(cached, ContentType.Image.JPEG)
                return@get
            }
            // Phase 78: O(1) cached lookup (was a full-library deserialize per request → CPU storm
            // under concurrent cold loads). Download is semaphore-bounded inside LogoDownloader.
            val profilePath = store.personProfilePath(tmdbId)
            if (profilePath != null) {
                logoDownloader.fetchPersonImage(tmdbId, profilePath)
                val bytes = logoDownloader.servePersonImage(tmdbId)
                if (bytes != null) {
                    call.respondCachedBytes(bytes, ContentType.Image.JPEG)
                    return@get
                }
            }
            call.respond(HttpStatusCode.NotFound)
        }

        // GET /api/people/search?q= — search TMDB for people
        get("/search") {
            val q = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "q required"))
            val results = tmdbClient.searchPeople(q).map { p ->
                PersonSearchResult(p.id, p.name, p.profilePath, p.knownForDepartment)
            }
            call.respond(results)
        }
    }

    post("/scan") {
        if (scanTracker.running) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "scan already running"))
            return@post
        }
        val libraryId = call.request.queryParameters["library"]?.takeIf { it.isNotBlank() }
        // Phase 175 — honors the freshness/cooldown filter like every other trigger (previously it
        // always processed the whole library unconditionally). `?full=true` bypasses it.
        val full = call.request.queryParameters["full"] == "true"
        val jobId = scanTracker.startNew()
        launchScanRun(jobId, "manual", scanTracker, appScope, configStore, pipelineDeps, libraryId = libraryId, full = full)
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "started", "library" to (libraryId ?: "all")))
    }

    // Phase 93c / 175 — runs through the exact same launchScanRun() every other trigger uses now
    // (manual click on any page, scheduler, startup); the only thing specific to this route is Phase
    // 154's optional one-run step skip from the pre-run dialog.
    post("/pipeline/run") {
        if (scanTracker.running) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "scan already running"))
            return@post
        }
        val full = call.request.queryParameters["full"] == "true"
        // Phase 154 (FR-PIPE1-6): optional per-run step skip from the pre-run dialog. Read defensively so
        // a bodyless call (the pre-154 client, curl, the command palette) keeps working unchanged. This is
        // a ONE-RUN filter — nothing is written to config, and the scheduled path never sees it.
        // scan_files is never skippable: runPipeline runs discovery regardless of whether it's in the
        // list (its own default-PipelineStep fallback), so honouring it here would be a lie (FR-PIPE1-4).
        val skipSteps = runCatching { call.receive<PipelineRunRequest>() }.getOrDefault(PipelineRunRequest())
            .skipSteps.filterNot { it == "scan_files" }.toSet()
        val jobId = scanTracker.startNew()
        val pipeline = launchScanRun(jobId, "manual", scanTracker, appScope, configStore, pipelineDeps, full = full, skipSteps = skipSteps)
        // Bug fix (2026-07-03): mapOf("status" to "started", "steps" to pipeline.size) mixes a
        // String and an Int, inferring Map<String, Any> — kotlinx.serialization's default Json can't
        // serialize Any without a polymorphic module, so respond() threw here on every successful
        // start. The frontend's post() call then errored (never seeing the 202 that was already
        // committed), landing in runCatching's default and showing "Failed to start pipeline" even
        // though the pipeline had, in fact, started correctly — the exact "it says failed but
        // something did start running" symptom. Every sibling route (e.g. POST /api/scan two routes
        // up) happens to only ever mix String values, which is why only this one route hit it.
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "started", "steps" to pipeline.size.toString()))
    }

    // Phase 178 §FR-178-4 — "Run anyway" for a run currently sitting in PipelineEngine.awaitPlaybackClear
    // because a TV is playing. [jobId] comes from the JobEvent.Deferred payload the dashboard already
    // received over the WS — this is a one-run override (never written to config), matching Phase 154's
    // pre-run-dialog pattern.
    post("/pipeline/{jobId}/run-anyway") {
        val jobId = call.parameters["jobId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        dev.jellystructure.media.PipelineDeferOverride.runAnyway(jobId)
        call.respond(mapOf("status" to "ok"))
    }

    post("/scan/resume") {
        val currentStatus = scanTracker.status().status
        if (currentStatus != "CANCELLED") {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "no cancelled scan to resume (status=$currentStatus)"))
            return@post
        }
        val skipIds = scanTracker.processedIdsSnapshot
        val jobId = scanTracker.startResume()
        // fullRun=true — finishing an interrupted run means the SAME scope it started with, not a fresh
        // freshness computation; this trigger never had a freshness filter pre-Phase-175 either.
        launchScanRun(jobId, "manual", scanTracker, appScope, configStore, pipelineDeps, full = true, resumeSkipIds = skipIds)
        Logger.info("Scan resumed jobId=$jobId, skipping ${skipIds.size} already-processed items")
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "resumed"))
    }

    post("/scan/cancel") {
        if (!scanTracker.running) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "no scan running"))
            return@post
        }
        // Phase 182 (FR-182-5) — cancelRun actually cancels the run's Job (not just the cooperative
        // flag) and force-frees its slots if it doesn't wind down within a bounded grace period.
        scanTracker.cancelRun(appScope)
        call.respond(mapOf("status" to "cancel requested"))
    }

    get("/scan/status") {
        // 93e: compute the next scheduled run FRESH from the current config so the indicator updates
        // the instant the admin saves a new schedule (no waiting for the scheduler loop to recompute).
        val sched = configStore.current.scanSchedule
        val next = if (sched.isNotBlank()) nextRunDelayMs(sched, nowEpochSec())?.let { nowEpochSec() + it / 1000L } else null
        call.respond(scanTracker.status().copy(nextScheduledRun = next))
    }

    get("/stats") {
        call.respond(
            mapOf(
                "movies" to store.movieCount(),
                "tvShows" to store.tvShowCount(),
                "tvEpisodes" to store.tvEpisodeCount(),
                "issues" to store.totalIssueCount(),
                "nfoCoverage" to store.nfoCoveragePercent(),
            )
        )
    }

    get("/activity/recent") {
        call.respond(mediaHistory.recent())
    }

    post("/jellyfin/refresh") {
        val cfg = configStore.current
        if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Jellyfin not configured"))
            return@post
        }
        appScope.launch {
            jellyfinClient.triggerLibraryRefresh(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken)
        }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "refresh triggered"))
    }

    post("/media/batch/artwork") {
        val items = store.allItems()
        appScope.launch {
            for (item in items) {
                val status = artwork.check(item)
                if (!status.posterExists || !status.fanartExists) {
                    artwork.fetch(item)
                    // Bug fix: fetch() may just have created episode stills (TMDB or a screengrab
                    // fallback — either is a real, valid still); Episode.hasStill is a persisted
                    // snapshot, so it must be re-stamped + saved now, or triage/Library/Dashboard
                    // keep reporting these episodes "missing" indefinitely (no-ops for movies).
                    store.updateOne(artwork.stampHasStill(store.get(item.id) ?: item))
                }
            }
        }
        call.respond(HttpStatusCode.Accepted, BatchStartedResponse("artwork fetch started", items.size))
    }

    // POST /api/media/batch/jellyfin-push — write NFOs for all items and refresh each in Jellyfin
    post("/media/batch/jellyfin-push") {
        val cfg = configStore.current
        if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Jellyfin not configured"))
            return@post
        }
        val items = store.allItems()
        call.respond(HttpStatusCode.Accepted, BatchStartedResponse("push started", items.size))
        appScope.launch {
            var nfoOk = 0
            var nfoFail = 0
            var refreshOk = 0
            var refreshFail = 0
            val freshCfg = configStore.current
            for (item in items) {
                var current = item
                NfoWriter.writeTracked(item, freshCfg.apiKeys.jellyfinUrl, freshCfg.metadata.ageRatingCascade)
                    .onSuccess { result ->
                        nfoOk++
                        current = item.copy(nfoWrittenAt = result.writtenAt, nfoHash = result.hash)
                        store.updateOne(current)
                        if (item.kind == MediaKind.TV_SHOW) {
                            for ((group, result) in NfoWriter.writeEpisodeNfos(item.episodes, item.cast)) {
                                result.onFailure { Logger.warn("batch-push: episode NFO failed for ${group.first().filename}: ${it.message}") }
                            }
                        }
                    }
                    .onFailure { nfoFail++; Logger.warn("batch-push: NFO write failed for '${item.id}': ${it.message}") }
                if (!item.jellyfinId.isNullOrBlank()) {
                    val ok = jellyfinClient.refreshItem(freshCfg.apiKeys.jellyfinUrl, freshCfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)
                    if (ok) { refreshOk++; store.updateOne(current.copy(jfSyncedAt = nowEpochSec())) }
                    else { refreshFail++; Logger.warn("batch-push: Jellyfin refresh failed for '${item.id}'") }
                }
            }
            // Trigger a library scan after all NFOs are written so Jellyfin reliably picks up
            // tvshow.nfo changes — per-item FullRefresh alone is not sufficient for TV series NFOs.
            jellyfinClient.triggerLibraryRefresh(freshCfg.apiKeys.jellyfinUrl, freshCfg.apiKeys.jellyfinToken)
            Logger.info("batch-push complete: nfoOk=$nfoOk nfoFail=$nfoFail refreshOk=$refreshOk refreshFail=$refreshFail (+ library scan triggered)")
        }
    }
}

/** Write NFO files, sync artwork, and do a full recursive Jellyfin refresh. */
/**
 * Writes NFO + artwork and asks Jellyfin to re-read the item. Returns whether the targeted per-item
 * refresh succeeded (or was not applicable: no jellyfinId / no Jellyfin config) so callers can report
 * an honest result instead of implying a refresh that didn't run (Phase 50).
 */
// Phase 114 — internal (not private): RealtimeIngestService also calls this after a targeted ingest,
// same as every route handler in this file that finishes a re-scan.
internal suspend fun pushToJellyfin(
    item: MediaItem,
    artwork: ArtworkDownloader,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    appScope: CoroutineScope,
    store: MediaStore,
    arrRescan: ArrRescanService? = null,
): Boolean {
    var current = item
    NfoWriter.writeTracked(item, configStore.current.apiKeys.jellyfinUrl, configStore.current.metadata.ageRatingCascade)
        .onSuccess { result ->
            Logger.info("pushToJellyfin: wrote NFO ${result.path}")
            current = item.copy(nfoWrittenAt = result.writtenAt, nfoHash = result.hash)
            store.updateOne(current)
            if (item.kind == MediaKind.TV_SHOW) {
                var epWritten = 0
                for ((group, result) in NfoWriter.writeEpisodeNfos(item.episodes, item.cast)) {
                    result.onSuccess { epWritten += group.size }
                        .onFailure { Logger.warn("pushToJellyfin: episode NFO failed for ${group.first().filename}: ${it.message}") }
                }
                if (epWritten > 0) Logger.info("pushToJellyfin: wrote $epWritten episode NFOs for '${item.id}'")
            }
        }
        .onFailure { Logger.warn("pushToJellyfin: NFO write failed for '${item.id}': ${it.message}") }

    // Bug fix: fetch() may create episode stills on disk (TMDB or a screengrab fallback — either is a
    // real, valid still); Episode.hasStill is a persisted snapshot, so it must be re-stamped + saved
    // after fetch completes, or triage/Library/Dashboard keep reporting these episodes "missing"
    // indefinitely (stampHasStill no-ops for movies).
    appScope.launch {
        artwork.fetch(item)
        val fresh = store.get(item.id) ?: item
        store.updateOne(artwork.stampHasStill(fresh))
    }

    val cfg = configStore.current
    if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) return true

    var refreshOk = true
    if (!item.jellyfinId.isNullOrBlank()) {
        refreshOk = jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)
        if (refreshOk) store.updateOne(current.copy(jfSyncedAt = nowEpochSec()))
        else Logger.warn("pushToJellyfin: Jellyfin refresh failed for '${item.id}' (jellyfinId=${item.jellyfinId})")
    } else {
        Logger.warn("pushToJellyfin: no jellyfinId for '${item.id}' — skipping per-item Jellyfin refresh")
    }

    // For TV shows, also trigger a library scan so Jellyfin reliably re-reads tvshow.nfo from disk.
    // Per-item FullRefresh alone does not consistently pick up tvshow.nfo changes in Jellyfin.
    if (item.kind == MediaKind.TV_SHOW) {
        jellyfinClient.triggerLibraryRefresh(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken)
        Logger.info("pushToJellyfin: triggered library scan to pick up tvshow.nfo for '${item.id}'")
    }
    // Phase 54 — nudge Radarr/Sonarr to rescan this title (best-effort, non-blocking; independent of
    // the Jellyfin refresh above). No-op unless the matching *arr is enabled with rescan_after_write.
    arrRescan?.nudge(item)
    return refreshOk
}

/**
 * Phase 175 (follow-up, 2026-08-25) — the ONE place any scan/pipeline run is launched, no matter which
 * trigger asked for it: a manual click on any admin page (Dashboard/Library "Scan library", Settings
 * "Run pipeline now"), a resume, the scheduler, or `SCAN_ON_START`. Every trigger resolves its step list
 * the exact same way — [effectivePipeline], minus a one-run [skipSteps] override — and launches through
 * the same [runTagged] + [runPipeline] call. There is no separate "plain scan" vs "pipeline run" vs
 * "startup scan" code path left — only different arguments to this one function, including
 * `SCAN_ON_START`, which previously ran its own bespoke reduced step list (`[scan_files, pull_tmdb]`,
 * no artwork) instead of whatever the operator actually has configured.
 *
 * Returns the resolved step list — callers that report a step count in their HTTP response (e.g.
 * `POST /pipeline/run`) read it off the return value instead of recomputing it themselves.
 */
internal fun launchScanRun(
    jobId: String,
    triggerKind: String,   // "manual" | "scheduled" | "startup"
    scanTracker: ScanTracker,
    appScope: CoroutineScope,
    configStore: ConfigStore,
    pipelineDeps: PipelineDeps,
    libraryId: String? = null,
    full: Boolean = false,
    skipSteps: Set<String> = emptySet(),
    resumeSkipIds: Set<String> = emptySet(),
): List<PipelineStep> {
    val runsPipeline = configStore.current.scan.pipeline.any { it.enabled }
    val pipeline = effectivePipeline(configStore.current).filter { it.step !in skipSteps }
    val kindWord = if (runsPipeline) "pipeline" else "scan"
    val suffix = buildString {
        if (full) append(" (full)")
        if (resumeSkipIds.isNotEmpty()) append(" (resumed)")
        if (skipSteps.isNotEmpty()) append(" · skipped: ${skipSteps.sorted().joinToString(", ")}")
    }
    val startMsg = "▶ ${triggerKind.replaceFirstChar { it.uppercase() }} $kindWord started$suffix"
    // Phase 178 §FR-178-2 — only a "scheduled" trigger defers; "manual" (any admin-page button) and
    // "startup" (SCAN_ON_START, itself an explicit operator action) always proceed immediately.
    val deferEligible = triggerKind == "scheduled" && configStore.current.scan.deferWhilePlaying
    // Phase 182 (FR-182-6/FR-182-5) — GateClass.BACKGROUND tags this coroutine and everything launched
    // under it (every runPipelineStepPool worker, every per-episode async{} inside Scanner) so
    // OutboundHttp/ProcessGate never let this run starve an interactive request; attachJob lets
    // ScanTracker.cancelRun actually cancel this Job instead of only flipping a cooperative flag.
    val job = appScope.launch(dev.jellystructure.ops.GateClass.BACKGROUND) {
        runTagged(
            jobId, triggerKind, if (runsPipeline) "pipeline" else "library",
            if (runsPipeline) (if (full) "full" else "normal") else null,
            startMsg, scanTracker,
        ) {
            runPipeline(RunTarget.Library(libraryId, resumeSkipIds), pipeline, jobId, scanTracker, pipelineDeps, fullRun = full, deferEligible = deferEligible)
        }
    }
    scanTracker.attachJob(job)
    return pipeline
}

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class) // channel.isClosedForReceive — best-effort worker-pool guard
internal suspend fun runScan(
    jobId: String,
    skipIds: Set<String>,
    store: MediaStore,
    scanner: Scanner,
    scanTracker: ScanTracker,
    broadcaster: WsBroadcaster,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    scanDispatcher: CoroutineDispatcher,
    libraryJellyfinId: String? = null,
    freshnessFilter: ((JellyfinItem) -> Boolean)? = null,
    // Phase 93: when set (manual "Scan library" / legacy scheduled scan), download any missing artwork
    // for each scanned item right after it's stored. Left null for the pipeline's scan_files step, which
    // has its own download_artwork step.
    artworkDownloader: ArtworkDownloader? = null,
    // Bug fix (2026-07-03): false when this call is executePipeline's scan_files sub-step — calling this
    // is NOT the end of the run, and marking scanTracker complete()/firing scan_complete here reset
    // activeWorkers to 0 and flipped running=false while pull_tmdb/fetch_artwork/etc. were still ahead,
    // which is exactly what surfaced as "Run pipeline now" racing to a false "already running" 409 and
    // the Activity page's worker count freezing at 0/N for the rest of the run. executePipeline signals
    // completion itself once every step has actually finished.
    signalCompletion: Boolean = true,
): List<MediaItem> {
    val allItems = mutableListOf<MediaItem>()
    val allItemsMutex = Mutex()
    val succeeded = AtomicInt(0)
    val nextWorkerId = AtomicInt(0)
    val processed = AtomicInt(0)

    Logger.info("Library scan started jobId=$jobId (skip=${skipIds.size}${if (libraryJellyfinId != null) " library=$libraryJellyfinId" else ""})", "scan")

    val jellyfinItems = if (libraryJellyfinId != null) scanner.fetchItemsForLibrary(libraryJellyfinId) else scanner.fetchItems()
    if (jellyfinItems == null) {
        if (signalCompletion) {
            scanTracker.complete()
            broadcaster.broadcast(JobEvent.Started(jobId, 0))
            broadcaster.broadcast(JobEvent.Finished(jobId, 0, 0))
        }
        return emptyList()
    }
    // Phase 116: precompute the EFFECTIVE worklist (skipIds + freshness filter applied) up front so the
    // Started total is exact — the scanner already knows the full item list before processing starts,
    // there's nothing to guess. Items dropped here are counted in `skipped.size` at the end, not here.
    val worklist = jellyfinItems.filter { jItem ->
        jItem.id !in skipIds && (freshnessFilter == null || freshnessFilter(jItem))
    }
    Logger.info("Jellyfin returned ${jellyfinItems.size} items (${skipIds.size} skipped for resume, ${worklist.size} in the effective worklist)", "scan")
    broadcaster.broadcast(JobEvent.Started(jobId, worklist.size))

    // coroutineScope suspends here until the producer, all workers, and the supervisor have ALL finished.
    // Post-scan cleanup runs only after this block returns.
    try {
        coroutineScope {
            val channel = Channel<JellyfinItem>(Channel.UNLIMITED)

            scanTracker.targetWorkers.value = configStore.current.behavior.scanWorkers.coerceIn(1, 100)

            // Producer: fills the channel from the precomputed worklist (total already broadcast above).
            launch {
                for (jItem in worklist) {
                    if (scanTracker.cancelRequested) break
                    channel.send(jItem)
                }
                channel.close()
            }

            // Worker factory
            fun launchWorker() {
                val wid = nextWorkerId.incrementAndGet()
                // Increment before launch so the supervisor sees the new worker immediately,
                // not after its coroutine has been scheduled and started.
                scanTracker.activeWorkers.incrementAndGet()
                launch(scanDispatcher + WorkerId(wid)) {
                    Logger.info("Worker starting", "scan")
                    try {
                        for (jItem in channel) {
                            if (scanTracker.cancelRequested) break
                            val activeToken = scanTracker.beginItem(jItem.name)
                            // Phase 182 (FR-182-4/FR-182-5) — same fix as runPipelineStepPool: bounded per-
                            // item deadline (a hung scanItem used to occupy this worker forever, matching
                            // the reported incident's shape exactly), and `catch (e: Exception)` alone is a
                            // real bug — CancellationException IS an Exception subtype in Kotlin, so a
                            // genuinely cancelled worker (ScanTracker.cancelRun) used to have its own
                            // cancellation silently swallowed here and keep looping instead of stopping.
                            val item = try {
                                kotlinx.coroutines.withTimeout(dev.jellystructure.media.pipelineStepItemDeadlineMs("scan_files")) {
                                    scanner.scanItem(jItem)?.let { artworkDownloader?.stampHasStill(it) ?: it }
                                }
                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                Logger.error("scanItem timed out for '${jItem.name}' — abandoning this item", "scan")
                                null
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e   // real outer cancellation (ScanTracker.cancelRun) — must propagate
                            } catch (e: Exception) {
                                Logger.error("scanItem failed for '${jItem.name}': ${e.message}", "scan")
                                null
                            } finally {
                                scanTracker.endItem(activeToken)
                            }
                            if (item != null) {
                                allItemsMutex.withLock { allItems += item }
                                store.addOrUpdate(item)
                                scanTracker.recordProcessed(jItem.id)
                                broadcaster.broadcast(JobEvent.ItemScanned(jobId, item))
                                succeeded.incrementAndGet()
                                // Phase 93: download any missing artwork for this item (poster/fanart, plus
                                // stills + season posters for series). Gated by fetch_images via the caller
                                // passing a non-null downloader; gap-fill only, bounded by the downloader's gate.
                                if (artworkDownloader != null && artworkDownloader.isArtworkIncomplete(item)) {
                                    val cur = store.get(item.id) ?: item
                                    runCatching { artworkDownloader.fetch(cur) }
                                        .onSuccess {
                                            // Bug fix: fetch() may just have created episode stills (TMDB or a
                                            // screengrab fallback — either is a real, valid still); Episode.hasStill
                                            // was stamped BEFORE this fetch ran (line 1984, correctly reflecting
                                            // "nothing on disk yet"), so it must be re-stamped + persisted now, or
                                            // triage/Library/Dashboard keep reporting these episodes "missing"
                                            // indefinitely (stampHasStill no-ops for movies).
                                            val restamped = artworkDownloader.stampHasStill(cur)
                                            store.updateOne(restamped)
                                            allItemsMutex.withLock {
                                                val idx = allItems.indexOfFirst { it.id == restamped.id }
                                                if (idx >= 0) allItems[idx] = restamped
                                            }
                                        }
                                        .onFailure { Logger.warn("scan artwork fetch failed for '${item.id}': ${it.message}", "artwork") }
                                }
                            }
                            // Phase 116: determinate progress — one tick per item attempted (success or
                            // failure), clamped so a mid-scan item never pushes past the precomputed total.
                            val done = processed.incrementAndGet().coerceAtMost(worklist.size)
                            broadcaster.broadcast(JobEvent.FileProgress(jobId, item?.title ?: jItem.name, done, worklist.size))
                            // Scale-down drain: exit if we are excess
                            if (scanTracker.activeWorkers.value > scanTracker.targetWorkers.value) {
                                Logger.info("Worker draining (scale-down)", "scan")
                                break
                            }
                        }
                    } finally {
                        scanTracker.activeWorkers.decrementAndGet()
                        Logger.info("Worker stopped", "scan")
                    }
                }
            }

            // Start initial workers
            repeat(scanTracker.targetWorkers.value) { launchWorker() }

            // Supervisor: polls for scale-up requests until all items are processed
            launch {
                while (!channel.isClosedForReceive || scanTracker.activeWorkers.value > 0) {
                    delay(500)
                    val newTarget = configStore.current.behavior.scanWorkers.coerceIn(1, 100)
                    if (newTarget != scanTracker.targetWorkers.value) {
                        Logger.info("Scan workers: ${scanTracker.targetWorkers.value} → $newTarget", "scan")
                        scanTracker.targetWorkers.value = newTarget
                    }
                    val active = scanTracker.activeWorkers.value
                    val target = scanTracker.targetWorkers.value
                    if (target > active && !channel.isClosedForReceive) {
                        repeat(target - active) { launchWorker() }
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.error("Scan failed: ${e.message}", "scan")
        scanTracker.cancel()
        broadcaster.broadcast(JobEvent.Finished(jobId, succeeded.value, 1))
        return emptyList()
    }

    // Phase 95: the scanner is NON-DESTRUCTIVE — it only adds/updates, never deletes. (The old
    // deleteMissing(allItems) pruned everything a scan didn't re-process — and a freshness-filtered
    // scheduled scan only re-processes a stale subset, so it wiped the library down to that subset.)
    // Instead, on a FULL scan, flag any item whose Jellyfin source is gone as `missingFromSource` so it
    // surfaces in Triage + an Activity warning, rather than vanishing. Per-library scans skip this (they
    // can't distinguish a removal from an item that lives in another library).
    if (libraryJellyfinId == null) {
        val presentJfIds = jellyfinItems.mapNotNull { it.id }.toSet()
        val newlyMissing = store.flagMissingFromSource(presentJfIds, nowEpochSec())
        for (m in newlyMissing) {
            Logger.warn("Scan: '${m.title}' is no longer in Jellyfin — kept and flagged for triage (the scanner never deletes)", "scan")
        }
        if (newlyMissing.isNotEmpty()) {
            Logger.warn("Scan: ${newlyMissing.size} item(s) missing from Jellyfin — flagged for triage, none deleted", "scan")
        }
    }

    // Phase 53-D: report Jellyfin items that were returned but produced no stored item, with reasons,
    // so silent drops (file-not-found, unmatched library, …) are visible — not just a buried per-item warn.
    // Bug fix (2026-07-03): this used to filter over ALL of jellyfinItems with no regard for the
    // freshness filter, so every item that was simply not due for a recheck yet (excluded from
    // `worklist` above, by design, on every incremental scan) landed in `skipped` too. classifySkip()
    // finds nothing actually wrong with those (valid path, library match, file exists) so it falls
    // through to "other" — which isn't in the `expected` set below — so a completely normal
    // freshness-filtered scan logged "N item(s) unexpectedly skipped" for the ENTIRE rest of the
    // library every single run. Excluding not-due items here (we already know exactly why they weren't
    // touched) makes `skipped` mean what the log claims: something that should have been processed
    // this run but wasn't.
    val notDueThisRun = if (freshnessFilter != null)
        jellyfinItems.filter { it.id !in skipIds && !freshnessFilter(it) }.map { it.id }.toSet()
        else emptySet()
    if (notDueThisRun.isNotEmpty()) Logger.info("Scan: ${notDueThisRun.size} item(s) not due for a recheck yet — skipped by the freshness filter", "scan")
    val scannedJfIds = allItems.mapNotNull { it.jellyfinId }.toSet()
    val skipped = jellyfinItems.filter { it.id !in scannedJfIds && it.id !in skipIds && it.id !in notDueThisRun }
    if (skipped.isNotEmpty()) {
        val reasons = skipped.associateWith { scanner.classifySkip(it) }
        val byReason = reasons.values.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" }
        Logger.info("Scan summary: ${allItems.size} stored, ${skipped.size} skipped ($byReason)", "scan")
        // Expected skips (out-of-scope content) vs unexpected (something the operator likely wants fixed).
        val expected = setOf("no-matching-library", "unsupported-type", "no-path")
        val unexpected = reasons.filterValues { it !in expected }
        if (unexpected.isNotEmpty()) {
            Logger.warn("Scan: ${unexpected.size} item(s) unexpectedly skipped:", "scan")
            unexpected.forEach { (j, r) -> Logger.warn("  • '${j.name}' [${j.path ?: "no path"}] — $r", "scan") }
        }
        // Phase 175 (§9) — "no-matching-library" is expected/benign (a real, common config gap: a new
        // library folder Settings hasn't been told about yet), but was previously only ever counted, not
        // named — an admin had no way to see WHICH items it applied to short of grepping raw logs. Named
        // at INFO (not WARN — it's not an error) so it's discoverable from the Activity page's own log.
        val noLibraryMatch = reasons.filterValues { it == "no-matching-library" }
        if (noLibraryMatch.isNotEmpty()) {
            Logger.info("Scan: ${noLibraryMatch.size} item(s) skipped — no configured library matches their path:", "scan")
            noLibraryMatch.forEach { (j, _) -> Logger.info("  • '${j.name}' [${j.path ?: "no path"}]", "scan") }
        }
    }

    val cancelled = scanTracker.cancelRequested
    Logger.info("Library scan ${if (cancelled) "cancelled" else "complete"} — ${succeeded.value} items", "scan")
    if (signalCompletion) {
        if (!cancelled) {
            scanTracker.complete()
            broadcaster.broadcast(JobEvent.Finished(jobId, succeeded.value, 0))
            val cfg = configStore.current
            if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()) {
                jellyfinClient.triggerLibraryRefresh(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken)
            }
            if (cfg.behavior.notifyOnScanDone)
                fireWebhook(cfg, """{"event":"scan_complete","jobId":"$jobId","items":${succeeded.value}}""")
            if (cfg.behavior.notifyOnNoMatch) {
                // Phase 168/171: a music video IS searched (Phase 171 reversed the "never" rule), but
                // a miss stays unflagged — routinely and legitimately has no TMDB entry.
                val unmatched = allItems.count { it.tmdbId == null && it.kind != dev.jellystructure.model.MediaKind.MUSIC_VIDEO }
                if (unmatched > 0)
                    fireWebhook(cfg, """{"event":"no_tmdb_match","jobId":"$jobId","unmatched":$unmatched}""")
            }
        } else {
            broadcaster.broadcast(JobEvent.Finished(jobId, succeeded.value, 0))
        }
    }
    return allItems
}

// Security fix (2026-08-02 review, finding H5) — this used to shell out via
// posixSystem("curl ... -d '$safePayload' '$url' &") with a broken single-quote escape
// (`replace("'", "\\'")` isn't a valid escape inside a POSIX single-quoted string — backslash isn't
// special there, so a `'` in the payload closes the quote early and anything after it is interpreted
// by the shell) and NO escaping at all on the admin-configured url. A media filename with an
// apostrophe (open-file paths reach this via FdWatchdog's census, crash text via CrashResilience) was
// a real path to command execution once notifications_webhook was configured. Using the existing
// OutboundHttp client removes the shell entirely — no escaping to get right because there's no shell
// to escape for.
internal suspend fun fireWebhook(cfg: dev.jellystructure.config.AppConfig, payload: String) {
    val url = cfg.behavior.notificationsWebhook
    if (url.isBlank()) return
    runCatching {
        dev.jellystructure.OutboundHttp.withPermit {
            dev.jellystructure.OutboundHttp.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }
    }.onFailure { Logger.warn("Webhook delivery failed: ${it.message}", "notify") }
    Logger.info("Webhook fired: $payload", "notify")
}

/** Tree label for an episode NFO node: "S01E03 — Title", falling back to the filename. */
private fun episodeNfoLabel(ep: Episode): String {
    fun pad2(n: Int) = if (n in 0..9) "0$n" else "$n"
    val s = ep.seasonNumber
    val e = ep.episodeNumber
    val code = if (s != null && e != null) "S${pad2(s)}E${pad2(e)}" else null
    val title = ep.title?.takeIf { it.isNotBlank() } ?: ep.filename
    return if (code != null) "$code — $title" else title
}
