package dev.jellystructure.ravilo.ui.remote

import kotlin.math.abs

/** DEB-2185 (FR-11.5) — how long a viewer's own request is held after release before the thumb returns. */
const val VOLUME_HOLD_MS = 3_000L

/** DEB-2185 (FR-11.5) — how long *{place} kept {n} %* stays under the slider after a request was not taken. */
const val VOLUME_KEPT_LINE_MS = 3_000L

/** DEB-2185 (FR-11.2) — `set_volume` goes at most this often during a drag (and once more on release). */
const val VOLUME_SEND_EVERY_MS = 200L

/** DEB-2185 (FR-11.3/4) — a report within this of the requested level is the request taken. */
const val VOLUME_MATCH_TOLERANCE = 1

/**
 * DEB-2185 (FR-11) — what the volume slider draws.
 *
 * [level] is the thumb: null is *—* (no level known, not draggable, never 0). [inFlight] says the thumb is the
 * viewer's own request, held while the command is out. [kept] is the level the place actually has, for the line
 * *{place} kept {n} %* after a request was not taken, and null while nothing is marked.
 */
data class VolumeShown(val level: Int?, val inFlight: Boolean = false, val kept: Int? = null)

/**
 * DEB-2185 (FR-11, FR-12) — the one rule for the thumb, pure so a report sequence can be replayed against it.
 *
 * - No level reported yet ([reported] null) and nothing of our own: *—*, never 0 (FR-11.1).
 * - [dragging] wins over every report while the finger is down (FR-11.2).
 * - A [request] sent at [sentAtMs] is held until a report matches it ± 1 (FR-11.3/4), or until [VOLUME_HOLD_MS]
 *   has passed with no match: then the thumb is the latest reported level and [VolumeShown.kept] names it for
 *   [VOLUME_KEPT_LINE_MS] (FR-11.5).
 * - With no request in flight the thumb is whatever the place last reported (FR-12.1): a TV's own remote, Google
 *   Home, another app — live.
 *
 * Only this app's own request is ever held, and that is bounded at 3 s (FR-11.6): a command in flight, not a derived
 * state. The holder ([VolumeHold]) forgets the request once it matched, so a later change from elsewhere is drawn
 * live rather than compared against a request that already landed.
 */
fun volumeShown(reported: Int?, request: Int?, sentAtMs: Long?, nowMs: Long, dragging: Int? = null): VolumeShown {
    if (dragging != null) return VolumeShown(dragging.coerceIn(0, 100))
    if (request == null || sentAtMs == null) return VolumeShown(reported)
    if (reported != null && volumeMatches(reported, request)) return VolumeShown(reported)
    val elapsed = nowMs - sentAtMs
    return when {
        elapsed < VOLUME_HOLD_MS -> VolumeShown(request.coerceIn(0, 100), inFlight = true)
        elapsed < VOLUME_HOLD_MS + VOLUME_KEPT_LINE_MS -> VolumeShown(reported, kept = reported)
        else -> VolumeShown(reported)
    }
}

/** FR-11.4 — the place took the request: its report is within ± 1 of it. */
fun volumeMatches(reported: Int, request: Int): Boolean = abs(reported - request) <= VOLUME_MATCH_TOLERANCE

/**
 * DEB-2185 (FR-11) — one slider's own state: the drag, the send gate and the request in flight. Plain Kotlin, no
 * clock of its own (every call takes `nowMs`), so a test replays a sequence against it. One instance per slider —
 * a master and each room hold their own request (R371's amendment of 2026-10-23).
 */
class VolumeHold {
    /** The level under the finger, null when not dragging. */
    var dragging: Int? = null
        private set
    /** The viewer's own request after release, forgotten once a report matched it. */
    var request: Int? = null
        private set
    var sentAtMs: Long? = null
        private set
    private var lastSentAtMs: Long? = null

    /**
     * The finger moved to [level]. Returns the level to send now, or null when the last send was under
     * [VOLUME_SEND_EVERY_MS] ago (FR-11.2: at most once per 200 ms during a drag).
     */
    fun onDrag(level: Int, nowMs: Long): Int? {
        val l = level.coerceIn(0, 100)
        dragging = l
        val last = lastSentAtMs
        if (last != null && nowMs - last < VOLUME_SEND_EVERY_MS) return null
        lastSentAtMs = nowMs
        return l
    }

    /** The finger lifted at [level]: always sent once more (FR-11.2), and held from now (FR-11.3). Returns the level to send. */
    fun onRelease(level: Int, nowMs: Long): Int {
        val l = level.coerceIn(0, 100)
        dragging = null
        request = l
        sentAtMs = nowMs
        lastSentAtMs = nowMs
        return l
    }

    /** A report reached this app: a match ± 1 ends the in-flight state (FR-11.4). Returns true when it did. */
    fun onReport(reported: Int?, nowMs: Long): Boolean {
        val r = request ?: return false
        val sent = sentAtMs
        // Past the hold and its line, the request is history whatever arrives (FR-11.5): nothing left to settle.
        if (sent != null && nowMs - sent >= VOLUME_HOLD_MS + VOLUME_KEPT_LINE_MS) { clear(); return false }
        if (reported != null && volumeMatches(reported, r)) { clear(); return true }
        return false
    }

    /** What the slider draws now, by [volumeShown]. */
    fun shown(reported: Int?, nowMs: Long): VolumeShown = volumeShown(reported, request, sentAtMs, nowMs, dragging)

    /** The hold still needs the clock to move: a request is out, or its *kept* line is up. */
    fun ticking(nowMs: Long): Boolean {
        val sent = sentAtMs ?: return false
        return request != null && nowMs - sent < VOLUME_HOLD_MS + VOLUME_KEPT_LINE_MS
    }

    private fun clear() { request = null; sentAtMs = null }
}
