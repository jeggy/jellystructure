package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.LiveTvStreamTicket

/** DEB-2627 FR-1/FR-2 — the spellings of `LiveTvStreamTicket.streamKind`. */
const val STREAM_KIND_HLS_LADDER = "hls_ladder"
const val STREAM_KIND_HLS = "hls"
const val STREAM_KIND_OTHER = "other"

/**
 * DEB-2627 (FR-3) — is this live ticket an HLS stream? A backend that classified it says so with [STREAM_KIND_HLS_LADDER] or
 * [STREAM_KIND_HLS]. One that predates the classification sends nothing, and a field the server's `Json` leaves off the wire
 * decodes as its default, [STREAM_KIND_OTHER] — so "other" is also what an old backend looks like. The URL then decides: `m3u8`
 * anywhere in it, the query included (the production abroad feed ends `…smil?type=m3u8`), is HLS.
 */
fun isHlsTicket(streamKind: String, url: String): Boolean =
    streamKind == STREAM_KIND_HLS_LADDER || streamKind == STREAM_KIND_HLS || url.contains("m3u8")

/**
 * DEB-2627 (FR-3) — what a live load tells the player, and the mark of a live load itself: a live tune starts from no film
 * ladder state, so a player given one clears or skips [PlayerLadderHints], `MacLadderPeak`, the web's `WebLadderCap` and
 * the Android load control's direct-stream keying.
 *
 * @param hls the ticket is HLS: Android's `MediaItem` carries the M3U8 type and the browser plays it through hls.js, whatever
 *   the URL looks like.
 * @param ladderBps the source's rungs (`BANDWIDTH`), highest first; non-empty only for an `hls_ladder` ticket. Linux needs it
 *   (mpv cannot switch rungs); everywhere else the player adapts over the rungs it reads itself.
 * @param linkEstimateBps what this device last measured its link to carry, or null; Linux's start rung.
 */
data class LiveLoad(
    val hls: Boolean,
    val ladderBps: List<Long> = emptyList(),
    val linkEstimateBps: Long? = null,
) {
    companion object {
        fun of(ticket: LiveTvStreamTicket, linkEstimateBps: Long? = LinkEstimate.bps): LiveLoad = LiveLoad(
            hls = isHlsTicket(ticket.streamKind, ticket.hlsUrl),
            ladderBps = if (ticket.streamKind == STREAM_KIND_HLS_LADDER) ticket.ladder.map { it.bandwidthBps }.filter { it > 0 }.sortedDescending() else emptyList(),
            linkEstimateBps = linkEstimateBps,
        )
    }
}

/** DEB-2627 (FR-3) — the last link measurement a film's ticket carried (`measuredBandwidthBps`); a live tune on Linux starts its rung from it. */
object LinkEstimate {
    @kotlin.concurrent.Volatile var bps: Long? = null
}

/** What a live player failure or a missing first frame asks the screen to do next. */
enum class LiveFallbackAction { IGNORE, RETRY_SOURCE, FALL_BACK, SHOW_ERROR }

/**
 * DEB-2627 (FR-4) — one tune's fallback decision. The source is the broadcaster's own stream ([viaJellyfin] false). It falls
 * back to Jellyfin's remux, once, when the source gives no first frame within [NO_FIRST_FRAME_MS], when the player raises a fatal
 * error before the first frame, or when it raises one again after one retry of the source itself.
 *
 * It never falls back on a channel the backend did not classify as HLS (`other` is already Jellyfin's own stream, and an old
 * backend ignores `via`), and never from the fallback itself: a second failure is today's live error. The 15 s limit belongs
 * to the source alone — Jellyfin's remux takes about 11 s to its first segment and is not timed.
 */
class LiveFallbackPolicy(streamKind: String, private val viaJellyfin: Boolean) {
    private val canFallBack = !viaJellyfin && (streamKind == STREAM_KIND_HLS_LADDER || streamKind == STREAM_KIND_HLS)
    private var sawFirstFrame = false
    private var retried = false

    /** The player drew a frame of this tune. */
    fun onFirstFrame() { sawFirstFrame = true }

    /** Whether the clock on the first frame is worth keeping: only the source is timed. */
    val timesFirstFrame: Boolean get() = canFallBack

    /** The source has been loading for [NO_FIRST_FRAME_MS] with no frame. */
    fun onNoFirstFrame(): LiveFallbackAction = if (canFallBack && !sawFirstFrame) LiveFallbackAction.FALL_BACK else LiveFallbackAction.IGNORE

    /** The player raised a fatal error on the current load. */
    fun onFatal(): LiveFallbackAction = when {
        !canFallBack -> LiveFallbackAction.SHOW_ERROR
        !sawFirstFrame -> LiveFallbackAction.FALL_BACK
        !retried -> { retried = true; sawFirstFrame = false; LiveFallbackAction.RETRY_SOURCE }
        else -> LiveFallbackAction.FALL_BACK
    }

    companion object {
        const val NO_FIRST_FRAME_MS = 15_000L
    }
}
