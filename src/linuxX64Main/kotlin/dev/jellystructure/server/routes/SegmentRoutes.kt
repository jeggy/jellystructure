package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.jobs.MediaJobParams
import dev.jellystructure.media.DuplicateEpisodes
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.FingerprintService
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaSegmentRow
import dev.jellystructure.media.MediaSegmentStore
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.validSegmentKeys
import dev.jellystructure.media.PipelineStepOps
import dev.jellystructure.media.SegmentKind
import dev.jellystructure.media.SegmentSource
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.model.fileDurationMs
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
private val SEGMENTS_IDENTITY = dev.jellystructure.auth.JellyfinDeviceIdentity(SEGMENTS_STREAM_DEVICE_ID, "Jellystructure Segment Editor")
/** Phase 222 (FR-222-5) — how far past the measured end an edit may reach before it is refused: a
 *  container's own duration and the last decodable frame disagree by a hair, never by seconds. */
private const val EDIT_PAST_END_TOLERANCE_MS = 2_000L
private val streamNonce = kotlin.concurrent.AtomicInt(0)

// Phase 190 (FR-190-2) — the browser-safe set a Chromium-class desktop browser decodes natively.
// Confirmed against the 2026-09-06 codec census: 46.4% of library files lead with a codec NOT in this
// set (eac3/ac3/dts/truehd), and 90% of those are MKV (also not in the safe-container set below) — the
// video decodes, the audio never does, and nothing on the client can tell the difference (no `error`
// event fires; see Segments.kt's wireVideo). The decision must be made here, from stored Track data,
// before the browser ever sees a URL.
private val BROWSER_SAFE_AUDIO_CODECS = setOf("aac", "mp3", "opus", "flac", "vorbis")
private val BROWSER_SAFE_CONTAINERS = setOf("mp4", "m4v", "webm")

/** Phase 190 (FR-190-2) — the container is never stored on [Track]; the file extension of the unit's
 *  own path (movie file, or the specific episode file) is the container and is already on hand here. */
internal fun containerOf(path: String): String = path.substringAfterLast('.', "").lowercase()

/** Phase 190 — true when every audio track the file carries is one the browser decodes natively AND
 *  the container itself is one a bare `<video>` element opens without a remux. Both must hold —
 *  an AAC track inside an MKV still needs remuxing to a container Chromium's `<video>` will open,
 *  and TS_190's audio census was measured per FIRST track, but this checks every track: a second,
 *  commentary-style AC-3 track the picker's audio tab could switch to later is out of scope for THIS
 *  editor (it never picks a non-default audio track — see the phase's own non-goals), so only the
 *  file's default/first track actually matters, but checking all of them costs nothing and is honest
 *  about multi-track files whose non-default track is what a future feature might one day play. */
internal fun isBrowserSafeDirectPlay(tracks: List<Track>, path: String): Boolean {
    val audio = tracks.filter { it.kind == TrackKind.AUDIO }
    if (audio.isEmpty()) return true  // nothing to fail on — the video-only `error` fallback still applies
    return audio.all { it.codec.lowercase() in BROWSER_SAFE_AUDIO_CODECS } && containerOf(path) in BROWSER_SAFE_CONTAINERS
}

/**
 * Phase 222 (FR-222-1) — UNIQUE per stream start. Phase 190 made this deterministic ("a reopen reuses the
 * same id, which is harmless"); it was not harmless. Jellyfin 10.11.11 hashes a progressive transcode's
 * output path from `MediaPath-UserAgent-DeviceId-PlaySessionId` (StreamingHelpers.cs:376) — never
 * `StartTimeTicks` — and serves an existing file from byte zero without starting ffmpeg
 * (FileStreamResponseHelpers.cs:149-163). So every seek the editor made was handed the stream it was
 * already playing, and two admin tabs on one title shared one transcode. The readable prefix stays (it is
 * what Jellyfin's session list shows); [nonce] makes each start its own output path, so a seek's `-ss`
 * really runs. The previous id is stopped by the stream route (see there). No UUID source is needed on
 * Kotlin/Native: epoch seconds plus a process counter cannot collide within a job's 10 s lifetime.
 */
