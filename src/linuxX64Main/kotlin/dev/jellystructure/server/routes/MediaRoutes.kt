package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.MkvpropeditRunner
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
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
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
            val result = store.list(kind, filter, search, sort, pageNum, pageSize)
            // Strip episode data from list responses — full episode list is on the individual item endpoint
            val stripped = result.copy(items = result.items.map { it.copy(episodes = emptyList()) })
            call.respond(stripped)
        }

        route("/{id}") {
            get {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val item = store.get(id)
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
                    val item = store.get(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val raw = NfoWriter.readRaw(item)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondText(raw, ContentType.Text.Xml)
                }

                post {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.get(id)
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
                                        .onFailure { println("[WARN] Episode NFO write failed for ${ep.filename}: ${it.message}") }
                                }
                                if (epWritten > 0) println("[INFO] Wrote $epWritten episode NFO(s) for '$id'")
                            }
                            val cfg = configStore.current
                            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                            }
                            call.respond(mapOf("path" to path))
                        }
                        .onFailure { e ->
                            println("[ERROR] NFO write failed for $id: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "write failed")))
                        }
                }
            }

            route("/artwork") {
                get {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.get(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(artwork.check(item))
                }

                post {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.get(id)
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
                        if (stillsFetched > 0) println("[INFO] Fetched $stillsFetched episode still(s) for '$id'")
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
                    val item = store.get(id)
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
                    println("[INFO] Artwork uploaded: $destPath (${bytes.size} bytes)")

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
                val item = store.get(id)
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
                val item = store.get(id)
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
                val item = store.get(id)
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
                val item = store.get(id)
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
                println("[INFO] Episode still uploaded: $destPath (${bytes.size} bytes)")

                val cfg = configStore.current
                if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                    jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                }
                call.respond(mapOf("ok" to true, "path" to destPath))
            }

            // Episode track routes — {epFilename} identifies the episode by filename
            route("/{epFilename}") {
                // GET /api/media/{id}/episodes/{epFilename}/tracks/plan?specifier=...
                get("/tracks/plan") {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.get(id)
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
                    val item = store.get(id)
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
                    val item = store.get(id)
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
                    store.updateOne(item.copy(episodes = updatedEpisodes))
                    mediaHistory.record(id, "set_language", "ep=${ep.filename} specifier=${req.specifier} lang=${req.language}")
                    call.respond(mapOf("ok" to true, "language" to req.language))
                }

                // PATCH /api/media/{id}/episodes/{epFilename}/metadata — edit episode title/overview
                patch("/metadata") {
                    val id = call.parameters["id"]
                        ?: return@patch call.respond(HttpStatusCode.BadRequest)
                    val epFilename = call.parameters["epFilename"]
                        ?: return@patch call.respond(HttpStatusCode.BadRequest)
                    val item = store.get(id)
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
            val item = store.get(id)
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
            val item = store.get(id)
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

        // POST /api/media/{id}/repull — re-fetch TMDB metadata without re-probing the file
        route("/{id}/repull") {
            post {
                val id = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val item = store.get(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val updated = scanner.rescanMetadata(item)
                if (updated == null) {
                    println("[WARN] Re-pull TMDB failed for $id (no TMDB match or API error)")
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "TMDB fetch failed — no match found"))
                    return@post
                }

                store.updateOne(updated)
                println("[INFO] Re-pulled TMDB for '$id': title='${updated.title}' tmdbId=${updated.tmdbId}")

                val cfg = configStore.current
                if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                    jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
                }

                call.respond(updated)
            }
        }
    }

    post("/scan") {
        if (scanTracker.running) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "scan already running"))
            return@post
        }
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        val jobId = "scan-${platform.posix.time(null)}"
        appScope.launch {
            scanTracker.running = true
            scanTracker.reset()
            val allItems = mutableListOf<dev.jellystructure.model.MediaItem>()
            var succeeded = 0
            try {
                println("[INFO] Library scan started (background) jobId=$jobId")
                broadcaster.broadcast(JobEvent.Started(jobId, -1))
                scanner.scan(tracker = scanTracker) { item ->
                    allItems += item
                    store.addOrUpdate(item)
                    succeeded++
                    scanTracker.lastCount = succeeded
                    broadcaster.broadcast(JobEvent.ItemScanned(jobId, item))
                }
                store.update(allItems)
                scanTracker.lastCount = succeeded
                val cancelled = scanTracker.cancelRequested
                println("[INFO] Library scan ${if (cancelled) "cancelled" else "complete"} — $succeeded items")
                broadcaster.broadcast(JobEvent.Finished(jobId, succeeded, 0))

                if (!cancelled) {
                    val cfg = configStore.current
                    if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank()) {
                        jellyfinClient.triggerLibraryRefresh(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken)
                    }
                }
            } catch (e: Exception) {
                println("[ERROR] Scan failed: ${e.message}")
                broadcaster.broadcast(JobEvent.Finished(jobId, succeeded, 1))
            } finally {
                scanTracker.running = false
            }
        }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "started"))
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
}
