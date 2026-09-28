package dev.jellystructure.api

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicCandidateDto
import dev.jellystructure.model.MusicGenresRequest
import dev.jellystructure.model.MusicLockRequest
import dev.jellystructure.model.MusicMatchRequest
import dev.jellystructure.model.MusicProvidersDto
import dev.jellystructure.model.MusicProvidersUpdate
import dev.jellystructure.model.MusicRecordingOption
import dev.jellystructure.model.MusicRecordingRequest
import dev.jellystructure.model.MusicReleaseOption
import dev.jellystructure.model.MusicSearchRequest
import dev.jellystructure.model.MusicSoundResult
import dev.jellystructure.model.MusicStatusDto
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicUseRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

@Serializable private data class ProviderTestResult(val result: String = "")

/** Phases 275/276 — the admin's music calls (`/api/music/…`). Null / false = the call failed (the page says so). */
object MusicApi {
    suspend fun status(): MusicStatusDto? = runCatching { httpClient.get("/api/music/status").body<MusicStatusDto>() }.getOrNull()

    suspend fun matchNow(albumIds: List<String>? = null, scope: String = "missing"): Boolean = runCatching {
        httpClient.post("/api/music/match") { contentType(ContentType.Application.Json); setBody(MusicMatchRequest(albumIds, scope)) }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun search(albumId: String, query: String?, url: String?): List<MusicCandidateDto>? = runCatching {
        val r = httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/search") { contentType(ContentType.Application.Json); setBody(MusicSearchRequest(query, url)) }
        if (r.status.isSuccess()) r.body<List<MusicCandidateDto>>() else null
    }.getOrNull()

    suspend fun releases(albumId: String, rg: String): List<MusicReleaseOption>? = runCatching {
        val r = httpClient.get("/api/music/album/${albumId.encodeURLParameter()}/releases?rg=${rg.encodeURLParameter()}")
        if (r.status.isSuccess()) r.body<List<MusicReleaseOption>>() else null
    }.getOrNull()

    suspend fun identify(albumId: String): MusicSoundResult? = runCatching {
        httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/identify").body<MusicSoundResult>()
    }.getOrNull()

    suspend fun use(albumId: String, releaseGroup: String, release: String?, lock: Boolean): MusicAlbum? = runCatching {
        val r = httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/use") { contentType(ContentType.Application.Json); setBody(MusicUseRequest(releaseGroup, release, lock)) }
        if (r.status.isSuccess()) r.body<MusicAlbum>() else null
    }.getOrNull()

    suspend fun lock(albumId: String, locked: Boolean): MusicAlbum? = runCatching {
        httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/lock") { contentType(ContentType.Application.Json); setBody(MusicLockRequest(locked)) }.body<MusicAlbum>()
    }.getOrNull()

    suspend fun clear(albumId: String): MusicAlbum? = runCatching {
        httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/clear").body<MusicAlbum>()
    }.getOrNull()

    suspend fun setGenres(albumId: String, genres: List<String>?): MusicAlbum? = runCatching {
        httpClient.put("/api/music/album/${albumId.encodeURLParameter()}/genres") { contentType(ContentType.Application.Json); setBody(MusicGenresRequest(genres)) }.body<MusicAlbum>()
    }.getOrNull()

    suspend fun recordings(trackId: String): List<MusicRecordingOption>? = runCatching {
        val r = httpClient.get("/api/music/track/${trackId.encodeURLParameter()}/recordings")
        if (r.status.isSuccess()) r.body<List<MusicRecordingOption>>() else null
    }.getOrNull()

    suspend fun useRecording(trackId: String, recording: String): MusicTrack? = runCatching {
        httpClient.post("/api/music/track/${trackId.encodeURLParameter()}/recording") { contentType(ContentType.Application.Json); setBody(MusicRecordingRequest(recording)) }.body<MusicTrack>()
    }.getOrNull()

    suspend fun providers(): MusicProvidersDto? = runCatching { httpClient.get("/api/music/providers").body<MusicProvidersDto>() }.getOrNull()

    suspend fun saveProviders(update: MusicProvidersUpdate): Boolean = runCatching {
        httpClient.put("/api/music/providers") { contentType(ContentType.Application.Json); setBody(update) }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun testProvider(name: String): String = runCatching {
        httpClient.post("/api/music/providers/test/${name.encodeURLParameter()}").body<ProviderTestResult>().result
    }.getOrDefault("The test could not be run")
}