internal fun segmentsPlaySessionId(jellyfinId: String, episodeKey: String?, nonce: String): String =
    "segeditor-$jellyfinId" + (episodeKey?.let { "-${it.hashCode()}" } ?: "") + "-$nonce"

internal fun nextStreamNonce(): String = "${dev.jellystructure.nowEpochSec()}-${streamNonce.incrementAndGet()}"

/**
 * Phase 222 — what the trim view needs to play a unit. [startedAtMs] is the media time of the first frame
 * the stream will contain: 0 for direct play and a fresh remux open; for a remux seek, the keyframe at or
 * before the requested offset (FR-222-2), because `-ss` with stream copy starts there and the browser's
 * clock starts at zero there (the fragmented MP4's edit list is ignored by ffmpeg-based demuxers —
 * "advanced_editlist does not work with fragmented MP4"). [startedAtExact] is false when that could not
 * be measured, so the client says so instead of pretending.
 */
@kotlinx.serialization.Serializable
data class SegmentStreamInfo(val url: String, val mode: String, val playSessionId: String = "", val startedAtMs: Long = 0, val startedAtExact: Boolean = true)

/** Phase 222 (FR-222-6) — the stored envelope as the client reads it: [peaks] one 0-100 value per [bucketMs]. */
@kotlinx.serialization.Serializable
data class SegmentWaveformDto(val bucketMs: Long, val peaks: List<Int>)

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
    /** Phase 222 (FR-222-3) — where [durationSec] came from: `file` (measured) · `tmdb` (whole-minute
     *  estimate, labelled as such) · `markers` (furthest marker edge) · `unknown` (0). */
    val durationSource: String = "unknown",
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
    val durationSource: String = "unknown",
    val kind: String,   // "tv" | "movie"
    val partCount: Int = 1,
    val segments: List<SegmentDto> = emptyList(),
    val evidence: List<SegmentEvidenceDto> = emptyList(),
    val checked: Boolean = false,
    val rail: List<SegmentRailItem> = emptyList(),
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
)

