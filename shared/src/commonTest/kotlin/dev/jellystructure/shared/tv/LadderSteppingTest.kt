package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 309 (FR-309-1/-8/-9) — the shared stall rule, the Mac's peak and mpv's restream stepping. */
class LadderSteppingTest {
    private fun stall(at: Long, ms: Long) = QoeStall(afterFirstFrameMs = at, positionMs = at, bufferedMs = 0, durationMs = ms, phase = "mid")

    @Test fun `a stall of two seconds counts a short one alone does not`() {
        assertTrue(StallRule.anyCounts(listOf(stall(60_000, 2_000))))
        assertFalse(StallRule.anyCounts(listOf(stall(60_000, 400))))
        assertFalse(StallRule.anyCounts(emptyList()))
    }

    @Test fun `two short stalls within a minute both count a minute and a half apart neither does`() {
        assertEquals(2, StallRule.counting(listOf(stall(10_000, 300), stall(55_000, 300))).size)
        assertTrue(StallRule.counting(listOf(stall(10_000, 300), stall(100_000, 300))).isEmpty())
    }

    @Test fun `the masters bandwidths are read duplicates and media playlists ignored`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=18256000,AVERAGE-BANDWIDTH=12000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            v0/main.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6256000,RESOLUTION=1280x720
            v1/main.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6256000,RESOLUTION=1280x720
            v1b/main.m3u8
        """.trimIndent()
        assertEquals(listOf(18_256_000L, 6_256_000L), masterBandwidths(master))
        assertTrue(masterBandwidths("#EXTM3U\n#EXTINF:2.0,\nseg0.ts").isEmpty())
    }

    @Test fun `the Macs peak climbs one rung at a time with enough buffered and drops when the buffer falls`() {
        val rungs = listOf(2_506_000L, 6_256_000L, 12_256_000L, 18_256_000L)
        // Not enough buffered on Jellyfin's per-rung jobs: hold.
        assertEquals(6_256_000L, nextPeakBps(rungs, 6_256_000L, 15_000, 14_000, oursEncoder = false))
        // 30 s buffered: one rung up, never two.
        assertEquals(12_256_000L, nextPeakBps(rungs, 6_256_000L, 31_000, 30_000, oursEncoder = false))
        // On our own encoder the stock 10 s is enough.
        assertEquals(12_256_000L, nextPeakBps(rungs, 6_256_000L, 11_000, 10_000, oursEncoder = true))
        // Under 20 s and falling: below the playing rung.
        assertEquals(6_256_000L, nextPeakBps(rungs, 12_256_000L, 12_000, 18_000, oursEncoder = true))
    }

    @Test fun `mpv steps down once the buffer falls under 20 s not again within 30 s`() {
        val s = RestreamStepper()
        assertNull(s.evaluate(0, 25_000, streamVideoBps = 12_000_000))
        val down = assertNotNull(s.evaluate(10_000, 18_000, streamVideoBps = 12_000_000))
        assertTrue(down.down)
        assertEquals(8_000_000L, down.capVideoBps)
        // Still falling 10 s later: within the gap, nothing; once the gap has passed, the next rung down.
        assertNull(s.evaluate(20_000, 15_000, streamVideoBps = 12_000_000))
        assertNull(s.evaluate(30_000, 12_000, streamVideoBps = 12_000_000))
        assertEquals(4_000_000L, s.evaluate(45_000, 9_000, streamVideoBps = 12_000_000)?.capVideoBps)
    }

    @Test fun `mpv climbs back one rung after two clean minutes with 30 s buffered and lifts the cap above the top rung`() {
        val s = RestreamStepper()
        s.evaluate(0, 25_000, streamVideoBps = 12_000_000)
        assertEquals(8_000_000L, s.evaluate(10_000, 18_000, streamVideoBps = 12_000_000)?.capVideoBps)
        // Clean but not two minutes yet.
        assertNull(s.evaluate(60_000, 35_000, null))
        assertEquals(12_000_000L, s.evaluate(140_000, 35_000, null)?.capVideoBps)
        // Not enough buffered: hold even after two more minutes.
        assertNull(s.evaluate(270_000, 20_000, null))
        val up = assertNotNull(s.evaluate(400_000, 35_000, null))
        assertFalse(up.down)
        assertEquals(0L, up.capVideoBps)
        // Uncapped: nothing left to climb to.
        assertNull(s.evaluate(600_000, 40_000, null))
    }

    @Test fun `with nothing below the lowest rung there is no step`() {
        val s = RestreamStepper()
        s.evaluate(0, 25_000, streamVideoBps = 1_500_000)
        assertNull(s.evaluate(10_000, 10_000, streamVideoBps = 1_500_000))
    }

    @Test fun `a variants bandwidth gives back roughly its video bitrate`() {
        assertEquals(8_000_000L, RestreamStepper.videoBpsOf(12_256_000L))
        assertNull(RestreamStepper.videoBpsOf(null))
        assertNull(RestreamStepper.videoBpsOf(0))
    }
}
