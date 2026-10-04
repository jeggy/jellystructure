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
import dev.jellystructure.model.ProviderTestResult
import dev.jellystructure.model.MusicRecordingRequest
import dev.jellystructure.model.MusicSearchRequest
import dev.jellystructure.model.MusicStatusDto
import dev.jellystructure.model.MusicUseRequest
import dev.jellystructure.model.MusicAlbumPageDto
import dev.jellystructure.model.MusicArtistPageDto
import dev.jellystructure.model.MusicBrowseDto
import dev.jellystructure.model.MusicBulkRequest
import dev.jellystructure.model.MusicTagsPreview
import dev.jellystructure.model.MusicWriteTagsRequest
import dev.jellystructure.model.MusicBulkResult
import dev.jellystructure.model.MusicConvertPlan
import dev.jellystructure.model.MusicConvertRequest
import dev.jellystructure.model.MusicLibraryInfo
import dev.jellystructure.model.MusicStreamDto
import dev.jellystructure.model.MusicVideoRow
import dev.jellystructure.model.effectiveGenres
import dev.jellystructure.model.originalYear
import dev.jellystructure.music.MusicBrowse
import dev.jellystructure.music.MusicScanner
import dev.jellystructure.music.MusicScoring
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
                // Phase 305 (FR-305-4) — *Change…*: every pressing with *against the official*, `pickable`, *+ N not in
                // the library*.
                if (call.request.queryParameters["official"] == "1")
                    return@get call.respond(matcher.pressings(id) ?: return@get call.respond(HttpStatusCode.BadGateway, noAnswer))
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
                // Phase 284 (FR-284-2 moment A) — a pick in *Find match…* writes identity + ids at once, on the media lane.
                if (configStore.current.music.writeTags && music.tags?.available() == true) jobs?.enqueue("write_tags", id, "Tags into the files · ${music.store.album(id)?.title ?: id}", dev.jellystructure.jobs.MediaJobParams(), fileCount = music.store.snapshot().tracksByAlbum[id]?.size ?: 1)
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
            val id = call.parameters["id"]!!
            val outcome = media.saveAlbum(id, sync) ?: return@post call.respond(HttpStatusCode.NotFound)
            // Phase 284 (FR-284-7) — *Save everything* (`files=1`): the NFO, then the tags, on the media lane.
            if (call.request.queryParameters["files"] == "1" && configStore.current.music.writeTags) {
                jobs?.enqueue("write_tags", id, "Tags into the files · ${music.store.album(id)?.title ?: id}", dev.jellystructure.jobs.MediaJobParams(), fileCount = music.store.snapshot().tracksByAlbum[id]?.size ?: 1)
            }
            call.respond(mapOf("outcome" to outcome.name.lowercase()))
        }
        // Phase 284 (FR-284-5) — the Files tab: what the files say against what this page states.
        get("/album/{id}/files") {
            val w = music.tags ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(w.filesFor(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        // FR-284-2 moment B — *Write tags to N files*: one job on the media lane; the choices ride the body.
        post("/album/{id}/write-tags") {
            val w = music.tags ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val q = jobs ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val id = call.parameters["id"]!!
            val a = music.store.album(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            if (!configStore.current.music.writeTags) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Tag writing is off in Settings → Music providers"))
            if (!w.available()) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "This server has no tagger"))
            val req = runCatching { call.receive<MusicWriteTagsRequest>() }.getOrDefault(MusicWriteTagsRequest())
            if (req.embedCover != null && req.embedCover != a.embedCover) music.store.putAlbum(a.copy(embedCover = req.embedCover))
            if (req.take == "file" && a.matchLocked) w.writeAlbum(id, take = "file")   // takes the files' facts first; the job then writes what is now ours
            val snap = q.enqueue("write_tags", id, "Tags into the files · ${a.title}" + (if (req.removeJunk) " · junk frames removed" else ""), dev.jellystructure.jobs.MediaJobParams(), fileCount = music.store.snapshot().tracksByAlbum[id]?.size ?: 1)
            call.respond(mapOf("job" to snap.id))
        }
        // FR-284-10 — the bulk confirm line's counts before anything runs.
        post("/bulk/tags-preview") {
            val req = call.receive<MusicBulkRequest>()
            val w = music.tags
            val snap = music.store.snapshot()
            val cfg = configStore.current
            var songs = 0; var unmatched = 0; var wma = 0
            for (id in req.albumIds.distinct()) {
                val a = snap.albums[id] ?: continue
                if (a.matchState != dev.jellystructure.model.MusicMatch.MATCHED) { unmatched++; continue }
                for (t in snap.tracksByAlbum[id].orEmpty().filter { it.missingSince == null && it.path != null }) { songs++; if (t.path!!.endsWith(".wma", true)) wma++ }
            }
            call.respond(MusicTagsPreview(selected = req.albumIds.distinct().size, songs = songs, unmatched = unmatched, seeding = 0, wma = wma, taggerAvailable = w?.available() == true, writeEnabled = cfg.music.writeTags))
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

        /** The Music kind (FR-278-1..4). Facets ride `f.<key>=a,b`; everything is counted here. A Dashboard row's
         *  triage key rides `filter=<key>` and chooses the view (293 FR-293-2/3). */
        get("/browse") {
            val cfg = configStore.current
            val qp = call.request.queryParameters
            val triage = qp["filter"]?.takeIf { it in dev.jellystructure.music.MusicTriage.MUSIC }
            val view = dev.jellystructure.music.MusicTriage.viewOf(triage) ?: qp["view"]?.takeIf { it in MusicBrowse.VIEWS } ?: MusicBrowse.ALBUMS
            val selected = MusicBrowse.FACETS.mapNotNull { (k, _) -> qp["f.$k"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.let { k to it } }.toMap()
            // Phase 292 (FR-292-11) — *Hide* rides `x.<key>=` beside `f.<key>=`; `artist=` narrows Songs to one artist.
            val excluded = MusicBrowse.FACETS.mapNotNull { (k, _) -> qp["x.$k"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.let { k to it } }.toMap()
            val artist = qp["artist"]?.takeIf { it.isNotBlank() }
            val libs = MusicScanner.musicLibraries(cfg)
            val snap = music.store.snapshot()
            val r = MusicBrowse.browse(snap, view, selected, qp["q"], libs.associate { it.jellyfinId to it.name.ifBlank { it.jellyfinId } }, qp["sort"], dev.jellystructure.music.MusicFlags.roots(cfg), triage,
                excluded = excluded, artist = artist.takeIf { view == MusicBrowse.SONGS }, everyCopy = qp["copies"] == "all")
            call.respond(MusicBrowseDto(
                mapped = libs.isNotEmpty(), scanned = music.scanner.lastScanAt != null || snap.albums.isNotEmpty() || snap.tracks.isNotEmpty(),
                health = if (libs.isNotEmpty()) music.store.health() else null, match = matcher.status,
                libraries = libs.map { MusicLibraryInfo(it.jellyfinId, it.name, it.jellyfinPath, it.localPath) },
                view = view, total = r.rowsTotal, facets = r.facets, albums = r.albums, artists = r.artists, songs = r.songs,
                musicbrainzEnabled = cfg.musicbrainz.enabled,
                filter = triage, filterLabel = triage?.let { dev.jellystructure.music.MusicTriage.MUSIC[it]?.label }, writeTags = cfg.music.writeTags,
                sentence = r.sentence, artist = artist.takeIf { view == MusicBrowse.SONGS }, versionTypes = music.versions.chipTypes(snap),
                folded = r.folded, everyCopy = r.everyCopy,
            ))
        }

        get("/genres") { call.respond(MusicBrowse.genres(music.store.snapshot())) }

        get("/album/{id}/page") {
            val cfg = configStore.current
            val snap = music.store.snapshot()
            val a = snap.albums[call.parameters["id"]!!] ?: return@get call.respond(HttpStatusCode.NotFound)
            val lib = cfg.libraries.firstOrNull { it.jellyfinId == a.libraryId }
            // Phase 283 (FR-283-3) — the album's flags, and the kinds the admin said *This is right* to.
            val roots = dev.jellystructure.music.MusicFlags.roots(cfg)
            val all = dev.jellystructure.music.MusicFlags.raw(snap.albums.values, roots, withDismissed = true)[a.id].orEmpty()
            val shown = dev.jellystructure.music.MusicFlags.of(snap.albums.values, roots)[a.id].orEmpty()
            // Phase 305 (FR-305-12) — the official rows, gaps, extras, singles and B-sides come from one pure builder.
            val page = dev.jellystructure.music.MusicAlbumPage.build(snap, a.id) { media.lyricsStateOf(it) } ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(page.copy(
                drift = media.albumDrift(a), lyricsEnabled = cfg.music.fetchLyrics, acoustId = matcher.acoustId.available,
                jellyfinUrl = jellyfinWebUrl(cfg, a.id), library = lib?.name, jellyfinLocked = a.jellyfinLocked,
                flags = shown, dismissedFlags = all.map { it.kind }.filter { k -> shown.none { it.kind == k } },
                writeTags = cfg.music.writeTags && music.tags?.available() == true,   // Phase 284 (FR-284-7)
                versionTypes = music.versions.chipTypes(snap),
            ))
        }

        /** Phase 283 (FR-283-4) — *This is right*: the flag goes until what it was said for changes. */
        post("/album/{id}/flags/{kind}/dismiss") {
            val kind = call.parameters["kind"]!!
            val roots = dev.jellystructure.music.MusicFlags.roots(configStore.current)
            val snap = music.store.snapshot()
            val a = snap.albums[call.parameters["id"]!!] ?: return@post call.respond(HttpStatusCode.NotFound)
            val flag = dev.jellystructure.music.MusicFlags.raw(snap.albums.values, roots, withDismissed = true)[a.id].orEmpty().firstOrNull { it.kind == kind }
                ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "This album has no such flag"))
            val others = flag.others.mapNotNull { snap.albums[it.id] }
            music.store.putAlbum(a.copy(flagsDismissed = a.flagsDismissed + (kind to dev.jellystructure.music.MusicFlags.fingerprint(kind, a, others, roots))))
            call.respond(mapOf("status" to "ok"))
        }
        /** *Show it again*. */
        delete("/album/{id}/flags/{kind}/dismiss") {
            val a = music.store.snapshot().albums[call.parameters["id"]!!] ?: return@delete call.respond(HttpStatusCode.NotFound)
            music.store.putAlbum(a.copy(flagsDismissed = a.flagsDismissed - call.parameters["kind"]!!))
            call.respond(mapOf("status" to "ok"))
        }

        get("/artist/{id}/page") {
            val cfg = configStore.current
            val snap = music.store.snapshot()
            val r = snap.artists[call.parameters["id"]!!] ?: return@get call.respond(HttpStatusCode.NotFound)
            val browse = MusicBrowse.browse(snap, MusicBrowse.ALBUMS, emptyMap(), null, emptyMap(), "year", dev.jellystructure.music.MusicFlags.roots(cfg))
            val mine = snap.albumsByArtist[r.id].orEmpty().filter { it.missingSince == null }
            val own = mine.filter { a -> a.albumArtists.any { it.artistId == r.id } }.map { it.id }.toSet()
            val credited = mine.map { it.id }.toSet() - own
            // Phase 305 (FR-305-15, dev review 2e) — a single or EP living under an album leaves whichever group held it.
            val homed = snap.editions.homes.filterValues { it.albumId != null }.keys
            val rows = browse.albums.filter { it.id !in homed }
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
                versionCounts = music.versions.artistCounts(r.id),   // Phase 292 (FR-292-14)
                singlesUnder = dev.jellystructure.music.MusicAlbumPage.singlesUnder(snap, own.sortedBy { snap.albums[it]?.originalYear() ?: 0 }),
            ))
        }

        // ── Phase 305: the official album, singles' homes, one copy of every song ──

        /** *Use as the official album*. */
        put("/album/{id}/official") {
            val req = call.receive<dev.jellystructure.model.MusicOfficialRequest>()
            when (val o = matcher.pickOfficial(call.parameters["id"]!!, req.release)) {
                is dev.jellystructure.music.MusicPickOutcome.Picked -> call.respond(o.album)
                dev.jellystructure.music.MusicPickOutcome.NotFound -> call.respond(HttpStatusCode.NotFound)
                dev.jellystructure.music.MusicPickOutcome.NotAnAlbum -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Only a matched album has an official tracklist"))
                dev.jellystructure.music.MusicPickOutcome.NotOfThisAlbum -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "That pressing is not one of this album's"))
                dev.jellystructure.music.MusicPickOutcome.NoAnswer -> call.respond(HttpStatusCode.BadGateway, noAnswer)
            }
        }
        /** *Back to automatic*. */
        delete("/album/{id}/official") {
            call.respond(matcher.unpickOfficial(call.parameters["id"]!!) ?: return@delete call.respond(HttpStatusCode.NotFound))
        }
        /** *Move to…* — an album of the same artist, or `album_id: null` for *No album* (FR-305-6 rule 4). */
        put("/album/{id}/home") {
            val id = call.parameters["id"]!!
            val req = call.receive<dev.jellystructure.model.MusicHomeRequest>()
            val snap = music.store.snapshot()
            val single = snap.albums[id] ?: return@put call.respond(HttpStatusCode.NotFound)
            val target = req.albumId?.let { snap.albums[it] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No such album")) }
            if (target != null && (target.id == id || target.albumArtists.none { c -> single.albumArtists.any { it.artistId == c.artistId } }))
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Only an album of the same artist"))
            music.store.putHome(id, target?.id, dev.jellystructure.nowEpochSec())
            runCatching { music.matcher.recordHistory(id, "music_home", "Moved by hand to " + (target?.let { "“${it.title}”" } ?: "no album")) }
            call.respond(mapOf("status" to "ok"))
        }
        delete("/album/{id}/home") {
            val id = call.parameters["id"]!!
            music.store.album(id) ?: return@delete call.respond(HttpStatusCode.NotFound)
            music.store.putHome(id, null, dev.jellystructure.nowEpochSec(), clear = true)
            runCatching { music.matcher.recordHistory(id, "music_home", "Album back to automatic") }
            call.respond(mapOf("status" to "ok"))
        }
        /** The copies panel (FR-305-13). */
        get("/track/{id}/copies") {
            call.respond(dev.jellystructure.music.MusicAlbumPage.copies(music.store.snapshot(), call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        /** *Same song as…* · *Not the same song* · the Dashboard's *Yes, one song* / *No, two songs* (FR-305-13/14). */
        put("/same-song") {
            val req = call.receive<dev.jellystructure.model.MusicSameSongRequest>()
            if (req.a == req.b) return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A song is always the same as itself"))
            if (req.state !in setOf(dev.jellystructure.music.MusicSameSong.SAME, dev.jellystructure.music.MusicSameSong.NOT_SAME)) return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "state is same or not_same"))
            val snap = music.store.snapshot()
            if (snap.tracks[req.a] == null || snap.tracks[req.b] == null) return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown song"))
            music.store.putSameSong(req.a, req.b, req.state, dev.jellystructure.nowEpochSec())
            call.respond(mapOf("status" to "ok"))
        }
        /** *Listen and decide*: the open pairs (FR-305-14). */
        get("/same-song/suggestions") {
            call.respond(dev.jellystructure.music.MusicAlbumPage.suggestions(music.store.snapshot()))
        }

        // ── Phase 292: a song's version ──

        /** The side panel (FR-292-7). */
        get("/track/{id}/versions") {
            call.respond(music.versions.panel(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound))
        }
        /** One tick, saved at once. Session on also writes Live on; Live off leaves Session (dev review 7). */
        put("/track/{id}/versions/{type}") {
            val req = call.receive<dev.jellystructure.model.MusicVersionSetRequest>()
            call.respond(music.versions.set(call.parameters["id"]!!, call.parameters["type"]!!, req.on) ?: return@put call.respond(HttpStatusCode.NotFound))
        }
        /** *Back to automatic*. */
        delete("/track/{id}/versions") {
            call.respond(music.versions.reset(call.parameters["id"]!!) ?: return@delete call.respond(HttpStatusCode.NotFound))
        }
        /** *Set version…*'s dialog: *N of M have it* per type, and the copies on other albums. */
        post("/versions/preview") {
            val req = call.receive<dev.jellystructure.model.MusicVersionBulkRequest>()
            call.respond(music.versions.preview(req.trackIds))
        }
        post("/versions/bulk") {
            val req = call.receive<dev.jellystructure.model.MusicVersionBulkRequest>()
            call.respond(MusicBulkResult(music.versions.bulk(req.trackIds, req.add.toSet(), req.remove.toSet())))
        }
        /** Metadata → Versions (FR-292-13): saved as the owner edits, like JS tags — not Settings. */
        get("/version-types") { call.respond(music.versions.types()) }
        patch("/version-types") {
            val req = call.receive<dev.jellystructure.model.MusicVersionTypePatch>()
            call.respond(music.versions.patchType(req) ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown type or a colour that is not #rrggbb")))
        }
        /** FR-292-15 action 1 — by hand only: delete our sidecars beside songs with no singing, and block them. */
        post("/lyrics/remove-instrumental") {
            call.respond(MusicBulkResult(media.removeLyricsOnNoSinging()))
        }
        /** FR-292-15 action 2 — by hand only, in the background (a proof of work per song); the outcome goes to History. */
        post("/lyrics/tell-lrclib") {
            val n = music.store.snapshot().let { s -> s.tracks.values.count { it.missingSince == null && s.versions.lyricsOnNoSinging(it) } }
            appScope.launch { runCatching { media.tellLrclibInstrumental() }.onFailure { Logger.warn("LRCLIB publish failed: ${it.message}", "music") } }
            call.respond(MusicBulkResult("Telling LRCLIB about $n song${if (n == 1) "" else "s"} — the outcome goes to each album’s History"))
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
                // Phase 284 (FR-284-2 moment D) — one job per matched album on the media lane; the skips are said up front.
                "tags" -> {
                    val q = jobs ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
                    if (!configStore.current.music.writeTags) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Tag writing is off in Settings → Music providers"))
                    val snap = music.store.snapshot()
                    var queued = 0; var skipped = 0
                    for (id in ids) {
                        val a = snap.albums[id] ?: continue
                        if (a.matchState != dev.jellystructure.model.MusicMatch.MATCHED) { skipped++; continue }
                        q.enqueue("write_tags", id, "Tags into the files · ${a.title}", dev.jellystructure.jobs.MediaJobParams(), fileCount = snap.tracksByAlbum[id]?.size ?: 1); queued++
                    }
                    "Writing tags for ${albums(queued)}" + if (skipped > 0) " · $skipped unmatched, skipped" else ""
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
                writeTags = cfg.music.writeTags, keepId3Version = cfg.music.keepId3Version,   // Phase 284 (FR-284-8)
                keepUnmanagedFrames = cfg.music.keepUnmanagedFrames, taggerAvailable = music.tags?.available() == true,
                acoustIdCheck = dev.jellystructure.music.ProviderKeyChecks.acoustId.last(cfg.apiKeys.acoustidClientKey),
                fanartCheck = dev.jellystructure.music.ProviderKeyChecks.fanart.last(cfg.apiKeys.fanartTvKey),
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
                music = cur.music.copy(fetchLyrics = req.lyricsEnabled ?: cur.music.fetchLyrics, writeTags = req.writeTags ?: cur.music.writeTags,
                    keepId3Version = req.keepId3Version ?: cur.music.keepId3Version, keepUnmanagedFrames = req.keepUnmanagedFrames ?: cur.music.keepUnmanagedFrames),
            ))
            call.respond(mapOf("saved" to true))
        }
        post("/providers/test/{name}") {
            // 2026-09-28 amendment — a keyed provider's Test is one real request with the saved key.
            val keys = configStore.current.apiKeys
            val checks = dev.jellystructure.music.ProviderKeyChecks
            val check = when (call.parameters["name"]) {
                "fanart" -> keys.fanartTvKey.takeIf { it.isNotBlank() }?.let { checks.checkFanart(it) } ?: return@post call.respond(ProviderTestResult("No project key yet"))
                "acoustid" -> keys.acoustidClientKey.takeIf { it.isNotBlank() }?.let { checks.checkAcoustId(it) } ?: return@post call.respond(ProviderTestResult("No client key yet"))
                else -> return@post call.respond(ProviderTestResult(matcher.test(call.parameters["name"]!!)))
            }
            call.respond(ProviderTestResult(check.message, check))
        }
    }
}

/** Where the album or artist opens in Jellyfin's own web UI (the External links menu). */
internal fun jellyfinWebUrl(cfg: dev.jellystructure.config.AppConfig, id: String): String? =
    cfg.apiKeys.jellyfinUrl.trimEnd('/').takeIf { it.isNotBlank() }?.let { "$it/web/#/details?id=$id" }

