package dev.jellystructure.autofetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ---- DEB-3567 (FR-4.5, amended 2026-11-04) ----

    private val drWhen = WhenSpec("published", """(?<day>\d{1,2})\.\s*(?<month>\p{L}+)\.?\s+(?<year>\d{4})\s+(?<hour>\d{1,2})[.:](?<minute>\d{2})""", mapOf("nov" to 11, "november" to 11, "jan" to 1, "dec" to 12))
    private fun at(raw: String, w: WhenSpec = drWhen) = PageDates.read(NumberingSpec("datetime", w), ListedItem(published = raw))

    @Test
    fun `the recipes date pattern reads the date and the time as the page prints them`() {
        assertEquals("2026-11-04 06:00", at("4. Nov. 2026 6.00 | 4t 0m")?.stamp)
        assertEquals("2026-11-03 21:00", at("3. Nov. 2026 21.00 | 25m")?.stamp)
        assertEquals("2026-11-03 18:30", at("3. Nov. 2026 18.30 | 25m")?.stamp)
        assertEquals("2026-11-03 18:30", at("3. november 2026 18:30")?.stamp)
        assertEquals(0, at("3. Nov. 2026 0.00")?.minute)
    }

    @Test
    fun `a month name the table lacks or a date or time that is not real gives no date`() {
        assertNull(at("3. Mar. 2026 18.30"))
        assertNull(at("31. Nov. 2026 18.30"))
        assertNull(at("3. Nov. 2026 24.00"))
        assertNull(at("3. Nov. 2026 18.75"))
        assertNull(at("3. Nov. 2026"))
        assertNull(at("no date here"))
    }

    @Test
    fun `a pattern may name digits for the month and a two digit year and date numbering needs no time`() {
        val w = WhenSpec("title", """(?<day>\d{1,2})/(?<month>\d{1,2})/(?<year>\d{2})""")
        val p = PageDates.read(NumberingSpec("date", w), ListedItem(title = "Tíðindi 5/11/26"))
        assertEquals(PublishedAt("2026-11-05", null), p)
    }

    @Test
    fun `a date recipe without a pattern reads its dates as before and isoDate is unchanged`() {
        assertEquals("2026-10-27", PageDates.read(NumberingSpec("date"), ListedItem(published = "27. oktober 2026"))?.date)
        assertNull(PageDates.read(NumberingSpec("datetime"), ListedItem(published = "2026-10-27")))
        assertNull(Numbering.isoDate("4. Nov. 2026 6.00 | 4t 0m"))
        assertEquals("2026-10-27", Numbering.isoDate("20261027"))
    }

    @Test
    fun `datetime numbers a date and time by the year and week and the day and slot`() {
        fun n(raw: String) = at(raw)!!.let { Numbering.fromDateTime(it.date, it.minute!!) }
        assertEquals(SeasonEpisode(2644, 808), n("3. Nov. 2026 18.30 | 25m"))
        assertEquals(SeasonEpisode(2644, 823), n("3. Nov. 2026 21.00 | 25m"))
        assertEquals(SeasonEpisode(2644, 876), n("4. Nov. 2026 6.00 | 4t 0m"))
        assertEquals(SeasonEpisode(2601, 1), n("1. Jan. 2026 0.00"))
        assertEquals(SeasonEpisode(2653, 140), n("31. Dec. 2026 23.59"))
        // 18.25 and 18.30 share a slot; 18.30 and 18.35 do not.
        assertEquals(n("3. Nov. 2026 18.25"), n("3. Nov. 2026 18.30"))
        assertTrue(n("3. Nov. 2026 18.30") != n("3. Nov. 2026 18.35"))
        assertEquals("S2644E808", Numbering.codeFor("datetime", n("3. Nov. 2026 18.30")!!))
        assertEquals("S2601E001", Numbering.codeFor("datetime", n("1. Jan. 2026 0.00")!!))
        assertEquals("S2653E140", Numbering.codeFor("datetime", n("31. Dec. 2026 23.59")!!))
        assertEquals("S2026E05", Numbering.codeFor("date", SeasonEpisode(2026, 5)))
        // The episode stays within 1–980 for every minute of every day of a week, and the season never exceeds week 53.
        for (d in listOf("2026-01-01", "2026-01-07", "2026-12-31", "2028-12-30", "2028-12-31"))
            for (m in 0..1439) { val se = Numbering.fromDateTime(d, m)!!; assertTrue(se.episode in 1..980, "$d $m ${se.episode}"); assertTrue(se.season % 100 in 1..53) }
    }

    @Test
    fun `a year outside 2010 to 2099 gets no datetime number`() {
        assertNull(Numbering.fromDateTime("2009-12-31", 600))
        assertNull(Numbering.fromDateTime("2100-01-01", 600))
        assertEquals(SeasonEpisode(1001, 1), Numbering.fromDateTime("2010-01-01", 0))
    }

    @Test
    fun `datetime names the file with the code the date and the time`() {
        val se = SeasonEpisode(2644, 808)
        assertEquals("Kvøldbók - S2644E808 - 2026-11-03 18.30 - Kvøldbók · 3.mkv", Numbering.fileName("Kvøldbók", se, "2026-11-03", "Kvøldbók · 3", "18.30"))
        assertEquals("Season 2644", Numbering.seasonFolder(2644))
    }
}
