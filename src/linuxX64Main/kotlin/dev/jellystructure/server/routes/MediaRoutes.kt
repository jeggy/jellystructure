package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.log.Logger
import dev.jellystructure.log.WorkerId
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.MkvpropeditRunner
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.NfoFileNode
import dev.jellystructure.model.NfoFileTree
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.nfo.NfoWriter
import dev.jellystructure.tmdb.TmdbClient
import dev.jellystructure.tmdb.TmdbImage
import dev.jellystructure.tv.RaviloConfigService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
import platform.posix.system as posixSystem
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class JellyfinLocksResponse(val lockData: Boolean, val lockedFields: List<String>)

// --- Phase 47: artwork candidate gallery DTOs ---
@Serializable
private data class ArtworkCandidate(
    val filePath: String,
    val lang: String?,       // null = no-language / textless (surfaced as "xx" in the UI)
    val voteAverage: Double,
    val width: Int,
    val height: Int,
    val onDisk: Boolean,     // best-effort: matches the file_path captured at scan time
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

// Wire shape for GET /api/media/{id}/episodes/stills — mirrors the frontend's EpisodeStillStatus.
@Serializable
private data class EpisodeStillStatusDto(val filename: String, val stillExists: Boolean, val stillPath: String)

// Wire shape for the batch fire-and-forget endpoints ("…started", item count).
@Serializable
private data class BatchStartedResponse(val status: String, val total: Int)

// Wire shape for GET /api/media/{id}/seasons — mirrors the frontend's SeasonStatus.
@Serializable
private data class SeasonStatusDto(val season: Int, val posterExists: Boolean)

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
    raviloConfigService: RaviloConfigService,
) {
    route("/media") {
        get {
            val kindStr = call.request.queryParameters["kind"]
            val kind = kindStr?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
            val filter = call.request.queryParameters["filter"]
            val search = call.request.queryParameters["search"]?.takeIf { it.isNotBlank() }
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
            val heroIds = if (heroMode != null && viewer != null)
                raviloConfigService.getConfig(viewer).heroes.map { it.itemId }.toSet() else emptySet()
            val result = store.list(kind, filter, search, sort, pageNum, pageSize, studios, networks, genres, audioLangs, trackTitle, audioCodec, untaggedAudio, tags, heroIds, heroMode)
            // Strip episode data from list responses — full episode list is on the individual item endpoint
            val stripped = result.copy(items = result.items.map { it.copy(episodes = emptyList()) })
            call.respond(stripped)
        }

        get("/meta-facets") {
            @Serializable data class FacetItem(val value: String, val count: Int)
            @Serializable data class MetaFacetsResponse(
                val studios: List<FacetItem>,
                val networks: List<FacetItem>,
                val genres: List<FacetItem>,
                val tags: List<FacetItem>,
            )
            val f = store.metaFacets()
            call.respond(MetaFacetsResponse(
                studios  = f.studios.map  { FacetItem(it.value, it.count) },
                networks = f.networks.map { FacetItem(it.value, it.count) },
                genres   = f.genres.map   { FacetItem(it.value, it.count) },
                tags     = f.tags.map     { FacetItem(it.value, it.count) },
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
                val json = Json { ignoreUnknownKeys = true }
                val reverted: dev.jellystructure.model.MediaItem = when (entry.action) {
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
                        item.copy(title = snap.title, overview = snap.overview, year = snap.year, originalTitle = snap.originalTitle, director = snap.director, studio = snap.studio, network = snap.network, tags = snap.tags, genres = if (snap.genres.isNotEmpty()) snap.genres else item.genres)
                    }
                    "language_override" -> {
                        @Serializable data class LangSnap(val language: String? = null)
                        val snap = runCatching { json.decodeFromString<LangSnap>(entry.beforeSnapshot) }.getOrNull()
                            ?: return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "corrupt snapshot"))
                        item.copy(resolvedLanguage = snap.language?.ifBlank { null })
                    }
                    else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown action type"))
                }
                store.updateOne(reverted)
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
                    NfoWriter.write(item)
                        .onSuccess { path ->
                            mediaHistory.record(id, "nfo_write", path)
                            // For TV shows, also write episodedetails.nfo for each episode
                            if (item.kind == MediaKind.TV_SHOW) {
                                var epWritten = 0
                                for (ep in item.episodes) {
                                    NfoWriter.writeEpisode(ep)
                                        .onSuccess { epWritten++ }
                                        .onFailure { Logger.warn("Episode NFO write failed for ${ep.filename}: ${it.message}") }
                                }
                                if (epWritten > 0) Logger.info("Wrote $epWritten episode NFO(s) for '$id'")
                            }
                            call.respond(mapOf("path" to path))
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
                        MediaKind.MOVIE -> item.path.substringBeforeLast('/')
                        MediaKind.TV_SHOW -> item.path
                    }
                    val testFile = "$dir/.jellystructure-write-test.tmp"
                    @Serializable data class WritableResult(val writable: Boolean, val path: String, val error: String? = null)
                    val error = runCatching {
                        val sink = SystemFileSystem.sink(Path(testFile)).buffered()
                        sink.close()
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
                    val status = artwork.fetch(item)
                    // For TV shows, also fetch episode stills
                    if (item.kind == MediaKind.TV_SHOW) {
                        var stillsFetched = 0
                        for (ep in item.episodes) {
                            if (!ep.stillPath.isNullOrBlank()) {
                                val result = artwork.fetchEpisodeStill(ep)
                                if (result.stillExists) stillsFetched++
                            }
                        }
                        if (stillsFetched > 0) Logger.info("Fetched $stillsFetched episode still(s) for '$id'")
                    }
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
                        "banner" -> "banner.jpg"
                        else -> ""
                    }
                    if (filename.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "type must be poster, backdrop, clearlogo, or banner"))
                        return@post
                    }
                    val bytes = fileBytes
                    if (bytes == null || bytes.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file data received"))
                        return@post
                    }

                    val dir = item.kind.let { if (it == MediaKind.TV_SHOW) item.path else item.path.substringBeforeLast('/') }
                    val destPath = "$dir/$filename"
                    val tmpPath = "$destPath.tmp"
                    val sink = SystemFileSystem.sink(Path(tmpPath)).buffered()
                    sink.write(bytes, 0, bytes.size)
                    sink.flush()
                    sink.close()
                    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
                    platform.posix.rename(tmpPath, destPath)
                    Logger.info("Artwork uploaded: $destPath (${bytes.size} bytes)")

                    val cfg = configStore.current
                    if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                        jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                    }

                    call.respond(artwork.check(item))
                }

                // GET /api/media/{id}/artwork/candidates?asset=poster|backdrop|clearlogo|banner
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
                        "banner" -> artwork.assetPath(item, asset)?.let { SystemFileSystem.exists(Path(it)) } ?: false
                        else -> false
                    }
                    val images = item.tmdbId?.let { tid ->
                        if (item.kind == MediaKind.MOVIE) tmdbClient.getMovieImages(tid) else tmdbClient.getTvImages(tid)
                    }
                    val list = when (asset) {
                        "poster" -> images?.posters
                        "backdrop" -> images?.backdrops
                        "clearlogo" -> images?.logos
                        else -> null  // banner: TMDB has no banner type — upload / URL only
                    } ?: emptyList()
                    val onDiskSource = when (asset) {
                        "poster" -> item.posterPath
                        "backdrop" -> item.backdropPath
                        else -> null
                    }
                    call.respond(ArtworkCandidatesResponse(asset, onDiskExists, item.resolvedLanguage, mapCandidates(list, onDiskSource)))
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
                    mediaHistory.record(id, "artwork_save", "asset=${req.asset}")
                    val cfg = configStore.current
                    if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                        jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                    }
                    call.respond(artwork.check(item))
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
                call.respond(seasons.map { SeasonStatusDto(it, artwork.checkSeasonPoster(item, it)) })
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
                    EpisodeStillStatusDto(ep.filename, status.stillExists, status.stillPath)
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
                for (ep in item.episodes) {
                    NfoWriter.writeEpisode(ep)
                        .onSuccess { written++ }
                        .onFailure { failed++ }
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
                val ep = item.episodes.firstOrNull { it.filename == epFilename }
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
                val sink = SystemFileSystem.sink(Path(tmpPath)).buffered()
                sink.write(bytes, 0, bytes.size)
                sink.flush()
                sink.close()
                @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
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
                    val ep = item.episodes.firstOrNull { it.filename == epFilename }
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val tid = item.tmdbId
                    val s = ep.seasonNumber
                    val e = ep.episodeNumber
                    val images = if (tid != null && s != null && e != null)
                        tmdbClient.getEpisodeImages(tid, s, e) else null
                    val onDisk = artwork.checkEpisodeStill(ep).stillExists
                    call.respond(ArtworkCandidatesResponse("still", onDisk, ep.resolvedLanguage, mapCandidates(images?.stills ?: emptyList(), ep.stillPath)))
                }

                // POST /api/media/{id}/episodes/{epFilename}/still/save  { source }
                post("/still/save") {
                    val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val ep = item.episodes.firstOrNull { it.filename == epFilename }
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val req = call.receive<SaveCandidateRequest>()
                    if (req.source.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "source required"))
                    val ok = artwork.saveEpisodeStill(ep, req.source)
                    if (!ok) return@post call.respond(HttpStatusCode.BadGateway, mapOf("error" to "download failed"))
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
                        val parts = sameType.map { t ->
                            val flag = if (t.streamIndex == targetTrack.streamIndex) 1 else 0
                            "--edit track:@${t.streamIndex + 1} --set flag-default=$flag"
                        }.joinToString(" \\\n  ")
                        call.respond(TrackPlan("mkvpropedit '$escaped' \\\n  $parts", "mkvpropedit", 40, specifier, beforeSnaps, afterSnaps))
                    } else {
                        call.respond(TrackPlan(FfmpegRunner.planSetDefault(ep.path, targetTrack.streamIndex, sameType.map { it.streamIndex }, targetTrack.kind), "ffmpeg", 5000, specifier, beforeSnaps, afterSnaps))
                    }
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
                    updatedEpisodes[epIdx] = ep.copy(tracks = newTracks, issueCount = newIssue)
                    // Keep item.tracks in sync with the first episode's tracks so repull language resolution is correct
                    val updatedItemTracks = if (epIdx == 0) newTracks else item.tracks
                    store.updateOne(item.copy(episodes = updatedEpisodes, tracks = updatedItemTracks))

                    // Verify the tag actually persisted (B/T-agnostic); report disk truth, not the request.
                    val probed = newTracks.firstOrNull { it.specifier == req.specifier }?.language
                    val persisted = probed != null && LanguageResolver.normalize(probed) == LanguageResolver.normalize(req.language)
                    if (!persisted) {
                        mediaHistory.record(id, "set_language", "ep=${ep.filename} specifier=${req.specifier} FAILED to persist (on disk: ${probed ?: "none"})")
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "the language tag did not persist (file shows '${probed ?: "none"}') — the container may not support per-stream language"))
                        return@post
                    }
                    mediaHistory.record(id, "set_language", "ep=${ep.filename} specifier=${req.specifier} language=$probed")
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
                    call.respond(mapOf("ok" to true))
                }

                // POST /api/media/{id}/episodes/{epFilename}/tracks/reorder — reorder tracks (ffmpeg remux)
                post("/tracks/reorder") {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.resolve(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    val epIdx = item.episodes.indexOfFirst { it.filename == epFilename }
                    if (epIdx < 0) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "episode not found"))
                    val ep = item.episodes[epIdx]

                    @Serializable data class ReorderReq(val kind: String, val order: List<String>)
                    val req = call.receive<ReorderReq>()
                    val kind = when (req.kind.lowercase()) {
                        "audio" -> TrackKind.AUDIO
                        "subtitle" -> TrackKind.SUBTITLE
                        else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "kind must be audio or subtitle"))
                    }
                    val orderedTracks = req.order.mapNotNull { spec -> ep.tracks.firstOrNull { it.specifier == spec } }
                    if (orderedTracks.size != req.order.size)
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "one or more specifiers not found"))

                    when (val guard = seedingGuard.check(ep.path, configStore.current)) {
                        is SeedingCheckResult.Blocked -> { call.respond(HttpStatusCode.Conflict, mapOf("error" to "File is seeded by '${guard.torrentName}'")); return@post }
                        is SeedingCheckResult.Unreachable -> { call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "qBittorrent unreachable: ${guard.reason}")); return@post }
                        else -> Unit
                    }

                    val ok = FfmpegRunner.reorderTracks(ep.path, kind, orderedTracks.map { it.streamIndex })
                    if (!ok) { call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "ffmpeg remux failed")); return@post }

                    val newTracks = FfprobeRunner.probe(ep.path)
                    val newIssue = newTracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                    val updatedEpisodes = item.episodes.toMutableList()
                    updatedEpisodes[epIdx] = ep.copy(tracks = newTracks, issueCount = newIssue)
                    store.updateOne(item.copy(episodes = updatedEpisodes))
                    mediaHistory.record(id, "reorder_tracks", "ep=${ep.filename} kind=${req.kind} order=${req.order.joinToString(",")}")
                    call.respond(mapOf("ok" to true))
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
            val updatedTitlesByLang = if (req.title != null && req.title.isNotBlank() && item.resolvedLanguage != null) {
                item.titlesByLang + mapOf(item.resolvedLanguage to newTitle)
            } else item.titlesByLang
            val updated = item.copy(
                title = newTitle,
                overview = if (req.overview != null) req.overview else item.overview,
                year = req.year ?: item.year,
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
            call.respond(updated)
        }

        // PATCH /api/media/{id}/tmdb-id — set or clear the TMDB id; does not trigger a resync
        patch("/{id}/tmdb-id") {
            val id = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@patch call.respond(HttpStatusCode.NotFound)
            @Serializable data class TmdbIdReq(val tmdbId: Int? = null)
            val req = call.receive<TmdbIdReq>()
            val tmdbSnap = """{"tmdbId":${item.tmdbId ?: "null"}}"""
            val updated = item.copy(tmdbId = req.tmdbId)
            store.updateOne(updated)
            mediaHistory.record(id, "set_tmdb_id", "tmdbId=${req.tmdbId}", revertable = true, beforeSnapshot = tmdbSnap)
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

        // GET /api/media/{id}/tmdb-languages — language codes TMDB has translations for
        get("/{id}/tmdb-languages") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val tmdbId = item.tmdbId
            if (tmdbId == null) {
                call.respond(emptyList<String>())
                return@get
            }
            val langs = scanner.translationLanguages(tmdbId, item.kind == MediaKind.MOVIE)
            call.respond(langs)
        }

        // GET /api/media/{id}/seeding — check if the item's file is currently seeded in qBittorrent
        get("/{id}/seeding") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val path = if (item.kind == dev.jellystructure.model.MediaKind.MOVIE) item.path else item.path
            val result = seedingGuard.check(path, configStore.current)
            @Serializable data class SeedingStatus(val status: String, val torrentName: String? = null, val detail: String? = null)
            val response = when (result) {
                is SeedingCheckResult.Unconfigured -> SeedingStatus("unconfigured")
                is SeedingCheckResult.Allowed -> SeedingStatus("allowed")
                is SeedingCheckResult.Blocked -> SeedingStatus("blocked", result.torrentName)
                is SeedingCheckResult.Unreachable -> SeedingStatus("unreachable", detail = result.reason)
            }
            call.respond(response)
        }

        // GET /api/media/{id}/drift — compare live Jellyfin metadata vs stored DB state
        get("/{id}/drift") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val jid = item.jellyfinId
            if (jid.isNullOrBlank()) {
                call.respond(emptyList<Map<String, String>>())
                return@get
            }
            val cfg = configStore.current
            if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) {
                call.respond(emptyList<Map<String, String>>())
                return@get
            }
            val jItem = jellyfinClient.getItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, jid)
            if (jItem == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "item not found in Jellyfin"))
                return@get
            }
            @Serializable data class DriftField(val field: String, val inJellyfin: String, val inDb: String)
            val drifts = buildList<DriftField> {
                val jfTitle = jItem.name
                val dbTitle = item.title
                if (jfTitle != dbTitle) add(DriftField("title", jfTitle, dbTitle))
                val jfYear = jItem.year?.toString() ?: ""
                val dbYear = item.year?.toString() ?: ""
                if (jfYear != dbYear) add(DriftField("year", jfYear, dbYear))
                val jfTmdb = jItem.providerIds?.tmdb ?: ""
                val dbTmdb = item.tmdbId?.toString() ?: ""
                if (jfTmdb != dbTmdb) add(DriftField("tmdbId", jfTmdb, dbTmdb))
            }
            call.respond(drifts)
            if (drifts.isNotEmpty()) {
                val cfg = configStore.current
                if (cfg.behavior.notifyOnDrift)
                    fireWebhook(cfg, """{"event":"drift_detected","mediaId":"$id","fields":${drifts.size}}""")
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
            val updated = scanner.rescanFromJellyfin(item)
            if (updated == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "re-pull failed — item not found in Jellyfin or config missing"))
                return@post
            }
            store.updateOne(updated)
            broadcaster.broadcast(JobEvent.ItemScanned("repull-jellyfin-$id", updated))
            mediaHistory.record(id, "repull_jellyfin", "jellyfinId=${item.jellyfinId}")
            pushToJellyfin(updated, artwork, configStore, jellyfinClient, appScope)
            call.respond(updated)
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
            }
            if (updated == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "sync failed — file not found or no TMDB match"))
                return@post
            }
            store.updateOne(updated)
            broadcaster.broadcast(JobEvent.ItemScanned("sync-$id", updated))
            mediaHistory.record(id, "sync", "kind=${item.kind.name.lowercase()} scope=${req.scope}")
            pushToJellyfin(updated, artwork, configStore, jellyfinClient, appScope)
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
            val (updatedItem, synced) = scanner.syncSeason(item, seasonNumber, probeFiles = req.scope != "season")
            store.updateOne(updatedItem)
            broadcaster.broadcast(JobEvent.ItemScanned("sync-$id-s$seasonNumber", updatedItem))
            mediaHistory.record(id, "season_sync", "season=$seasonNumber scope=${req.scope} synced=$synced")
            pushToJellyfin(updatedItem, artwork, configStore, jellyfinClient, appScope)
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

                store.updateOne(updated)
                Logger.info("Re-pulled TMDB for '$id': title='${updated.title}' tmdbId=${updated.tmdbId} episodes=${updated.episodes.size}")
                pushToJellyfin(updated, artwork, configStore, jellyfinClient, appScope)
                call.respond(updated)
            }
        }
    }

    post("/scan") {
        if (scanTracker.running) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "scan already running"))
            return@post
        }
        val libraryId = call.request.queryParameters["library"]?.takeIf { it.isNotBlank() }
        val jobId = scanTracker.startNew()
        appScope.launch { runScan(jobId, emptySet(), store, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher, libraryId) }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "started", "library" to (libraryId ?: "all")))
    }

    post("/scan/resume") {
        val currentStatus = scanTracker.status().status
        if (currentStatus != "CANCELLED") {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "no cancelled scan to resume (status=$currentStatus)"))
            return@post
        }
        val skipIds = scanTracker.processedIdsSnapshot
        val jobId = scanTracker.startResume()
        appScope.launch { runScan(jobId, skipIds, store, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher) }
        Logger.info("Scan resumed jobId=$jobId, skipping ${skipIds.size} already-processed items")
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "resumed"))
    }

    post("/scan/cancel") {
        if (!scanTracker.running) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "no scan running"))
            return@post
        }
        scanTracker.cancel()
        call.respond(mapOf("status" to "cancel requested"))
    }

    get("/scan/status") {
        call.respond(scanTracker.status())
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
                NfoWriter.write(item)
                    .onSuccess {
                        nfoOk++
                        if (item.kind == MediaKind.TV_SHOW) {
                            for (ep in item.episodes) {
                                NfoWriter.writeEpisode(ep)
                                    .onFailure { Logger.warn("batch-push: episode NFO failed for ${ep.filename}: ${it.message}") }
                            }
                        }
                    }
                    .onFailure { nfoFail++; Logger.warn("batch-push: NFO write failed for '${item.id}': ${it.message}") }
                if (!item.jellyfinId.isNullOrBlank()) {
                    val ok = jellyfinClient.refreshItem(freshCfg.apiKeys.jellyfinUrl, freshCfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)
                    if (ok) refreshOk++ else { refreshFail++; Logger.warn("batch-push: Jellyfin refresh failed for '${item.id}'") }
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
private suspend fun pushToJellyfin(
    item: MediaItem,
    artwork: ArtworkDownloader,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    appScope: CoroutineScope,
) {
    NfoWriter.write(item)
        .onSuccess { path ->
            Logger.info("pushToJellyfin: wrote NFO $path")
            if (item.kind == MediaKind.TV_SHOW) {
                var epWritten = 0
                for (ep in item.episodes) {
                    NfoWriter.writeEpisode(ep)
                        .onSuccess { epWritten++ }
                        .onFailure { Logger.warn("pushToJellyfin: episode NFO failed for ${ep.filename}: ${it.message}") }
                }
                if (epWritten > 0) Logger.info("pushToJellyfin: wrote $epWritten episode NFOs for '${item.id}'")
            }
        }
        .onFailure { Logger.warn("pushToJellyfin: NFO write failed for '${item.id}': ${it.message}") }

    appScope.launch { artwork.fetch(item) }

    val cfg = configStore.current
    if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) return

    if (!item.jellyfinId.isNullOrBlank()) {
        val ok = jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId, full = true)
        if (!ok) Logger.warn("pushToJellyfin: Jellyfin refresh failed for '${item.id}' (jellyfinId=${item.jellyfinId})")
    } else {
        Logger.warn("pushToJellyfin: no jellyfinId for '${item.id}' — skipping per-item Jellyfin refresh")
    }

    // For TV shows, also trigger a library scan so Jellyfin reliably re-reads tvshow.nfo from disk.
    // Per-item FullRefresh alone does not consistently pick up tvshow.nfo changes in Jellyfin.
    if (item.kind == MediaKind.TV_SHOW) {
        jellyfinClient.triggerLibraryRefresh(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken)
        Logger.info("pushToJellyfin: triggered library scan to pick up tvshow.nfo for '${item.id}'")
    }
}

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
) {
    val allItems = mutableListOf<MediaItem>()
    val allItemsMutex = Mutex()
    val succeeded = AtomicInt(0)
    val nextWorkerId = AtomicInt(0)

    Logger.info("Library scan started jobId=$jobId (skip=${skipIds.size}${if (libraryJellyfinId != null) " library=$libraryJellyfinId" else ""})", "scan")
    broadcaster.broadcast(JobEvent.Started(jobId, -1))

    val jellyfinItems = if (libraryJellyfinId != null) scanner.fetchItemsForLibrary(libraryJellyfinId) else scanner.fetchItems()
    if (jellyfinItems == null) {
        scanTracker.complete()
        broadcaster.broadcast(JobEvent.Finished(jobId, 0, 0))
        return
    }
    Logger.info("Jellyfin returned ${jellyfinItems.size} items (${skipIds.size} will be skipped for resume)", "scan")

    // coroutineScope suspends here until the producer, all workers, and the supervisor have ALL finished.
    // Post-scan cleanup runs only after this block returns.
    try {
        coroutineScope {
            val channel = Channel<JellyfinItem>(Channel.UNLIMITED)

            scanTracker.targetWorkers.value = configStore.current.behavior.scanWorkers.coerceIn(1, 32)

            // Producer: fills the channel, skipping already-processed IDs
            launch {
                for (jItem in jellyfinItems) {
                    if (scanTracker.cancelRequested) break
                    if (jItem.id in skipIds) {
                        Logger.info("Resume: skipping '${jItem.name}'")
                        continue
                    }
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
                            val item = try { scanner.scanItem(jItem) } catch (e: Exception) {
                                Logger.error("scanItem failed for '${jItem.name}': ${e.message}", "scan")
                                null
                            }
                            if (item != null) {
                                allItemsMutex.withLock { allItems += item }
                                store.addOrUpdate(item)
                                jItem.id?.let { scanTracker.recordProcessed(it) }
                                broadcaster.broadcast(JobEvent.ItemScanned(jobId, item))
                                succeeded.incrementAndGet()
                            }
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
                    val newTarget = configStore.current.behavior.scanWorkers.coerceIn(1, 32)
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
        return
    }

    // Post-scan cleanup — runs only after all workers have finished
    store.update(allItems)
    val cancelled = scanTracker.cancelRequested
    Logger.info("Library scan ${if (cancelled) "cancelled" else "complete"} — ${succeeded.value} items", "scan")
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
            val unmatched = allItems.count { it.tmdbId == null }
            if (unmatched > 0)
                fireWebhook(cfg, """{"event":"no_tmdb_match","jobId":"$jobId","unmatched":$unmatched}""")
        }
    } else {
        broadcaster.broadcast(JobEvent.Finished(jobId, succeeded.value, 0))
    }
}

@OptIn(ExperimentalForeignApi::class)
internal suspend fun fireWebhook(cfg: dev.jellystructure.config.AppConfig, payload: String) {
    val url = cfg.behavior.notificationsWebhook
    if (url.isBlank()) return
    val safePayload = payload.replace("'", "\\'")
    runCatching { posixSystem("""curl -sf --max-time 10 -X POST -H 'Content-Type: application/json' -d '$safePayload' '$url' &""") }
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
