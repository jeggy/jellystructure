package dev.jellystructure.bazarr

import dev.jellystructure.OutboundHttp
import dev.jellystructure.arr.ArrPing
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.http.isSuccess
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
    /** Phase 273 (FR-273-18) — blank means Bazarr searches this show's subtitles by title. */
    val imdbId: String? = null,
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

/** Bazarr's own language object on a history entry (`{name, code2, code3, forced, hi}`). */
@Serializable
data class BazarrLanguageRef(val name: String = "", val code2: String = "", val code3: String = "", val forced: Boolean = false, val hi: Boolean = false)

/**
 * Phase 273 — one entry of Bazarr's episode or movie history as Bazarr sends it. Phase 157 decoded these straight
 * into [BazarrHistoryEvent], whose `language` is a string while Bazarr sends an object, so every history read
 * failed to decode and came back empty. Decoded here, then mapped to the admin's [BazarrHistoryEvent] shape.
 */
@Serializable
data class BazarrHistoryRow(
    val action: Int = 0,
    val language: BazarrLanguageRef? = null,
    val provider: String? = null,
    val score: String? = null,
    val timestamp: String? = null,
    val description: String? = null,
    @SerialName("subs_id") val subsId: String? = null,
    @SerialName("subtitles_path") val subtitlesPath: String? = null,
    val sonarrEpisodeId: Int? = null,
    val sonarrSeriesId: Int? = null,
    val radarrId: Int? = null,
    val blacklisted: Boolean = false,
    val upgradable: Boolean = false,
    val matches: List<String> = emptyList(),
) {
    fun toEvent() = BazarrHistoryEvent(action, language?.code2, provider, score, timestamp, description)

    companion object {
        /** Bazarr's history actions that put a subtitle on disk: downloaded, manual download, upgraded, uploaded, translated. */
        val PLACED = setOf(1, 2, 3, 4, 6)
    }
}

@Serializable
data class BazarrTask(
    @SerialName("job_id") val jobId: String = "",
    val name: String = "",
    val running: Boolean = false,
    @SerialName("next_run_in") val nextRunIn: String? = null,
)

/** One result from a manual provider search (`GET /api/providers/{movies,episodes}`), enough to re-submit via
 *  [BazarrClient.downloadProviderEpisodeSubtitle]. Bazarr wraps the list in `data` and sends `forced` and
 *  `hearing_impaired` as the strings `"True"`/`"False"` (Phase 273: the Phase 157 model expected a bare list of
 *  booleans, so every manual search decoded to nothing). [subtitle] is a cache key Bazarr keeps for an hour. */
@Serializable
data class BazarrProviderResult(
    val provider: String = "",
    val subtitle: String = "",
    val language: String = "",
    @SerialName("forced") val forcedText: String? = null,
    @SerialName("hearing_impaired") val hiText: String? = null,
    val score: Int? = null,
    @SerialName("orig_score") val origScore: Int? = null,
    @SerialName("matches") val matches: List<String> = emptyList(),
    @SerialName("release_info") val releaseInfo: List<String> = emptyList(),
    val uploader: String? = null,
) {
    val forced: Boolean get() = forcedText.equals("true", ignoreCase = true)
    val hi: Boolean get() = hiText.equals("true", ignoreCase = true)
}

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
class BazarrClient : BazarrOps {
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
    override suspend fun allMovies(url: String, apiKey: String): List<BazarrMovie> = runCatching {
        httpGet(base(url) + "/movies", apiKey) { parameter("length", -1) }.body<BazarrEnvelope<BazarrMovie>>().data
    }.getOrElse { emptyList() }

    /** All series Bazarr knows about (for the tvdbId matching join). */
    override suspend fun allSeries(url: String, apiKey: String): List<BazarrSeries> = runCatching {
        httpGet(base(url) + "/series", apiKey) { parameter("length", -1) }.body<BazarrEnvelope<BazarrSeries>>().data
    }.getOrElse { emptyList() }

