package dev.jellystructure.ravilo.ui.seams

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R293 acceptance 4 — the backoff sequence for a socket dying at 60 s, and its one reset. */
class ReconnectBackoffTest {
    private fun noJitter() = ReconnectBackoff(jitter = 0.0)

    @Test
    fun `a socket that dies every minute doubles to the 60 s cap`() {
        val b = noJitter()
        val seq = (1..8).map { b.next(heldOpenMs = 60_000L) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), seq)
    }

    @Test
    fun `only a five-minute hold resets it`() {
        val b = noJitter()
        repeat(5) { b.next(60_000L) }
        assertEquals(32_000L, b.next(4 * 60_000L + 59_000L))   // 4:59 is not healthy: still climbing
        assertEquals(1_000L, b.next(5 * 60_000L))              // 5:00 is: back to the base
        assertEquals(1_000L, b.next(0L))                        // and the next failure starts over
    }

    @Test
    fun `jitter stays within twenty percent`() {
        val b = ReconnectBackoff(jitter = 0.2, random = Random(7))
        repeat(20) {
            val base = b.currentMs   // the un-jittered delay next() is about to return
            val d = b.next(0L)
            assertTrue(d in (base * 0.8).toLong()..(base * 1.2).toLong(), "delay $d for base $base")
        }
    }
}
