package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.MediaJobParams
import dev.jellystructure.media.DuplicateEpisodes
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FingerprintService
import dev.jellystructure.media.MediaSegmentStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.PipelineStepOps
import dev.jellystructure.media.SegmentKind
import dev.jellystructure.media.SegmentSource
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Phase 163 (step 2) — REST surface for the intro/credits editor. Standard admin cookie auth
 *  (AuthPlugin), not added to OPEN_API_PATHS — same as TriageRoutes/MediaRoutes. */

// An intro that starts >45s from the season's median is treated as a real mismatch (a recap or cold
// open mistaken for the intro), not natural per-episode variance — cold opens vary by a few seconds,
// not the better part of a minute. Matches the "odd one out" concept the design mockup calls out.
private const val OUTLIER_THRESHOLD_MS = 45_000L
private const val LOW_CONFIDENCE_THRESHOLD = 0.60
private const val SEGMENTS_STREAM_DEVICE_ID = "jellystructure-segments-editor"

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

@kotlinx.serialization.Serializable
data class SegmentEditRequest(val startMs: Long, val endMs: Long? = null)

@kotlinx.serialization.Serializable
data class SegmentLockRequest(val locked: Boolean)

@kotlinx.serialization.Serializable
data class SegmentEpisodeRef(val itemId: String, val episodeKey: String = "", val episodeNumber: Int = 0)

@kotlinx.serialization.Serializable
data class SegmentCheckedRequest(val items: List<SegmentEpisodeRef>)

@kotlinx.serialization.Serializable
data class SegmentBulkLockRequest(val items: List<SegmentEpisodeRef>, val locked: Boolean)

@kotlinx.serialization.Serializable
data class SegmentApplyRequest(val series: String, val season: Int, val kind: String, val targets: List<SegmentEpisodeRef>, val lock: Boolean = false)

/** [series]+[season] re-detects a whole season (chapter/heuristic tier + fingerprint tier); [movie] a
 *  single film; [items] a specific episode/movie list (chapter/heuristic tier only — the fingerprint
 *  tier is a pairwise, whole-season consensus algorithm; dropping the unselected siblings from
 *  comparison would degrade the consensus for everyone, not just narrow the work — see
 *  MediaJobQueue.kt's `runSegmentsEpisodes` doc). */
@kotlinx.serialization.Serializable
data class SegmentRedetectRequest(val series: String? = null, val season: Int? = null, val movie: String? = null, val items: List<SegmentEpisodeRef> = emptyList())

/** Phase 164 (FR-164-7) — one or more job ids were enqueued (a multi-series/multi-season [items]
 *  selection can produce several); [deduped] is how many of those were already queued/running under the
 *  same dedupe key rather than newly inserted. */
@kotlinx.serialization.Serializable
data class SegmentRedetectResponse(val jobIds: List<String> = emptyList(), val enqueued: Int = 0, val deduped: Int = 0)

@kotlinx.serialization.Serializable
data class SegmentJellyfinCandidate(val kind: String, val startMs: Long, val endMs: Long?)

@kotlinx.serialization.Serializable
data class SegmentEvidenceDto(
    val evidenceType: String,
    val startMs: Long,
    val endMs: Long? = null,
    val detail: String? = null,
    val accepted: Boolean = false,
)

/** One other episode in the same season, for the trim view's queue rail. */
@kotlinx.serialization.Serializable
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

/** Step 4 — one title's (or one episode's) full detail for the trim view: its own segments + raw
 *  detection evidence, plus (TV only) the rest of its season for the queue rail. */
@kotlinx.serialization.Serializable
data class SegmentTrimResponse(
    val mediaId: String,
    val itemTitle: String,
    val seasonNumber: Int = 0,
    val episodeKey: String = "",
    val episodeNumber: Int = 0,
    val code: String,
    val title: String,
    val durationSec: Double,
    val kind: String,   // "tv" | "movie"
    val partCount: Int = 1,
    val segments: List<SegmentDto> = emptyList(),
    val evidence: List<SegmentEvidenceDto> = emptyList(),
    val checked: Boolean = false,
    val rail: List<SegmentRailItem> = emptyList(),
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
)

