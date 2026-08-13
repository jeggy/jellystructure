package dev.jellystructure.server.routes

import dev.jellystructure.media.DuplicateEpisodes
import dev.jellystructure.media.MediaSegmentStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.SegmentKind
import dev.jellystructure.media.SegmentSource
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlin.math.abs

/** Phase 163 (step 2) — REST surface for the intro/credits editor. Standard admin cookie auth
 *  (AuthPlugin), not added to OPEN_API_PATHS — same as TriageRoutes/MediaRoutes. */

// An intro that starts >45s from the season's median is treated as a real mismatch (a recap or cold
// open mistaken for the intro), not natural per-episode variance — cold opens vary by a few seconds,
// not the better part of a minute. Matches the "odd one out" concept the design mockup calls out.
private const val OUTLIER_THRESHOLD_MS = 45_000L
private const val LOW_CONFIDENCE_THRESHOLD = 0.60

@kotlinx.serialization.Serializable
data class SegmentDto(
    val kind: String,
    val startMs: Long,
    val endMs: Long? = null,
    val source: String? = null,
    val confidence: Double? = null,
    val locked: Boolean = false,
)

/** One row on the season sheet (or, in cross-library `filter=` mode, one row anywhere in the
 *  library) — [mediaId]/[itemTitle] identify the owning title (constant within a season sheet,
 *  varying per row in cross-library mode). */
@kotlinx.serialization.Serializable
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

/** The season's agreed-on marker, for the sheet's "what the season agrees on" stat and step 3's
 *  bulk-apply. Intro is expressed as an absolute [startMs]/[endMs] (a season's intros cluster around
 *  the same offset from the start); credits as [leadMs] — how long before each episode's own end the
 *  credits begin — since credits length is naturally end-relative, not absolute. */
@kotlinx.serialization.Serializable
data class SegmentConsensus(
    val kind: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val leadMs: Long? = null,
)

@kotlinx.serialization.Serializable
data class SegmentStats(
    val total: Int,
    val found: Int,
    val lowConfidence: Int,
    val outliers: Int,
    val locked: Int,
)

@kotlinx.serialization.Serializable
data class SegmentSheetResponse(
    val itemId: String? = null,
    val title: String,
    val seasonNumber: Int? = null,
    val seasonName: String? = null,
    val kind: String,   // "tv" | "movie" | "cross" (a dashboard filter= deep link, spans the library)
    val episodes: List<SegmentEpisodeRow>,
    val consensus: List<SegmentConsensus> = emptyList(),
    val stats: SegmentStats,
)

