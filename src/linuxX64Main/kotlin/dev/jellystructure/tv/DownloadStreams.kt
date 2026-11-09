package dev.jellystructure.tv

import dev.jellystructure.log.Logger
import dev.jellystructure.shared.tv.ClientCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * DEB-4062 (DEB-3995 FR-6) — the policy over the encoder's download streams: a smaller copy a phone saves is made by the same encoder a
 * play uses, but it is **no play** and it **yields to every play**.
 *
 * - at most one runs, server-wide ([Encoder.registerDownload]);
 * - none is started while a play is live, and one running is ended the moment a play starts ([preempt]) — the process is killed at
 *   once and nothing is awaited, so the play's start does not wait for it;
 * - one that is not running answers "waiting" ([holds]), never 404, so the phone can show *Waiting for the server* and ask `start` again;
 * - one nobody reads for [abandonedMs] is reaped with its files ([reap]).
 */
class DownloadStreams(
    private val encoder: Encoder,
    /** Is a play live (the tracker's, with its grace)? Read at every `start` and every segment; a play's own start calls [preempt]. */
    private val playing: () -> Boolean,
    private val abandonedMs: Long = ABANDONED_MS,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    sealed interface Opened {
        data class Ok(val id: String) : Opened
        /** Not now: [reason] says why (a play is live, another phone holds the one stream). The route answers `503` "waiting". */
        data class Waiting(val reason: String) : Opened
    }

    /** Whether anything that is not a download stream holds the encoder, the GPU or the source disk now. */
    suspend fun playLive(): Boolean = playing() || encoder.playJobsRunning()

    /** Registers [plan] as the one download stream, or says why it cannot be one now. */
    suspend fun open(plan: EncoderPlan, ownerDeviceId: String, itemId: String, expiresAt: Long, startSegment: Int): Opened {
        if (playLive()) return Opened.Waiting("a play is using the server")
        val id = encoder.registerDownload(plan, ownerDeviceId, itemId, expiresAt, startSegment)
            ?: return Opened.Waiting("another download is running")
        return Opened.Ok(id)
    }

    /** A play needs the encoder, the GPU or the source disk: ends the download stream, if any. Returns how many it ended. */
    suspend fun preempt(why: String): Int {
        val ended = encoder.downloadStreams().count { encoder.endDownload(it.id, "yielded: $why") }
        if (ended > 0) Logger.info("download: stream ended — $why (DEB-4062 FR-6)", "tv")
        return ended
    }

    /**
     * Must a request for stream [id] be answered "waiting" (503) instead of served? True for a `dl-` stream that is not running: ended by
     * a play, reaped, or lost to a restart. And for a running one when a play is live (it is ended there and then: a play that did not
     * come through a start, such as a progress report, still wins).
     */
    suspend fun holds(id: String): Boolean {
        if (!isDownloadStreamId(id)) return false
        if (encoder.downloadStreams().none { it.id == id }) return true
        if (!playLive()) return false
        preempt("a play is live")
        return true
    }

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
