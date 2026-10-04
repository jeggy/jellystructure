package dev.jellystructure.media

import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.MusicSteps
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.config.RecommendationsStep
import dev.jellystructure.config.SuggestionsStep
import dev.jellystructure.config.WholeLibrarySteps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 261 (FR-261-10/11) — the Checks card prints what the server says; these are its words for dates. */
class TitleChecksTextTest {
    // 2026-09-26T12:00Z and friends, as epoch ms.
    private val now = 1_790_424_000_000L
    private val DAY = 86_400_000L

    @Test
    fun `a last run this year is day and month — another year carries the year`() {
        assertEquals("26 Sep", TitleChecks.dayText(now, now))
        assertEquals("3 Aug", TitleChecks.dayText(now - 54 * DAY, now))
        assertEquals("26 Sep 2025", TitleChecks.dayText(now - 365 * DAY, now))
    }

    @Test
    fun `next due is due now — a date this year — or a later year alone`() {
        assertEquals("due now", TitleChecks.dueText(now, now))
        assertEquals("3 Oct", TitleChecks.dueText(now + 7 * DAY, now))
        assertEquals("2031", TitleChecks.dueText(now + 5 * 365 * DAY, now))
        // The card prints the server's whole phrase — *Verify files · 3 Aug · clean · next due 2031*.
        assertEquals("next due 2031", TitleChecks.dueLine(now + 5 * 365 * DAY, now))
        assertEquals("due now", TitleChecks.dueLine(now - DAY, now))
    }
    // ── Phase 303 — the card lists only the steps done to a title ──

    @Test
    fun `no music or whole-library or control step is on the card — the per-title steps keep their order`() {
        val pipeline = (listOf("scan_files") + MusicSteps.ALL.take(2) + listOf("pull_tmdb", "fetch_artwork") +
            MusicSteps.ALL.drop(2) + listOf(FileCheckSteps.VERIFY, FileCheckSteps.LENGTHS, FileCheckSteps.SUBTITLES,
                RecommendationsStep.STEP, SuggestionsStep.STEP, "wait", "notify"))
            .map { PipelineStep(step = it, enabled = it != MusicSteps.LYRICS) }
        assertEquals(
            listOf("scan_files", "pull_tmdb", "fetch_artwork", FileCheckSteps.VERIFY, FileCheckSteps.LENGTHS, FileCheckSteps.SUBTITLES),
            TitleChecks.cardSteps(pipeline).map { it.step },
        )
    }

    @Test
    fun `an empty pipeline gives scan_files alone`() {
        assertEquals(listOf("scan_files"), TitleChecks.cardSteps(emptyList()).map { it.step })
    }

    @Test
    fun `scan_files comes first even when the pipeline lists it later`() {
        val steps = TitleChecks.cardSteps(listOf(PipelineStep(step = "pull_tmdb"), PipelineStep(step = "scan_files", scope = "all")))
        assertEquals(listOf("scan_files", "pull_tmdb"), steps.map { it.step })
        assertEquals("all", steps.first().scope)
    }

    @Test
    fun `every music step is whole-library — a later one follows without touching TitleChecks`() {
        for (step in MusicSteps.ALL) {
            assertTrue(WholeLibrarySteps.contains(step))
            assertFalse(TitleChecks.isTitleStep(step))
        }
        assertTrue(WholeLibrarySteps.contains(RecommendationsStep.STEP))
        assertTrue(WholeLibrarySteps.contains(SuggestionsStep.STEP))
        assertFalse(WholeLibrarySteps.contains("pull_tmdb"))
        assertTrue(TitleChecks.isTitleStep("pull_tmdb"))
    }
}
