package dev.jellystructure.ravilo.ui.music

/*
 * DEB-3034 (a-song-that-waits-says-so.md, FR-1, FR-2) — when does a song's player count as *waiting*, and what does the
 * report say. One pure decision on a clock the caller feeds, so the Mac, Linux and Android engines cannot disagree and a
 * test can drive it with a fake clock.
 */

/** FR-1 — a wait shorter than this reports nothing; an ordinary song start takes about this long. */
internal const val SONG_WAIT_REPORT_AFTER_MS = 2_000L

/** FR-2 — a wait that began this soon after a seek is a `seek` wait. */
internal const val SONG_WAIT_SEEK_WINDOW_MS = 1_000L

/** FR-2 — the wire's limit on `wait_detail`. */
internal const val SONG_WAIT_DETAIL_MAX = 120

/** What the engine saw at [nowMs]. [wantsPlay] and [buffering] are the engine's own published state (FR-1). */
internal data class SongWaitSample(
    val nowMs: Long,
    val wantsPlay: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
    /** Media buffered ahead of [positionMs], ms. */
    val bufferedMs: Long,
    /** The bits/s the player has seen from the network; null where it has no figure (left out of the detail). */
    val observedBps: Long? = null,
    /** The Mac's `reasonForWaitingToPlay`; null elsewhere. */
    val why: String? = null,
)

/** What a [SongWaitTracker.feed] asks the engine to do. */
internal enum class SongWaitEdge { NONE, STARTED, ENDED }

internal class SongWaitTracker(private val afterMs: Long = SONG_WAIT_REPORT_AFTER_MS) {
    private var bufferingSince: Long? = null
    private var lastSeekAt: Long? = null
    private var firstPositionMs: Long? = null
    private var heard = false
    private var phase: String? = null

    /** True from the report that said `waiting = true` until the one that says `false`. */
    val waiting: Boolean get() = phase != null

    /** A new song was loaded: nothing has been heard of it, and a seek before it is another song's. */
    fun newSong() {
        bufferingSince = null; lastSeekAt = null; firstPositionMs = null; heard = false; phase = null
    }

    /** The listener (or a remote) moved the playhead at [nowMs]. */
    fun seeked(nowMs: Long) { lastSeekAt = nowMs }

    /**
     * Feed one sample. [SongWaitEdge.STARTED] at the first sample at least [afterMs] into a wait — send a report with
     * `waiting = true` at once; [SongWaitEdge.ENDED] at the first sample of a reported wait that is over (the player has
     * data again, or the listener paused, which ends a wait) — send `waiting = false` at once.
     */
    fun feed(s: SongWaitSample): SongWaitEdge {
        val first = firstPositionMs ?: s.positionMs.also { firstPositionMs = it }
        val waitingNow = s.wantsPlay && s.buffering
        if (!waitingNow) {
            bufferingSince = null
            // Sound: the player wants to play, has data, and the playhead is past where this song started.
            if (s.wantsPlay && s.positionMs != first) heard = true
            val wasWaiting = phase != null
            phase = null
            return if (wasWaiting) SongWaitEdge.ENDED else SongWaitEdge.NONE
        }
        val since = bufferingSince ?: s.nowMs.also { bufferingSince = it }
        if (phase == null && s.nowMs - since >= afterMs) {
            phase = when {
                !heard -> "start"
                lastSeekAt?.let { since - it in 0..SONG_WAIT_SEEK_WINDOW_MS } == true -> "seek"
                else -> "mid"
            }
            return SongWaitEdge.STARTED
        }
        return SongWaitEdge.NONE
    }

    /** FR-2 — `phase=<start|seek|mid> pos=<ms> buffered=<ms> observed=<bps>` (+ ` why=…`), at most 120 characters; null when no wait is reported. */
    fun detail(s: SongWaitSample): String? {
        val p = phase ?: return null
        val base = "phase=$p pos=${s.positionMs.coerceAtLeast(0)} buffered=${s.bufferedMs.coerceAtLeast(0)}" +
            (s.observedBps?.takeIf { it > 0 }?.let { " observed=$it" } ?: "")
        val why = s.why?.replace(Regex("[^A-Za-z0-9._:-]"), "_")?.takeIf { it.isNotEmpty() }
        return (if (why != null) "$base why=$why" else base).take(SONG_WAIT_DETAIL_MAX)
    }
}
