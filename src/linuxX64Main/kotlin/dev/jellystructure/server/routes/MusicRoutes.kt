package dev.jellystructure.server.routes

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MusicPipeline
import dev.jellystructure.model.MusicCandidateDto
import dev.jellystructure.model.MusicGenresRequest
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
