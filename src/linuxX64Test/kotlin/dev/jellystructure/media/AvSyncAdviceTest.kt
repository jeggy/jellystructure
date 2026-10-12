package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 317 acceptance 5 and 8 — the command and the words. An MKV with three audio tracks of which two
 * are in step gives two `--sync` arguments carrying mkvmerge's OWN ids; the third is named and left; a path
 * with a `'` is quoted; the output is never the input.
 */
class AvSyncAdviceTest {
    private val offset160 = AvSyncReading(AvSyncState.OFFSET, offsetMs = 160, medianFrames = 4, confidentWindows = 3)
    private val late160 = AvSyncReading(AvSyncState.OFFSET, offsetMs = -160, medianFrames = -4, confidentWindows = 3)

    private fun t(spec: String, label: String, mkvId: Int? = null, lag: Int? = null, corr: Double = 0.0) =
        AvSyncTrack(spec, label, mkvId, lag, corr)

    @Test
    fun `MKV - the two tracks in step move together — the third is named and left`() {
        val measured = t("0:a:0", "\"fao\" EAC3", mkvId = 1)
        val dub = t("0:a:1", "\"dan\" AAC", mkvId = 2, lag = 0, corr = 0.91)
        val commentary = t("0:a:2", "\"eng\" AC3 “Commentary”", mkvId = 3, lag = 37, corr = 0.12)
        val fix = AvSyncAdvice.advise(offset160, "/mnt/media/A/a.mkv", measured, listOf(dub, commentary))

        val cmd = fix.command!!
        assertTrue(cmd.contains("--sync 1:160"), cmd)
        assertTrue(cmd.contains("--sync 2:160"), cmd)
        assertFalse(cmd.contains("--sync 3:"), cmd)
        assertEquals(listOf(measured.label, dub.label), fix.shifted)
        assertEquals(listOf(commentary.label), fix.left)
        assertTrue(fix.suggestion.contains("Commentary"), fix.suggestion)
        assertTrue(fix.suggestion.contains("not in step"), fix.suggestion)
        // The shift is recorded in the file itself, so it can be recognised later (and 317b can undo it).
        assertTrue(cmd.contains("JELLYSTRUCTURE_AV_SHIFT"), cmd)
        // 311/FR-317-12 — the new file is written in the hidden folder and renamed over the original.
        assertTrue(cmd.contains("/mnt/media/A/${WorkFiles.DIR}/avshift_a.mkv"), cmd)
        assertTrue(cmd.contains("&& mv "), cmd)
    }

    @Test
    fun `the output is never the input`() {
        val fix = AvSyncAdvice.advise(offset160, "/mnt/media/A/a.mkv", t("0:a:0", "audio", mkvId = 1))
        val work = WorkFiles.pathFor("/mnt/media/A/a.mkv", WorkFiles.Kind.AV_SHIFT)
        assertTrue(work != "/mnt/media/A/a.mkv")
        assertTrue(fix.command!!.contains("-o '$work'"), fix.command!!)
    }

    @Test
    fun `a path with a quote is shell-quoted`() {
        val path = "/mnt/media/It's Here/it's.mkv"
        val cmd = AvSyncAdvice.advise(offset160, path, t("0:a:0", "audio", mkvId = 1)).command!!
        assertTrue(cmd.contains("'/mnt/media/It'\\''s Here/it'\\''s.mkv'"), cmd)
    }

    @Test
    fun `MP4 - the shifted stream comes from input 1 and the rest from input 0 — in order`() {
        val measured = t("0:a:0", "\"fao\" AAC")
        val other = t("0:a:1", "\"eng\" AAC", lag = 12, corr = 0.2)
        val fix = AvSyncAdvice.advise(offset160, "/mnt/series/S/e.mp4", measured, listOf(other))
        val cmd = fix.command!!
        assertTrue(cmd.contains("-itsoffset 0.160"), cmd)
        // 0:a:0 moves (it is the measured one); 0:a:1 is not in step, so it stays on input 0 — and both
        // are mapped, in the container's own order, so a player's track list is unchanged by the fix.
        assertTrue(cmd.contains("-map 0:v -map 1:a:0 -map 0:a:1 -map 0:s?"), cmd)
        assertTrue(cmd.contains("-c copy"), cmd)
        assertTrue(cmd.contains("-metadata JELLYSTRUCTURE_AV_SHIFT=160"), cmd)
        assertEquals(listOf(other.label), fix.left)
    }

