package dev.jellystructure.api

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicAlbumPageDto
import dev.jellystructure.model.MusicArtCandidate
import dev.jellystructure.model.MusicArtUseRequest
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicArtistArtworkDto
import dev.jellystructure.model.MusicArtistPageDto
import dev.jellystructure.model.MusicArtworkDto
import dev.jellystructure.model.MusicBiographyRequest
import dev.jellystructure.model.MusicBrowseDto
import dev.jellystructure.model.MusicBulkRequest
import dev.jellystructure.model.MusicBulkResult
import dev.jellystructure.model.MusicConvertPlan
import dev.jellystructure.model.MusicConvertRequest
import dev.jellystructure.model.MusicGenreRow
import dev.jellystructure.model.MusicNfoDto
import dev.jellystructure.model.MusicStreamDto
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
import io.ktor.client.request.delete
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

    // ── Phase 277: artwork, NFO, biography, lyrics ──

    suspend fun albumArtwork(albumId: String): MusicArtworkDto? = runCatching {
        val r = httpClient.get("/api/music/album/${albumId.encodeURLParameter()}/artwork")
        if (r.status.isSuccess()) r.body<MusicArtworkDto>() else null
    }.getOrNull()

    suspend fun artistArtwork(artistId: String): MusicArtistArtworkDto? = runCatching {
        httpClient.get("/api/music/artist/${artistId.encodeURLParameter()}/artwork").body<MusicArtistArtworkDto>()
    }.getOrNull()

    suspend fun useAlbumCover(albumId: String, c: MusicArtCandidate): Boolean = runCatching {
        httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/artwork/use") { contentType(ContentType.Application.Json); setBody(MusicArtUseRequest(c.url, c.kind, c.source, c.credit)) }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun useArtistImage(artistId: String, c: MusicArtCandidate): Boolean = runCatching {
        val kind = when (c.kind) { "background" -> "background"; "logo" -> "logo"; else -> "thumb" }
        httpClient.post("/api/music/artist/${artistId.encodeURLParameter()}/artwork/use") { contentType(ContentType.Application.Json); setBody(MusicArtUseRequest(c.url, kind, c.source, c.credit)) }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun clearAlbumCover(albumId: String): Boolean = runCatching {
        httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/artwork/clear").status.isSuccess()
    }.getOrDefault(false)

    suspend fun lockAlbumCover(albumId: String, locked: Boolean): Boolean = runCatching {
        httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/artwork/lock") { contentType(ContentType.Application.Json); setBody(MusicLockRequest(locked)) }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun clearArtistImage(artistId: String, kind: String): Boolean = runCatching {
        httpClient.post("/api/music/artist/${artistId.encodeURLParameter()}/artwork/clear?kind=$kind").status.isSuccess()
    }.getOrDefault(false)

    suspend fun lockArtistImage(artistId: String, kind: String, locked: Boolean): Boolean = runCatching {
        httpClient.post("/api/music/artist/${artistId.encodeURLParameter()}/artwork/lock?kind=$kind") { contentType(ContentType.Application.Json); setBody(MusicLockRequest(locked)) }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun setBiography(artistId: String, text: String?): MusicArtist? = runCatching {
        httpClient.put("/api/music/artist/${artistId.encodeURLParameter()}/biography") { contentType(ContentType.Application.Json); setBody(MusicBiographyRequest(text)) }.body<MusicArtist>()
    }.getOrNull()

    suspend fun albumNfo(albumId: String): MusicNfoDto? = runCatching { httpClient.get("/api/music/album/${albumId.encodeURLParameter()}/nfo").body<MusicNfoDto>() }.getOrNull()
    suspend fun artistNfo(artistId: String): MusicNfoDto? = runCatching { httpClient.get("/api/music/artist/${artistId.encodeURLParameter()}/nfo").body<MusicNfoDto>() }.getOrNull()

    /** Save → NFO (and Sync when [sync]); the answer is `written` · `unchanged` · `no_folder` · `failed`. */
    suspend fun saveAlbum(albumId: String, sync: Boolean): String? = runCatching {
        httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/save${if (sync) "?sync=1" else ""}").body<OutcomeResult>().outcome
    }.getOrNull()

    suspend fun saveArtist(artistId: String, sync: Boolean): String? = runCatching {
        httpClient.post("/api/music/artist/${artistId.encodeURLParameter()}/save${if (sync) "?sync=1" else ""}").body<OutcomeResult>().outcome
    }.getOrNull()

    /** Phase 283 (FR-283-4) — *This is right* on one flag, and *Show it again*. */
    suspend fun setFlagDismissed(albumId: String, kind: String, dismissed: Boolean): Boolean = runCatching {
        val url = "/api/music/album/${albumId.encodeURLParameter()}/flags/${kind.encodeURLParameter()}/dismiss"
        (if (dismissed) httpClient.post(url) else httpClient.delete(url)).status.isSuccess()
    }.getOrDefault(false)

    suspend fun syncAlbum(albumId: String): Boolean = runCatching {
        httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/sync").status.isSuccess()
    }.getOrDefault(false)

    suspend fun fetchLyrics(albumId: String): String? = runCatching {
        httpClient.post("/api/music/album/${albumId.encodeURLParameter()}/lyrics").body<LyricsResult>().result
    }.getOrNull()

    // ── Phase 278: the pages ──

    /** [facets] maps a facet key to the values ticked. */
    suspend fun browse(view: String, query: String?, facets: Map<String, Set<String>>, sort: String? = null): MusicBrowseDto? = runCatching {
        val qs = buildList {
            add("view=$view")
            query?.takeIf { it.isNotBlank() }?.let { add("q=${it.encodeURLParameter()}") }
            sort?.let { add("sort=$it") }
            facets.filterValues { it.isNotEmpty() }.forEach { (k, v) -> add("f.$k=${v.joinToString(",") { it.encodeURLParameter() }}") }
        }.joinToString("&")
        httpClient.get("/api/music/browse?$qs").body<MusicBrowseDto>()
    }.getOrNull()

    suspend fun albumPage(albumId: String): MusicAlbumPageDto? = runCatching {
        val r = httpClient.get("/api/music/album/${albumId.encodeURLParameter()}/page")
        if (r.status.isSuccess()) r.body<MusicAlbumPageDto>() else null
    }.getOrNull()

    suspend fun artistPage(artistId: String): MusicArtistPageDto? = runCatching {
        val r = httpClient.get("/api/music/artist/${artistId.encodeURLParameter()}/page")
        if (r.status.isSuccess()) r.body<MusicArtistPageDto>() else null
    }.getOrNull()

    suspend fun genres(): List<MusicGenreRow>? = runCatching { httpClient.get("/api/music/genres").body<List<MusicGenreRow>>() }.getOrNull()

    suspend fun bulk(action: String, albumIds: List<String>): String? = runCatching {
        val r = httpClient.post("/api/music/bulk") { contentType(ContentType.Application.Json); setBody(MusicBulkRequest(action, albumIds)) }
        if (r.status.isSuccess()) r.body<MusicBulkResult>().sentence else null
    }.getOrNull()

    suspend fun lockArtist(artistId: String, locked: Boolean): MusicArtist? = runCatching {
        httpClient.post("/api/music/artist/${artistId.encodeURLParameter()}/lock") { contentType(ContentType.Application.Json); setBody(MusicLockRequest(locked)) }.body<MusicArtist>()
    }.getOrNull()

    suspend fun clearArtist(artistId: String): MusicArtist? = runCatching {
        httpClient.post("/api/music/artist/${artistId.encodeURLParameter()}/clear").body<MusicArtist>()
    }.getOrNull()

    suspend fun convertPlan(req: MusicConvertRequest): MusicConvertPlan? = runCatching {
        val r = httpClient.post("/api/music/convert/plan") { contentType(ContentType.Application.Json); setBody(req) }
        if (r.status.isSuccess()) r.body<MusicConvertPlan>() else null
    }.getOrNull()

    suspend fun convert(req: MusicConvertRequest): MusicConvertPlan? = runCatching {
        val r = httpClient.post("/api/music/convert") { contentType(ContentType.Application.Json); setBody(req) }
        if (r.status.isSuccess()) r.body<MusicConvertPlan>() else null
    }.getOrNull()

    suspend fun streamUrl(trackId: String): String? = runCatching {
        val r = httpClient.get("/api/music/track/${trackId.encodeURLParameter()}/stream")
        if (r.status.isSuccess()) r.body<MusicStreamDto>().url else null
    }.getOrNull()
}

@Serializable private data class OutcomeResult(val outcome: String = "")
@Serializable private data class LyricsResult(val result: String = "")

