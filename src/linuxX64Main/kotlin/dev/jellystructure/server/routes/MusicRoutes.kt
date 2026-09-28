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

private fun fileSize(path: String): Long? = runCatching { SystemFileSystem.metadataOrNull(Path(path))?.size }.getOrNull()

/** The `file` part of a multipart upload (the films' upload shape). */
private suspend fun receiveImage(call: ApplicationCall): ByteArray? {
    var bytes: ByteArray? = null
    call.receiveMultipart().forEachPart { part ->
        if (part is PartData.FileItem && part.name == "file") bytes = part.provider().readRemaining().readByteArray()
        part.release()
    }
    return bytes?.takeIf { it.isNotEmpty() }
}

/** A music image file, typed by its own bytes; 404 when there is none. Cached briefly — a replaced cover shows after a reload. */
private suspend fun serveFile(call: ApplicationCall, path: String?) {
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
fun Route.musicRoutes(configStore: ConfigStore, music: MusicPipeline, appScope: CoroutineScope) {
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
            serveFile(call, media.existingCover(a))
        }
        get("/image/artist/{id}/{kind}") {
            val a = music.store.artist(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            serveFile(call, media.existingArtistImage(a, call.parameters["kind"]!!))
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