fun Route.segmentRoutes(store: MediaStore, segmentStore: MediaSegmentStore, configStore: ConfigStore, fingerprintService: FingerprintService?, appScope: CoroutineScope, jellyfinClient: JellyfinClient, mediaJobQueue: dev.jellystructure.media.MediaJobQueue) {
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

        // Manual edit (drag/stepper in the trim view, step 4) — write-through, preserves whatever lock
        // state the row already had (editing a value and locking it are independent actions).
        put("/{itemId}/{kind}") {
            val itemId = call.parameters["itemId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val kind = call.parameters["kind"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val episodeKey = call.request.queryParameters["episode"] ?: ""
            val episodeNumber = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            val body = runCatching { call.receive<SegmentEditRequest>() }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val existing = segmentStore.getSegment(itemId, episodeKey, episodeNumber, kind)
            // Phase 189 (FR-189-4) — upsertSegment is INSERT OR REPLACE, and `checked` is derived from
            // whether ANY row has a non-null checked_at (see this file's `checked` computation below):
            // preserving only `locked` and leaving `checkedAt` at its null default silently un-confirmed
            // the whole episode on the very next refinement, which is exactly the thing an operator does
            // right after confirming one. Preserve it exactly like `locked` already is.
            segmentStore.upsertSegment(itemId, episodeKey, episodeNumber, kind, body.startMs, body.endMs, SegmentSource.MANUAL, null, locked = existing?.locked ?: false, checkedAt = existing?.checkedAt)
            call.respond(HttpStatusCode.NoContent)
        }

        // Remove a marker entirely (the "＋ Kind" add flow's undo — a marker added by mistake, or one an
        // operator decides shouldn't exist for this title, wasn't otherwise removable once added).
        delete("/{itemId}/{kind}") {
            val itemId = call.parameters["itemId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val kind = call.parameters["kind"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val episodeKey = call.request.queryParameters["episode"] ?: ""
            val episodeNumber = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            segmentStore.deleteSegment(itemId, episodeKey, episodeNumber, kind)
            call.respond(HttpStatusCode.NoContent)
        }

        put("/{itemId}/{kind}/lock") {
            val itemId = call.parameters["itemId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val kind = call.parameters["kind"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val episodeKey = call.request.queryParameters["episode"] ?: ""
            val episodeNumber = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            val body = runCatching { call.receive<SegmentLockRequest>() }.getOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            segmentStore.setLocked(itemId, episodeKey, episodeNumber, kind, body.locked)
            call.respond(HttpStatusCode.NoContent)
        }

        // "Checked" is episode-scoped (spec §5) — stamps every kind row currently present for the
        // episode; MediaSegmentStore.setChecked already implements that.
        post("/checked") {
            val body = runCatching { call.receive<SegmentCheckedRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            for (ref in body.items) segmentStore.setChecked(ref.itemId, ref.episodeKey, ref.episodeNumber)
            call.respond(HttpStatusCode.NoContent)
        }

        // Bulk lock/unlock — every kind row currently present for each episode (mirrors the sheet's
        // single "Lock" button locking whatever that episode already has, not one specific kind).
        post("/lock") {
            val body = runCatching { call.receive<SegmentBulkLockRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            for (ref in body.items) {
                segmentStore.segmentsForEpisode(ref.itemId, ref.episodeKey, ref.episodeNumber).forEach { row ->
                    segmentStore.setLocked(ref.itemId, ref.episodeKey, ref.episodeNumber, row.kind, body.locked)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // "Give them the season's intro/credits" — recomputes consensus fresh server-side (never trusts
        // a client-supplied value) and writes+checks each target episode.
        post("/apply") {
            val body = runCatching { call.receive<SegmentApplyRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.get(body.series) ?: return@post call.respond(HttpStatusCode.NotFound)
            val applied = applyConsensusToTargets(item, body.season, body.kind, body.targets, body.lock, segmentStore)
            if (!applied) return@post call.respond(HttpStatusCode.UnprocessableEntity)
            call.respond(HttpStatusCode.NoContent)
        }

        // Step 4 — the trim view's full detail. Movie: the title itself. TV: one episode + the rest of
        // its season for the queue rail. Kept as distinct paths (not folded into the query-param GET
        // above) since the response shape genuinely differs — a sheet row vs one title's full detail.
        get("/{itemId}") {
            val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.get(itemId) ?: return@get call.respond(HttpStatusCode.NotFound)
            if (item.kind != MediaKind.MOVIE) return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(movieTrim(item, segmentStore))
        }

        get("/{itemId}/episode") {
            val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val key = call.request.queryParameters["key"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val n = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            val item = store.get(itemId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val ep = item.episodes.firstOrNull { it.filename == key && (it.episodeNumber ?: 0) == n } ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(episodeTrim(item, ep, segmentStore))
        }

        // Step 5 — a direct-play stream URL for the trim view's <video>, exactly the shape
        // PlaybackService.kt already mints for Ravilo, but using the admin's OWN Jellyfin session
        // (jellystructure Auth *is* Jellyfin Auth — every cookie session already carries a real
        // jellyfinUserToken) rather than a device token. Never transcodes: this is a scrub/preview tool,
        // not a client that needs HDR tone-mapping or codec negotiation — if the browser can't decode
        // the file directly, the trim view falls back to timecode-only editing (no video).
        get("/{itemId}/stream") {
            val session = runCatching { call.attributes[SessionKey] }.getOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.get(itemId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val episodeKey = call.request.queryParameters["episode"]
            val episodeNumber = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            val jellyfinId = resolveJellyfinId(item, episodeKey, episodeNumber)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "not matched in Jellyfin yet"))
            val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            val url = "$base/Videos/$jellyfinId/stream?Static=true&MediaSourceId=$jellyfinId&DeviceId=$SEGMENTS_STREAM_DEVICE_ID&api_key=${session.jellyfinUserToken}"
            call.respond(mapOf("url" to url))
        }

        // Step 6 — [buckets] peak amplitudes for the trim view's waveform, decoded on demand (never
        // cached/persisted — this is a display aid, not detection data). Bounded to a sane window so a
        // malformed request can't ask ffmpeg to decode an unbounded amount of audio.
        get("/{itemId}/waveform") {
            val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.get(itemId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val episodeKey = call.request.queryParameters["episode"]
            val episodeNumber = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            val startMs = call.request.queryParameters["startMs"]?.toLongOrNull() ?: 0L
            val endMs = call.request.queryParameters["endMs"]?.toLongOrNull()
            val buckets = call.request.queryParameters["buckets"]?.toIntOrNull()?.coerceIn(10, 600) ?: 150
            val path = if (episodeKey != null) {
                item.episodes.firstOrNull { it.filename == episodeKey && (it.episodeNumber ?: 0) == episodeNumber }?.path
            } else item.path
            if (path == null) return@get call.respond(HttpStatusCode.NotFound)
            val startSec = (startMs / 1000.0).coerceAtLeast(0.0)
            val windowSec = ((endMs?.let { it / 1000.0 } ?: (startSec + 1_800)) - startSec).coerceIn(0.0, 14_400.0)
            val peaks = FfmpegRunner.computeWaveform(path, startSec, windowSec, buckets)
            if (peaks == null) call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "couldn't decode audio")) else call.respond(peaks)
        }

        // Step 6 — Jellyfin's own MediaSegments, offered as read-only candidates (never auto-applied —
        // the admin reviews and, if they want it, PUTs it through the normal manual-edit route). Expect
        // an empty list on a server with no segment-provider plugin installed; that's normal.
        get("/{itemId}/jellyfin") {
            val session = runCatching { call.attributes[SessionKey] }.getOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.get(itemId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val episodeKey = call.request.queryParameters["episode"]
            val episodeNumber = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            val jellyfinId = resolveJellyfinId(item, episodeKey, episodeNumber)
            if (jellyfinId == null) return@get call.respond(emptyList<SegmentJellyfinCandidate>())
            val base = configStore.current.apiKeys.jellyfinUrl
            val segments = jellyfinClient.getMediaSegments(base, session.jellyfinUserToken, jellyfinId)
            call.respond(segments.mapNotNull { it.toCandidate() })
        }

        // Phase 164 (FR-164-7) — enqueues onto the segments lane instead of a bare appScope.launch: real
        // dedup (a double-click, or a redetect racing an already-queued pipeline pass for the same
        // season, reports back "already queued" rather than running twice), visibility on the Jobs page,
        // and cancel. Every unit here is force=true — an explicit "detect again" always re-derives even
        // over an existing unlocked value (PipelineStepOps' own per-kind lock check still applies).
        post("/redetect") {
            val body = runCatching { call.receive<SegmentRedetectRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val jobIds = mutableListOf<String>()
            var deduped = 0
            fun record(result: dev.jellystructure.media.SegmentEnqueueResult) {
                jobIds += result.snapshot.id
                if (result.deduped) deduped++
            }
            when {
                body.series != null && body.season != null -> {
                    val item = store.get(body.series) ?: return@post call.respond(HttpStatusCode.NotFound)
                    val eps = item.episodes.count { (it.seasonNumber ?: 0) == body.season && it.partCount == 1 }
                    record(mediaJobQueue.enqueueSegments(
                        "segments_season", item.id, "${item.title} S${body.season.toString().padStart(2, '0')}",
                        MediaJobParams(segmentSeason = body.season, segmentForce = true), eps.coerceAtLeast(1),
                        "seg:season:${item.id}:${body.season}",
                    ))
                }
                body.movie != null -> {
                    val item = store.get(body.movie) ?: return@post call.respond(HttpStatusCode.NotFound)
                    record(mediaJobQueue.enqueueSegments(
                        "segments_movie", item.id, item.title, MediaJobParams(segmentForce = true), 1, "seg:movie:${item.id}",
                    ))
                }
                body.items.isNotEmpty() -> {
                    // Selected-episode redetect skips the fingerprint tier — see runSegmentsEpisodes'
                    // own doc in MediaJobQueue.kt. Grouped by (series, season): in practice the editor
                    // only ever multi-selects within one season, but grouping defensively means a
                    // hypothetical cross-season selection still produces correct, separately-dedupable
                    // per-season units rather than one job silently spanning seasons.
                    for ((itemId, refs) in body.items.groupBy { it.itemId }) {
                        val item = store.get(itemId) ?: continue
                        if (item.kind == MediaKind.MOVIE) {
                            record(mediaJobQueue.enqueueSegments(
                                "segments_movie", item.id, item.title, MediaJobParams(segmentForce = true), 1, "seg:movie:${item.id}",
                            ))
                            continue
                        }
                        val wanted = refs.mapTo(mutableSetOf()) { it.episodeKey to it.episodeNumber }
                        val matched = item.episodes.filter { (it.filename to (it.episodeNumber ?: 0)) in wanted }
                        for ((season, seasonEps) in matched.groupBy { it.seasonNumber ?: 0 }) {
                            val keys = seasonEps.map { "${it.filename}#${it.episodeNumber}" }
                            record(mediaJobQueue.enqueueSegments(
                                "segments_episodes", item.id, "${item.title} S${season.toString().padStart(2, '0')} (${keys.size} episode${if (keys.size == 1) "" else "s"})",
                                MediaJobParams(segmentSeason = season, segmentEpisodeKeys = keys, segmentForce = true), keys.size,
                                "seg:episodes:${item.id}:$season:${keys.sorted().joinToString(",")}",
                            ))
                        }
                    }
                }
            }
            call.respond(HttpStatusCode.Accepted, SegmentRedetectResponse(jobIds = jobIds, enqueued = jobIds.size - deduped, deduped = deduped))
        }
    }
}

private fun applyConsensusToTargets(item: MediaItem, season: Int, kind: String, targets: List<SegmentEpisodeRef>, lock: Boolean, segmentStore: MediaSegmentStore): Boolean {
    val eps = DuplicateEpisodes.deduped(item.episodes).filter { (it.seasonNumber ?: 0) == season }.sortedBy { it.episodeNumber ?: 0 }
    val rows = markOutliers(eps.map { episodeRow(item.id, null, it, segmentStore) })
    val consensus = computeConsensus(rows).firstOrNull { it.kind == kind } ?: return false
    for (ref in targets) {
        val ep = eps.firstOrNull { it.filename == ref.episodeKey && (it.episodeNumber ?: 0) == ref.episodeNumber } ?: continue
        when (kind) {
            SegmentKind.INTRO -> {
                val start = consensus.startMs ?: continue
                val end = consensus.endMs ?: start
                segmentStore.upsertSegment(item.id, ep.filename, ep.episodeNumber ?: 0, SegmentKind.INTRO, start, end, SegmentSource.MANUAL, null, locked = lock)
            }
            SegmentKind.CREDITS -> {
                val lead = consensus.leadMs ?: continue
                val durMs = (durationSecOf(ep.runtime, segmentStore.segmentsForEpisode(item.id, ep.filename, ep.episodeNumber ?: 0)) * 1000).toLong()
                if (durMs <= 0) continue
                val start = (durMs - lead).coerceAtLeast(0)
                segmentStore.upsertSegment(item.id, ep.filename, ep.episodeNumber ?: 0, SegmentKind.CREDITS, start, null, SegmentSource.MANUAL, null, locked = lock)
            }
            else -> continue
        }
        segmentStore.setChecked(item.id, ep.filename, ep.episodeNumber ?: 0)
    }
    return true
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

private fun resolveJellyfinId(item: MediaItem, episodeKey: String?, episodeNumber: Int): String? =
    if (episodeKey != null) item.episodes.firstOrNull { it.filename == episodeKey && (it.episodeNumber ?: 0) == episodeNumber }?.jellyfinId
    else item.jellyfinId

private fun dev.jellystructure.auth.JellyfinMediaSegment.toCandidate(): SegmentJellyfinCandidate? {
    val kind = when (type) {
        "Intro" -> SegmentKind.INTRO
        "Outro" -> SegmentKind.CREDITS
        "Recap" -> SegmentKind.RECAP
        "Preview" -> SegmentKind.PREVIEW
        else -> return null   // Commercial/Unknown — deliberately unmapped, see SegmentKind's own doc
    }
    return SegmentJellyfinCandidate(kind, startTicks / 10_000, endTicks / 10_000)
}

private fun List<dev.jellystructure.media.SegmentEvidenceRow>.toEvidenceDtos(): List<SegmentEvidenceDto> =
    map { SegmentEvidenceDto(evidenceType = it.evidenceType, startMs = it.startMs, endMs = it.endMs, detail = it.detail, accepted = it.accepted) }

private fun movieTrim(item: MediaItem, segmentStore: MediaSegmentStore): SegmentTrimResponse {
    val rows = segmentStore.segmentsForItem(item.id)
    val evidence = segmentStore.evidenceForEpisode(item.id, "", 0)
    val checked = rows.any { it.checkedAt != null }
    return SegmentTrimResponse(
        mediaId = item.id, itemTitle = item.title, code = item.title, title = item.title,
        durationSec = durationSecOf(item.runtime, rows), kind = "movie", partCount = 1,
        segments = rows.toDtos(), evidence = evidence.toEvidenceDtos(), checked = checked,
        checkedCount = if (checked) 1 else 0, totalCount = 1,
    )
}

private fun episodeTrim(item: MediaItem, ep: Episode, segmentStore: MediaSegmentStore): SegmentTrimResponse {
    val season = ep.seasonNumber ?: 0
    val seasonEpisodes = DuplicateEpisodes.deduped(item.episodes).filter { (it.seasonNumber ?: 0) == season }.sortedBy { it.episodeNumber ?: 0 }
    val railRows = markOutliers(seasonEpisodes.map { episodeRow(item.id, null, it, segmentStore) })
    val ownRow = railRows.first { it.episodeKey == ep.filename && it.episodeNumber == (ep.episodeNumber ?: 0) }
    val evidence = segmentStore.evidenceForEpisode(item.id, ep.filename, ep.episodeNumber ?: 0)
    return SegmentTrimResponse(
        mediaId = item.id, itemTitle = item.title, seasonNumber = season, episodeKey = ep.filename, episodeNumber = ep.episodeNumber ?: 0,
        code = ownRow.code, title = ep.title ?: ownRow.code, durationSec = ownRow.durationSec, kind = "tv", partCount = ep.partCount,
        segments = ownRow.segments, evidence = evidence.toEvidenceDtos(), checked = ownRow.checked,
        rail = railRows.map { SegmentRailItem(it.episodeKey, it.episodeNumber, it.code, it.title, it.durationSec, it.segments, it.checked, it.outlier) },
        checkedCount = railRows.count { it.checked }, totalCount = railRows.size,
    )
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
