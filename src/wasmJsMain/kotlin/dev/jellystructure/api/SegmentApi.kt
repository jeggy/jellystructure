package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/** Mirrors `dev.jellystructure.media.SegmentKind` (linuxX64Main — a different source set, so this is a
 *  duplicated constant vocabulary, same pattern as every other DTO in this file). */
object SegKind {
    const val RECAP = "recap"
    const val INTRO = "intro"
    const val PREVIEW = "preview"
    const val CREDITS = "credits"
    const val STINGER = "stinger"
    val ORDER = listOf(RECAP, INTRO, PREVIEW, CREDITS, STINGER)
}

/** Mirrors `dev.jellystructure.media.SegmentSource`. */
object SegSource {
    const val FINGERPRINT = "fingerprint"
    const val HEURISTIC = "heuristic"
    const val CHAPTER = "chapter"
    const val JELLYFIN = "jellyfin"
    const val TMDB = "tmdb"
    const val MANUAL = "manual"
}

@Serializable
data class SegmentDto(
    val kind: String,
    val startMs: Long,
    val endMs: Long? = null,
    val source: String? = null,
    val confidence: Double? = null,
    val locked: Boolean = false,
)

@Serializable
data class SegmentEpisodeRow(
    val mediaId: String,
    val itemTitle: String? = null,
    val episodeKey: String = "",
    val episodeNumber: Int = 0,
    val code: String,
    val title: String,
    val durationSec: Double,
    val jellyfinId: String? = null,
    val partCount: Int = 1,
    val segments: List<SegmentDto> = emptyList(),
    val checked: Boolean = false,
    val outlier: Boolean = false,
)

@Serializable
data class SegmentConsensus(
    val kind: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val leadMs: Long? = null,
)

@Serializable
data class SegmentStats(
    val total: Int,
    val found: Int,
    val lowConfidence: Int,
    val outliers: Int,
    val locked: Int,
)

@Serializable
data class SegmentSheetResponse(
    val itemId: String? = null,
    val title: String,
    val seasonNumber: Int? = null,
    val seasonName: String? = null,
    val kind: String,
    val episodes: List<SegmentEpisodeRow>,
    val consensus: List<SegmentConsensus> = emptyList(),
    val stats: SegmentStats,
)

@Serializable
data class SegmentEditRequest(val startMs: Long, val endMs: Long? = null)

@Serializable
data class SegmentLockRequest(val locked: Boolean)

@Serializable
data class SegmentEpisodeRef(val itemId: String, val episodeKey: String = "", val episodeNumber: Int = 0)

@Serializable
data class SegmentCheckedRequest(val items: List<SegmentEpisodeRef>)

@Serializable
data class SegmentBulkLockRequest(val items: List<SegmentEpisodeRef>, val locked: Boolean)

@Serializable
data class SegmentApplyRequest(val series: String, val season: Int, val kind: String, val targets: List<SegmentEpisodeRef>, val lock: Boolean = false)

@Serializable
data class SegmentRedetectRequest(val series: String? = null, val season: Int? = null, val movie: String? = null, val items: List<SegmentEpisodeRef> = emptyList())

/** Phase 163 — client for the intro/credits editor's REST surface (SegmentRoutes.kt). */
object SegmentApi {
    suspend fun seasonSheet(seriesId: String, season: Int): SegmentSheetResponse? = runCatching {
        httpClient.get("/api/segments") {
            url { parameters.append("series", seriesId); parameters.append("season", season.toString()) }
        }.body<SegmentSheetResponse>()
    }.getOrNull()

    suspend fun movieSheet(movieId: String): SegmentSheetResponse? = runCatching {
        httpClient.get("/api/segments") { url { parameters.append("movie", movieId) } }.body<SegmentSheetResponse>()
    }.getOrNull()

    /** `filter` is `lowconf` | `none` — the dashboard's two segment-triage deep links. */
    suspend fun crossLibrarySheet(filter: String): SegmentSheetResponse? = runCatching {
        httpClient.get("/api/segments") { url { parameters.append("filter", filter) } }.body<SegmentSheetResponse>()
    }.getOrNull()

    suspend fun editSegment(itemId: String, kind: String, episodeKey: String, episodeNumber: Int, startMs: Long, endMs: Long?): Boolean = runCatching {
        httpClient.put("/api/segments/$itemId/$kind") {
            url { parameters.append("episode", episodeKey); parameters.append("n", episodeNumber.toString()) }
            contentType(ContentType.Application.Json)
            setBody(SegmentEditRequest(startMs, endMs))
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun setLock(itemId: String, kind: String, episodeKey: String, episodeNumber: Int, locked: Boolean): Boolean = runCatching {
        httpClient.put("/api/segments/$itemId/$kind/lock") {
            url { parameters.append("episode", episodeKey); parameters.append("n", episodeNumber.toString()) }
            contentType(ContentType.Application.Json)
            setBody(SegmentLockRequest(locked))
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun setChecked(items: List<SegmentEpisodeRef>): Boolean = runCatching {
        httpClient.post("/api/segments/checked") {
            contentType(ContentType.Application.Json)
            setBody(SegmentCheckedRequest(items))
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun bulkLock(items: List<SegmentEpisodeRef>, locked: Boolean): Boolean = runCatching {
        httpClient.post("/api/segments/lock") {
            contentType(ContentType.Application.Json)
            setBody(SegmentBulkLockRequest(items, locked))
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun applyConsensus(series: String, season: Int, kind: String, targets: List<SegmentEpisodeRef>, lock: Boolean = false): Boolean = runCatching {
        httpClient.post("/api/segments/apply") {
            contentType(ContentType.Application.Json)
            setBody(SegmentApplyRequest(series, season, kind, targets, lock))
        }.status == HttpStatusCode.NoContent
    }.getOrDefault(false)

    suspend fun redetect(series: String? = null, season: Int? = null, movie: String? = null, items: List<SegmentEpisodeRef> = emptyList()): Boolean = runCatching {
        httpClient.post("/api/segments/redetect") {
            contentType(ContentType.Application.Json)
            setBody(SegmentRedetectRequest(series, season, movie, items))
        }.status == HttpStatusCode.Accepted
    }.getOrDefault(false)
}
