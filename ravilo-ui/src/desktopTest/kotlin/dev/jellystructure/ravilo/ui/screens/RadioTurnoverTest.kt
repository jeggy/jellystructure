package dev.jellystructure.ravilo.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-3561 (DEB-2628 FR-8) — a station left on across a programme boundary turns over, on an injected clock. */
class RadioTurnoverTest {
    private val minute = 60_000L
    private val t0 = 1_800_000_000_000L

    @Test fun `the wait is until the programme ends, and never over a minute or under five seconds`() {
        assertEquals(30_000L + RadioTurnover.END_SLACK_MS, RadioTurnover.waitMs(t0 + 30_000L, t0))
        assertEquals(RadioTurnover.IDLE_REFRESH_MS, RadioTurnover.waitMs(t0 + 50 * minute, t0))
        assertEquals(RadioTurnover.IDLE_REFRESH_MS, RadioTurnover.waitMs(null, t0))
        assertEquals(RadioTurnover.MIN_WAIT_MS, RadioTurnover.waitMs(t0 - 10 * minute, t0))
    }

    /** Runs the loop on a clock that [wait] moves, for [rounds] re-reads. */
    private fun drive(rounds: Int, startEnd: Long, lineup: (Long) -> Long?): Pair<List<Long>, Long> {
        var now = t0
        var end: Long? = startEnd
        val readAt = mutableListOf<Long>()
        try {
            runBlocking {
                RadioTurnover.run(
                    endMs = { end }, nowMs = { now },
                    refresh = { readAt += now; end = lineup(now); if (readAt.size == rounds) throw CancellationException("done") },
                    wait = { now += it },
                )
            }
        } catch (_: CancellationException) {}
        return readAt to (end ?: 0L)
    }

    @Test fun `a station left across two boundaries shows each new programme within a minute of its start`() {
        // Programmes end at +5 and +65 minutes; the lineup serves whichever is on at the time it is read.
        val ends = listOf(t0 + 5 * minute, t0 + 65 * minute, t0 + 125 * minute, t0 + 185 * minute)
        fun onAt(now: Long) = ends.first { it > now }
        val shown = mutableListOf<Pair<Long, Long>>()   // (read time, end of the programme served)
        var now = t0
        var end: Long? = onAt(now)
        var reads = 0
        try {
            runBlocking {
                RadioTurnover.run(
                    endMs = { end }, nowMs = { now },
                    refresh = { end = onAt(now); shown += now to end!!; if (++reads == 130) throw CancellationException("done") },
                    wait = { now += it },
                )
            }
        } catch (_: CancellationException) {}
        // After the first end (+5 min) the next read is a minute at most later, and serves the second programme …
        val first = shown.first { it.second == ends[1] }
        assertTrue(first.first - ends[0] in 0..minute, "turned over ${first.first - ends[0]} ms after the end")
        // … and the loop kept going: the third is reached too, which a once-only effect never did.
        val second = shown.first { it.second == ends[2] }
        assertTrue(second.first - ends[1] in 0..minute, "turned over ${second.first - ends[1]} ms after the end")
    }

    @Test fun `a lineup that still holds the ended programme is read again, not given up on`() {
        val (readAt, _) = drive(rounds = 6, startEnd = t0 + minute) { t0 + minute }   // the server lags: the same ended programme
        assertEquals(6, readAt.size)
        assertTrue(readAt.zipWithNext().all { (a, b) -> b - a >= RadioTurnover.MIN_WAIT_MS }, "never faster than the floor")
    }
}