fun Route.segmentRoutes(store: MediaStore, segmentStore: MediaSegmentStore, configStore: ConfigStore, fingerprintService: FingerprintService?, appScope: CoroutineScope, jellyfinClient: JellyfinClient, mediaJobQueue: dev.jellystructure.media.MediaJobQueue, mediaHistory: MediaHistory) {
    // Phase 222 (FR-222-7) — opening a title in the editor prunes its orphaned rows first (cheap: one
    // key listing per table), so what the sheet counts is what the episodes on disk can carry.
    fun pruneOnOpen(item: MediaItem) {
        val removed = segmentStore.pruneOrphans(item.id, validSegmentKeys(item))
        if (removed > 0) mediaHistory.record(item.id, "segments_pruned", "$removed orphaned intro/credits unit(s) removed — the episode files they were filed under no longer exist")
    }
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
                    pruneOnOpen(item)
                    call.respond(seasonSheet(item, season, segmentStore))
                }
                movieId != null -> {
                    val item = store.get(movieId) ?: return@get call.respond(HttpStatusCode.NotFound)
                    pruneOnOpen(item)
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
            // Phase 222 (FR-222-5) — validated against the unit's MEASURED length when known; an unknown
            // length only relaxes the past-the-end rule, never the others.
            val item = store.get(itemId)
            val unitDurationMs = if (episodeKey.isEmpty()) item?.tracks?.fileDurationMs()
                else item?.episodes?.firstOrNull { it.filename == episodeKey && (it.episodeNumber ?: 0) == episodeNumber }?.tracks?.fileDurationMs()
            validateSegmentEdit(kind, body.startMs, body.endMs, unitDurationMs)?.let { reason ->
                return@put call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to reason))
            }
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
            val item0 = store.get(itemId) ?: return@get call.respond(HttpStatusCode.NotFound)
            if (item0.kind != MediaKind.MOVIE) return@get call.respond(HttpStatusCode.BadRequest)
            pruneOnOpen(item0)
            val item = ensureMeasured(store, item0, null)
            call.respond(movieTrim(item, segmentStore))
        }

        get("/{itemId}/episode") {
            val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val key = call.request.queryParameters["key"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val n = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            val item0 = store.get(itemId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val ep0 = item0.episodes.firstOrNull { it.filename == key && (it.episodeNumber ?: 0) == n } ?: return@get call.respond(HttpStatusCode.NotFound)
            pruneOnOpen(item0)
            val item = ensureMeasured(store, item0, ep0)
            val ep = item.episodes.firstOrNull { it.filename == key && (it.episodeNumber ?: 0) == n } ?: ep0
            call.respond(episodeTrim(item, ep, segmentStore))
        }

        // Step 5 — a stream URL for the trim view's <video>, using the admin's OWN Jellyfin session
        // (jellystructure Auth *is* Jellyfin Auth — every cookie session already carries a real
        // jellyfinUserToken) rather than a device token.
        //
        // Phase 190 — Phase 163's "never transcodes" was right about VIDEO and wrong about AUDIO: a
        // browser that can't decode the video fires `error` (caught, honest fallback); one that decodes
        // the video but not the audio fires NOTHING — it plays picture with no sound, and the old code
        // here always minted the same Static=true URL regardless. `isBrowserSafeDirectPlay` decides,
        // server-side, from the unit's own stored Track codecs + its container — never a client
        // capability guess — whether that file needs the video-copy/audio-remux shape instead. Probed
        // live against this house's Jellyfin 10.11.11 before writing this (2026-09-06, against
        // 40 Weeks Gone, a real DTS/MKV title, jellyfinId 22a1aa25e38aac188da9d3f25e043d3d):
        //   - `/Videos/{id}/stream.mp4?Static=false&VideoCodec=copy&AudioCodec=aac&AudioChannels=2&...`
        //     works with NO PlaybackInfo negotiation needed (a bare GET, same shape as Static=true) —
        //     but responds `Accept-Ranges: none`. A live progressive transcode has no known total size
        //     and is NOT byte-range seekable — `video.currentTime = x` cannot work against it as a single
        //     persistent resource. This is standard Jellyfin/Emby behaviour, not specific to this file.
        //   - `/Videos/{id}/master.m3u8?VideoCodec=copy&AudioCodec=aac&...` returns a proper
        //     `#EXT-X-PLAYLIST-TYPE:VOD` playlist (confirmed: 1001 segments, ~6s each) — genuinely
        //     seekable by design, but native `<video src>` HLS playback only works in Safari; Chromium
        //     (the browser this bug was reported against) has no built-in HLS support, and this admin
        //     frontend has no HLS.js (or similar) dependency today. Adopting HLS would need that new
        //     client-side dependency — out of scope for this phase's fix; see the open questions.
        //   - `StartTimeTicks` IS honoured on the progressive endpoint (confirmed: a request 20 minutes
        //     in returned 200 in ~2s, vs ~6.5s from the start — ffmpeg seeking with `-ss`, not decoding
        //     from zero). This is exactly how Jellyfin's own web client seeks a live transcode: reload
        //     `<video src>` with a new `StartTimeTicks`, rather than an in-place `currentTime` set.
        // FR-190-5's guidance ("say so and re-scope rather than shipping a player that plays sound but
        // cannot jump to the credits") therefore led to the progressive+StartTimeTicks-reload shape
        // (implemented client-side in Segments.kt's seekToAbsoluteMs), not the HLS one.
        get("/{itemId}/stream") {
            val session = runCatching { call.attributes[SessionKey] }.getOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.get(itemId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val episodeKey = call.request.queryParameters["episode"]
            val episodeNumber = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            // Phase 190 (FR-190-5) — a reload-to-seek request from the client, only meaningful in remux
            // mode; ignored (not merely harmless — actively wrong) for a direct-play URL, since Static=true
            // already serves the whole file and StartTimeTicks has no effect there.
            val startMs = call.request.queryParameters["startMs"]?.toLongOrNull()?.coerceAtLeast(0L)
            // Phase 222 (FR-222-1) — the id of the stream this tab is abandoning, if any.
            val prev = call.request.queryParameters["prev"]?.takeIf { it.isNotBlank() }
            val jellyfinId = resolveJellyfinId(item, episodeKey, episodeNumber)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "not matched in Jellyfin yet"))
            val (tracks, path) = if (episodeKey != null) {
                val ep = item.episodes.firstOrNull { it.filename == episodeKey && (it.episodeNumber ?: 0) == episodeNumber }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                ep.tracks to ep.path
            } else item.tracks to item.path
            val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            // Phase 222 (FR-222-1) — the previous stream is stopped by ITS OWN id, never by DeviceId alone
            // (that would kill another tab's stream on the same title). Fire-and-forget: the new id hashes
            // to a new output path on Jellyfin's side, so correctness does not depend on the old file being
            // gone first — Jellyfin's delete retries on a 500 ms ladder and a request would win that race.
            if (prev != null) {
                val token = session.jellyfinUserToken
                appScope.launch { jellyfinClient.stopActiveEncoding(base, token, SEGMENTS_IDENTITY, prev) }
            }
            val directPlay = isBrowserSafeDirectPlay(tracks, path)
            if (directPlay) {
                val url = "$base/Videos/$jellyfinId/stream?Static=true&MediaSourceId=$jellyfinId&DeviceId=$SEGMENTS_STREAM_DEVICE_ID&api_key=${session.jellyfinUserToken}"
                return@get call.respond(SegmentStreamInfo(url = url, mode = "direct"))
            }
            val psid = segmentsPlaySessionId(jellyfinId, episodeKey, nextStreamNonce())
            val requested = startMs ?: 0L
            // Phase 222 (FR-222-2) — where the picture will really start. `-ss` with stream copy cannot cut
            // inside a GOP, so the stream begins at the keyframe at or before the request and the browser's
            // clock starts at zero there; the client bases the playhead on THIS, not on what it asked for.
            val keyframeSec = if (requested > 0) FfprobeRunner.keyframeAtOrBefore(path, requested / 1000.0) else 0.0
            val startedAtMs = keyframeSec?.let { (it * 1000).toLong().coerceAtLeast(0L) } ?: requested
            val startParam = if (requested > 0) "&StartTimeTicks=${requested * 10_000}" else ""
            val url = "$base/Videos/$jellyfinId/stream.mp4?Static=false&VideoCodec=copy&AudioCodec=aac&AudioChannels=2" +
                "&MediaSourceId=$jellyfinId&DeviceId=$SEGMENTS_STREAM_DEVICE_ID&PlaySessionId=$psid$startParam&api_key=${session.jellyfinUserToken}"
            call.respond(SegmentStreamInfo(url = url, mode = "remux", playSessionId = psid, startedAtMs = startedAtMs, startedAtExact = keyframeSec != null))
        }

        // Phase 190 (FR-190-6) — releases an in-flight audio-remux transcode when the trim view closes
        // (navigate away, or open a different title). Safe to call even for a direct-play session or one
        // already torn down — StopEncodingProcess no-ops when nothing matches (see stopActiveEncoding's
        // own doc). Phase 180 exists because an abandoned transcode kept NVENC busy for nobody; this tool
        // is a smaller version of the exact same risk.
        post("/stream/stop") {
            val playSessionId = call.request.queryParameters["playSessionId"]?.takeIf { it.isNotBlank() }
                ?: return@post call.respond(HttpStatusCode.NoContent)
            val session = runCatching { call.attributes[SessionKey] }.getOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            jellyfinClient.stopActiveEncoding(base, session.jellyfinUserToken, SEGMENTS_IDENTITY, playSessionId)
            call.respond(HttpStatusCode.NoContent)
        }

        // Phase 222 (FR-222-6) — the stored envelope, and ONLY the stored envelope. Phase 163 decoded the
        // whole file here on every open and every structural edit (a 60 GB linear read for a 4K remux —
        // the 2026-09-15 stall's I/O class) for 150 buckets that resolved to one peak per 18 s. Now a
        // missing envelope queues one background job for exactly this unit and answers 404; the trim view
        // renders an empty lane and says so. This handler never spawns a process.
        get("/{itemId}/waveform") {
            val itemId = call.parameters["itemId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.get(itemId) ?: return@get call.respond(HttpStatusCode.NotFound)
            val episodeKey = call.request.queryParameters["episode"]?.takeIf { it.isNotEmpty() }
            val episodeNumber = call.request.queryParameters["n"]?.toIntOrNull() ?: 0
            val stored = segmentStore.getWaveform(item.id, episodeKey ?: "", episodeNumber)
            when {
                stored != null && stored.bucketMs > 0 ->
                    call.respond(SegmentWaveformDto(stored.bucketMs, stored.peaks.map { it.toInt() and 0xFF }))
                stored != null -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "couldn't decode audio"))
                else -> {
                    val label = if (episodeKey != null) "${item.title} · $episodeKey" else item.title
                    mediaJobQueue.enqueueWaveformUnit(item, episodeKey ?: "", episodeNumber, label)
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "not computed yet"))
                }
            }
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
                // Phase 222 (FR-222-3) — end-relative placement only against a MEASURED length; an
                // estimate here wrote credits into the last TMDB-minute, not the last file-minute.
                val durMs = ep.tracks.fileDurationMs() ?: continue
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
    val duration = resolveDuration(ep.tracks.fileDurationMs(), ep.runtime, rows)
    val code = if (ep.seasonNumber != null && ep.episodeNumber != null) {
        "S${ep.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
    } else ep.filename.substringBeforeLast('.')
    return SegmentEpisodeRow(
        mediaId = mediaId, itemTitle = itemTitle, episodeKey = ep.filename, episodeNumber = ep.episodeNumber ?: 0,
        code = code, title = ep.title ?: code, durationSec = duration.first, durationSource = duration.second,
        jellyfinId = ep.jellyfinId, partCount = ep.partCount, segments = rows.toDtos(), checked = rows.any { it.checkedAt != null },
    )
}