    override suspend fun episodesFor(url: String, apiKey: String, sonarrSeriesId: Int): List<BazarrEpisode> = runCatching {
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

    suspend fun movieHistory(url: String, apiKey: String, radarrId: Int? = null, start: Int = 0, length: Int = 20): List<BazarrHistoryEvent> =
        movieHistoryRows(url, apiKey, radarrId, start, length).map { it.toEvent() }

    suspend fun episodeHistory(url: String, apiKey: String, episodeId: Int? = null, start: Int = 0, length: Int = 20): List<BazarrHistoryEvent> =
        episodeHistoryRows(url, apiKey, episodeId, start, length).map { it.toEvent() }

    override suspend fun movieHistoryRows(url: String, apiKey: String, radarrId: Int?, start: Int, length: Int): List<BazarrHistoryRow> = runCatching {
        httpGet(base(url) + "/movies/history", apiKey) {
            parameter("start", start); parameter("length", length)
            if (radarrId != null) parameter("radarrid", radarrId)
        }.body<BazarrEnvelope<BazarrHistoryRow>>().data
    }.getOrElse { emptyList() }

    override suspend fun episodeHistoryRows(url: String, apiKey: String, episodeId: Int?, start: Int, length: Int): List<BazarrHistoryRow> = runCatching {
        httpGet(base(url) + "/episodes/history", apiKey) {
            parameter("start", start); parameter("length", length)
            if (episodeId != null) parameter("episodeid", episodeId)
        }.body<BazarrEnvelope<BazarrHistoryRow>>().data
    }.getOrElse { emptyList() }

    /** Phase 273 — one episode by its Sonarr id (the hook's `{{episode_id}}`), with its subtitle files as Bazarr names them. */
    override suspend fun episodeById(url: String, apiKey: String, sonarrEpisodeId: Int): BazarrEpisode? = runCatching {
        httpGet(base(url) + "/episodes", apiKey) { parameter("episodeid[]", sonarrEpisodeId) }.body<BazarrEnvelope<BazarrEpisode>>().data.firstOrNull()
    }.getOrNull()

    /** Phase 273 — one movie by its Radarr id. */
    override suspend fun movieById(url: String, apiKey: String, radarrId: Int): BazarrMovie? = runCatching {
        httpGet(base(url) + "/movies", apiKey) { parameter("radarrid[]", radarrId) }.body<BazarrEnvelope<BazarrMovie>>().data.firstOrNull()
    }.getOrNull()

    /** Phase 273 (FR-273-18) — Bazarr's whole configuration. It carries every secret Bazarr holds: read on the server
     *  only, never forwarded to a browser. */
    suspend fun systemSettings(url: String, apiKey: String): kotlinx.serialization.json.JsonObject? = runCatching {
        httpGet(base(url) + "/system/settings", apiKey).body<kotlinx.serialization.json.JsonObject>()
    }.getOrNull()

    /** Phase 273 (FR-273-19) — *Apply in Bazarr*: posts only [fields] (`settings-<section>-<key>` → value), the way
     *  Bazarr's own settings page saves; Bazarr changes only the keys it receives. */
    suspend fun applySettings(url: String, apiKey: String, fields: Map<String, String>): Boolean = runCatching {
        OutboundHttp.withPermit {
            http.submitForm(url = base(url) + "/system/settings", formParameters = parameters { fields.forEach { (k, v) -> append(k, v) } }) {
                header("X-API-KEY", apiKey)
            }
        }.status.isSuccess()
    }.getOrElse { false }

    /** Phase 273 (FR-273-13) — blacklist a subtitle for this episode. Bazarr writes the (provider, id) row, deletes
     *  the file, then searches again at once. [subtitlesPath] is Bazarr's own path for the file. */
    override suspend fun blacklistEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, provider: String, subsId: String, language: String, subtitlesPath: String): Boolean = runCatching {
        httpPost(base(url) + "/episodes/blacklist", apiKey) {
            parameter("seriesid", seriesId); parameter("episodeid", episodeId); parameter("provider", provider)
            parameter("subs_id", subsId); parameter("language", language); parameter("subtitles_path", subtitlesPath)
        }.status.isSuccess()
    }.getOrElse { false }

    override suspend fun blacklistMovieSubtitle(url: String, apiKey: String, radarrId: Int, provider: String, subsId: String, language: String, subtitlesPath: String): Boolean = runCatching {
        httpPost(base(url) + "/movies/blacklist", apiKey) {
            parameter("radarrid", radarrId); parameter("provider", provider)
            parameter("subs_id", subsId); parameter("language", language); parameter("subtitles_path", subtitlesPath)
        }.status.isSuccess()
    }.getOrElse { false }

