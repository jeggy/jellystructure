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
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind
import dev.jellystructure.nfo.NfoWriter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
import kotlinx.serialization.Serializable


fun Route.mediaRoutes(
    store: MediaStore,
    scanner: Scanner,
    artwork: ArtworkDownloader,
    appScope: CoroutineScope,
    scanTracker: ScanTracker,
    broadcaster: WsBroadcaster,
    jellyfinClient: JellyfinClient,
    configStore: ConfigStore,
    mediaHistory: MediaHistory,
    scanDispatcher: CoroutineDispatcher,
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
            val studio = call.request.queryParameters["studio"]?.takeIf { it.isNotBlank() }
            val network = call.request.queryParameters["network"]?.takeIf { it.isNotBlank() }
            val genre = call.request.queryParameters["genre"]?.takeIf { it.isNotBlank() }
            val result = store.list(kind, filter, search, sort, pageNum, pageSize, studio, network, genre)
            // Strip episode data from list responses — full episode list is on the individual item endpoint
            val stripped = result.copy(items = result.items.map { it.copy(episodes = emptyList()) })
            call.respond(stripped)
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

                    if (type !in setOf("poster", "fanart", "logo")) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "type must be poster, fanart, or logo"))
                        return@post
                    }
                    val bytes = fileBytes
                    if (bytes == null || bytes.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file data received"))
                        return@post
                    }

                    val dir = item.path.substringBeforeLast('/')
                    val filename = when (type) {
                        "poster" -> "poster.jpg"
                        "fanart" -> "fanart.jpg"
                        else -> "clearlogo.png"
                    }
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
                    mapOf("filename" to ep.filename, "stillExists" to status.stillExists, "stillPath" to status.stillPath)
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
                    val targetTrack = ep.tracks.firstOrNull { it.specifier == req.specifier }
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))
                    val ext = ep.path.substringAfterLast('.').lowercase()

                    val ok = if (ext == "mkv") MkvpropeditRunner.setLanguage(ep.path, targetTrack.streamIndex, req.language)
                             else FfmpegRunner.setLanguage(ep.path, targetTrack.streamIndex, req.language)

                    if (!ok) { call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "tool failed")); return@post }

                    val newTracks = FfprobeRunner.probe(ep.path)
                    val newIssue = newTracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
                    val updatedEpisodes = item.episodes.toMutableList()
                    updatedEpisodes[epIdx] = ep.copy(tracks = newTracks, issueCount = newIssue)
                    // Keep item.tracks in sync with the first episode's tracks so repull language resolution is correct
                    val updatedItemTracks = if (epIdx == 0) newTracks else item.tracks
                    store.updateOne(item.copy(episodes = updatedEpisodes, tracks = updatedItemTracks))
                    mediaHistory.record(id, "set_language", "ep=${ep.filename} specifier=${req.specifier} lang=${req.language}")
                    call.respond(mapOf("language" to req.language))
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
            val updated = item.copy(resolvedLanguage = lang)
            store.updateOne(updated)
            mediaHistory.record(id, "language_override", lang)
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
                val director: String? = null,
                val studio: String? = null,
                val network: String? = null,
            )
            val req = call.receive<MetadataEditReq>()
            val updated = item.copy(
                title = req.title?.takeIf { it.isNotBlank() } ?: item.title,
                overview = if (req.overview != null) req.overview else item.overview,
                year = req.year ?: item.year,
                originalTitle = if (req.originalTitle != null) req.originalTitle.ifBlank { null } else item.originalTitle,
                tags = req.tags ?: item.tags,
                director = if (req.director != null) req.director.ifBlank { null } else item.director,
                studio = if (req.studio != null) req.studio.ifBlank { null } else item.studio,
                network = if (req.network != null) req.network.ifBlank { null } else item.network,
            )
            store.updateOne(updated)
            mediaHistory.record(id, "metadata_edit", "title=${updated.title}")
            call.respond(updated)
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
        val jobId = scanTracker.startNew()
        appScope.launch { runScan(jobId, emptySet(), store, scanner, scanTracker, broadcaster, configStore, jellyfinClient, scanDispatcher) }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "started"))
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
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "artwork fetch started", "total" to items.size))
    }

    // POST /api/media/batch/jellyfin-push — write NFOs for all items and refresh each in Jellyfin
    post("/media/batch/jellyfin-push") {
        val cfg = configStore.current
        if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Jellyfin not configured"))
            return@post
        }
        val items = store.allItems()
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "push started", "total" to items.size))
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

private suspend fun runScan(
    jobId: String,
    skipIds: Set<String>,
    store: MediaStore,
    scanner: Scanner,
    scanTracker: ScanTracker,
    broadcaster: WsBroadcaster,
    configStore: ConfigStore,
    jellyfinClient: JellyfinClient,
    scanDispatcher: CoroutineDispatcher,
) {
    val allItems = mutableListOf<MediaItem>()
    val allItemsMutex = Mutex()
    val succeeded = AtomicInt(0)
    val nextWorkerId = AtomicInt(0)

    Logger.info("Library scan started jobId=$jobId (skip=${skipIds.size})", "scan")
    broadcaster.broadcast(JobEvent.Started(jobId, -1))

    val jellyfinItems = scanner.fetchItems()
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
    } else {
        broadcaster.broadcast(JobEvent.Finished(jobId, succeeded.value, 0))
    }
}