private fun movieRow(item: MediaItem, itemTitle: String?, segmentStore: MediaSegmentStore): SegmentEpisodeRow {
    val rows = segmentStore.segmentsForItem(item.id)
    val duration = resolveDuration(item.tracks.fileDurationMs(), item.runtime, rows)
    return SegmentEpisodeRow(
        mediaId = item.id, itemTitle = itemTitle, code = item.title, title = item.title,
        durationSec = duration.first, durationSource = duration.second, jellyfinId = item.jellyfinId, partCount = 1,
        segments = rows.toDtos(), checked = rows.any { it.checkedAt != null },
    )
}

/**
 * Phase 222 (FR-222-3) — one duration, labelled. `file` = measured by jellystructure's own ffprobe
 * (`Track.durationMs` on the video track, stored with the track list at every examination and by
 * [ensureMeasured] the first time the editor opens a unit); `tmdb` = TMDB's whole-minute runtime, kept
 * ONLY as a labelled fallback until the file is measured — it is shorter than the file for most credits
 * (2 152 credits markers sat past it in production on 2026-09-16) and the client says so; `markers` = the
 * furthest marker edge when nothing else is known; `unknown` = 0. The client never rescales any of these
 * from the `<video>` element: a fragmented-MP4 remux reports one GOP as its duration.
 */
