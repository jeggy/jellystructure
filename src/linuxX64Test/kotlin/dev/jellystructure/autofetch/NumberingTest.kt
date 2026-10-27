package dev.jellystructure.autofetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-2476 (FR-4.1, FR-4.5) — how an item becomes SxxEyy, and the file's name. */
class NumberingTest {
    @Test
    fun `27 October 2026 is S2026E300`() {
        assertEquals(SeasonEpisode(2026, 300), Numbering.fromDate("2026-10-27"))
        assertEquals(SeasonEpisode(2026, 1), Numbering.fromDate("2026-01-01"))
        assertEquals(SeasonEpisode(2026, 365), Numbering.fromDate("2026-12-31"))
        assertEquals(SeasonEpisode(2028, 366), Numbering.fromDate("2028-12-31"))
    }

    @Test
    fun `dates are read as printed`() {
        assertEquals("2026-10-27", Numbering.isoDate("2026-10-27T21:00:00+00:00"))
        assertEquals("2026-10-27", Numbering.isoDate("27.10.2026"))
        assertEquals("2026-10-27", Numbering.isoDate("27/10/2026"))
        assertEquals("2026-10-27", Numbering.isoDate("27. oktober 2026"))
        assertEquals("2026-03-05", Numbering.isoDate("5 March 2026"))
        assertNull(Numbering.isoDate("31.02.2026"))
        assertNull(Numbering.isoDate("yesterday"))
        assertNull(Numbering.isoDate(null))
    }

    @Test
    fun `date numbering names the file with the code and the date`() {
        val se = Numbering.fromDate("2026-10-27")!!
        assertEquals("Dagbók - S2026E300 - 2026-10-27 - Dagbók · 27. oktober.mkv", Numbering.fileName("Dagbók", se, "2026-10-27", "Dagbók · 27. oktober"))
        assertEquals("Dagbók - S2026E300 - 2026-10-27.mkv", Numbering.fileName("Dagbók", se, "2026-10-27", null))
        assertEquals("Season 2026", Numbering.seasonFolder(2026))
    }

    @Test
    fun `source numbering names the file Series - SxxEyy - name`() {
        assertEquals("Royndin - S2026E01 - Útfall.mkv", Numbering.fileName("Royndin", SeasonEpisode(2026, 1), null, "Útfall"))
        assertEquals("Season 01", Numbering.seasonFolder(1))
        assertEquals("S01E12", Numbering.code(SeasonEpisode(1, 12)))
    }

    @Test
    fun `path characters are cleaned out of a name`() {
        assertEquals("a b c d", Numbering.cleanName("a/b\\c:d"))
        assertEquals("Nordvest", Numbering.cleanName("  ..Nordvest.. "))
        assertEquals("x", Numbering.cleanName("x\u0000"))
    }

    @Test
    fun `a pattern finds a year season and an episode in a title`() {
        val rules = listOf(PatternRule("title", """(?<season>\d{4})"""), PatternRule("title", """(?<episode>\d+)\. partur"""))
        val item = ListedItem(title = "Royndin 2026 · 3. partur")
        assertEquals(SeasonEpisode(2026, 3), Numbering.fromSource(rules, item))
        assertNull(Numbering.fromSource(rules, ListedItem(title = "Royndin 2026")))
    }

    @Test
    fun `the listing's own season and episode fields win`() {
        val rules = listOf(PatternRule("title", """(?<season>\d{4}).*(?<episode>\d+)\. partur"""))
        assertEquals(SeasonEpisode(2, 9), Numbering.fromSource(rules, ListedItem(title = "Royndin 2026 1. partur", season = "2", episode = "9")))
    }

    @Test
    fun `an S and E rule reads the code`() {
        val rules = listOf(PatternRule("title", """[Ss](?<season>\d{1,4})[Ee](?<episode>\d{1,3})"""))
        assertEquals(SeasonEpisode(2026, 12), Numbering.fromSource(rules, ListedItem(title = "x S2026E12 y")))
    }

    @Test
    fun `item page value must pass the character rule`() {
        val p = """var media = '(?<value>[^']+)'"""
        assertEquals("DOV7302", Numbering.itemPageValue(p, "<script>var media = 'DOV7302';</script>"))
        assertNull(Numbering.itemPageValue(p, "<script>var media = '../etc/passwd';</script>"))
        assertNull(Numbering.itemPageValue(p, "<script>var media = '" + "A".repeat(129) + "';</script>"))
        assertNull(Numbering.itemPageValue(p, "nothing here"))
    }
}
