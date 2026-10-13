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
        assertTrue(fix.suggestion.contains("could not be matched against the main audio"), fix.suggestion)
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
        // FR-317-24 — the words FR-317-11 promised and no Kotlin carried until now.
        val reason = fix.noCommandReason!!
        assertEquals(AvSyncAdvice.NO_COMMAND_CONTAINER, reason)
        assertTrue(reason.contains("Remux it to MKV first"), reason)
    }

    @Test
    fun `an MKV with no mkvmerge ids read gets no command rather than a guess`() {
        // Without the ids `--sync` would move whichever track mkvmerge numbers 1 — never a guess.
        val fix = AvSyncAdvice.advise(offset160, "/m/a.mkv", t("0:a:0", "audio", mkvId = null))
        assertNull(fix.command)
        val reason = fix.noCommandReason!!
        assertEquals(AvSyncAdvice.NO_COMMAND_NO_IDS, reason)
        assertTrue(reason.contains("Re-run the check with ↻"), reason)
    }

    @Test
    fun `an MKV with too few ids gets no command at all — never a shift that moves fewer tracks than the prose`() {
        // FR-317-22: the measured track and a dub in step with it both move, but the dub's id was past the
        // cut. Two --sync arguments under prose saying three tracks move is the one thing the contract
        // forbids, so there is no command — and the page says why.
        val measured = t("0:a:0", "\"fao\" EAC3", mkvId = 1)
        val dub = t("0:a:1", "\"dan\" AAC", mkvId = null, lag = 0, corr = 0.91)
        val second = t("0:a:2", "\"nor\" AAC", mkvId = 4, lag = 0, corr = 0.88)
        val fix = AvSyncAdvice.advise(offset160, "/m/a.mkv", measured, listOf(dub, second))
        assertEquals(3, fix.shifted.size)
        assertNull(fix.command)
        assertEquals(AvSyncAdvice.NO_COMMAND_NO_IDS, fix.noCommandReason)

        // Every id present: three --sync arguments, unchanged from before this rule.
        val whole = AvSyncAdvice.advise(offset160, "/m/a.mkv", measured, listOf(dub.copy(mkvId = 2), second))
        val cmd = whole.command
        assertTrue(cmd!!.contains("--sync 1:160"), cmd)
        assertTrue(cmd.contains("--sync 2:160"), cmd)
        assertTrue(cmd.contains("--sync 4:160"), cmd)
        assertNull(whole.noCommandReason)
    }

    @Test
    fun `an mp4 needs no mkvmerge id and still gets its command`() {
        // The mp4 branch maps by ffmpeg specifier, so FR-317-22's rule must not reach it.
        val fix = AvSyncAdvice.advise(offset160, "/m/a.mp4", t("0:a:0", "audio"), listOf(t("0:a:1", "dub", lag = 0, corr = 0.9)))
        val cmd = fix.command
        assertTrue(cmd!!.contains("-itsoffset 0.160"), cmd)
        assertNull(fix.noCommandReason)
    }

    @Test
    fun `a drift has no command at all`() {
        val drift = AvSyncReading(AvSyncState.DRIFT, driftFromMs = 40, driftToMs = 420, driftFromS = 1200.0, driftToS = 6000.0, confidentWindows = 5)
        val fix = AvSyncAdvice.advise(drift, "/m/a.mkv", t("0:a:0", "audio", mkvId = 1))
        assertNull(fix.command)
        // FR-317-24 — and no sentence either: the suggestion already explains the absence in full, and the
        // same thing said twice is worse than said once.
        assertNull(fix.noCommandReason)
        assertTrue(fix.what.contains("+40 ms"), fix.what)
        assertTrue(fix.what.contains("+420 ms"), fix.what)
        assertTrue(fix.suggestion.contains("Replace the file"), fix.suggestion)
    }

    // ── FR-317-25: a left track names the test it failed and quotes no other number ──────────────

    @Test
    fun `a track left on correlation names the correlation against its bar and quotes no lag`() {
        // The live line this replaces read "+0 ms of it, correlation 0.546" — a lag that reads as perfectly
        // in step, in front of a sentence excluding the track.
        val fix = AvSyncAdvice.advise(
            offset160, "/m/a.mkv", t("0:a:0", "main", mkvId = 1),
            listOf(t("0:a:1", "Untagged EAC3 (0:a:1)", mkvId = 2, lag = 0, corr = 0.546)),
        )
        assertTrue(fix.suggestion.contains("correlation 0.55, below 0.6"), fix.suggestion)
        assertTrue(fix.suggestion.contains("different audio, not a delayed copy of it"), fix.suggestion)
        assertFalse(fix.suggestion.contains("+0 ms"), fix.suggestion)
        assertFalse(fix.suggestion.contains("0.546"), fix.suggestion)
    }

    @Test
    fun `a track left on its own lag names the lag and quotes no correlation`() {
        val fix = AvSyncAdvice.advise(
            offset160, "/m/a.mkv", t("0:a:0", "main", mkvId = 1),
            listOf(t("0:a:1", "dub", mkvId = 2, lag = 3, corr = 0.80)),
        )
        assertTrue(fix.suggestion.contains("is itself +120 ms out of step"), fix.suggestion)
        assertTrue(fix.suggestion.contains("one shift cannot put both right"), fix.suggestion)
        assertFalse(fix.suggestion.contains("correlation"), fix.suggestion)
        assertFalse(fix.suggestion.contains("0.8"), fix.suggestion)
    }

    @Test
    fun `a track with nothing in common says only that it could not be compared`() {
        val fix = AvSyncAdvice.advise(
            offset160, "/m/a.mkv", t("0:a:0", "main", mkvId = 1),
            listOf(t("0:a:1", "silent", mkvId = 2, lag = null, corr = 0.0)),
        )
        // The clause joins first, so its own first character is the one the sentence uppercases.
        assertTrue(fix.suggestion.contains("Silent could not be compared with the main audio"), fix.suggestion)
        assertFalse(fix.suggestion.contains("correlation"), fix.suggestion)
        assertFalse(fix.suggestion.contains(" ms of it"), fix.suggestion)
    }

    @Test
    fun `a correlation is two decimals and never a locale's comma`() {
        assertEquals("0.55", AvSyncAdvice.twoDecimals(0.546))
        assertEquals("0.60", AvSyncAdvice.twoDecimals(0.6))
        assertEquals("0.00", AvSyncAdvice.twoDecimals(0.0))
        assertEquals("1.00", AvSyncAdvice.twoDecimals(0.999))
    }

    @Test
    fun `the containers a shift fits are one list — the probe and the command cannot drift apart`() {
        assertTrue(AvSyncAdvice.isMatroska("/m/a.mkv"))
        assertTrue(AvSyncAdvice.isMatroska("/m/a.MKA"))
        assertFalse(AvSyncAdvice.isMatroska("/m/a.mp4"))
        assertEquals(AvSyncAdvice.MATROSKA_EXTENSIONS, setOf("mkv", "mka", "mks"))
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
