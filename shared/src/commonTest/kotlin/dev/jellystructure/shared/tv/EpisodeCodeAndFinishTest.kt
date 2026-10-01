package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R346 (FR-R346-5) and R347 (FR-R347-1) — the episode code's one spelling, and the one "finished" rule. */
class EpisodeCodeAndFinishTest {
    @Test fun `a lone episode reads S01E05`() = assertEquals("S01E05", episodeCode(1, 5))
    @Test fun `a range reads S01E01–E03`() = assertEquals("S01E01–E03", episodeCode(1, 1, 3))
    @Test fun `a range end that is not above the start is a lone episode`() {
        assertEquals("S02E07", episodeCode(2, 7, 7)); assertEquals("S02E07", episodeCode(2, 7, null))
    }
    @Test fun `big numbers are never cut`() = assertEquals("S12E120", episodeCode(12, 120))

    private val elevenMin = 11 * 60_000L

    @Test fun `an episode left at its credits is finished`() {
        // credits at 9:30 of 11:00 — 86 %, below the 90 % rule
        assertTrue(playbackFinished(9 * 60_000L + 30_000L, elevenMin, 9 * 60_000L + 30_000L))
        assertFalse(playbackFinished(9 * 60_000L + 29_000L, elevenMin, 9 * 60_000L + 30_000L))
    }

    @Test fun `no marker keeps the 90 percent rule`() {
        assertFalse(playbackFinished((elevenMin * 86) / 100, elevenMin, null))
        assertTrue(playbackFinished((elevenMin * 90) / 100, elevenMin, null))
    }

    @Test fun `an untrusted marker is ignored`() {
        assertFalse(playbackFinished(60_000L, elevenMin, 30_000L), "a marker in the first half belongs to another stream")
        assertFalse(playbackFinished(elevenMin / 2, elevenMin, elevenMin + 1), "a marker past the end is not trusted")
        assertNull(trustedCreditsStartMs(0, elevenMin))
        assertEquals(600_000L, trustedCreditsStartMs(600_000L, elevenMin))
    }

    @Test fun `no duration is never finished`() = assertFalse(playbackFinished(5_000L, 0L, 1_000L))
}
