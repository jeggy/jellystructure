package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.delete
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

/** Mirrors `dev.jellystructure.media.EvidenceType`. */
object EvType {
    const val BLACK_FRAME = "black_frame"
    const val SILENCE = "silence"
    const val FINGERPRINT_MATCH = "fingerprint_match"
    const val CHAPTER_CANDIDATE = "chapter_candidate"
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

// Phase 164 — mirrors dev.jellystructure.server.routes.SegmentRedetectResponse.
@Serializable
private data class SegmentRedetectResponseDto(val jobIds: List<String> = emptyList(), val enqueued: Int = 0, val deduped: Int = 0)

data class SegmentRedetectResult(val ok: Boolean, val enqueued: Int = 0, val deduped: Int = 0)

@Serializable
data class SegmentEvidenceDto(
    val evidenceType: String,
    val startMs: Long,
    val endMs: Long? = null,
    val detail: String? = null,
    val accepted: Boolean = false,
)

@Serializable
data class SegmentRailItem(
    val episodeKey: String = "",
    val episodeNumber: Int = 0,
    val code: String,
    val title: String,
    val durationSec: Double,
    val segments: List<SegmentDto> = emptyList(),
    val checked: Boolean = false,
    val outlier: Boolean = false,
)

@Serializable
data class SegmentJellyfinCandidate(val kind: String, val startMs: Long, val endMs: Long? = null)

@Serializable
data class SegmentTrimResponse(
    val mediaId: String,
    val itemTitle: String,
    val seasonNumber: Int = 0,
    val episodeKey: String = "",
    val episodeNumber: Int = 0,
    val code: String,
    val title: String,
    val durationSec: Double,
    val kind: String,
    val partCount: Int = 1,
    val segments: List<SegmentDto> = emptyList(),
    val evidence: List<SegmentEvidenceDto> = emptyList(),
    val checked: Boolean = false,
    val rail: List<SegmentRailItem> = emptyList(),
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
)

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

    suspend fun deleteSegment(itemId: String, kind: String, episodeKey: String, episodeNumber: Int): Boolean = runCatching {
        httpClient.delete("/api/segments/$itemId/$kind") {
            url { parameters.append("episode", episodeKey); parameters.append("n", episodeNumber.toString()) }
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

    /** Phase 164 (FR-164-7) — now enqueues onto the segments job lane (dedup-aware) instead of a bare
     *  fire-and-forget launch; the response says how many units were newly queued vs. already
     *  queued/running under the same dedupe key, so the caller's toast can be honest about which. */
    suspend fun redetect(series: String? = null, season: Int? = null, movie: String? = null, items: List<SegmentEpisodeRef> = emptyList()): SegmentRedetectResult = runCatching {
        val response = httpClient.post("/api/segments/redetect") {
            contentType(ContentType.Application.Json)
            setBody(SegmentRedetectRequest(series, season, movie, items))
        }
        if (response.status == HttpStatusCode.Accepted) {
            val body = response.body<SegmentRedetectResponseDto>()
            SegmentRedetectResult(ok = true, enqueued = body.enqueued, deduped = body.deduped)
        } else SegmentRedetectResult(ok = false)
    }.getOrDefault(SegmentRedetectResult(ok = false))

    suspend fun movieTrim(movieId: String): SegmentTrimResponse? = runCatching {
        httpClient.get("/api/segments/$movieId").body<SegmentTrimResponse>()
    }.getOrNull()

    suspend fun episodeTrim(seriesId: String, episodeKey: String, episodeNumber: Int): SegmentTrimResponse? = runCatching {
        httpClient.get("/api/segments/$seriesId/episode") {
            url { parameters.append("key", episodeKey); parameters.append("n", episodeNumber.toString()) }
        }.body<SegmentTrimResponse>()
    }.getOrNull()

    suspend fun waveform(mediaId: String, episodeKey: String, episodeNumber: Int, startMs: Long, endMs: Long, buckets: Int = 150): List<Int>? = runCatching {
        httpClient.get("/api/segments/$mediaId/waveform") {
            url {
                if (episodeKey.isNotEmpty()) { parameters.append("episode", episodeKey); parameters.append("n", episodeNumber.toString()) }
                parameters.append("startMs", startMs.toString())
                parameters.append("endMs", endMs.toString())
                parameters.append("buckets", buckets.toString())
            }
        }.body<List<Int>>()
    }.getOrNull()

    suspend fun jellyfinCandidates(mediaId: String, episodeKey: String, episodeNumber: Int): List<SegmentJellyfinCandidate> = runCatching {
        httpClient.get("/api/segments/$mediaId/jellyfin") {
            url { if (episodeKey.isNotEmpty()) { parameters.append("episode", episodeKey); parameters.append("n", episodeNumber.toString()) } }
        }.body<List<SegmentJellyfinCandidate>>()
    }.getOrDefault(emptyList())

    @Serializable
    private data class StreamUrlResponse(val url: String)

    /** Direct-play only — never transcodes (see SegmentRoutes.kt's doc). Null when the title/episode
     *  isn't matched in Jellyfin yet, or on any other failure; the trim view falls back to timecode-only
     *  editing rather than showing a broken video element. */
    suspend fun streamUrl(mediaId: String, episodeKey: String?, episodeNumber: Int?): String? = runCatching {
        httpClient.get("/api/segments/$mediaId/stream") {
            url { episodeKey?.let { parameters.append("episode", it) }; episodeNumber?.let { parameters.append("n", it.toString()) } }
        }.body<StreamUrlResponse>().url
    }.getOrNull()
}
