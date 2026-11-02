package dev.jellystructure.autofetch

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3407 (DEB-2474, FR-4.1, FR-15.8) — an item a yt-dlp flat entry gives no date is dated from its own answer: three in the proof, 20 a run,
 * once, stored on its row. Acceptance item 16, against a fake yt-dlp whose flat entries carry no date and whose item answers do.
 */
class YtDlpDatingTest {
    private val h = Harness()

    @AfterTest
    fun tearDown() = h.close()

    private val recipe get() = h.dagbokRecipe.copy(list = ListSpec(kind = "ytdlp"), media = MediaSpec(kind = "ytdlp"))

    private fun undated(count: Int) {
        h.tools.ytdlpEntries = (1..count).map { d -> listOf("N$d", "https://varpid.example.fo/sendingar/dagbok/$d", "Dagbók · $d", "") }
    }

    /** Entry n was published on 2026-10-n, as the item's own answer says. */
    private fun answerDates() {
        h.tools.ytdlpDateOf = { url -> "202610" + url.substringAfterLast('/').padStart(2, '0') }
    }

    private suspend fun save(r: Recipe = recipe): SaveResult = h.service.save(SaveRequest(h.dagbokUrl, null, true, r))

    @Test
    fun `a page-only date fetcher over five undated entries is saved with four yt-dlp calls and the first run dates the other two`() = runBlocking<Unit> {
        undated(5); answerDates()
        val f = assertIs<SaveResult.Saved>(save()).fetcher
        assertEquals(1, h.tools.ytdlpListings.size)
        assertEquals(3, h.tools.ytdlpInfos.size + h.tools.ytdlpDateReads.size, "three item reads: the probe's, and two date reads")
        assertEquals(1, h.tools.ytdlpInfos.size, "the probe dates its own item")
        h.tools.ytdlpDateReads.clear(); h.tools.ytdlpInfos.clear(); h.tools.ytdlpListings.clear()

        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(5, r.placed, r.lines.toString())
        assertEquals(2, h.tools.ytdlpDateReads.size, "only the two the proof did not date: ${h.tools.ytdlpDateReads}")
        assertFalse(r.summary.contains("not dated yet"), r.summary)
        assertEquals("2026-10-05", h.store.items(f.id).first { it.itemId == "N5" }.published)
    }

    @Test
    fun `the second run asks yt-dlp for the listing only`() = runBlocking<Unit> {
        undated(5); answerDates()
        val f = assertIs<SaveResult.Saved>(save()).fetcher
        h.engine.run(f.id, RunTrigger.MANUAL)
        h.tools.ytdlpDateReads.clear(); h.tools.ytdlpInfos.clear(); h.tools.ytdlpListings.clear(); h.tools.ytdlpDownloads.clear()
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertEquals(1, h.tools.ytdlpListings.size)
        assertTrue(h.tools.ytdlpDateReads.isEmpty() && h.tools.ytdlpInfos.isEmpty() && h.tools.ytdlpDownloads.isEmpty())
    }

    @Test
    fun `a listing of 25 new entries is dated 20 in its first run and the run line says how many wait`() = runBlocking<Unit> {
        undated(25); answerDates()
        val f = assertIs<SaveResult.Saved>(save()).fetcher
        h.tools.ytdlpDateReads.clear()
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(20, h.tools.ytdlpDateReads.size, "the proof dated three of the 25, so the run dates the 20 it is allowed of the other 22")
        assertTrue(r.summary.endsWith("· 2 items not dated yet, on the next runs"), r.summary)
    }

    @Test
    fun `an entry whose own answer has no date is skipped and never read again or counted as a failure`() = runBlocking<Unit> {
        undated(5)
        h.tools.ytdlpDateOf = { url -> if (url.endsWith("/3")) null else "202610" + url.substringAfterLast('/').padStart(2, '0') }
        val f = assertIs<SaveResult.Saved>(save()).fetcher
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(4, r.placed, r.lines.toString())
        assertTrue(r.lines.any { it.kind == "skipped" && it.text == "Dagbók · 3: yt-dlp gives it no date" }, r.lines.toString())
        assertTrue(r.lines.none { it.kind == "failed" })
        assertEquals(0, h.store.items(f.id).first { it.itemId == "N3" }.failures)
        h.tools.ytdlpDateReads.clear()
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertTrue(h.tools.ytdlpDateReads.isEmpty(), "a no-date answer is stored: ${h.tools.ytdlpDateReads}")
    }

    @Test
    fun `when every item answer lacks a date the proof fails and nothing is written`() = runBlocking<Unit> {
        undated(5)
        val r = assertIs<SaveResult.Refused>(save())
        assertTrue("yt-dlp gives these items no date" in r.reason, r.reason)
        assertTrue(h.store.fetchers().isEmpty())
    }

    @Test
    fun `two dated items with one date are not enough to number by date`() = runBlocking<Unit> {
        undated(5)
        h.tools.ytdlpDateOf = { "20261001" }
        assertTrue("no date" in assertIs<SaveResult.Refused>(save()).reason)
    }

    @Test
    fun `the item reads ask for the three date fields only`() {
        val c = YtDlpCommands.date("https://varpid.example.fo/x", "ua")
        assertTrue("--print '%(.{upload_date,release_date,timestamp})j'" in c, c)
        assertFalse(" -J " in c); assertFalse("--flat-playlist" in c)
    }

    @Test
    fun `the date is the first of upload_date and release_date and timestamp and a timestamp is its UTC date`() {
        assertEquals("2026-10-27", YtDlpDate.parse("""{"upload_date":"20261027","release_date":"20261001","timestamp":1}"""))
        assertEquals("2026-10-01", YtDlpDate.parse("""{"upload_date":null,"release_date":"20261001","timestamp":1}"""))
        assertEquals("2026-10-26", YtDlpDate.parse("{\"timestamp\":1793059199}"), "23:59:59 UTC, no zone shift")
        assertEquals("2026-10-27", YtDlpDate.parse("{\"timestamp\":1793059200}"))
        assertNull(YtDlpDate.parse("{}"))
        assertNull(YtDlpDate.parse("""{"upload_date":null}"""))
        assertEquals("1970-01-01", Numbering.dateOfEpoch(0)); assertEquals("2024-02-29", Numbering.dateOfEpoch(1709164800))
    }

    @Test
    fun `a flat entry with only a release_date or a timestamp is dated by the listing`() {
        val json = """{"entries":[{"id":"a","url":"u1","title":"A","upload_date":null,"release_date":"20261001"},{"id":"b","url":"u2","title":"B","timestamp":1793059200},{"id":"c","url":"u3","title":"C"}]}"""
        val list = ListSpec(kind = "ytdlp")
        val items = ListingReader.readJson(YtDlpListing.asJsonList(list), json, "https://x.example.fo/", null)!!.items
        assertEquals(listOf("2026-10-01", "2026-10-27", null), YtDlpListing.fillDates(list, json, items).map { Numbering.isoDate(it.published) })
    }
}
