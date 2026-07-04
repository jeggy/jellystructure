package dev.jellystructure.arr

import dev.jellystructure.OutboundHttp
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
data class ArrSystemStatus(val version: String = "", val appName: String = "")

@Serializable
private data class ArrRootFolder(val path: String = "")

@Serializable
private data class ArrMovieRef(val id: Int = 0, val tmdbId: Int = 0, val path: String = "")

@Serializable
private data class ArrSeriesRef(
    val id: Int = 0,
    val path: String = "",
    val status: String = "",          // "continuing" | "ended"
    val nextAiring: String? = null,   // ISO UTC datetime e.g. "2026-07-04T20:00:00Z"
    val tmdbId: Int = 0,              // Phase 139 — Sonarr v4 exposes this directly, no bridge needed
)

/** Sonarr series info exposed to R149 enrichment: id, path, status, next-airing UTC datetime. */
data class ArrSeriesInfo(
    val id: Int,
    val path: String,
    val status: String,
    val nextAiringUtc: String?,
)

/** Outcome of a connection probe. */
data class ArrPing(val ok: Boolean, val detail: String, val version: String? = null)

/**
 * Phase 54 — a tiny shared client for Radarr & Sonarr. Their v3 API is identical for the calls we
 * make. **Read + rescan only**: we never call any add/grab/delete endpoint here (that scope fence
 * belongs to Phase 56's acquisition engine). All methods take `url`/`apiKey` explicitly so the same
 * client serves both the test flow (temporary creds) and the post-write hook (stored config).
 */
class ArrClient {
    private suspend fun httpGet(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.get(url, block) }
    private suspend fun httpPost(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.post(url, block) }
    private suspend fun httpPut(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.put(url, block) }
    private suspend fun httpDelete(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): io.ktor.client.statement.HttpResponse =
        OutboundHttp.withPermit { http.delete(url, block) }

    // Phase 129 (FR-OPS1 §B.1) — shared client, one idle connection pool for all outbound callers.
    private val http = OutboundHttp.client

    private fun base(url: String) = url.trimEnd('/') + "/api/v3"

    suspend fun ping(url: String, apiKey: String): ArrPing = runCatching {
        val status: ArrSystemStatus = httpGet(base(url) + "/system/status") {
            header("X-Api-Key", apiKey)
        }.body()
        ArrPing(true, "Connected", status.version.ifBlank { null })
    }.getOrElse { e -> ArrPing(false, e.message ?: "Unknown error") }

    suspend fun rootFolders(url: String, apiKey: String): List<String> = runCatching {
        val folders: List<ArrRootFolder> = httpGet(base(url) + "/rootfolder") {
            header("X-Api-Key", apiKey)
        }.body()
        folders.map { it.path }.filter { it.isNotBlank() }
    }.getOrElse { emptyList() }

    /** Radarr: resolve a movie's `movieId` by its TMDB id. */
    suspend fun findMovieId(url: String, apiKey: String, tmdbId: Int): Int? = runCatching {
        val movies: List<ArrMovieRef> = httpGet(base(url) + "/movie") {
            header("X-Api-Key", apiKey)
            parameter("tmdbId", tmdbId)
        }.body()
        // Radarr honours ?tmdbId= (verified live: 1 hit for a present movie, 0 for absent), but we still
        // match exactly — never fall back to "first movie", so a server that ignored the filter and
        // returned the whole library could never make us rescan the wrong title.
        movies.firstOrNull { it.tmdbId == tmdbId }?.id
    }.getOrNull()

    /**
     * Sonarr: resolve a series' `seriesId` by matching its folder path. Our [path] is an episode file
     * inside the series folder, so we take the longest series `path` that is a prefix of it.
     */
    suspend fun findSeriesIdByPath(url: String, apiKey: String, path: String): Int? = runCatching {
        val series: List<ArrSeriesRef> = httpGet(base(url) + "/series") {
            header("X-Api-Key", apiKey)
        }.body()
        series.filter { it.path.isNotBlank() && (path == it.path || path.startsWith(it.path.trimEnd('/') + "/")) }
            .maxByOrNull { it.path.length }?.id
    }.getOrNull()

