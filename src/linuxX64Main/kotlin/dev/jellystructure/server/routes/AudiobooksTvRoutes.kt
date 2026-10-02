package dev.jellystructure.server.routes

import dev.jellystructure.audiobooks.AudiobooksMediaService
import dev.jellystructure.audiobooks.AudiobooksStore
import dev.jellystructure.audiobooks.AudiobooksTvService
import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.model.MusicArt
import dev.jellystructure.server.respondCachedBytes
import dev.jellystructure.shared.tv.AudiobookBookmarkRequest
import dev.jellystructure.shared.tv.AudiobookFinishedRequest
import dev.jellystructure.shared.tv.AudiobookProgressRequest
import dev.jellystructure.shared.tv.AudiobookSpeedRequest
import dev.jellystructure.tv.RaviloArtworkService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * Phase 281 (FR-281-10) — `/api/tv/music/audiobooks…`, the phone's audiobooks. Device-token routes, each scoped to
 * the device's viewer by [AudiobooksTvService]. Playing a part is `POST /tv/music/play {audiobook_id, part}` (one
 * play shape with 279); the part's stop is the films' own stop, as a song's is. The cover sits under the open
 * `/api/tv/image/`.
 */
fun Route.audiobooksTvRoutes(
    svc: AudiobooksTvService,
    jellyfin: JellyfinClient,
    configStore: ConfigStore,
    store: AudiobooksStore,
    media: AudiobooksMediaService?,
    images: RaviloArtworkService?,
) {
    get("/tv/music/audiobooks") {
        val q = call.request.queryParameters
        call.respond(svc.shelf(call.attributes[DeviceKey], q["sort"], q["author"]?.takeIf { it.isNotBlank() }, q["series"]?.takeIf { it.isNotBlank() }))
    }
    get("/tv/music/audiobook/{id}") {
        call.respond(svc.detail(call.attributes[DeviceKey], call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound))
    }
    get("/tv/music/audiobook-author/{id}") {
        call.respond(svc.author(call.attributes[DeviceKey], call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound))
    }
    put("/tv/music/audiobook/{id}/progress") {
        val req = call.receive<AudiobookProgressRequest>()
        call.respond(svc.progress(call.attributes[DeviceKey], call.parameters["id"]!!, req.part, req.positionMs, req.paused, req.volumePercent, req.muted)   // R357
            ?: return@put call.respond(HttpStatusCode.NotFound))
    }
    put("/tv/music/audiobook/{id}/speed") {
        val req = call.receive<AudiobookSpeedRequest>()
        if (!svc.speed(call.attributes[DeviceKey], call.parameters["id"]!!, req.speed)) return@put call.respond(HttpStatusCode.NotFound)
        call.respond(mapOf("speed" to req.speed.coerceIn(0.5, 3.0)))
    }
    put("/tv/music/audiobook/{id}/finished") {
        val req = call.receive<AudiobookFinishedRequest>()
        call.respond(svc.finished(call.attributes[DeviceKey], call.parameters["id"]!!, req.finished) ?: return@put call.respond(HttpStatusCode.NotFound))
    }
    post("/tv/music/audiobook/{id}/bookmarks") {
        val req = call.receive<AudiobookBookmarkRequest>()
        call.respond(svc.addBookmark(call.attributes[DeviceKey], call.parameters["id"]!!, req.positionMs, req.note) ?: return@post call.respond(HttpStatusCode.NotFound))
    }
    delete("/tv/music/audiobook/{id}/bookmarks/{bookmark}") {
        val bm = call.parameters["bookmark"]?.toLongOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
        svc.deleteBookmark(call.attributes[DeviceKey], bm)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/tv/image/audiobook/{id}") {
        val b = store.book(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
        val width = call.request.queryParameters["w"]?.toIntOrNull()?.coerceIn(64, 1600)
        val path = media?.existingCover(b)
        if (path != null && images != null) {
            val r = images.serveMusicFile(path, "audiobook-${b.id}-${width ?: 0}", "poster", width ?: 480)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            return@get call.respondCachedBytes(r.first, ContentType.parse(r.second))
        }
        if (b.coverState != MusicArt.JELLYFIN) return@get call.respond(HttpStatusCode.NotFound)
        // Art only inside the files: Jellyfin's own image of the folder, else of the first part that has one.
        val cfg = configStore.current
        val ids = listOf(b.splitFrom ?: b.id).filter { !it.startsWith("f:") } + svc.parts(b.id).map { it.id }
        for (id in ids) {
            val img = jellyfin.getItemPrimaryImage(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, id, width ?: 480) ?: continue
            return@get call.respondCachedBytes(img.first, ContentType.parse(img.second))
        }
        call.respond(HttpStatusCode.NotFound)
    }
}
