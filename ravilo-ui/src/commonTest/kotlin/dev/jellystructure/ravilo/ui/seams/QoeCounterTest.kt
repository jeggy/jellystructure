package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R381 (FR-R381-6, tests 1–2) — the counter as a pure state machine, driven by event sequences. */
class QoeCounterTest {
    /** One item's normal life on a (possibly reused) engine: load → start wait → first frame → playing. */
    private fun QoeCounter.start(key: String, at: Long): QoeCounter {
        beginItem(key); load()
        buffering(at, 0, 0, null); ready(at + 900); firstFrame(at + 950)
        return this
    }

    @Test
    fun `one item with no stall reports nothing`() {
        val c = QoeCounter().start("ep1", 0)
        assertEquals(0, c.rebufferCount)
        assertEquals(mapOf("start" to 1), c.waitCounts())
    }

    @Test
    fun `a binge of three items on one engine reports zero each, and the next item's start is not a stall`() {
        val c = QoeCounter()
        for ((i, ep) in listOf("ep1", "ep2", "ep3").withIndex()) {
            c.start(ep, i * 1_000_000L)
            assertEquals(0, c.rebufferCount, "item $ep")
            assertTrue(c.stallEvents().isEmpty())
        }
        assertEquals(0, c.sessionRebufferCount)
    }

    @Test
    fun `a seek is not a stall`() {
        val c = QoeCounter().start("ep1", 0)
        c.seek(); c.buffering(60_000, 900_000, 0, null); c.ready(61_500)
        assertEquals(0, c.rebufferCount)
        assertEquals(1, c.waitCounts()["seek"])
    }

    @Test
    fun `a real mid-play stall counts once with its phase and position`() {
        val c = QoeCounter().start("ep1", 0)
        c.buffering(120_950, 120_000, 300, 8_000_000L); c.ready(122_450)
        assertEquals(1, c.rebufferCount)
        assertEquals(1_500L, c.rebufferMs)
        val s = c.stallEvents().single()
        assertEquals("mid", s.phase)
        assertEquals(120_000L, s.positionMs)
        assertEquals(300L, s.bufferedMs)
        assertEquals(120_000L, s.afterFirstFrameMs)
        assertEquals(8_000_000L, s.variantBps)
    }

    @Test
    fun `a stall in the first ten seconds is a start-phase stall`() {
        val c = QoeCounter().start("ep1", 0)
        c.buffering(4_950, 4_000, 0, null); c.ready(5_400)
        assertEquals("start", c.stallEvents().single().phase)
    }

    @Test
    fun `an engine rebuild, a recovery, a track switch and a variant switch are each named and none is a stall`() {
        val c = QoeCounter().start("ep1", 0)
        c.engineRebuilt(); c.buffering(30_000, 30_000, 0, null); c.ready(31_000); c.firstFrame(31_050)
        c.surfaceRecovery(); c.buffering(40_000, 39_000, 0, null); c.ready(40_500)
        c.trackSwitch(); c.buffering(50_000, 49_000, 0, null); c.ready(50_400)
        c.variantSwitched(60_000); c.buffering(61_000, 60_000, 0, null); c.ready(61_300)
        assertEquals(0, c.rebufferCount)
        assertEquals(mapOf("start" to 1, "rebuild" to 1, "recovery" to 1, "track_switch" to 1, "variant_switch" to 1), c.waitCounts())
    }

    @Test
    fun `a stall in item two of a binge does not show in item three`() {
        val c = QoeCounter()
        c.start("ep1", 0)
        c.start("ep2", 1_000_000)
        c.buffering(1_200_000, 199_000, 0, null); c.ready(1_200_600)
        assertEquals(1, c.rebufferCount)
        c.start("ep3", 2_000_000)
        assertEquals(0, c.rebufferCount)
        assertTrue(c.stallEvents().isEmpty())
        assertEquals(1, c.sessionRebufferCount)
        assertEquals(600L, c.sessionRebufferMs)
    }

    @Test
    fun `a restream of the same item keeps its stall and its own start is not a stall`() {
        val c = QoeCounter().start("ep1", 0)
        c.buffering(100_000, 99_000, 0, null); c.ready(100_800)
        assertEquals(1, c.rebufferCount)
        // A subtitle switch restreams the same item: a new load, the same key.
        assertEquals(false, c.beginItem("ep1"))
        c.load(); c.buffering(200_000, 99_500, 0, null); c.ready(203_000); c.firstFrame(203_050)
        assertEquals(1, c.rebufferCount)
        assertEquals(2, c.waitCounts()["start"])
    }

    @Test
    fun `a pause while waiting drops the wait`() {
        val c = QoeCounter().start("ep1", 0)
        c.buffering(50_000, 49_000, 0, null); c.interrupted(); c.ready(400_000)
        assertEquals(0, c.rebufferCount)
    }

    @Test
    fun `at most twenty stalls are kept, the newest`() {
        val c = QoeCounter().start("ep1", 0)
        repeat(25) { i -> val t = 100_000L + i * 10_000; c.buffering(t, t, 0, null); c.ready(t + 100) }
        assertEquals(25, c.rebufferCount)
        assertEquals(20, c.stallEvents().size)
        assertEquals(150_000L, c.stallEvents().first().positionMs)
    }

    @Test
    fun `the item's time to its first frame is its first load's (309)`() {
        val c = QoeCounter()
        c.beginItem("film"); c.load(10_000)
        kotlin.test.assertNull(c.firstFrameMs())
        c.firstFrame(11_200)
        assertEquals(1_200L, c.firstFrameMs())
        c.load(50_000); c.firstFrame(50_400)   // a restream of the same item keeps the first one
        assertEquals(1_200L, c.firstFrameMs())
        c.beginItem("next"); c.load(); c.firstFrame(60_000)   // untimed load: nothing reported
        kotlin.test.assertNull(c.firstFrameMs())
    }
}