    @Test
    fun `audio behind the picture gives a negative shift — both containers`() {
        val mkv = AvSyncAdvice.advise(late160, "/m/a.mkv", t("0:a:0", "audio", mkvId = 1)).command!!
        assertTrue(mkv.contains("--sync 1:-160"), mkv)
        val mp4 = AvSyncAdvice.advise(late160, "/m/a.mp4", t("0:a:0", "audio")).command!!
        assertTrue(mp4.contains("-itsoffset -0.160"), mp4)
        val fix = AvSyncAdvice.advise(late160, "/m/a.mkv", t("0:a:0", "audio", mkvId = 1))
        assertTrue(fix.what.contains("behind"), fix.what)
        assertTrue(fix.experience.contains("lips move before"), fix.experience)
    }

    @Test
    fun `other containers get no command — and say why`() {
        val fix = AvSyncAdvice.advise(offset160, "/m/a.avi", t("0:a:0", "audio"))
        assertNull(fix.command)
    }

    @Test
    fun `an MKV with no mkvmerge ids read gets no command rather than a guess`() {
        // Without the ids `--sync` would move whichever track mkvmerge numbers 1 — never a guess.
        assertNull(AvSyncAdvice.advise(offset160, "/m/a.mkv", t("0:a:0", "audio", mkvId = null)).command)
    }

    @Test
    fun `a drift has no command at all`() {
        val drift = AvSyncReading(AvSyncState.DRIFT, driftFromMs = 40, driftToMs = 420, driftFromS = 1200.0, driftToS = 6000.0, confidentWindows = 5)
        val fix = AvSyncAdvice.advise(drift, "/m/a.mkv", t("0:a:0", "audio", mkvId = 1))
        assertNull(fix.command)
        assertTrue(fix.what.contains("+40 ms"), fix.what)
        assertTrue(fix.what.contains("+420 ms"), fix.what)
        assertTrue(fix.suggestion.contains("Replace the file"), fix.suggestion)
    }

    @Test
    fun `in step is both a correlation and a lag`() {
        assertTrue(AvSyncAdvice.inStep(t("0:a:1", "x", lag = 1, corr = 0.6)))
        assertTrue(AvSyncAdvice.inStep(t("0:a:1", "x", lag = -1, corr = 0.95)))
        assertFalse(AvSyncAdvice.inStep(t("0:a:1", "x", lag = 2, corr = 0.95)))
        assertFalse(AvSyncAdvice.inStep(t("0:a:1", "x", lag = 0, corr = 0.59)))
        assertFalse(AvSyncAdvice.inStep(t("0:a:1", "x", lag = null, corr = 0.95)))   // every window silent
    }

    @Test
    fun `seconds are built by hand — never by a locale`() {
        assertEquals("0.160", AvSyncAdvice.secondsArg(160))
        assertEquals("-0.040", AvSyncAdvice.secondsArg(-40))
        assertEquals("1.200", AvSyncAdvice.secondsArg(1200))
    }

    // ── FR-317-4: which track is measured ───────────────────────────────────────────────────────

    private fun same(a: String?, b: String?): Boolean =
        !a.isNullOrBlank() && !b.isNullOrBlank() && a.lowercase().take(2) == b.lowercase().take(2)

    @Test
    fun `the original language track is measured — the default one among several`() {
        val tracks = listOf(
            Triple("0:a:0", "dan" as String?, false),
            Triple("0:a:1", "fao" as String?, false),
            Triple("0:a:2", "fao" as String?, true),
        )
        assertEquals("0:a:2", AvSyncAdvice.measuredTrack(tracks, "fao", ::same))
    }

    @Test
    fun `a file whose only audio is untagged is measured on it`() {
        assertEquals("0:a:0", AvSyncAdvice.measuredTrack(listOf(Triple("0:a:0", null, false)), "fao", ::same))
    }

    @Test
    fun `a dub-only file is not measured at all - a dub has no lips to follow`() {
        val tracks = listOf(Triple("0:a:0", "dan" as String?, true), Triple("0:a:1", "nor" as String?, false))
        assertNull(AvSyncAdvice.measuredTrack(tracks, "fao", ::same))
    }

    @Test
    fun `no audio at all is not measured`() {
        assertNull(AvSyncAdvice.measuredTrack(emptyList(), "eng", ::same))
    }
}
