package dev.jellystructure.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2205 (FR-8) — the encoder's own record of a play: what it keeps, and how it is read from ffmpeg and /proc. */
class EncoderSummaryTest {
    @Test
    fun `speed min and median longest wait waits over a second and the rungs are kept`() {
        val s = EncoderStats(listOf("2160p@20000k", "1080p@8000k"))
        s.newJob()
        listOf(1.9, 0.8, 1.4, 2.2).forEach { s.speed(it) }
        listOf(40L, 1_000L, 1_001L, 3_500L).forEach { s.waited(it) }
        s.segmentsMade(120)
        val sum = s.summary()
        assertEquals(120, sum.segments)
        assertEquals(0.8, sum.speedMin)
        assertEquals(1.65, sum.speedMedian)
        assertEquals(3_500L, sum.longestWaitMs)
        assertEquals(2, sum.waitsOver1s, "1000 ms is not over a second")
        assertEquals(listOf("2160p@20000k", "1080p@8000k"), sum.rungs)
        assertEquals(false, sum.paused)
    }

    @Test
    fun `the input rate counts only the intervals the job was running`() {
        val s = EncoderStats(emptyList())
        s.newJob()
        s.inputSample(0, 0)
        s.inputSample(10_000_000, 1_000)          // 10 MB in 1 s
        s.inputSample(10_000_000, 61_000)         // paused for a minute: neither bytes nor time
        s.inputSample(30_000_000, 62_000)         // 20 MB in 1 s
        assertEquals(15.0, s.summary().inputMBps)
        s.newJob()
        s.inputSample(500, 70_000)                // a new job has no predecessor to difference with
        assertEquals(15.0, s.summary().inputMBps)
    }

    @Test
    fun `a stream with no samples reports none rather than zero`() {
        val sum = EncoderStats(listOf("1080p@8000k")).summary()
        assertNull(sum.speedMin); assertNull(sum.speedMedian); assertNull(sum.inputMBps)
        assertEquals(0L, sum.longestWaitMs)
    }

    @Test
    fun `ffmpeg progress speed and proc io rchar are read`() {
        assertEquals(1.02, parseProgressSpeed("speed=1.02x\n"))
        assertEquals(12.0, parseProgressSpeed("speed=  12x"))
        assertNull(parseProgressSpeed("speed=N/A"))
        assertNull(parseProgressSpeed("progress=continue"))
        assertEquals(4096L, parseRchar("rchar: 4096\nwchar: 1\nsyscr: 3\n"))
        assertNull(parseRchar("wchar: 1\n"))
    }

    @Test
    fun `the json and the log line carry the figures and two streams of one play merge`() {
        val a = EncoderSummary(segments = 100, speedMin = 1.1, speedMedian = 1.5, longestWaitMs = 800, waitsOver1s = 0, inputMBps = 40.0, rungs = listOf("2160p@20000k"), paused = true)
        val b = EncoderSummary(segments = 10, speedMin = 0.9, speedMedian = 1.0, longestWaitMs = 2_500, waitsOver1s = 1, inputMBps = 5.0, rungs = listOf("1080p@8000k"))
        val m = a + b
        assertEquals(110, m.segments)
        assertEquals(0.9, m.speedMin)
        assertEquals(1.5, m.speedMedian, "the stream that made most of the segments speaks for the median")
        assertEquals(2_500L, m.longestWaitMs)
        assertEquals(1, m.waitsOver1s)
        assertEquals(listOf("2160p@20000k", "1080p@8000k"), m.rungs)
        assertTrue(m.paused)
        assertEquals(m, EncoderSummary.fromJson(m.toJson()))
        assertTrue(m.toJson().contains("\"longest_wait_ms\":2500"))
        val line = a.logLine("abc", "playback stopped")
        assertTrue("segments=100" in line && "longest-wait=800ms" in line && "waits>1s=0" in line && "paused=true" in line, line)
    }
}
