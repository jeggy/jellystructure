package dev.jellystructure.ravilo.ui.screens

import kotlinx.coroutines.delay

/**
 * DEB-3561 (DEB-2628 FR-8) — a station left on turns over to its next programme, again and again: the lineup is re-read when the
 * programme on screen ends, and then when the next one does, without a re-tune. The loop lives here, not in a `LaunchedEffect` keyed on
 * the programme, because a re-read that finds nothing new leaves that key unchanged and the effect would never run a second time.
 */
internal object RadioTurnover {
    /** With no programme to wait for (a gap, or no guide), the lineup is asked again this often. */
    const val IDLE_REFRESH_MS = 60_000L

    /** A re-read is never sooner than this after the last, so a lineup that still holds an ended programme is asked at a walk, not a run. */
    const val MIN_WAIT_MS = 5_000L

    /** The time past a programme's end when it is read again, so the server has moved on by then. */
    const val END_SLACK_MS = 2_000L

    /** How long to wait before the next re-read: until [endMs] has passed (plus slack), within [MIN_WAIT_MS]..[IDLE_REFRESH_MS]. */
    fun waitMs(endMs: Long?, nowMs: Long): Long {
        val untilEnd = if (endMs != null) endMs - nowMs + END_SLACK_MS else IDLE_REFRESH_MS
        return untilEnd.coerceIn(MIN_WAIT_MS, IDLE_REFRESH_MS)
    }

    /** Waits, re-reads, and does it again until cancelled. [endMs] is read afresh each round: it is the programme on screen then. */
    suspend fun run(
        endMs: () -> Long?,
        nowMs: () -> Long,
        refresh: suspend () -> Unit,
        wait: suspend (Long) -> Unit = { delay(it) },
    ) {
        while (true) {
            wait(waitMs(endMs(), nowMs()))
            refresh()
        }
    }
}
