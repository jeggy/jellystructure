package dev.jellystructure.ravilo.ui.music

import kotlin.math.abs

/**
 * DEB-3400 (FR-1) — a song that stops moving. Fed one [Sample] per monitor tick; answers with how long the position has
 * stood still once it has stood still for [windowMs] while the viewer wants the song to play, and null otherwise.
 *
 * A position that moved [moveMs] or more starts the count again, and so does any tick that came more than [gapMs] after
 * the one before (the Mac asleep, the process suspended): only time the monitor actually ran counts, so waking the Mac
 * is never a freeze. [Sample.nowMs] is wall-clock time for that reason — a monotonic clock stops during sleep.
 *
 * DEB-5538 (FR-7) — until the position has first moved [moveMs] past where the song was loaded, the song has not started
 * and the count is [startWindowMs], not [windowMs]: a hi-res file on a thin line spends its first seconds loading. A
 * verdict reached in that state has [neverStarted] true.
 */
internal class FreezeWatch(
    private val windowMs: Long = 8_000L,
    private val startWindowMs: Long = 30_000L,
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
    private var loadedPos = -1L
    private var started = false

    /** FR-7 — the position has moved [moveMs] past where this load began. */
    val hasStarted: Boolean get() = started

    /** True when the verdict [feed] last gave came before the song had moved [moveMs] past where it was loaded (FR-7). */
    var neverStarted = false
        private set

    /** Milliseconds the position has stood still (≥ [windowMs]) when this tick finds the song frozen; null when it is not. */
    fun feed(s: Sample): Long? {
        val gap = lastAt >= 0 && s.nowMs - lastAt > gapMs
        lastAt = s.nowMs
        if (loadedPos < 0) loadedPos = s.positionMs
        if (!started && abs(s.positionMs - loadedPos) >= moveMs) started = true
        if (!(s.wantsPlay && !s.seeking && !s.ended && !s.failed)) { anchorAt = -1L; return null }
        if (anchorAt < 0 || gap || abs(s.positionMs - anchorPos) >= moveMs) { anchorAt = s.nowMs; anchorPos = s.positionMs; return null }
        val still = s.nowMs - anchorAt
        if (still < (if (started) windowMs else startWindowMs)) return null
        neverStarted = !started
        anchorAt = -1L   // found: the verdict is the engine's, and the next count starts from nothing
        return still
    }

    fun reset() { anchorAt = -1L; lastAt = -1L; loadedPos = -1L; started = false; neverStarted = false }
}

/**
 * DEB-5531 (a-song-that-stops-moving-picks-itself-up.md, FR-9, FR-10) — a song that has not started [afterMs] after its
 * load asks the server for the song once more, because the line figure its `waiting` report carried may make the server
 * answer with the lighter copy. Free of any player: the engine feeds one tick per monitor pass and acts when [due] says so.
 *
 * Only time the monitor ran counts, and only while the viewer wants the song to play (a pause or a tick more than
 * [gapMs] after the one before starts the count again), the same rule as [FreezeWatch]. A song asks once: [newSong]
 * (not a restart of ours) is the only thing that lets it ask again.
 */
internal class StartReask(
    private val afterMs: Long = 6_000L,
    private val gapMs: Long = 2_000L,
) {
    private var since = -1L
    private var lastAt = -1L
    private var asked = false

    /** A song or part started from scratch (not by a restart of ours). */
    fun newSong() { asked = false; newLoad() }

    /** A load began (a start, a restart, the lighter copy): its wait counts from nothing. */
    fun newLoad() { since = -1L; lastAt = -1L }

    /**
     * True once per song: [started] is false (FR-7), the viewer wants it to play, and [eligible] — a song on a direct
     * load whose `waiting` report carried an `observed` figure (FR-9, FR-10) — has held for [afterMs].
     */
    fun due(nowMs: Long, started: Boolean, wantsPlay: Boolean, eligible: Boolean): Boolean {
        val gap = lastAt >= 0 && nowMs - lastAt > gapMs
        lastAt = nowMs
        if (asked || started || !wantsPlay || !eligible) { since = -1L; return false }
        if (since < 0 || gap) { since = nowMs; return false }
        if (nowMs - since < afterMs) return false
        asked = true
        return true
    }

    companion object {
        /** FR-9 — the server's answer is loaded only when it is a lighter copy than the direct load; the same direct copy is left alone. */
        fun loadsAnswer(currentDirect: Boolean, answerDirect: Boolean): Boolean = currentDirect && !answerDirect
    }
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
 * DEB-3400 (FR-2 to FR-5) — the rules for one song or book part, free of any player. A freeze point gets one restart; the
 * restart has failed when the song stops again before it has played [recoverMs] past that point (a position below it,
 * as a loading player reports, counts). A stop further on is a new freeze point with a restart of its own.
 */
internal class FreezeRecovery(
    private val nearEndMs: Long = 5_000L,
    private val recoverMs: Long = 30_000L,
) {
    private var restartedFrom: Long? = null

    /** A song or part started from scratch (not by a restart of ours). */
    fun newSong() { restartedFrom = null }

    /** The viewer moved the playhead: the old freeze point says nothing about the new place. */
    fun moved() { restartedFrom = null }

    /** DEB-5538 (FR-7) — [neverStarted] is a load that did not start: a reload at the same place only starts the download again, so there is no restart. */
    fun onFreeze(positionMs: Long, durationMs: Long, book: Boolean, neverStarted: Boolean = false): FreezeVerdict {
        if (durationMs > 0 && positionMs >= durationMs - nearEndMs) return FreezeVerdict.ENDED
        if (neverStarted) return if (book) FreezeVerdict.SHEET else FreezeVerdict.SKIP
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
