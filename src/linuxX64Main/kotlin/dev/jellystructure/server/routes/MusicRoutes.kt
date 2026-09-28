package dev.jellystructure.server.routes

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MusicPipeline
import dev.jellystructure.io.FileIo
import dev.jellystructure.model.MusicArtUseRequest
import dev.jellystructure.model.MusicArtistArtworkDto
import dev.jellystructure.model.MusicArtworkDto
import dev.jellystructure.model.MusicBiographyRequest
import dev.jellystructure.model.MusicCandidateDto
import dev.jellystructure.model.MusicGenresRequest
import dev.jellystructure.model.MusicNfoDto
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.readRemaining
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import dev.jellystructure.model.MusicLockRequest
import dev.jellystructure.model.MusicMatchRequest
import dev.jellystructure.model.MusicProvidersDto
import dev.jellystructure.model.MusicProvidersUpdate
import dev.jellystructure.model.MusicRecordingRequest
import dev.jellystructure.model.MusicSearchRequest
import dev.jellystructure.model.MusicStatusDto
import dev.jellystructure.model.MusicUseRequest
import dev.jellystructure.model.MusicAlbumPageDto
import dev.jellystructure.model.MusicArtistPageDto
import dev.jellystructure.model.MusicBrowseDto
import dev.jellystructure.model.MusicBulkRequest
import dev.jellystructure.model.MusicBulkResult
import dev.jellystructure.model.MusicConvertPlan
import dev.jellystructure.model.MusicConvertRequest
import dev.jellystructure.model.MusicLibraryInfo
import dev.jellystructure.model.MusicStreamDto
import dev.jellystructure.model.MusicVideoRow
import dev.jellystructure.model.effectiveGenres
import dev.jellystructure.music.MusicBrowse
import dev.jellystructure.music.MusicScanner
import dev.jellystructure.music.MusicScoring
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun fileSize(path: String): Long? = runCatching { SystemFileSystem.metadataOrNull(Path(path))?.size }.getOrNull()

/** The `file` part of a multipart upload (the films' upload shape). */
internal suspend fun receiveImage(call: ApplicationCall): ByteArray? {
    var bytes: ByteArray? = null
    call.receiveMultipart().forEachPart { part ->
        if (part is PartData.FileItem && part.name == "file") bytes = part.provider().readRemaining().readByteArray()
        part.release()
    }
    return bytes?.takeIf { it.isNotEmpty() }
}

/** A music image file, typed by its own bytes; 404 when there is none. Cached briefly — a replaced cover shows after a reload. */
internal suspend fun serveFile(call: ApplicationCall, path: String?) {
    val bytes = path?.let { runCatching { FileIo.readBytes(Path(it)) }.getOrNull() } ?: return call.respond(HttpStatusCode.NotFound)
    val type = when (dev.jellystructure.media.sniffImageSignature(bytes)) {
        dev.jellystructure.media.SniffedImage.PNG -> ContentType.Image.PNG
        dev.jellystructure.media.SniffedImage.GIF -> ContentType.Image.GIF
        dev.jellystructure.media.SniffedImage.WEBP -> ContentType("image", "webp")
        dev.jellystructure.media.SniffedImage.SVG -> ContentType.Image.SVG
        else -> ContentType.Image.JPEG
    }
    call.response.headers.append("Cache-Control", "private, max-age=60")
    call.respondBytes(bytes, type)
}

/**
 * Phase 276 — the admin's MusicBrainz surface: *Match now*, Find match… (search, releases, identify by sound, use),
 * lock / clear, genre ticks, *Match this track…*, and the providers card's own save (FR-276-8/9).
 */
