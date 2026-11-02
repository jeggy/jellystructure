package dev.jellystructure.ravilo.ui.music

import kotlin.math.abs

/**
 * DEB-3400 (FR-1) — a song that stops moving. Fed one [Sample] per monitor tick; answers with how long the position has
 * stood still once it has stood still for [windowMs] while the viewer wants the song to play, and null otherwise.
 *
 * A position that moved [moveMs] or more starts the count again, and so does any tick that came more than [gapMs] after
 * the one before (the Mac asleep, the process suspended): only time the monitor actually ran counts, so waking the Mac
 * is never a freeze. [Sample.nowMs] is wall-clock time for that reason — a monotonic clock stops during sleep.
 */
internal class FreezeWatch(
    private val windowMs: Long = 8_000L,
    private val moveMs: Long = 1_000L,
    private val gapMs: Long = 2_000L,
) {
    data class Sample(
        val nowMs: Long,
        val positionMs: Long,
        val wantsPlay: Boolean,
        val seeking: Boolean,
        val ended: Boolean,
        val failed: Boolean,
    )

    private var anchorAt = -1L
    private var anchorPos = 0L
    private var lastAt = -1L

    /** Milliseconds the position has stood still (≥ [windowMs]) when this tick finds the song frozen; null when it is not. */
    fun feed(s: Sample): Long? {
        val gap = lastAt >= 0 && s.nowMs - lastAt > gapMs
        lastAt = s.nowMs
        if (!(s.wantsPlay && !s.seeking && !s.ended && !s.failed)) { anchorAt = -1L; return null }
        if (anchorAt < 0 || gap || abs(s.positionMs - anchorPos) >= moveMs) { anchorAt = s.nowMs; anchorPos = s.positionMs; return null }
        val still = s.nowMs - anchorAt
        if (still < windowMs) return null
        anchorAt = -1L   // found: the verdict is the engine's, and the next count starts from nothing
        return still
    }

    fun reset() { anchorAt = -1L; lastAt = -1L }
}

/** What the engine does about a frozen song or a failure (FR-2 to FR-5). */
internal enum class FreezeVerdict(val label: String) {
    /** FR-2 — near its end: the song has ended, R322's next-song rule runs. */
    ENDED("ended"),
    /** FR-3 / FR-5 — load it again at the frozen position, without a word. */
    RESTART("restarted"),
    /** FR-4's lean — the restart did not help: the next song. */
    SKIP("skipped"),
    /** R322's failure sheet. */
    SHEET("sheet"),
}

/**
 * DEB-3400 (FR-2 to FR-6) — the rules for one song or book part, free of any player. A freeze point gets one restart; the
 * restart has failed when the song stops again before it has played [recoverMs] past that point (a position below it,
 * as a loading player reports, counts). A stop further on is a new freeze point with a restart of its own.
 */
internal class FreezeRecovery(
    private val nearEndMs: Long = 5_000L,
    private val recoverMs: Long = 30_000L,
) {
    private var restartedFrom: Long? = null
    var freezes = 0
        private set
    var frozenMs = 0L
        private set

    /** A song or part started from scratch (not by a restart of ours). */
    fun newSong() { restartedFrom = null; freezes = 0; frozenMs = 0L }

    /** The viewer moved the playhead: the old freeze point says nothing about the new place. */
    fun moved() { restartedFrom = null }

    /** What the freeze QoE carries once the song closes, and forgets it. Null when nothing froze. */
    fun takeTrace(): Pair<Int, Long>? = if (freezes > 0) (freezes to frozenMs).also { freezes = 0; frozenMs = 0L } else null

    fun onFreeze(positionMs: Long, durationMs: Long, stillMs: Long, book: Boolean): FreezeVerdict {
        freezes++; frozenMs += stillMs
        if (durationMs > 0 && positionMs >= durationMs - nearEndMs) return FreezeVerdict.ENDED
        if (restartDone(positionMs)) return if (book) FreezeVerdict.SHEET else FreezeVerdict.SKIP   // skipping a part loses some of the book
        restartedFrom = positionMs
        return FreezeVerdict.RESTART
    }

    /** FR-5 — a player failure: a network one gets the same single restart, anything else goes straight to the sheet (R237). */
    fun onFailure(positionMs: Long, network: Boolean): FreezeVerdict {
        if (!network || restartDone(positionMs)) return FreezeVerdict.SHEET
        restartedFrom = positionMs
        return FreezeVerdict.RESTART
    }

    private fun restartDone(positionMs: Long): Boolean = restartedFrom?.let { positionMs < it + recoverMs } ?: false
}
