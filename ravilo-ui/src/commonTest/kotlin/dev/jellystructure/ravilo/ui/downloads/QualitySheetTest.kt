package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.DownloadOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-4070 (DEB-3995 FR-3, FR-5) — the sheet's options, its no-room rule and its preselection. */
class QualitySheetTest {
    private val gb = 1_000_000_000L
    private fun orig(b: Long) = DownloadOption("original", 2160, b, exact = true)
    private fun high(b: Long) = DownloadOption("high", 1080, b)
    private fun medium(b: Long) = DownloadOption("medium", 720, b)

    @Test fun `original comes first with its exact size and the copies follow`() {
        val m = buildQualitySheet(listOf(medium(3 * gb), orig(18 * gb), high(7 * gb)), freeBytes = 100 * gb, lastPick = null)
        assertEquals(listOf("original", "high", "medium"), m.rows.map { it.option.quality })
        assertTrue(m.rows.first().option.exact)
        assertTrue(m.rows.drop(1).none { it.option.exact })
    }

    @Test fun `wide frame copy retains the encoder rung in the sheet`() {
        val m = buildQualitySheet(listOf(DownloadOption("high", 1036, 7 * gb)), freeBytes = 100 * gb, lastPick = null)
        assertEquals("1080p", resolutionLabel(m.rows.single().option))
    }

    @Test fun `a choice larger than the free space minus 1 GB is disabled and says how much to free`() {
        val m = buildQualitySheet(listOf(orig(18 * gb), medium(3 * gb)), freeBytes = 10 * gb, lastPick = null)
        val o = m.rows[0]; val c = m.rows[1]
        assertFalse(o.enabled)
        assertEquals(18 * gb - (10 * gb - gb), o.shortfallBytes)
        assertTrue(c.enabled)
        assertEquals(0L, c.shortfallBytes)
    }

    @Test fun `exactly free minus 1 GB still fits`() {
        val m = buildQualitySheet(listOf(orig(9 * gb)), freeBytes = 10 * gb, lastPick = null)
        assertTrue(m.rows[0].enabled)
        val over = buildQualitySheet(listOf(orig(9 * gb + 1)), freeBytes = 10 * gb, lastPick = null)
        assertFalse(over.rows[0].enabled)
    }

    @Test fun `the last pick is preselected, so Download then confirm is two taps`() {
        val m = buildQualitySheet(listOf(orig(18 * gb), high(7 * gb), medium(3 * gb)), freeBytes = 100 * gb, lastPick = "medium")
        assertEquals("medium", m.selected?.option?.quality)
        assertEquals(3 * gb, m.buttonBytes)
    }

    @Test fun `with no last pick the first row that fits is selected`() {
        val m = buildQualitySheet(listOf(orig(18 * gb), medium(3 * gb)), freeBytes = 10 * gb, lastPick = null)
        assertEquals("medium", m.selected?.option?.quality)
    }

    @Test fun `a last pick that no longer fits or is no longer offered falls back to the first that fits`() {
        val tooBig = buildQualitySheet(listOf(orig(18 * gb), medium(3 * gb)), freeBytes = 10 * gb, lastPick = "original")
        assertEquals("medium", tooBig.selected?.option?.quality)
        val gone = buildQualitySheet(listOf(orig(2 * gb)), freeBytes = 10 * gb, lastPick = "small")
        assertEquals("original", gone.selected?.option?.quality)
    }

    @Test fun `when nothing fits nothing is selected and the button is off`() {
        val m = buildQualitySheet(listOf(orig(18 * gb), medium(8 * gb)), freeBytes = 5 * gb, lastPick = null)
        assertNull(m.selected)
        assertFalse(m.canDownload)
        assertEquals(0L, m.buttonBytes)
    }

    @Test fun `until smaller copies ship the sheet lists the original alone`() {
        val m = buildQualitySheet(listOf(orig(6 * gb)), freeBytes = 50 * gb, lastPick = "medium")
        assertEquals(1, m.rows.size)
        assertTrue(m.canDownload)
    }

    // ── FR-5, a season ─────────────────────────────────────────────────────────────────────────────

    private fun ep(id: String, onPhone: Boolean, o: Long, m: Long?) =
        SeasonEpisode(id, onPhone, listOfNotNull(orig(o), m?.let { medium(it) }))

    @Test fun `a season row shows the season total and the button counts only the episodes not on the phone`() {
        val eps = listOf(ep("1", true, 4 * gb, 1 * gb), ep("2", false, 4 * gb, 1 * gb), ep("3", false, 4 * gb, 1 * gb), ep("4", false, 4 * gb, 1 * gb))
        val m = buildSeasonSheet(eps, freeBytes = 100 * gb, lastPick = "medium")
        assertEquals(16 * gb, m.rows.first { it.option.quality == "original" }.option.bytes)
        assertEquals(4 * gb, m.rows.first { it.option.quality == "medium" }.option.bytes)
        assertEquals("medium", m.selected?.option?.quality)
        assertEquals(3 * gb, m.buttonBytes)
        assertEquals(1, m.haveEpisodes)
        assertEquals(4, m.allEpisodes)
    }

    @Test fun `a quality is listed for a season only when every episode offers it`() {
        val eps = listOf(ep("1", false, 4 * gb, 1 * gb), ep("2", false, 4 * gb, null))
        val m = buildSeasonSheet(eps, freeBytes = 100 * gb, lastPick = null)
        assertEquals(listOf("original"), m.rows.map { it.option.quality })
    }

    @Test fun `a season row fits on what would be downloaded, not on what the season totals`() {
        val eps = listOf(ep("1", true, 20 * gb, null), ep("2", false, 4 * gb, null))
        val m = buildSeasonSheet(eps, freeBytes = 10 * gb, lastPick = null)
        assertTrue(m.rows[0].enabled)
        assertEquals(4 * gb, m.buttonBytes)
    }

    @Test fun `a season copy total is exact only when every episode's is`() {
        val m = buildSeasonSheet(listOf(ep("1", false, 4 * gb, 1 * gb)), freeBytes = 100 * gb, lastPick = null)
        assertTrue(m.rows.first { it.option.quality == "original" }.option.exact)
        assertFalse(m.rows.first { it.option.quality == "medium" }.option.exact)
    }
}