internal fun resolveDuration(durationMs: Long?, runtimeMinutes: Int?, rows: List<MediaSegmentRow>): Pair<Double, String> {
    if (durationMs != null && durationMs > 0) return durationMs / 1000.0 to "file"
    if (runtimeMinutes != null && runtimeMinutes > 0) return runtimeMinutes * 60.0 to "tmdb"
    val edge = rows.maxOfOrNull { it.endMs ?: it.startMs }
    if (edge != null && edge > 0) return edge / 1000.0 to "markers"
    return 0.0 to "unknown"
}

/** Phase 222 (FR-222-5) — null = acceptable; else the reason, in the operator's words. [durationMs] null
 *  = not measured, which only relaxes the past-the-end rule. */
internal fun validateSegmentEdit(kind: String, startMs: Long, endMs: Long?, durationMs: Long?): String? = when {
    kind !in SegmentKind.ALL -> "'$kind' is not a marker kind"
    startMs < 0 -> "a marker cannot start before the file does"
    endMs != null && endMs <= startMs -> "a marker's end must come after its start"
    durationMs != null && startMs > durationMs + EDIT_PAST_END_TOLERANCE_MS -> "start is past the end of the file (${fmtMs(durationMs)})"
    durationMs != null && endMs != null && endMs > durationMs + EDIT_PAST_END_TOLERANCE_MS -> "end is past the end of the file (${fmtMs(durationMs)})"
    else -> null
}

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * Phase 222 (FR-222-3) — the first time the editor opens a unit whose file has not been measured since
 * `Track.durationMs` existed, measure it now (one bounded ffprobe of the container header — not a read
 * of the file) and persist it on the video track, so the very next scan-less open is a plain read and the
 * consensus/apply paths see the same number. Returns the item as stored afterwards. A file with no video
 * track has nowhere to carry the figure and stays unmeasured (the resolver then labels the fallback).
 */
