package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
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
}