    /** R149: fetch all Sonarr series with status + next-airing datetime (one API call). */
    suspend fun getAllSeriesInfo(url: String, apiKey: String): List<ArrSeriesInfo> = runCatching {
        val series: List<ArrSeriesRef> = httpGet(base(url) + "/series") {
            header("X-Api-Key", apiKey)
        }.body()
        series.filter { it.path.isNotBlank() }.map {
            ArrSeriesInfo(id = it.id, path = it.path.trimEnd('/'), status = it.status, nextAiringUtc = it.nextAiring)
        }
    }.getOrElse { emptyList() }

    suspend fun rescanMovie(url: String, apiKey: String, movieId: Int): Boolean =
        command(url, apiKey, """{"name":"RescanMovie","movieId":$movieId}""")

    suspend fun rescanSeries(url: String, apiKey: String, seriesId: Int): Boolean =
        command(url, apiKey, """{"name":"RescanSeries","seriesId":$seriesId}""")

    private suspend fun command(url: String, apiKey: String, body: String): Boolean = runCatching {
        val resp = httpPost(base(url) + "/command") {
            header("X-Api-Key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.OK || resp.status == HttpStatusCode.Accepted
    }.getOrElse { false }

    // ---- Phase 56: acquisition (add + search, queue, cancel) ----

    suspend fun getQualityProfiles(url: String, apiKey: String): List<ArrQualityProfile> = runCatching {
        httpGet(base(url) + "/qualityprofile") { header("X-Api-Key", apiKey) }.body<List<ArrQualityProfile>>()
    }.getOrElse { emptyList() }

    /** Resolve a profile name to its id; blank name → the *arr's first/default profile. */
    suspend fun resolveQualityProfileId(url: String, apiKey: String, name: String): Int? {
        val profiles = getQualityProfiles(url, apiKey)
        if (profiles.isEmpty()) return null
        return if (name.isBlank()) profiles.first().id
        else profiles.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: profiles.first().id
    }

    /** Resolve the root folder: configured value, else the sole root; null = ambiguous (>1 root, none set). */
    suspend fun resolveRootFolder(url: String, apiKey: String, configured: String): String? {
        if (configured.isNotBlank()) return configured
        val roots = rootFolders(url, apiKey)
        return if (roots.size == 1) roots.first() else null
    }

    /** Sonarr: resolve a series' `seriesId` by TMDB id (Sonarr v4 exposes `tmdbId` directly on `/series`,
     *  unlike [findSeriesIdByPath] which is needed only where no tmdbId is in hand yet). */
    suspend fun findSeriesIdByTmdbId(url: String, apiKey: String, tmdbId: Int): Int? = runCatching {
        val series: List<ArrSeriesRef> = httpGet(base(url) + "/series") { header("X-Api-Key", apiKey) }.body()
        series.firstOrNull { it.tmdbId == tmdbId }?.id
    }.getOrNull()

    // ---- Phase 139: request-language provisioning (custom format + cloned quality profile) ----

    /** Every existing custom format, with the release-title regex it holds (blank if it isn't a
     *  `ReleaseTitleSpecification`) — enough to decide create-vs-update by name, idempotently. */
    suspend fun getCustomFormats(url: String, apiKey: String): List<ArrCustomFormatRef> = runCatching {
        val arr = httpGet(base(url) + "/customformat") { header("X-Api-Key", apiKey) }.body<JsonArray>()
        arr.map { it.jsonObject }.map { o ->
            val spec = o["specifications"]?.jsonArray?.firstOrNull()?.jsonObject
            val value = spec?.get("fields")?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { f -> f["name"]?.jsonPrimitive?.contentOrNull == "value" }
                ?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()
            ArrCustomFormatRef(o["id"]?.jsonPrimitive?.intOrNull ?: 0, o["name"]?.jsonPrimitive?.contentOrNull ?: "", value)
        }
    }.getOrElse { emptyList() }

    /**
     * Create-or-update (by [name]) a single-specification `ReleaseTitleSpecification` custom format
     * matching [regex]. Idempotent: a matching name+regex is left untouched; a matching name with a
     * different regex is updated in place (never duplicated). Returns the format's id, or null on failure.
     */
    suspend fun upsertReleaseTitleCustomFormat(url: String, apiKey: String, name: String, regex: String): Int? = runCatching {
        val existing = getCustomFormats(url, apiKey).firstOrNull { it.name == name }
        if (existing != null && existing.matchValue == regex) return existing.id
        val payload = buildJsonObject {
            put("name", name)
            put("includeCustomFormatWhenRenaming", false)
            put("specifications", buildJsonArray {
                add(buildJsonObject {
                    put("name", name)
                    put("implementation", "ReleaseTitleSpecification")
                    put("negate", false)
                    put("required", false)
                    put("fields", buildJsonArray {
                        add(buildJsonObject { put("name", "value"); put("value", regex) })
                    })
                })
            })
        }
        if (existing == null) {
            val resp = httpPost(base(url) + "/customformat") {
                header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(payload.toString())
            }
            if (resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.OK)
                resp.body<JsonObject>()["id"]?.jsonPrimitive?.intOrNull else null
        } else {
            val body = buildJsonObject { put("id", existing.id); payload.forEach { (k, v) -> put(k, v) } }
            val resp = httpPut(base(url) + "/customformat/${existing.id}") {
                header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(body.toString())
            }
            if (resp.status == HttpStatusCode.Accepted || resp.status == HttpStatusCode.OK) existing.id else null
        }
    }.getOrNull()

    /** Existing *arr indexer tags (name → id). Best-effort — a configured tag name with no match is
     *  simply dropped by the caller rather than blocking the request over a decorative extra (Phase 139's
     *  `tags` field is traffic hygiene, never load-bearing for correctness). */
    suspend fun getTags(url: String, apiKey: String): List<ArrTag> = runCatching {
        httpGet(base(url) + "/tag") { header("X-Api-Key", apiKey) }.body<List<ArrTag>>()
    }.getOrElse { emptyList() }

    /** Exact-name profile id lookup, no fallback-to-first (unlike [resolveQualityProfileId], which is
     *  for acquisition where a blank/unmatched name should still resolve to *something*) — provisioning
     *  must fail loudly on a typo'd base-profile name rather than silently clone the wrong one. */
    suspend fun findQualityProfileIdByName(url: String, apiKey: String, name: String): Int? =
        getQualityProfiles(url, apiKey).firstOrNull { it.name.equals(name, ignoreCase = true) }?.id

    /**
     * Create-or-update (by [newName]) a quality profile that scores [customFormatId]. On first creation,
     * clones every field from the [baseProfileName] profile (items/cutoff/language/etc. — the same
     * "echo the looked-up object, override a few keys" idiom as [addMovie]/[addSeries]); on later runs it
     * re-fetches and updates the **existing target profile itself** (not the base again), so a manual
     * tweak made after creation (e.g. widening allowed resolutions) survives a re-provision. [strict]
     * sets `minFormatScore` to the same value as the format's own score (a hard gate — only a release
     * carrying it ever qualifies) vs `0` (the format is preferred, not required). Returns the resulting
     * profile's id, or null if [baseProfileName] doesn't exist on first creation.
     */
    suspend fun upsertScoredQualityProfile(
        url: String,
        apiKey: String,
        baseProfileName: String,
        newName: String,
        customFormatId: Int,
        customFormatName: String,
        strict: Boolean,
    ): Int? = runCatching {
        val profiles = getQualityProfiles(url, apiKey)
        val existingId = profiles.firstOrNull { it.name.equals(newName, ignoreCase = true) }?.id
        val sourceId = existingId
            ?: profiles.firstOrNull { it.name.equals(baseProfileName, ignoreCase = true) }?.id
            ?: return null
        val sourceJson = httpGet(base(url) + "/qualityprofile/$sourceId") { header("X-Api-Key", apiKey) }.body<JsonObject>()
        val formatScore = if (strict) 10000 else 1000
        val minScore = if (strict) formatScore else 0
        val newFormatItems = buildJsonArray {
            (sourceJson["formatItems"]?.jsonArray ?: JsonArray(emptyList()))
                .map { it.jsonObject }
                .filter { it["format"]?.jsonPrimitive?.intOrNull != customFormatId }
                .forEach { add(it) }
            add(buildJsonObject { put("format", customFormatId); put("name", customFormatName); put("score", formatScore) })
        }
        val payload = buildJsonObject {
            sourceJson.forEach { (k, v) -> if (k != "id") put(k, v) }
            put("name", newName)
            put("formatItems", newFormatItems)
            put("minFormatScore", minScore)
        }
        if (existingId == null) {
            val resp = httpPost(base(url) + "/qualityprofile") {
                header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(payload.toString())
            }
            if (resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.OK)
                resp.body<JsonObject>()["id"]?.jsonPrimitive?.intOrNull else null
        } else {
            val body = buildJsonObject { put("id", existingId); payload.forEach { (k, v) -> put(k, v) } }
            val resp = httpPut(base(url) + "/qualityprofile/$existingId") {
                header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(body.toString())
            }
            if (resp.status == HttpStatusCode.Accepted || resp.status == HttpStatusCode.OK) existingId else null
        }
    }.getOrNull()

    /** Change-later (Phase 139 §E): re-point an already-added movie at a different quality profile,
     *  echoing its current object back with just `qualityProfileId` overridden (same idiom as [addMovie]). */
    suspend fun setMovieQualityProfile(url: String, apiKey: String, movieId: Int, profileId: Int): Boolean = runCatching {
        val movie = httpGet(base(url) + "/movie/$movieId") { header("X-Api-Key", apiKey) }.body<JsonObject>()
        val payload = buildJsonObject { movie.forEach { (k, v) -> put(k, v) }; put("qualityProfileId", profileId) }
        val resp = httpPut(base(url) + "/movie/$movieId") {
            header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(payload.toString())
        }
        resp.status == HttpStatusCode.Accepted || resp.status == HttpStatusCode.OK
    }.getOrElse { false }

    /** Change-later (Phase 139 §E): series equivalent of [setMovieQualityProfile]. */
    suspend fun setSeriesQualityProfile(url: String, apiKey: String, seriesId: Int, profileId: Int): Boolean = runCatching {
        val series = httpGet(base(url) + "/series/$seriesId") { header("X-Api-Key", apiKey) }.body<JsonObject>()
        val payload = buildJsonObject { series.forEach { (k, v) -> put(k, v) }; put("qualityProfileId", profileId) }
        val resp = httpPut(base(url) + "/series/$seriesId") {
            header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(payload.toString())
        }
        resp.status == HttpStatusCode.Accepted || resp.status == HttpStatusCode.OK
    }.getOrElse { false }

    /** Trigger an immediate search for one movie (verified against Radarr's `MoviesSearchCommand`:
     *  `name="MoviesSearch"`, `movieIds` is a list even for one item). */
    suspend fun searchMovieNow(url: String, apiKey: String, movieId: Int): Boolean =
        command(url, apiKey, """{"name":"MoviesSearch","movieIds":[$movieId]}""")

    /** Trigger an immediate search for one series (verified against Sonarr's `SeriesSearchCommand`:
     *  `name="SeriesSearch"`, singular `seriesId` — mirrors the existing [rescanSeries] shape exactly). */
    suspend fun searchSeriesNow(url: String, apiKey: String, seriesId: Int): Boolean =
        command(url, apiKey, """{"name":"SeriesSearch","seriesId":$seriesId}""")

    /** Radarr: add by TMDB id + start a search. Returns the new movieId, or null. */
    suspend fun addMovie(url: String, apiKey: String, tmdbId: Int, rootFolder: String, qualityProfileId: Int): Int? = runCatching {
        val lookups = httpGet(base(url) + "/movie/lookup") {
            header("X-Api-Key", apiKey); parameter("term", "tmdb:$tmdbId")
        }.body<JsonArray>()
        val movie = lookups.map { it.jsonObject }.firstOrNull { it["tmdbId"]?.jsonPrimitive?.intOrNull == tmdbId }
            ?: lookups.firstOrNull()?.jsonObject ?: return null
        val payload = buildJsonObject {
            movie.forEach { (k, v) -> put(k, v) }
            put("qualityProfileId", qualityProfileId)
            put("rootFolderPath", rootFolder)
            put("monitored", true)
            put("addOptions", buildJsonObject { put("searchForMovie", true) })
        }
        val resp = httpPost(base(url) + "/movie") {
            header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(payload.toString())
        }
        if (resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.OK)
            resp.body<JsonObject>()["id"]?.jsonPrimitive?.intOrNull else null
    }.getOrNull()

    /** Sonarr: add by TheTVDB id (bridge tmdb→tvdb first) + monitor scope + search. Returns seriesId. */
    suspend fun addSeries(url: String, apiKey: String, tvdbId: Int, rootFolder: String, qualityProfileId: Int, monitor: String, seasonFolder: Boolean): Int? = runCatching {
        val lookups = httpGet(base(url) + "/series/lookup") {
            header("X-Api-Key", apiKey); parameter("term", "tvdb:$tvdbId")
        }.body<JsonArray>()
        val series = lookups.map { it.jsonObject }.firstOrNull { it["tvdbId"]?.jsonPrimitive?.intOrNull == tvdbId }
            ?: lookups.firstOrNull()?.jsonObject ?: return null
        val payload = buildJsonObject {
            series.forEach { (k, v) -> put(k, v) }
            put("qualityProfileId", qualityProfileId)
            put("rootFolderPath", rootFolder)
            put("monitored", true)
            put("seasonFolder", seasonFolder)
            put("addOptions", buildJsonObject {
                put("monitor", monitor)
                put("searchForMissingEpisodes", true)
                put("searchForCutoffUnmetEpisodes", false)
            })
        }
        val resp = httpPost(base(url) + "/series") {
            header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(payload.toString())
        }
        if (resp.status == HttpStatusCode.Created || resp.status == HttpStatusCode.OK)
            resp.body<JsonObject>()["id"]?.jsonPrimitive?.intOrNull else null
    }.getOrNull()

    /** The *arr download queue (Radarr movies + Sonarr episodes), normalized. */
    suspend fun getQueue(url: String, apiKey: String): List<ArrQueueItem> = runCatching {
        val obj = httpGet(base(url) + "/queue") {
            header("X-Api-Key", apiKey); parameter("pageSize", 200); parameter("includeEpisode", true)
        }.body<JsonObject>()
        val records = obj["records"]?.jsonArray ?: return emptyList()
        records.map { it.jsonObject }.mapNotNull { r ->
            val refId = (r["movieId"] ?: r["seriesId"])?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val ep = r["episode"]?.jsonObject
            ArrQueueItem(
                id = r["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                refId = refId,
                downloadId = r["downloadId"]?.jsonPrimitive?.contentOrNull,
                status = r["status"]?.jsonPrimitive?.contentOrNull ?: "",
                trackedState = r["trackedDownloadState"]?.jsonPrimitive?.contentOrNull ?: "",
                trackedStatus = r["trackedDownloadStatus"]?.jsonPrimitive?.contentOrNull ?: "",
                sizeLeft = r["sizeleft"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                size = r["size"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                timeLeft = r["timeleft"]?.jsonPrimitive?.contentOrNull,
                season = ep?.get("seasonNumber")?.jsonPrimitive?.intOrNull,
                episode = ep?.get("episodeNumber")?.jsonPrimitive?.intOrNull,
                errorMessage = r["errorMessage"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }.getOrElse { emptyList() }

    /** Sonarr: monitored/aired/hasFile state per episode, for the series roll-up. */
    suspend fun getSeriesEpisodes(url: String, apiKey: String, seriesId: Int): List<ArrEpisode> = runCatching {
        httpGet(base(url) + "/episode") {
            header("X-Api-Key", apiKey); parameter("seriesId", seriesId)
        }.body<List<ArrEpisode>>()
    }.getOrElse { emptyList() }

    /**
     * R160 — Sonarr calendar: monitored episodes airing within [start, end] (both "yyyy-MM-dd"),
     * nested `series` object included (id/title/network/tvdbId) so the caller can resolve a
     * catalogue match without a second per-series call. Best-effort: empty list on any failure.
     */
    suspend fun getSonarrCalendar(url: String, apiKey: String, start: String, end: String): List<ArrCalendarEpisode> = runCatching {
        httpGet(base(url) + "/calendar") {
            header("X-Api-Key", apiKey)
            parameter("start", start); parameter("end", end)
            parameter("includeSeries", true)
        }.body<List<ArrCalendarEpisode>>()
    }.getOrElse { emptyList() }

    /**
     * R160 — Radarr calendar: monitored movies releasing within [start, end] (both "yyyy-MM-dd").
     * Best-effort: empty list on any failure.
     */
    suspend fun getRadarrCalendar(url: String, apiKey: String, start: String, end: String): List<ArrCalendarMovie> = runCatching {
        httpGet(base(url) + "/calendar") {
            header("X-Api-Key", apiKey)
            parameter("start", start); parameter("end", end)
        }.body<List<ArrCalendarMovie>>()
    }.getOrElse { emptyList() }

    suspend fun deleteMovie(url: String, apiKey: String, movieId: Int): Boolean =
        del(url, apiKey, "/movie/$movieId?deleteFiles=false&addImportExclusion=false")

    suspend fun deleteSeries(url: String, apiKey: String, seriesId: Int): Boolean =
        del(url, apiKey, "/series/$seriesId?deleteFiles=false&addImportExclusion=false")

    suspend fun deleteQueueItem(url: String, apiKey: String, queueId: Long): Boolean =
        del(url, apiKey, "/queue/$queueId?removeFromClient=true&blocklist=false")

    private suspend fun del(url: String, apiKey: String, path: String): Boolean = runCatching {
        val resp = httpDelete(base(url) + path) { header("X-Api-Key", apiKey) }
        resp.status == HttpStatusCode.OK || resp.status == HttpStatusCode.NoContent || resp.status == HttpStatusCode.Accepted
    }.getOrElse { false }
}

@Serializable
data class ArrQualityProfile(val id: Int = 0, val name: String = "")

@Serializable
data class ArrTag(val id: Int = 0, val label: String = "")

/** Phase 139 — an existing custom format's id/name/regex, enough to decide idempotent create-vs-update
 *  without a second per-id fetch (the `/customformat` list endpoint already returns full specs). */
data class ArrCustomFormatRef(val id: Int, val name: String, val matchValue: String)

@Serializable
data class ArrEpisode(
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val monitored: Boolean = false,
    val hasFile: Boolean = false,
    val airDateUtc: String? = null,
    val title: String? = null,
)

/** Unified download-queue item across Radarr (movieId) and Sonarr (seriesId + episode). */
data class ArrQueueItem(
    val id: Long,
    val refId: Int,
    val downloadId: String?,
    val status: String,
    val trackedState: String,
    val trackedStatus: String,
    val sizeLeft: Double,
    val size: Double,
    val timeLeft: String?,
    val season: Int?,
    val episode: Int?,
    val errorMessage: String?,
)

/** R160 — one Sonarr calendar entry (`GET /calendar?includeSeries=true`). */
@Serializable
data class ArrCalendarEpisode(
    val seriesId: Int = 0,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String = "",             // episode title
    val airDateUtc: String? = null,     // "2026-07-10T20:00:00Z"
    val airDate: String? = null,        // "2026-07-10" — series-local calendar date
    val overview: String? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
    val series: ArrCalendarSeriesRef? = null,
)

@Serializable
data class ArrCalendarSeriesRef(
    val title: String = "",
    val tvdbId: Int = 0,
    // Bug fix: TMDB's own external_ids can lag/omit the TheTVDB cross-reference for a show even when
    // Sonarr (which resolves TVDB IDs directly) has the correct one — jellystructure's own scan then
    // stores a null tvdbId, so tvdbId-only matching against our MediaStore permanently misses a show we
    // actually hold in full. Sonarr's calendar series object also carries tmdbId; used as a fallback
    // match key in UpcomingService.
    val tmdbId: Int = 0,
    val network: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val images: List<ArrCalendarImage> = emptyList(),  // R167 — poster/fanart remoteUrl, client-direct CDN
)

/** R167 — one Sonarr/Radarr calendar-response image ref. `remoteUrl` points at the upstream CDN
 *  (TheTVDB/TMDB/fanart.tv) and is loaded by the client verbatim — no jellystructure proxy. */
@Serializable
data class ArrCalendarImage(
    val coverType: String = "",  // "poster" | "fanart" | ...
    val remoteUrl: String? = null,
    val url: String? = null,
)

/** R160 — one Radarr calendar entry (`GET /calendar`). [id] is Radarr's own movieId (matches
 *  [ArrQueueItem.refId] — the queue is keyed by Radarr's internal id, not tmdbId). */
@Serializable
data class ArrCalendarMovie(
    val id: Int = 0,
    val tmdbId: Int = 0,
    val title: String = "",
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val inCinemas: String? = null,       // "2026-07-10" or "2026-07-10T00:00:00Z"
    val physicalRelease: String? = null,
    val digitalRelease: String? = null,
    val hasFile: Boolean = false,
    val monitored: Boolean = false,
    val isAvailable: Boolean = false,
    val minimumAvailability: String = "",  // "tba"|"announced"|"inCinemas"|"released"|"preDB" (R168 — display only; isAvailable already bakes it in)
    val images: List<ArrCalendarImage> = emptyList(),  // R167 — poster/fanart remoteUrl, client-direct CDN
)
