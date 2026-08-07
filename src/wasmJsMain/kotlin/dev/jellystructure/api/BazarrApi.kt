package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BazarrOverview(
    val connected: Boolean,
    val wantedMovies: Int = 0,
    val wantedEpisodes: Int = 0,
    val providersHealthy: Int = 0,
    val providersTotal: Int = 0,
)

@Serializable
data class BazarrMissingSubtitle(val name: String = "", val code2: String = "", val forced: Boolean = false, val hi: Boolean = false)

@Serializable
data class BazarrWantedRow(
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val radarrId: Int? = null,
    val sonarrSeriesId: Int? = null,
    val sonarrEpisodeId: Int? = null,
    val missing: List<BazarrMissingSubtitle> = emptyList(),
)

@Serializable
data class BazarrWantedPageDto(val items: List<BazarrWantedRow> = emptyList(), val total: Int = 0)

@Serializable
data class BazarrSubtitleFile(
    val name: String = "",
    val code2: String = "",
    val path: String? = null,
    val forced: Boolean = false,
    val hi: Boolean = false,
    @SerialName("file_size") val fileSize: Long? = null,
)

@Serializable
data class BazarrLanguageRow(
    val language: String,
    val code2: String,
    val forced: Boolean = false,
    val hi: Boolean = false,
    val present: BazarrSubtitleFile? = null,
)

@Serializable
data class BazarrTitleState(
    val connected: Boolean,
    val matched: Boolean,
    val radarrId: Int? = null,
    val sonarrSeriesId: Int? = null,
    val sonarrEpisodeId: Int? = null,
    val languages: List<BazarrLanguageRow> = emptyList(),
)

@Serializable
data class BazarrEpisodeRow(
    val title: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val sonarrEpisodeId: Int = 0,
    @SerialName("missing_subtitles") val missingSubtitles: List<BazarrMissingSubtitle> = emptyList(),
    val subtitles: List<BazarrSubtitleFile> = emptyList(),
)

@Serializable
data class BazarrProviderStatus(val name: String = "", val status: String = "", val retry: String = "")

@Serializable
data class BazarrLanguageProfileItem(val id: Int = 0, val language: String = "", val hi: String = "False", val forced: String = "False")

@Serializable
data class BazarrLanguageProfile(val profileId: Int = 0, val name: String = "", val items: List<BazarrLanguageProfileItem> = emptyList())

@Serializable
data class BazarrHistoryEvent(
    val action: Int = 0,
    val language: String? = null,
    val provider: String? = null,
    val score: String? = null,
    val timestamp: String? = null,
    val description: String? = null,
)

/** Phase 157 — client for the admin's Bazarr subtitle routes (overview + per-title). */
object BazarrApi {
    suspend fun overview(): BazarrOverview? = runCatching {
        httpClient.get("/api/bazarr/overview").body<BazarrOverview>()
    }.getOrNull()

    suspend fun wanted(start: Int = 0, length: Int = 50, kind: String? = null): BazarrWantedPageDto? = runCatching {
        httpClient.get("/api/bazarr/wanted") {
            url { parameters.append("start", start.toString()); parameters.append("length", length.toString()); kind?.let { parameters.append("kind", it) } }
        }.body<BazarrWantedPageDto>()
    }.getOrNull()

    suspend fun searchAllWanted(kind: String? = null): Boolean = runCatching {
        httpClient.post("/api/bazarr/wanted/search-all") { url { kind?.let { parameters.append("kind", it) } } }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun runFullScan(): Boolean = runCatching {
        httpClient.post("/api/bazarr/scan").status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun history(start: Int = 0, length: Int = 30): List<BazarrHistoryEvent> = runCatching {
        httpClient.get("/api/bazarr/history") { url { parameters.append("start", start.toString()); parameters.append("length", length.toString()) } }
            .body<List<BazarrHistoryEvent>>()
    }.getOrDefault(emptyList())

    suspend fun providers(): List<BazarrProviderStatus> = runCatching {
        httpClient.get("/api/bazarr/providers").body<List<BazarrProviderStatus>>()
    }.getOrDefault(emptyList())

    suspend fun profiles(): List<BazarrLanguageProfile> = runCatching {
        httpClient.get("/api/bazarr/profiles").body<List<BazarrLanguageProfile>>()
    }.getOrDefault(emptyList())

    suspend fun titleState(mediaId: String): BazarrTitleState? = runCatching {
        httpClient.get("/api/media/$mediaId/bazarr").body<BazarrTitleState>()
    }.getOrNull()

    suspend fun seasonEpisodes(mediaId: String, season: Int): List<BazarrEpisodeRow> = runCatching {
        httpClient.get("/api/media/$mediaId/bazarr/season/$season").body<List<BazarrEpisodeRow>>()
    }.getOrDefault(emptyList())

    private fun actionBody(language: String, forced: Boolean = false, hi: Boolean = false, path: String = "", episodeSeason: Int? = null, episodeNumber: Int? = null) =
        buildString {
            append("{\"language\":\"${language.jsonEsc()}\",\"forced\":$forced,\"hi\":$hi,\"path\":\"${path.jsonEsc()}\"")
            if (episodeSeason != null) append(",\"episodeSeason\":$episodeSeason")
            if (episodeNumber != null) append(",\"episodeNumber\":$episodeNumber")
            append("}")
        }

    suspend fun search(mediaId: String): Boolean = runCatching {
        httpClient.post("/api/media/$mediaId/bazarr/search").status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun download(mediaId: String, language: String, forced: Boolean, hi: Boolean, episodeSeason: Int? = null, episodeNumber: Int? = null): Boolean = runCatching {
        httpClient.post("/api/media/$mediaId/bazarr/download") {
            contentType(ContentType.Application.Json)
            setBody(actionBody(language, forced, hi, episodeSeason = episodeSeason, episodeNumber = episodeNumber))
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun sync(mediaId: String, language: String, path: String, episodeSeason: Int? = null, episodeNumber: Int? = null): Boolean = runCatching {
        httpClient.post("/api/media/$mediaId/bazarr/sync") {
            contentType(ContentType.Application.Json)
            setBody(actionBody(language, path = path, episodeSeason = episodeSeason, episodeNumber = episodeNumber))
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun upgrade(mediaId: String, language: String, forced: Boolean, hi: Boolean, episodeSeason: Int? = null, episodeNumber: Int? = null): Boolean = runCatching {
        httpClient.post("/api/media/$mediaId/bazarr/upgrade") {
            contentType(ContentType.Application.Json)
            setBody(actionBody(language, forced, hi, episodeSeason = episodeSeason, episodeNumber = episodeNumber))
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun delete(mediaId: String, language: String, forced: Boolean, hi: Boolean, path: String, episodeSeason: Int? = null, episodeNumber: Int? = null): Boolean = runCatching {
        httpClient.delete("/api/media/$mediaId/bazarr") {
            contentType(ContentType.Application.Json)
            setBody(actionBody(language, forced, hi, path, episodeSeason, episodeNumber))
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)
}

private fun String.jsonEsc() = replace("\\", "\\\\").replace("\"", "\\\"")
