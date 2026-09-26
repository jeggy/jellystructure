package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