    /** Phase 273 (FR-273-11/15) — hand Bazarr a subtitle file for an episode; Bazarr names and places it and records
     *  it at the maximum score, so its upgrade job leaves it alone. */
    override suspend fun uploadEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, language: String, forced: Boolean, hi: Boolean, fileName: String, content: ByteArray): Boolean =
        upload(base(url) + "/episodes/subtitles", apiKey, mapOf("seriesid" to "$seriesId", "episodeid" to "$episodeId"), language, forced, hi, fileName, content)

    override suspend fun uploadMovieSubtitle(url: String, apiKey: String, radarrId: Int, language: String, forced: Boolean, hi: Boolean, fileName: String, content: ByteArray): Boolean =
        upload(base(url) + "/movies/subtitles", apiKey, mapOf("radarrid" to "$radarrId"), language, forced, hi, fileName, content)

    private suspend fun upload(endpoint: String, apiKey: String, ids: Map<String, String>, language: String, forced: Boolean, hi: Boolean, fileName: String, content: ByteArray): Boolean = runCatching {
        OutboundHttp.withPermit {
            http.submitFormWithBinaryData(url = endpoint, formData = formData {
                ids.forEach { (k, v) -> append(k, v) }
                append("language", language)
                append("forced", if (forced) "True" else "False")
                append("hi", if (hi) "True" else "False")
                append("file", content, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                    append(HttpHeaders.ContentType, "application/x-subrip")
                })
            }) { header("X-API-KEY", apiKey) }
        }.status.isSuccess()
    }.getOrElse { false }

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

    override suspend fun deleteMovieSubtitle(url: String, apiKey: String, radarrId: Int, language: String, forced: Boolean, hi: Boolean, path: String): Boolean = runCatching {
        httpDelete(base(url) + "/movies/subtitles", apiKey) {
            parameter("radarrid", radarrId); parameter("language", language)
            parameter("forced", forced.toString()); parameter("hi", hi.toString()); parameter("path", path)
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    override suspend fun deleteEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, language: String, forced: Boolean, hi: Boolean, path: String): Boolean = runCatching {
        httpDelete(base(url) + "/episodes/subtitles", apiKey) {
            parameter("seriesid", seriesId); parameter("episodeid", episodeId); parameter("language", language)
            parameter("forced", forced.toString()); parameter("hi", hi.toString()); parameter("path", path)
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    /** ffsubsync re-time — the one endpoint the spec's "Sync" button maps to directly. Phase 273 (FR-273-12) tells it
     *  how: [reference] (`s:N`, `a:N`, or a subtitle file's path as Bazarr sees it), [maxOffsetSeconds], whether to
     *  fix the frame rate and whether to search for the speed ([gss]). Absent = Bazarr's own settings. */
    override suspend fun syncSubtitle(
        url: String, apiKey: String, type: String, id: Int, language: String, path: String,
        reference: String?, maxOffsetSeconds: Int?, noFixFramerate: Boolean?, gss: Boolean?,
        forced: Boolean, hi: Boolean,
    ): Boolean = runCatching {
        httpPatch(base(url) + "/subtitles", apiKey) {
            parameter("action", "sync"); parameter("type", type); parameter("id", id)
            parameter("language", language); parameter("path", path)
            parameter("forced", if (forced) "True" else "False"); parameter("hi", if (hi) "True" else "False")
            if (reference != null) parameter("reference", reference)
            if (maxOffsetSeconds != null) parameter("max_offset_seconds", maxOffsetSeconds.toString())
            if (noFixFramerate != null) parameter("no_fix_framerate", if (noFixFramerate) "True" else "False")
            if (gss != null) parameter("gss", if (gss) "True" else "False")
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    /** Manual search — list candidate subtitles for a movie/episode so the UI can offer a pick. */
    suspend fun searchProvidersMovie(url: String, apiKey: String, radarrId: Int): List<BazarrProviderResult> = runCatching {
        httpGet(base(url) + "/providers/movies", apiKey) { parameter("radarrid", radarrId) }.body<BazarrEnvelope<BazarrProviderResult>>().data
    }.getOrElse { emptyList() }

    override suspend fun searchProvidersEpisode(url: String, apiKey: String, episodeId: Int): List<BazarrProviderResult> = runCatching {
        httpGet(base(url) + "/providers/episodes", apiKey) { parameter("episodeid", episodeId) }.body<BazarrEnvelope<BazarrProviderResult>>().data
    }.getOrElse { emptyList() }

    /** Manually download one specific search result (also how "Upgrade" is composed — search again,
     *  then download whichever result now beats what's on disk). */
    suspend fun downloadProviderMovieSubtitle(url: String, apiKey: String, radarrId: Int, hi: Boolean, forced: Boolean, provider: String, subtitle: String): Boolean = runCatching {
        httpPost(base(url) + "/providers/movies", apiKey) {
            parameter("radarrid", radarrId); parameter("hi", hi.toString()); parameter("forced", forced.toString())
            parameter("original_format", "False"); parameter("provider", provider); parameter("subtitle", subtitle)
        }.status == HttpStatusCode.NoContent
    }.getOrElse { false }

    /** Phase 273 — Bazarr requires `seriesid` here (Phase 157 never sent it). A candidate key found by searching one
     *  episode can be downloaded onto another: Bazarr saves it against the episode this call names (FR-273-14). */
    override suspend fun downloadProviderEpisodeSubtitle(url: String, apiKey: String, seriesId: Int, episodeId: Int, hi: Boolean, forced: Boolean, provider: String, subtitle: String): Boolean = runCatching {
        httpPost(base(url) + "/providers/episodes", apiKey) {
            parameter("seriesid", seriesId); parameter("episodeid", episodeId); parameter("hi", if (hi) "True" else "False"); parameter("forced", if (forced) "True" else "False")
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