private suspend fun ensureMeasured(store: MediaStore, item: MediaItem, ep: Episode?): MediaItem {
    val tracks = ep?.tracks ?: item.tracks
    if (tracks.fileDurationMs() != null) return item
    val path = ep?.path ?: item.path
    val sec = FfprobeRunner.duration(path)?.takeIf { it > 0 } ?: return item
    val stamped = stampDuration(tracks, (sec * 1000).toLong()) ?: return item
    val updated = if (ep == null) item.copy(tracks = stamped)
        else item.copy(episodes = item.episodes.map { if (it.filename == ep.filename && it.episodeNumber == ep.episodeNumber) it.copy(tracks = stamped) else it })
    store.updateOne(updated)
    return store.get(item.id) ?: updated
}

private fun stampDuration(tracks: List<Track>, ms: Long): List<Track>? {
    val idx = tracks.indexOfFirst { it.kind == TrackKind.VIDEO }
    if (idx < 0) return null
    return tracks.mapIndexed { i, t -> if (i == idx) t.copy(durationMs = ms) else t }
}

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
    // Phase 222 (FR-222-3) — an end-relative lead is only meaningful against a measured length.
    val creditsLeads = nonOutliers.filter { it.durationSource == "file" }.mapNotNull { r ->
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
    val duration = resolveDuration(item.tracks.fileDurationMs(), item.runtime, rows)
    return SegmentTrimResponse(
        mediaId = item.id, itemTitle = item.title, code = item.title, title = item.title,
        durationSec = duration.first, durationSource = duration.second, kind = "movie", partCount = 1,
        segments = rows.toDtos(), evidence = evidence.toEvidenceDtos(), checked = checked,
        checkedCount = if (checked) 1 else 0, totalCount = 1,
    )
}

private fun episodeTrim(item: MediaItem, ep: Episode, segmentStore: MediaSegmentStore): SegmentTrimResponse {
    val season = ep.seasonNumber ?: 0
    val seasonEpisodes = DuplicateEpisodes.deduped(item.episodes).filter { (it.seasonNumber ?: 0) == season }.sortedBy { it.episodeNumber ?: 0 }
    val railRows = markOutliers(seasonEpisodes.map { episodeRow(item.id, null, it, segmentStore) })
    // An episode the duplicate-dedup dropped from the rail still gets its own row rather than a 500.
    val ownRow = railRows.firstOrNull { it.episodeKey == ep.filename && it.episodeNumber == (ep.episodeNumber ?: 0) }
        ?: episodeRow(item.id, null, ep, segmentStore)
    val evidence = segmentStore.evidenceForEpisode(item.id, ep.filename, ep.episodeNumber ?: 0)
    return SegmentTrimResponse(
        mediaId = item.id, itemTitle = item.title, seasonNumber = season, episodeKey = ep.filename, episodeNumber = ep.episodeNumber ?: 0,
        code = ownRow.code, title = ep.title ?: ownRow.code, durationSec = ownRow.durationSec, durationSource = ownRow.durationSource, kind = "tv", partCount = ep.partCount,
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
