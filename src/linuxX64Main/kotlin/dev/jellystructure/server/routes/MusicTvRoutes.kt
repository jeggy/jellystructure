package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.model.MusicArt
import dev.jellystructure.music.MusicMediaService
import dev.jellystructure.music.MusicStore
import dev.jellystructure.music.MusicTvService
import dev.jellystructure.server.respondCachedBytes
import dev.jellystructure.shared.tv.MusicFavoriteRequest
import dev.jellystructure.shared.tv.MusicPlayRequest
import dev.jellystructure.tv.PlaybackService
import dev.jellystructure.tv.RaviloArtworkService
import dev.jellystructure.tv.tvToken
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Phase 279 — `/api/tv/music/…`, the phone's music. Device-token routes like every other `/api/tv/` path, each scoped
 * to the device's viewer by [MusicTvService]. Progress and stop are the films' own endpoints (the song's id is the
 * item id), so Jellyfin's play counts move exactly as a film's do. The image route sits under `/api/tv/image/`,
 * which is open (a phone's image loader cannot attach a token) — covers are not sensitive.
 */
fun Route.musicTvRoutes(
    svc: MusicTvService,
    playback: PlaybackService,
    jellyfin: JellyfinClient,
    configStore: ConfigStore,
    store: MusicStore,
    media: MusicMediaService,
    images: RaviloArtworkService?,
    /** 281 — audiobook parts play through the same `/tv/music/play`. */
    audiobooks: dev.jellystructure.audiobooks.AudiobooksTvService? = null,
) {
    get("/tv/music/home") { call.respond(svc.home(call.attributes[DeviceKey])) }

    for (what in listOf("albums", "artists", "tracks")) get("/tv/music/$what") {
        val q = call.request.queryParameters
        call.respond(svc.browse(call.attributes[DeviceKey], what, q["sort"], q["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0, q["genre"]?.takeIf { it.isNotBlank() }))
    }
    get("/tv/music/genres") { call.respond(svc.genres(call.attributes[DeviceKey])) }
    get("/tv/music/playlists") { call.respond(svc.playlists(call.attributes[DeviceKey])) }

    get("/tv/music/playlist/{id}") {
        call.respond(svc.playlist(call.attributes[DeviceKey], call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound))
    }
    get("/tv/music/album/{id}") {
        call.respond(svc.album(call.attributes[DeviceKey], call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound))
    }
    get("/tv/music/artist/{id}") {
        val lang = call.request.queryParameters["lang"]?.take(8)
        call.respond(svc.artist(call.attributes[DeviceKey], call.parameters["id"]!!, lang) ?: return@get call.respond(HttpStatusCode.NotFound))
    }
    get("/tv/music/search") { call.respond(svc.search(call.attributes[DeviceKey], call.request.queryParameters["q"])) }

    // FR-279-6 — one song, one session; the next song in a queue is a stop and a start.
    post("/tv/music/play") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<MusicPlayRequest>()
        // 281 FR-281-10 — one part of an audiobook: the part's own session, exactly as a song's.
        val audiobookId = req.audiobookId
        if (audiobookId != null) {
            val partId = audiobooks?.partId(device, audiobookId, req.part ?: 0)
                ?: return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not available"))
            return@post call.respond(playback.startMusicPlayback(device, partId, req.capabilities, req.startPositionMs, bookId = audiobookId))   // R368: kind = audiobook
        }
        val trackId = req.trackId ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "track_id or audiobook_id"))
        val track = store.track(trackId)
        if (!svc.visible(device, trackId) || track == null) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not available"))
        // Phase 288 — a FLAC with no seek table, for a player that cannot scan it, is negotiated as a conversion.
        val capabilities = dev.jellystructure.music.FlacIndex.capabilitiesFor(req.capabilities, track.container, track.codec, track.path)
        call.respond(playback.startMusicPlayback(device, trackId, capabilities, req.startPositionMs))
        svc.forget(device)   // Recently played moved.
    }

    // R373 (FR-R373-4) — *Also on*: the other copies of a song this viewer may open.
    get("/tv/music/track/{id}/copies") {
        call.respond(svc.copies(call.attributes[DeviceKey], call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound))
    }

    get("/tv/music/track/{id}/lyrics") {
        call.respond(svc.lyrics(call.attributes[DeviceKey], call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound))
    }

    get("/tv/music/last-played") {
        call.respond(svc.lastPlayed(call.attributes[DeviceKey]) ?: return@get call.respond(HttpStatusCode.NoContent))
    }

    // FR-279-9 (dev review 2) — a song or an album; the films' favourite route only knows films.
    post("/tv/music/favorite") {
        val device = call.attributes[DeviceKey]
        val req = call.receive<MusicFavoriteRequest>()
        if (!svc.visible(device, req.itemId)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not available"))
        // 305 dev review 8 — un-favouriting a song clears every favourited copy of it.
        svc.setFavorite(device, req.itemId, req.favorite)
        call.respond(mapOf("favorite" to req.favorite))
    }

    get("/tv/image/music/{kind}/{id}") {
        val kind = call.parameters["kind"]!!
        val id = call.parameters["id"]!!
        val width = call.request.queryParameters["w"]?.toIntOrNull()?.coerceIn(64, 1600)
        val (path, embedded) = when (kind) {
            "album" -> store.album(id)?.let { media.existingCover(it) to (it.coverState == MusicArt.JELLYFIN) }
            "artist" -> store.artist(id)?.let { media.existingArtistImage(it, "thumb") to (it.imageState == MusicArt.JELLYFIN) }
            "background" -> store.artist(id)?.let { media.existingArtistImage(it, "background") to false }
            else -> null
        } ?: return@get call.respond(HttpStatusCode.NotFound)
        if (path != null && images != null) {
            val type = if (kind == "background") "backdrop" else "poster"
            val r = images.serveMusicFile(path, "music-$kind-$id-${width ?: 0}", type, width ?: if (kind == "background") null else 480)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            return@get call.respondCachedBytes(r.first, ContentType.parse(r.second))
        }
        if (!embedded) return@get call.respond(HttpStatusCode.NotFound)
        // Art only inside the files: Jellyfin's own image of the item.
        val cfg = configStore.current
        val img = jellyfin.getItemPrimaryImage(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, id, width ?: 480)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondCachedBytes(img.first, ContentType.parse(img.second))
    }
}
