package dev.jellystructure.media

import dev.jellystructure.model.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 255 acceptance 1–2 — every row of the spec's "What the library holds" table, ends as measured. */
class TrackCoverageTest {
    private fun v(i: Int, end: Long?, codec: String = "hevc", pic: Boolean = false) =
        CoverageStream(i, TrackKind.VIDEO, codec, null, null, default = false, attachedPic = pic, measuredEndMs = end)
    private fun a(i: Int, end: Long?, lang: String?, codec: String = "aac", def: Boolean = false) =
        CoverageStream(i, TrackKind.AUDIO, codec, lang, null, default = def, measuredEndMs = end)

    @Test
    fun `A - a short track with a complete one in the same language`() {
        val f = TrackCoverage.classify(1_353_700, listOf(v(0, 1_353_600), a(1, 281_250, "eng", "opus"), a(2, 1_349_820, "eng")))
        assertEquals(listOf("A"), f.map { it.kind })
        assertEquals(1, f[0].streamIndex)
        assertEquals(2, f[0].alternativeStreamIndex)
        assertTrue(f[0].firstOfKind)
    }

    @Test
    fun `B - a short track that is the only one in its language`() {
        val e28 = TrackCoverage.classify(429_000, listOf(v(0, 429_000), a(1, 429_000, "fao", def = true), a(2, 19_990, "dan")))
        val e32 = TrackCoverage.classify(429_000, listOf(v(0, 429_000), a(1, 429_000, "fao", def = true), a(2, 40_000, "dan")))
        assertEquals(listOf("B"), e28.map { it.kind }); assertEquals(2, e28[0].streamIndex); assertFalse(e28[0].firstOfKind)
        assertEquals(listOf("B"), e32.map { it.kind })
    }

    @Test
    fun `C - the only audio track is short`() {
        val f = TrackCoverage.classify(5_010_000, listOf(v(0, 5_009_500), a(1, 999_080, "eng")))
        assertEquals(listOf("C"), f.map { it.kind })
    }

    @Test
    fun `D - the video is short`() {
        val f = TrackCoverage.classify(1_500_000, listOf(v(0, 723_000), a(1, 1_499_000, "eng")))
        assertEquals(listOf("D"), f.map { it.kind })
    }

    @Test
    fun `E - the header claims more than the file holds and the content is complete`() {
        val ruse = TrackCoverage.classify(3_564_000, listOf(v(0, 2_873_000), a(1, 2_873_000, "eng")))
        assertEquals(listOf("E"), ruse.map { it.kind })
        assertEquals(2_873_000, ruse[0].endsMs)
        val blended = TrackCoverage.classify(1_677_000, listOf(v(0, 1_294_000), a(1, 1_294_000, "eng")))
        assertEquals(listOf("E"), blended.map { it.kind })
    }

    @Test
    fun `measured ends override the tags and cover art is never a track`() {
        // Beetlemania: tags said 3494 s of 7094 s; packets run to 7087 s.
        assertTrue(TrackCoverage.classify(7_094_000, listOf(v(0, 7_087_000, hintFor = 3_494_000), a(1, 7_087_000, "eng"))).isEmpty())
        // Playroom 3 / Blended Household: mjpeg cover images with 0 s tags.
        assertTrue(TrackCoverage.classify(1_300_000, listOf(v(0, 1_299_000), a(1, 1_299_500, "eng"), v(4, null, "mjpeg", pic = true), v(12, 0L, "mjpeg"))).isEmpty())
        // a few seconds of silent credit tail is never a finding
        assertTrue(TrackCoverage.classify(1_300_000, listOf(v(0, 1_299_000), a(1, 1_280_000, "eng"))).isEmpty())
        // an unmeasured stream is skipped, not judged
        assertTrue(TrackCoverage.classify(1_300_000, listOf(v(0, 1_299_000), a(1, null, "eng"))).isEmpty())
    }

    private fun v(i: Int, end: Long?, hintFor: Long) = CoverageStream(i, TrackKind.VIDEO, "hevc", null, null, false, hintMs = hintFor, measuredEndMs = end)

    @Test
    fun `duration hints parse from a matroska tag or plain seconds`() {
        assertEquals(281_265L, TrackCoverage.parseDurationHint("00:04:41.265000000", null))
        assertEquals(1_353_728L, TrackCoverage.parseDurationHint("00:22:33.728", null))
        assertEquals(2_612_480L, TrackCoverage.parseDurationHint(null, "2612.480000"))
        assertNull(TrackCoverage.parseDurationHint("N/A", "N/A"))
        assertEquals("4:41", TrackCoverage.mmss(281_250)); assertEquals("1:23:28", TrackCoverage.mmss(5_008_000))
    }

    @Test
    fun `advice quotes the path and names the stream index and never writes over the input`() {
        val path = "/mnt/series/Star's Haul/S02E14.mkv"
        val a = TrackCoverageAdvice.advise(CoverageFinding("A", 7, "AUDIO", "opus", "eng", null, "stereo", false, true, 281_250, 1_353_600, 1_353_700, alternativeStreamIndex = 9), path, 22)
        val cmd = assertNotNull(a.command)
        assertTrue(cmd.contains("-map -0:7"), cmd)                                  // the probe's index, not a list position
        assertTrue(cmd.contains("'/mnt/series/Star'\\''s Haul/S02E14.mkv'"), cmd)   // shell-quoted
        assertTrue(cmd.endsWith(".fixed.mkv'"), cmd)
        assertFalse(cmd.substringAfterLast(" ").trim('\'') == path)
        assertTrue(a.suggestion.contains("stream #9"))
        assertTrue(a.what.contains("4:41") && a.what.contains("22:33"))
        val c = TrackCoverageAdvice.advise(CoverageFinding("C", 1, "AUDIO", "aac", "eng", null, null, true, true, 999_080, 5_009_500, 5_010_000), path, 84)
        assertNull(c.command); assertTrue(c.suggestion.contains("Re-download"))
        val d = TrackCoverageAdvice.advise(CoverageFinding("D", 0, "VIDEO", "hevc", null, null, null, false, true, 723_000, 1_499_000, 1_500_000), path, null)
        assertNull(d.command); assertTrue(d.experience.contains("Black picture from 12:03"))
    }

    @Test
    fun `kind E follows the TMDB runtime - remux when complete or re-download or both when unknown`() {
        val complete = TrackCoverageAdvice.advise(CoverageFinding("E", null, null, null, null, null, null, false, false, 2_873_000, 2_873_000, 3_564_000), "/x/The Ruse S01E07.mkv", 45)
        assertTrue(complete.suggestion.contains("copy remux")); assertTrue(complete.command!!.startsWith("mkvmerge -o '/x/The Ruse S01E07.mkv.fixed.mkv'"))
        val short = TrackCoverageAdvice.advise(CoverageFinding("E", null, null, null, null, null, null, false, false, 1_870_000, 1_870_000, 3_564_000), "/x/e.mkv", 45)
        assertNull(short.command); assertTrue(short.suggestion.contains("incomplete"))
        val unknown = TrackCoverageAdvice.advise(CoverageFinding("E", null, null, null, null, null, null, false, false, 1_870_000, 1_870_000, 3_564_000), "/x/e.mp4", null)
        assertTrue(unknown.suggestion.contains("one of two things")); assertTrue(unknown.command!!.startsWith("ffmpeg -i '/x/e.mp4' -map 0 -c copy"))
        assertTrue(TrackCoverageAdvice.closeEnough(2_873_000, 45 * 60_000L)); assertFalse(TrackCoverageAdvice.closeEnough(1_870_000, 45 * 60_000L))
    }
}
