package dev.jellystructure.ravilo.ui.desktop

/**
 * DEB-3215 (DEB-3093 FR-9) — what mpv (Linux) is told about trouble, as pure decisions; libmpv itself, the network and the picture are the QA
 * row's.
 *
 * mpv reads one stream at a time, so FR-4's fall to a lower rung on a failed segment is the store's restream (a stream that ends in mpv's
 * load failure goes the existing way, DEB-3400) and not something an option can do; what the options can do is the waiting:
 *
 * - **FR-3.** `network-timeout` is ffmpeg's `rw_timeout`, which the HLS demuxer hands to every segment it opens. ffmpeg's tcp layer uses the
 *   same value to connect, so the read timeout (20 s) is also the connect timeout: 8 s on its own is not expressible.
 * - **FR-4.** ffmpeg's http reconnect (`stream-lavf-o`), on a dropped connection, a tcp/tls error at connect and a 4xx or 5xx status, with a
 *   delay that doubles from one second and gives up past 5 s. Whether the HLS demuxer passes these on to its segment requests, and not only
 *   to the playlist's, is not something this file can say: that is read off a real play.
 * - **FR-5.** `cache-pause-wait` is the buffered time mpv wants before it resumes after a stall (its default is 1 s).
 */
internal object MpvTrouble {
    /** FR-3 — the player's read timeout, seconds. */
    const val NETWORK_TIMEOUT_S = 20

    /** FR-5 — a stalled play resumes at 4 s buffered. */
    const val RESUME_AT_BUFFERED_S = 4

    /** FR-4 — the longest reconnect delay ffmpeg waits before it gives up, seconds. */
    const val RECONNECT_DELAY_MAX_S = 5

    /**
     * `stream-lavf-o` as mpv's key-value list reads it. The status list holds a comma, so its value is length-prefixed (`%7%4xx,5xx`),
     * which is how mpv quotes a value in an option list.
     */
    fun streamLavfOptions(): String {
        val statuses = "4xx,5xx"
        return listOf(
            "reconnect=1",
            "reconnect_streamed=1",
            "reconnect_on_network_error=1",
            "reconnect_on_http_error=%${statuses.length}%$statuses",
            "reconnect_delay_max=$RECONNECT_DELAY_MAX_S",
        ).joinToString(",")
    }

    /**
     * The options a film's mpv is created with (a music or radio engine keeps its own: a station's stream is not a segment and an audio book
     * is not a film). In the order they are set.
     */
    fun filmOptions(): List<Pair<String, String>> = listOf(
        "network-timeout" to "$NETWORK_TIMEOUT_S",
        "stream-lavf-o" to streamLavfOptions(),
        "cache-pause-wait" to "$RESUME_AT_BUFFERED_S",
    )
}

/**
 * DEB-3215 (DEB-3093 FR-5) — which of mpv's `paused-for-cache` edges are a stall of a playing picture. [MpvPlayer] counts every edge; the
 * cap one rung down must answer only the ones a viewer would see: not the first fill of a stream (no picture yet), not a seek's refill and
 * not the first seconds after a load, which are the player's own start. One per player, fed once a second by the watch loop on Compose's
 * thread.
 */
internal class MpvStallWatch {
    /** The count already answered; mpv's count restarts at each load. */
    private var seen = 0L

    /** A load (or a replacement) resets the engine's count. */
    fun reset() { seen = 0L }

    /**
     * Stalls to answer on this tick: the rise in [stalls] since the last one, when the picture has been shown and nothing started or moved
     * recently ([settling]: a load or a seek inside the last [SETTLE_MS]); otherwise the rise is taken as answered and nothing is returned.
     */
    fun onTick(stalls: Long, firstFrame: Boolean, settling: Boolean): Long {
        val rise = (stalls - seen).coerceAtLeast(0L)
        seen = stalls
        return if (firstFrame && !settling) rise else 0L
    }

    companion object {
        /** A load or a seek this recent explains a refill by itself. */
        const val SETTLE_MS = 3_000L
    }
}