fun Route.musicRoutes(configStore: ConfigStore, music: MusicPipeline, appScope: CoroutineScope, jobs: dev.jellystructure.media.MediaJobQueue? = null, jellyfinClient: dev.jellystructure.auth.JellyfinClient? = null, videos: suspend () -> Collection<dev.jellystructure.model.MediaItem> = { emptyList() }) {
    val matcher = music.matcher
    val noAnswer = mapOf("error" to "MusicBrainz didn't answer — try again in a moment")

    route("/music") {
        get("/status") {
            val cfg = configStore.current
            val mapped = MusicScanner.musicLibraries(cfg).isNotEmpty()
            call.respond(MusicStatusDto(mapped, if (mapped) music.store.health() else null, matcher.status, cfg.musicbrainz.enabled, matcher.acoustId.available))
        }

        // *Match now* — a whole pass or a selection; answered at once, progress on /music/status.
        post("/match") {
            val req = runCatching { call.receive<MusicMatchRequest>() }.getOrDefault(MusicMatchRequest())
            if (matcher.status.running) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "A matching pass is already running"))
            appScope.launch {
                runCatching { matcher.matchAlbums(req.albumIds, scopeAll = req.scope == "all") }
                    .onFailure { Logger.warn("Music match pass failed: ${it.message}", "music") }
            }
            call.respond(HttpStatusCode.Accepted, mapOf("started" to true))
        }

        route("/album/{id}") {
            post("/search") {
                val id = call.parameters["id"]!!
                val req = runCatching { call.receive<MusicSearchRequest>() }.getOrDefault(MusicSearchRequest())
                val cands = matcher.search(id, req.query, req.url) ?: return@post call.respond(HttpStatusCode.BadGateway, noAnswer)
                call.respond(cands.map { MusicCandidateDto(it, MusicScoring.fitSentence(it), it.agreeing > 0) })
            }
            get("/releases") {
                val id = call.parameters["id"]!!
                val rg = call.request.queryParameters["rg"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                call.respond(matcher.releases(id, rg) ?: return@get call.respond(HttpStatusCode.BadGateway, noAnswer))
            }
            post("/identify") {
                call.respond(matcher.identifyBySound(call.parameters["id"]!!))
            }
            post("/use") {
                val id = call.parameters["id"]!!
                val req = call.receive<MusicUseRequest>()
                matcher.applyMatch(id, req.releaseGroup, req.release, req.source, req.lock)
                    ?: return@post call.respond(HttpStatusCode.BadGateway, noAnswer)
                call.respond(music.store.album(id) ?: return@post call.respond(HttpStatusCode.NotFound))
            }
            post("/lock") {
                val req = call.receive<MusicLockRequest>()
                call.respond(matcher.setLocked(call.parameters["id"]!!, req.locked) ?: return@post call.respond(HttpStatusCode.NotFound))
            }
            post("/clear") {
                call.respond(matcher.clear(call.parameters["id"]!!) ?: return@post call.respond(HttpStatusCode.NotFound))
            }
            put("/genres") {
                val req = call.receive<MusicGenresRequest>()
                call.respond(matcher.setGenres(call.parameters["id"]!!, req.genres) ?: return@put call.respond(HttpStatusCode.NotFound))
            }
        }

        route("/track/{id}") {
            get("/recordings") {
                call.respond(matcher.recordingsFor(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.BadGateway, noAnswer))
            }
            post("/recording") {
                val req = call.receive<MusicRecordingRequest>()
                call.respond(matcher.useRecording(call.parameters["id"]!!, req.recording) ?: return@post call.respond(HttpStatusCode.NotFound))
            }
        }

        // ── Phase 277: artwork, biography, NFO, lyrics ──
        val media = music.media

        get("/album/{id}/artwork") {
            val a = music.store.album(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val cands = media.albumCandidates(a.id) ?: return@get call.respond(HttpStatusCode.BadGateway, mapOf("error" to "The Cover Art Archive didn't answer"))
            val inUse = media.existingCover(a)
            call.respond(MusicArtworkDto(
                inUse = inUse?.substringAfterLast('/'), inUseBytes = inUse?.let { fileSize(it) }, locked = media.isLocked(media.coverPath(a)),
                source = a.coverSource, candidates = cands,
            ))
        }
        post("/album/{id}/artwork/use") {
            val req = call.receive<MusicArtUseRequest>()
            val ok = media.useAlbumCover(call.parameters["id"]!!, req.url, req.source)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, mapOf("ok" to ok))
        }
        post("/album/{id}/artwork/upload") {
            val bytes = receiveImage(call) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file"))
            val ok = media.uploadAlbumCover(call.parameters["id"]!!, bytes)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, mapOf("ok" to ok))
        }
        post("/album/{id}/artwork/clear") { call.respond(mapOf("ok" to media.clearAlbumCover(call.parameters["id"]!!))) }
        post("/album/{id}/artwork/lock") {
            val a = music.store.album(call.parameters["id"]!!) ?: return@post call.respond(HttpStatusCode.NotFound)
            media.setLock(media.coverPath(a), call.receive<MusicLockRequest>().locked)
            call.respond(mapOf("ok" to true))
        }

        get("/artist/{id}/artwork") {
            val a = music.store.artist(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val cands = media.artistCandidates(a.id) ?: emptyList()
            call.respond(MusicArtistArtworkDto(
                thumb = media.existingArtistImage(a, "thumb")?.substringAfterLast('/'),
                background = media.existingArtistImage(a, "background")?.substringAfterLast('/'),
                logo = media.existingArtistImage(a, "logo")?.substringAfterLast('/'),
                locked = listOf("thumb", "background", "logo").filter { media.isLocked(media.artistImagePath(a, it)) },
                credit = a.imageCredit, candidates = cands, fanartKey = media.fanart.available,
            ))
        }
        post("/artist/{id}/artwork/use") {
            val req = call.receive<MusicArtUseRequest>()
            val ok = media.useArtistImage(call.parameters["id"]!!, req.kind, req.url, req.source, req.credit)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, mapOf("ok" to ok))
        }
        post("/artist/{id}/artwork/upload") {
            val kind = call.request.queryParameters["kind"] ?: "thumb"
            val bytes = receiveImage(call) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file"))
            val ok = media.uploadArtistImage(call.parameters["id"]!!, kind, bytes)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, mapOf("ok" to ok))
        }
        post("/artist/{id}/artwork/clear") {
            val kind = call.request.queryParameters["kind"] ?: "thumb"
            call.respond(mapOf("ok" to media.clearArtistImage(call.parameters["id"]!!, kind)))
        }
        post("/artist/{id}/artwork/lock") {
            val a = music.store.artist(call.parameters["id"]!!) ?: return@post call.respond(HttpStatusCode.NotFound)
            val kind = call.request.queryParameters["kind"] ?: "thumb"
            media.setLock(media.artistImagePath(a, kind), call.receive<MusicLockRequest>().locked)
            call.respond(mapOf("ok" to true))
        }
        put("/artist/{id}/biography") {
            val req = call.receive<MusicBiographyRequest>()
            call.respond(media.setBiography(call.parameters["id"]!!, req.text) ?: return@put call.respond(HttpStatusCode.NotFound))
        }

        // The image files themselves (the admin's grids and pages; 279 serves the phone its own way).
        get("/image/album/{id}") {
            val a = music.store.album(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val file = media.existingCover(a)
            // Phase 278 — no cover file, but Jellyfin has art (embedded in a track): show Jellyfin's.
            if (file == null && a.coverState == dev.jellystructure.model.MusicArt.JELLYFIN && jellyfinClient != null) {
                val cfg = configStore.current
                val img = jellyfinClient.getItemPrimaryImage(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, a.id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.headers.append("Cache-Control", "private, max-age=60")
                return@get call.respondBytes(img.first, ContentType.parse(img.second))
            }
            serveFile(call, file)
        }
        get("/image/artist/{id}/{kind}") {
            val a = music.store.artist(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val kind = call.parameters["kind"]!!
            val file = media.existingArtistImage(a, kind)
            if (file == null && kind == "thumb" && a.imageState == dev.jellystructure.model.MusicArt.JELLYFIN && jellyfinClient != null) {
                val cfg = configStore.current
                val img = jellyfinClient.getItemPrimaryImage(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, a.id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.headers.append("Cache-Control", "private, max-age=60")
                return@get call.respondBytes(img.first, ContentType.parse(img.second))
            }
            serveFile(call, file)
        }

        get("/album/{id}/nfo") {
            val a = music.store.album(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val path = dev.jellystructure.music.MusicNfo.albumPath(a)
            call.respond(MusicNfoDto(path, path?.let { dev.jellystructure.music.MusicNfo.read(it) }, media.albumDrift(a)))
        }
        get("/artist/{id}/nfo") {
            val a = music.store.artist(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val path = dev.jellystructure.music.MusicNfo.artistPath(a)
            call.respond(MusicNfoDto(path, path?.let { dev.jellystructure.music.MusicNfo.read(it) }, null))
        }
        // The split button: Save → NFO · Sync Jellyfin · Save & Sync. Save overwrites — the admin asked.
        post("/album/{id}/save") {
            val sync = call.request.queryParameters["sync"] == "1"
            val outcome = media.saveAlbum(call.parameters["id"]!!, sync) ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(mapOf("outcome" to outcome.name.lowercase()))
        }
        post("/album/{id}/sync") { media.syncAlbum(call.parameters["id"]!!); call.respond(mapOf("ok" to true)) }
        post("/artist/{id}/save") {
            val sync = call.request.queryParameters["sync"] == "1"
            val outcome = media.saveArtist(call.parameters["id"]!!, sync) ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(mapOf("outcome" to outcome.name.lowercase()))
        }
        post("/album/{id}/lyrics") {
            val id = call.parameters["id"]!!
            call.respond(mapOf("result" to media.fetchLyrics(scopeAll = false, albumIds = setOf(id), force = true).sentence()))
        }

        // ── Phase 278: the admin's music pages ──

        /** The Music kind (FR-278-1..4). Facets ride `f.<key>=a,b`; everything is counted here. */
        get("/browse") {
            val cfg = configStore.current
            val qp = call.request.queryParameters
            val view = qp["view"]?.takeIf { it in MusicBrowse.VIEWS } ?: MusicBrowse.ALBUMS
            val selected = MusicBrowse.FACETS.mapNotNull { (k, _) -> qp["f.$k"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.let { k to it } }.toMap()
            val libs = MusicScanner.musicLibraries(cfg)
            val snap = music.store.snapshot()
            val r = MusicBrowse.browse(snap, view, selected, qp["q"], libs.associate { it.jellyfinId to it.name.ifBlank { it.jellyfinId } }, qp["sort"])
            call.respond(MusicBrowseDto(
                mapped = libs.isNotEmpty(), scanned = music.scanner.lastScanAt != null || snap.albums.isNotEmpty() || snap.tracks.isNotEmpty(),
                health = if (libs.isNotEmpty()) music.store.health() else null, match = matcher.status,
                libraries = libs.map { MusicLibraryInfo(it.jellyfinId, it.name, it.jellyfinPath, it.localPath) },
                view = view, total = r.rowsTotal, facets = r.facets, albums = r.albums, artists = r.artists, songs = r.songs,
                musicbrainzEnabled = cfg.musicbrainz.enabled,
            ))
        }

        get("/genres") { call.respond(MusicBrowse.genres(music.store.snapshot())) }

        get("/album/{id}/page") {
            val cfg = configStore.current
            val snap = music.store.snapshot()
            val a = snap.albums[call.parameters["id"]!!] ?: return@get call.respond(HttpStatusCode.NotFound)
            val tracks = snap.tracksByAlbum[a.id].orEmpty().filter { it.missingSince == null }
            val lib = cfg.libraries.firstOrNull { it.jellyfinId == a.libraryId }
            call.respond(MusicAlbumPageDto(
                album = a, tracks = MusicBrowse.trackRows(tracks) { media.lyricsStateOf(it) },
                genres = a.effectiveGenres(),
                coverUrl = if (a.coverState != dev.jellystructure.model.MusicArt.NONE) "/api/music/image/album/${a.id}?v=${a.updatedAt}" else null,
                drift = media.albumDrift(a), lyricsEnabled = cfg.music.fetchLyrics, acoustId = matcher.acoustId.available,
                jellyfinUrl = jellyfinWebUrl(cfg, a.id), library = lib?.name, jellyfinLocked = a.jellyfinLocked,
                type = MusicBrowse.albumType(a),
            ))
        }

        get("/artist/{id}/page") {
            val cfg = configStore.current
            val snap = music.store.snapshot()
            val r = snap.artists[call.parameters["id"]!!] ?: return@get call.respond(HttpStatusCode.NotFound)
            val browse = MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap(), "year")
            val mine = snap.albumsByArtist[r.id].orEmpty().filter { it.missingSince == null }
            val own = mine.filter { a -> a.albumArtists.any { it.artistId == r.id } }.map { it.id }.toSet()
            val credited = mine.map { it.id }.toSet() - own
            val rows = browse.albums
            val genres = r.mbGenres.ifEmpty {
                mine.filter { it.id in own }.flatMap { it.mbGenres }.groupBy { it.name }
                    .map { (n, v) -> dev.jellystructure.model.MusicGenreVote(n, v.sumOf { it.count }) }.sortedByDescending { it.count }
            }
            val videos = dev.jellystructure.music.MusicVideoLinks.forArtist(r, videos())
            call.respond(MusicArtistPageDto(
                artist = r, albums = rows.filter { it.id in own }.sortedBy { it.year ?: 0 },
                creditedOn = rows.filter { it.id in credited }, songs = MusicBrowse.browse(snap, MusicBrowse.ARTISTS, emptyMap(), null, emptyMap())
                    .artists.firstOrNull { it.id == r.id }?.songs ?: 0,
                pictureUrl = if (r.imageState != dev.jellystructure.model.MusicArt.NONE) "/api/music/image/artist/${r.id}/thumb?v=${r.updatedAt}" else null,
                videos = videos.map { MusicVideoRow(it.id, it.title, it.year, it.runtime?.let { m -> m * 60 }) },
                genres = genres, jellyfinUrl = jellyfinWebUrl(cfg, r.id),
                biography = r.biographyEdited ?: r.biographies["en"] ?: r.biographies.values.firstOrNull(),
            ))
        }

        post("/artist/{id}/lock") {
            val req = call.receive<MusicLockRequest>()
            call.respond(matcher.setArtistLocked(call.parameters["id"]!!, req.locked) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/artist/{id}/clear") {
            call.respond(matcher.clearArtist(call.parameters["id"]!!) ?: return@post call.respond(HttpStatusCode.NotFound))
        }

        /** The selection bar (FR-278-3). Match now runs in the background (progress on /music/status); the rest
         *  answer when done — a household selection is a handful of albums. */
        post("/bulk") {
            val req = call.receive<MusicBulkRequest>()
            val ids = req.albumIds.distinct()
            val n = ids.size
            fun albums(k: Int) = "$k album${if (k == 1) "" else "s"}"
            val sentence = when (req.action) {
                "match" -> {
                    if (matcher.status.running) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "A matching pass is already running"))
                    appScope.launch { runCatching { matcher.matchAlbums(ids, scopeAll = true) }.onFailure { Logger.warn("Music bulk match failed: ${it.message}", "music") } }
                    "Matching ${albums(n)} against MusicBrainz — one request a second"
                }
                "covers" -> media.fetchArtworkFor(ids.toSet()).sentence()
                "nfo" -> {
                    val done = ids.count { media.saveAlbum(it, sync = true) == dev.jellystructure.music.MusicNfo.Outcome.WRITTEN }
                    "album.nfo written for ${albums(done)}" + if (done < n) " · ${n - done} not matched yet, so not written" else ""
                }
                "lock" -> { ids.forEach { matcher.setLocked(it, true) }; "${albums(n)} locked — runs leave them alone" }
                "unlock" -> { ids.forEach { matcher.setLocked(it, false) }; "${albums(n)} unlocked" }
                "clear" -> { ids.forEach { matcher.clear(it) }; "Match cleared on ${albums(n)} — locked so no run re-matches them" }
                else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown action"))
            }
            call.respond(MusicBulkResult(sentence))
        }

        /** FR-278-7 — what Convert… would do, then the job itself. */
        post("/convert/plan") {
            val conv = music.convert ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(conv.plan(runCatching { call.receive<MusicConvertRequest>() }.getOrDefault(MusicConvertRequest())))
        }
        post("/convert") {
            val conv = music.convert ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val q = jobs ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val req = runCatching { call.receive<MusicConvertRequest>() }.getOrDefault(MusicConvertRequest())
            val targets = conv.targets(req)
            if (targets.isEmpty()) return@post call.respond(MusicConvertPlan(0))
            val owner = req.albumId ?: targets.first().albumId ?: "music"
            val label = "Convert for phones: ${targets.size} song${if (targets.size == 1) "" else "s"}" +
                (req.albumId?.let { id -> music.store.album(id)?.title?.let { " · $it" } } ?: "")
            val snap = q.enqueue("convert_audio", owner, label, dev.jellystructure.jobs.MediaJobParams(repairPaths = targets.mapNotNull { it.path }), fileCount = targets.size)
            call.respond(MusicConvertPlan(songs = targets.size, job = snap.id))
        }

        /** The Tracks tab's ▶ — your own Jellyfin session, direct play or nothing (the segment editor's rule). */
        get("/track/{id}/stream") {
            val session = runCatching { call.attributes[dev.jellystructure.auth.SessionKey] }.getOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val t = music.store.track(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!MusicBrowse.browserPlays(t)) return@get call.respond(HttpStatusCode.Conflict, mapOf("error" to "A browser can't direct-play this format"))
            val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            call.respond(MusicStreamDto(dev.jellystructure.auth.withJellyfinToken("$base/Audio/${t.id}/stream?Static=true&DeviceId=jellystructure-admin-music", session.jellyfinUserToken)))
        }

        get("/providers") {
            val cfg = configStore.current
            call.respond(MusicProvidersDto(
                musicbrainzEnabled = cfg.musicbrainz.enabled, musicbrainzContact = cfg.musicbrainz.contact,
                musicbrainzContactEffective = cfg.musicbrainz.effectiveContact, musicbrainzRate = cfg.musicbrainz.ratePerSec,
                musicbrainzLast = matcher.mb.lastOutcome,
                acoustIdKeySet = cfg.apiKeys.acoustidClientKey.isNotBlank(), fanartKeySet = cfg.apiKeys.fanartTvKey.isNotBlank(),
                lyricsEnabled = cfg.music.fetchLyrics,
            ))
        }
        put("/providers") {
            val req = call.receive<MusicProvidersUpdate>()
            val cur = configStore.current
            configStore.update(cur.copy(
                musicbrainz = cur.musicbrainz.copy(
                    enabled = req.musicbrainzEnabled ?: cur.musicbrainz.enabled,
                    contact = req.musicbrainzContact?.trim() ?: cur.musicbrainz.contact,
                ),
                apiKeys = cur.apiKeys.copy(
                    acoustidClientKey = req.acoustIdKey?.trim() ?: cur.apiKeys.acoustidClientKey,
                    fanartTvKey = req.fanartKey?.trim() ?: cur.apiKeys.fanartTvKey,
                ),
                music = cur.music.copy(fetchLyrics = req.lyricsEnabled ?: cur.music.fetchLyrics),
            ))
            call.respond(mapOf("saved" to true))
        }
        post("/providers/test/{name}") {
            call.respond(mapOf("result" to matcher.test(call.parameters["name"]!!)))
        }
    }
}

/** Where the album or artist opens in Jellyfin's own web UI (the External links menu). */
internal fun jellyfinWebUrl(cfg: dev.jellystructure.config.AppConfig, id: String): String? =
    cfg.apiKeys.jellyfinUrl.trimEnd('/').takeIf { it.isNotBlank() }?.let { "$it/web/#/details?id=$id" }

