package dev.jellystructure.bazarr

import dev.jellystructure.OutboundHttp
import dev.jellystructure.arr.ArrPing
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class BazarrStatus(
    @SerialName("bazarr_version") val bazarrVersion: String = "",
)

@Serializable
private data class BazarrEnvelope<T>(val data: List<T> = emptyList(), val total: Int = 0)

@Serializable
data class BazarrLanguage(val name: String = "", val code2: String = "", val code3: String = "")

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
data class BazarrMissingSubtitle(val name: String = "", val code2: String = "", val forced: Boolean = false, val hi: Boolean = false)

/** `GET /api/movies` — one Bazarr movie entry. [imdbId] + [path] are the matching keys against jellystructure's own MediaItem. */
@Serializable
data class BazarrMovie(
    val title: String = "",
    val path: String = "",
    val imdbId: String? = null,
    val radarrId: Int = 0,
    val monitored: Boolean = false,
    val profileId: Int? = null,
    @SerialName("missing_subtitles") val missingSubtitles: List<BazarrMissingSubtitle> = emptyList(),
    val subtitles: List<BazarrSubtitleFile> = emptyList(),
)

/** `GET /api/series` — one Bazarr series entry. [tvdbId] + [path] are the matching keys. */
@Serializable
data class BazarrSeries(
    val title: String = "",
    val path: String = "",
    val tvdbId: Int? = null,
    val sonarrSeriesId: Int = 0,
    val monitored: Boolean = false,
    val profileId: Int? = null,
    @SerialName("episodeMissingCount") val episodeMissingCount: Int = 0,
)

/** `GET /api/episodes?seriesid[]=` — matched to a jellystructure Episode by season+episode number. */
@Serializable
data class BazarrEpisode(
    val title: String = "",
    val path: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val sonarrSeriesId: Int = 0,
    val sonarrEpisodeId: Int = 0,
    val monitored: Boolean = false,
    @SerialName("missing_subtitles") val missingSubtitles: List<BazarrMissingSubtitle> = emptyList(),
    val subtitles: List<BazarrSubtitleFile> = emptyList(),
)

@Serializable
data class BazarrWantedMovie(
    val title: String = "",
    val radarrId: Int = 0,
    @SerialName("missing_subtitles") val missingSubtitles: List<BazarrMissingSubtitle> = emptyList(),
)

@Serializable
data class BazarrWantedEpisode(
    val seriesTitle: String = "",
    val episodeTitle: String = "",
    @SerialName("episode_number") val episodeNumber: String = "",
    val sonarrSeriesId: Int = 0,
    val sonarrEpisodeId: Int = 0,
    @SerialName("missing_subtitles") val missingSubtitles: List<BazarrMissingSubtitle> = emptyList(),
)

data class BazarrWantedPage<T>(val items: List<T>, val total: Int)

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

@Serializable
data class BazarrTask(
    @SerialName("job_id") val jobId: String = "",
    val name: String = "",
    val running: Boolean = false,
    @SerialName("next_run_in") val nextRunIn: String? = null,
)

/** One result from a manual provider search (`GET /api/providers/{movies,episodes}`), enough to re-submit via [BazarrClient.downloadProviderSubtitle]. */
@Serializable
data class BazarrProviderResult(
    val provider: String = "",
    val subtitle: String = "",
    val language: String = "",
    val forced: Boolean = false,
    val hi: Boolean = false,
    val score: String? = null,
    @SerialName("matches") val matches: List<String> = emptyList(),
)

/**
 * Phase 157 — thin client for Bazarr's REST API, mirroring [dev.jellystructure.arr.ArrClient] /
 * [dev.jellystructure.seerr.SeerrClient]'s shape: shared [OutboundHttp] permit, `ping()` -> [ArrPing],
 * a `base(url)` helper. Auth header is `X-API-KEY` (Bazarr's own casing, verified against a live
 * instance's `/api/swagger.json`) rather than Arr's `X-Api-Key`.
 *
 * Every command endpoint here was verified against a real running Bazarr 1.6.0 instance during the
 * phase-157 dev-review addendum, not assumed from public docs — see that addendum for the full
 * swagger dump this is built from.
 */
class BazarrClient {
    private val http = OutboundHttp.client
    private fun base(url: String) = url.trimEnd('/') + "/api"

