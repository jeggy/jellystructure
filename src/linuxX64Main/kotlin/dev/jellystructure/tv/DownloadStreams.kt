package dev.jellystructure.tv

import dev.jellystructure.log.Logger
import dev.jellystructure.shared.tv.ClientCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * DEB-4062 / DEB-4256 (DEB-3995 FR-6) — the policy over the encoder's download streams: a smaller copy a phone saves is made by the
 * same encoder a play uses. It is **no play** (no tracker entry, no QoE row, not in *Playing now*), but since DEB-4256 (owner,
 * 2026-11-10) it has **a play's priority**:
 *
 * - it is admitted by the budget a play's job is admitted by ([Encoder.planFor]), on the card plays use last; whether anyone is
 *   playing is not asked, and no play's start or restream ends it;
 * - one per phone ([Encoder.registerDownload]): the same phone's `start` replaces its own; there is no limit server-wide;
 * - one that is not running (reaped, lost to a restart) answers "waiting" ([holds]), never 404, so the phone keeps what it has and asks
 *   `start` again;
 * - one nobody reads for [abandonedMs] is reaped with its files ([reap]).
 */
class DownloadStreams(
    private val encoder: Encoder,
    private val abandonedMs: Long = ABANDONED_MS,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** Registers [plan] as [ownerDeviceId]'s download stream and returns its id. Admission was [Encoder.planFor]'s: nothing waits here. */
    suspend fun open(plan: EncoderPlan, ownerDeviceId: String, itemId: String, expiresAt: Long, startSegment: Int): String =
        encoder.registerDownload(plan, ownerDeviceId, itemId, expiresAt, startSegment)

    /**
     * Must a request for stream [id] be answered "waiting" (503) instead of served? True for a `dl-` stream that is not running: reaped,
     * replaced by its phone's next `start`, or lost to a restart. Never for a play's stream, and (DEB-4256) never because a play is live.
     */
    suspend fun holds(id: String): Boolean = isDownloadStreamId(id) && encoder.downloadStreams().none { it.id == id }

    /** Ends every download stream nobody has fetched a segment of for [abandonedMs]; its files are removed with its job. Returns how many. */
    suspend fun reap(): Int {
        val now = clock()
        val stale = encoder.downloadStreams().filter { now - it.lastReadMs > abandonedMs }
        stale.forEach {
            encoder.endDownload(it.id, "abandoned")
            Logger.info("download: stream ${it.id} reaped — no segment fetched for ${(now - it.lastReadMs) / 60_000} min (DEB-4062 FR-6)", "tv")
        }
        return stale.size
    }

    fun startReaper(scope: CoroutineScope) {
        scope.launch { while (true) { delay(REAP_EVERY_MS); runCatching { reap() } } }
    }

    companion object {
        /** No segment fetched for this long and the download stream is abandoned (a phone that lost its Wi-Fi asks again within minutes). */
        const val ABANDONED_MS = 10 * 60_000L
        const val REAP_EVERY_MS = 60_000L
        /** What `Retry-After` says on a waiting answer. */
        const val RETRY_AFTER_SECONDS = 5
    }
}

// ── FR-4 — what a copy is ───────────────────────────────────────────────────────────────────────────────────────

/** FR-4 — the three copies and the picture box of each (the encoder's 1080p / 720p / 480p rungs). */
internal val DOWNLOAD_COPIES: List<Pair<String, Int>> = listOf("high" to 1080, "medium" to 720, "small" to 480)

/** A copy is listed only when its estimate is under this share of the original (the ladder's "clearly lower" rule, `encoderRungs`). */
internal const val CLEARLY_SMALLER = 0.8

/** The capabilities a download plans under: one rung, one audio, no subtitles in the manifest (the phone saves text subtitles as VTT). */
internal fun downloadCaps(caps: ClientCapabilities): ClientCapabilities =
    caps.copy(hlsAdaptive = false, hlsAudioRenditions = false, hlsSubtitles = false)

/**
 * FR-4 — the rung the encoder makes for [caps]'s codec family in picture box [box], or null when the file has none there (it is not
 * that tall: the rung would be a lower box, which is another copy). It is the rung [Encoder.planFor] keeps for a plan built with
 * [downloadCaps] and `boxLimit = box`, so what the options estimate is what `start` encodes.
 */
internal fun downloadRung(caps: ClientCapabilities, source: EncoderSource, box: Int): Pair<EncoderCodec, EncoderRung>? {
    val codec = encoderCodecFor(caps.hlsHevc, caps.hlsHevcCapable, caps.videoCodecs, caps.supportsHdr10, caps.supportsHlg, source)
    val ceiling = listOf(if (codec == EncoderCodec.HEVC) caps.maxHevcBitrate else caps.maxH264Bitrate, caps.maxVideoBitrate)
        .filter { it > 0 }.minOrNull()?.toLong()
    val rung = encoderRungs(codec, source.width, source.height, source.videoBps, ceiling, maxRungs = 1, boxLimit = box).first()
    return if (rung.boxHeight == box) codec to rung else null
}

/** FR-4 — (video + audio bitrate) × runtime, in bytes. */
internal fun downloadEstimateBytes(videoBps: Long, audioBps: Long, durationMs: Long): Long = (videoBps + audioBps) * durationMs / 8_000L