fun Route.segmentRoutes(store: MediaStore, segmentStore: MediaSegmentStore) {
    route("/segments") {
        get {
            val seriesId = call.request.queryParameters["series"]
            val season = call.request.queryParameters["season"]?.toIntOrNull()
            val movieId = call.request.queryParameters["movie"]
            val filter = call.request.queryParameters["filter"]
            when {
                filter != null -> call.respond(crossLibrarySheet(store, segmentStore, filter))
                seriesId != null && season != null -> {
                    val item = store.get(seriesId) ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(seasonSheet(item, season, segmentStore))
                }
                movieId != null -> {
                    val item = store.get(movieId) ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(movieSheet(item, segmentStore))
                }
                else -> call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
}

private fun episodeRow(mediaId: String, itemTitle: String?, ep: Episode, segmentStore: MediaSegmentStore): SegmentEpisodeRow {
    val rows = segmentStore.segmentsForEpisode(mediaId, ep.filename, ep.episodeNumber ?: 0)
    val code = if (ep.seasonNumber != null && ep.episodeNumber != null) {
        "S${ep.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
    } else ep.filename.substringBeforeLast('.')
    return SegmentEpisodeRow(
        mediaId = mediaId, itemTitle = itemTitle, episodeKey = ep.filename, episodeNumber = ep.episodeNumber ?: 0,
        code = code, title = ep.title ?: code, durationSec = durationSecOf(ep.runtime, rows),
        jellyfinId = ep.jellyfinId, partCount = ep.partCount, segments = rows.toDtos(), checked = rows.any { it.checkedAt != null },
    )
}

private fun movieRow(item: MediaItem, itemTitle: String?, segmentStore: MediaSegmentStore): SegmentEpisodeRow {
    val rows = segmentStore.segmentsForItem(item.id)
    return SegmentEpisodeRow(
        mediaId = item.id, itemTitle = itemTitle, code = item.title, title = item.title,
        durationSec = durationSecOf(item.runtime, rows), jellyfinId = item.jellyfinId, partCount = 1,
        segments = rows.toDtos(), checked = rows.any { it.checkedAt != null },
    )
}

// Real per-episode/movie duration lives nowhere in the model (only ffprobe knows it, at scan/detect
// time) — re-invoking ffprobe for a whole season just to render a comparison timeline would repeat
// this repo's own unthrottled-ffmpeg-call incident class. TMDB's runtime (minutes) is close enough for
// the sheet's relative-width bar chart; the trim view (step 4/5) gets the frame-accurate figure from
// the real <video> element once playback exists. Falls back to the furthest known segment edge so a
// title with markers but no TMDB runtime yet still renders something.
private fun durationSecOf(runtimeMinutes: Int?, rows: List<dev.jellystructure.media.MediaSegmentRow>): Double =
    runtimeMinutes?.let { it * 60.0 } ?: rows.maxOfOrNull { it.endMs ?: it.startMs }?.div(1000.0) ?: 0.0

private fun List<dev.jellystructure.media.MediaSegmentRow>.toDtos(): List<SegmentDto> =
    map { SegmentDto(kind = it.kind, startMs = it.startMs, endMs = it.endMs, source = it.source, confidence = it.confidence, locked = it.locked) }

private fun isLowConfidence(seg: SegmentDto): Boolean = seg.source == SegmentSource.HEURISTIC && (seg.confidence ?: 1.0) < LOW_CONFIDENCE_THRESHOLD

private fun markOutliers(rows: List<SegmentEpisodeRow>): List<SegmentEpisodeRow> {
    val intros = rows.mapNotNull { r -> r.segments.firstOrNull { it.kind == SegmentKind.INTRO }?.let { r to it.startMs } }
    if (intros.size < 3) return rows
    val median = intros.map { it.second }.sorted()[intros.size / 2]
    val outlierKeys = intros.filter { abs(it.second - median) > OUTLIER_THRESHOLD_MS }.mapTo(mutableSetOf()) { it.first.episodeKey to it.first.episodeNumber }
    return rows.map { if ((it.episodeKey to it.episodeNumber) in outlierKeys) it.copy(outlier = true) else it }
}

private fun computeConsensus(rows: List<SegmentEpisodeRow>): List<SegmentConsensus> {
    val nonOutliers = rows.filterNot { it.outlier }
    val intros = nonOutliers.mapNotNull { r -> r.segments.firstOrNull { it.kind == SegmentKind.INTRO } }
    val introConsensus = intros.takeIf { it.isNotEmpty() }?.let {
        val starts = it.map { s -> s.startMs }.sorted()
        val ends = it.mapNotNull { s -> s.endMs }.sorted()
        SegmentConsensus(kind = SegmentKind.INTRO, startMs = starts[starts.size / 2], endMs = ends.getOrNull(ends.size / 2))
    }
    val creditsLeads = nonOutliers.mapNotNull { r ->
        r.segments.firstOrNull { it.kind == SegmentKind.CREDITS }?.let { s -> (r.durationSec * 1000).toLong() - s.startMs }
    }
    val creditsConsensus = creditsLeads.takeIf { it.isNotEmpty() }?.let {
        SegmentConsensus(kind = SegmentKind.CREDITS, leadMs = it.sorted()[it.size / 2])
    }
    return listOfNotNull(introConsensus, creditsConsensus)
}

private fun computeStats(rows: List<SegmentEpisodeRow>): SegmentStats = SegmentStats(
    total = rows.size,
    found = rows.count { it.segments.isNotEmpty() },
    lowConfidence = rows.count { r -> r.segments.any { isLowConfidence(it) } },
    outliers = rows.count { it.outlier },
    locked = rows.count { r -> r.segments.any { it.locked } },
)

private fun seasonSheet(item: MediaItem, season: Int, segmentStore: MediaSegmentStore): SegmentSheetResponse {
    val eps = DuplicateEpisodes.deduped(item.episodes)
        .filter { (it.seasonNumber ?: 0) == season }
        .sortedBy { it.episodeNumber ?: 0 }
    val rows = markOutliers(eps.map { episodeRow(item.id, null, it, segmentStore) })
    val seasonName = item.seasonNames[season] ?: "Season $season"
    return SegmentSheetResponse(
        itemId = item.id, title = item.title, seasonNumber = season, seasonName = seasonName, kind = "tv",
        episodes = rows, consensus = computeConsensus(rows), stats = computeStats(rows),
    )
}

private fun movieSheet(item: MediaItem, segmentStore: MediaSegmentStore): SegmentSheetResponse {
    val row = movieRow(item, null, segmentStore)
    return SegmentSheetResponse(itemId = item.id, title = item.title, kind = "movie", episodes = listOf(row), stats = computeStats(listOf(row)))
}

private fun matchesCrossLibraryFilter(row: SegmentEpisodeRow, filter: String): Boolean = when (filter) {
    "lowconf" -> row.segments.any { isLowConfidence(it) }
    "none" -> row.segments.none { it.kind == SegmentKind.INTRO || it.kind == SegmentKind.CREDITS }
    else -> false
}

private suspend fun crossLibrarySheet(store: MediaStore, segmentStore: MediaSegmentStore, filter: String): SegmentSheetResponse {
    val rows = store.allItems().flatMap { item ->
        if (item.kind == MediaKind.TV_SHOW) {
            DuplicateEpisodes.deduped(item.episodes).map { ep -> episodeRow(item.id, item.title, ep, segmentStore) }
        } else {
            listOf(movieRow(item, item.title, segmentStore))
        }
    }.filter { matchesCrossLibraryFilter(it, filter) }
    val title = if (filter == "lowconf") "Low-confidence segments" else "No intro/credits detected"
    return SegmentSheetResponse(title = title, kind = "cross", episodes = rows, stats = computeStats(rows))
}