    private suspend fun httpGet(url: String, apiKey: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}) =
        OutboundHttp.withPermit { http.get(url) { header("X-API-KEY", apiKey); block() } }
    private suspend fun httpPost(url: String, apiKey: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}) =
        OutboundHttp.withPermit { http.post(url) { header("X-API-KEY", apiKey); block() } }
    private suspend fun httpPatch(url: String, apiKey: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}) =
        OutboundHttp.withPermit { http.patch(url) { header("X-API-KEY", apiKey); block() } }
    private suspend fun httpDelete(url: String, apiKey: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}) =
        OutboundHttp.withPermit { http.delete(url) { header("X-API-KEY", apiKey); block() } }

    suspend fun ping(url: String, apiKey: String): ArrPing = runCatching {
        val status: BazarrStatus = httpGet(base(url) + "/system/status", apiKey).body()
        ArrPing(true, "Connected", status.bazarrVersion.ifBlank { null })
    }.getOrElse { e -> ArrPing(false, e.message ?: "Unknown error") }

    /** All movies Bazarr knows about (for the imdbId matching join) — `length=-1` returns everything. */
    suspend fun allMovies(url: String, apiKey: String): List<BazarrMovie> = runCatching {
        httpGet(base(url) + "/movies", apiKey) { parameter("length", -1) }.body<BazarrEnvelope<BazarrMovie>>().data
    }.getOrElse { emptyList() }

    /** All series Bazarr knows about (for the tvdbId matching join). */
    suspend fun allSeries(url: String, apiKey: String): List<BazarrSeries> = runCatching {
        httpGet(base(url) + "/series", apiKey) { parameter("length", -1) }.body<BazarrEnvelope<BazarrSeries>>().data
    }.getOrElse { emptyList() }

    suspend fun episodesFor(url: String, apiKey: String, sonarrSeriesId: Int): List<BazarrEpisode> = runCatching {
        httpGet(base(url) + "/episodes", apiKey) { parameter("seriesid[]", sonarrSeriesId) }.body<BazarrEnvelope<BazarrEpisode>>().data
    }.getOrElse { emptyList() }

    suspend fun wantedMovies(url: String, apiKey: String, start: Int, length: Int): BazarrWantedPage<BazarrWantedMovie> = runCatching {
        val env = httpGet(base(url) + "/movies/wanted", apiKey) { parameter("start", start); parameter("length", length) }
            .body<BazarrEnvelope<BazarrWantedMovie>>()
        BazarrWantedPage(env.data, env.total)
    }.getOrElse { BazarrWantedPage(emptyList(), 0) }

    suspend fun wantedEpisodes(url: String, apiKey: String, start: Int, length: Int): BazarrWantedPage<BazarrWantedEpisode> = runCatching {
        val env = httpGet(base(url) + "/episodes/wanted", apiKey) { parameter("start", start); parameter("length", length) }
            .body<BazarrEnvelope<BazarrWantedEpisode>>()
        BazarrWantedPage(env.data, env.total)
    }.getOrElse { BazarrWantedPage(emptyList(), 0) }

    suspend fun providers(url: String, apiKey: String): List<BazarrProviderStatus> = runCatching {
        httpGet(base(url) + "/providers", apiKey).body<BazarrEnvelope<BazarrProviderStatus>>().data
    }.getOrElse { emptyList() }

    suspend fun languageProfiles(url: String, apiKey: String): List<BazarrLanguageProfile> = runCatching {
        httpGet(base(url) + "/system/languages/profiles", apiKey).body<List<BazarrLanguageProfile>>()
    }.getOrElse { emptyList() }

    suspend fun movieHistory(url: String, apiKey: String, radarrId: Int? = null, start: Int = 0, length: Int = 20): List<BazarrHistoryEvent> = runCatching {
        httpGet(base(url) + "/movies/history", apiKey) {
            parameter("start", start); parameter("length", length)
            if (radarrId != null) parameter("radarrid", radarrId)
        }.body<BazarrEnvelope<BazarrHistoryEvent>>().data
    }.getOrElse { emptyList() }

    suspend fun episodeHistory(url: String, apiKey: String, episodeId: Int? = null, start: Int = 0, length: Int = 20): List<BazarrHistoryEvent> = runCatching {
        httpGet(base(url) + "/episodes/history", apiKey) {
            parameter("start", start); parameter("length", length)
            if (episodeId != null) parameter("episodeid", episodeId)
        }.body<BazarrEnvelope<BazarrHistoryEvent>>().data
    }.getOrElse { emptyList() }

    suspend fun tasks(url: String, apiKey: String): List<BazarrTask> = runCatching {
        httpGet(base(url) + "/system/tasks", apiKey).body<BazarrEnvelope<BazarrTask>>().data
    }.getOrElse { emptyList() }

    /** Fire a Bazarr scheduled task by id — used for full scan (`movies_full_scan_subtitles` /
     *  `series_full_scan_subtitles`) and library-wide upgrade (`upgrade_subtitles`); these are NOT
     *  per-item, see the phase-157 addendum. */
    suspend fun runTask(url: String, apiKey: String, taskId: String): Boolean = runCatching {
        httpPost(base(url) + "/system/tasks", apiKey) { parameter("taskid", taskId) }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    /** Auto-search + download the best-scoring subtitle for one wanted language. */
    suspend fun downloadMovieSubtitle(url: String, apiKey: String, radarrId: Int, language: String, forced: Boolean, hi: Boolean): Boolean = runCatching {
        httpPatch(base(url) + "/movies/subtitles", apiKey) {
            parameter("radarrid", radarrId); parameter("language", language)
            parameter("forced", forced.toString()); parameter("hi", hi.toString())
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    suspend fun downloadEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, language: String, forced: Boolean, hi: Boolean): Boolean = runCatching {
        httpPatch(base(url) + "/episodes/subtitles", apiKey) {
            parameter("seriesid", seriesId); parameter("episodeid", episodeId)
            parameter("language", language); parameter("forced", forced.toString()); parameter("hi", hi.toString())
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    suspend fun deleteMovieSubtitle(url: String, apiKey: String, radarrId: Int, language: String, forced: Boolean, hi: Boolean, path: String): Boolean = runCatching {
        httpDelete(base(url) + "/movies/subtitles", apiKey) {
            parameter("radarrid", radarrId); parameter("language", language)
            parameter("forced", forced.toString()); parameter("hi", hi.toString()); parameter("path", path)
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    suspend fun deleteEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, language: String, forced: Boolean, hi: Boolean, path: String): Boolean = runCatching {
        httpDelete(base(url) + "/episodes/subtitles", apiKey) {
            parameter("seriesid", seriesId); parameter("episodeid", episodeId); parameter("language", language)
            parameter("forced", forced.toString()); parameter("hi", hi.toString()); parameter("path", path)
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    /** ffsubsync re-time — the one endpoint the spec's "Sync" button maps to directly. */
    suspend fun syncSubtitle(url: String, apiKey: String, type: String, id: Int, language: String, path: String): Boolean = runCatching {
        httpPatch(base(url) + "/subtitles", apiKey) {
            parameter("action", "sync"); parameter("type", type); parameter("id", id)
            parameter("language", language); parameter("path", path)
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    /** Manual search — list candidate subtitles for a movie/episode so the UI can offer a pick. */
    suspend fun searchProvidersMovie(url: String, apiKey: String, radarrId: Int): List<BazarrProviderResult> = runCatching {
        httpGet(base(url) + "/providers/movies", apiKey) { parameter("radarrid", radarrId) }.body<List<BazarrProviderResult>>()
    }.getOrElse { emptyList() }

    suspend fun searchProvidersEpisode(url: String, apiKey: String, episodeId: Int): List<BazarrProviderResult> = runCatching {
        httpGet(base(url) + "/providers/episodes", apiKey) { parameter("episodeid", episodeId) }.body<List<BazarrProviderResult>>()
    }.getOrElse { emptyList() }

    /** Manually download one specific search result (also how "Upgrade" is composed — search again,
     *  then download whichever result now beats what's on disk). */
    suspend fun downloadProviderMovieSubtitle(url: String, apiKey: String, radarrId: Int, hi: Boolean, forced: Boolean, provider: String, subtitle: String): Boolean = runCatching {
        httpPost(base(url) + "/providers/movies", apiKey) {
            parameter("radarrid", radarrId); parameter("hi", hi.toString()); parameter("forced", forced.toString())
            parameter("original_format", "False"); parameter("provider", provider); parameter("subtitle", subtitle)
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    suspend fun downloadProviderEpisodeSubtitle(url: String, apiKey: String, episodeId: Int, hi: Boolean, forced: Boolean, provider: String, subtitle: String): Boolean = runCatching {
        httpPost(base(url) + "/providers/episodes", apiKey) {
            parameter("episodeid", episodeId); parameter("hi", hi.toString()); parameter("forced", forced.toString())
            parameter("original_format", "False"); parameter("provider", provider); parameter("subtitle", subtitle)
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    /** Per-item "search all wanted" sweep (movie/series card level) — `action` from
     *  `["scan-disk","search-missing","search-wanted","sync"]`. */
    suspend fun movieAction(url: String, apiKey: String, radarrId: Int, action: String): Boolean = runCatching {
        httpPatch(base(url) + "/movies", apiKey) { parameter("radarrid", radarrId); parameter("action", action) }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    suspend fun seriesAction(url: String, apiKey: String, seriesId: Int, action: String): Boolean = runCatching {
        httpPatch(base(url) + "/series", apiKey) { parameter("seriesid", seriesId); parameter("action", action) }.status == HttpStatusCode.NoContent
    }.getOrElse { false }
}
