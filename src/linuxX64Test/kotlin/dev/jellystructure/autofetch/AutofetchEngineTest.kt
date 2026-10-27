package dev.jellystructure.autofetch

import dev.jellystructure.filefix.PosixFileFixShell
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2476 — the engine against a stand-in site the test serves itself. Acceptance 4–15 of the spec, each on stand-in data. */
class AutofetchEngineTest {
    private val h = Harness()

    @AfterTest
    fun tearDown() = h.close()

    private fun refused(r: SaveResult) = assertIs<SaveResult.Refused>(r)

    // ---- Acceptance 9: a page-only fetcher ----------------------------------------------------------------------------

    @Test
    fun `a blank TMDB id without the tick is refused and one with it is saved page only`() = runBlocking<Unit> {
        val noTick = refused(h.service.save(SaveRequest(h.dagbokUrl, null, false, h.dagbokRecipe)))
        assertEquals(400, noTick.status); assertEquals(RecipeRefusal.NO_TMDB_ID, noTick.reason)
        assertTrue(h.store.fetchers().isEmpty())
        val f = h.saveDagbok()
        assertTrue(f.pageOnly); assertNull(f.tmdbId)
        assertEquals("Dagbók", f.name); assertEquals("${h.lib}/Dagbók", f.seriesPath)
        assertEquals(RetentionSpec("newest", 12, true), f.retention)   // FR-16.1: page-only defaults
        assertEquals("Dagligur tátturin.", f.seriesDescription)
        assertEquals("https://varpid.example.fo/img/dagbok.jpg", f.seriesImage)
        assertTrue(h.store.isPageOnlySeries(f.seriesPath))
        assertEquals(0, h.world.tmdbCalls, "nothing asks TMDB about a page-only fetcher")
        assertFalse(h.onDisk(f.seriesPath), "the series folder is created only on the first placed episode")
    }

    @Test
    fun `the first run places twelve episodes under their date numbers with a thumb each and no TMDB request`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("ok", r.status); assertEquals(12, r.placed)
        val season = h.seasonDir(f)
        assertTrue(h.onDisk("$season/Dagbók - S2026E300 - 2026-10-27 - Dagbók · 27. oktober.mkv"))
        assertTrue(h.onDisk("$season/Dagbók - S2026E289 - 2026-10-16 - Dagbók · 16. oktober.mkv"))
        assertEquals(12, h.files(f).size)
        assertTrue(h.files(f).all { it.startsWith("Dagbók - S2026E") && it.endsWith(".mkv") })
        assertTrue(h.onDisk("$season/Dagbók - S2026E300 - 2026-10-27 - Dagbók · 27. oktober-thumb.jpg"))
        assertTrue(h.onDisk("${f.seriesPath}/poster.jpg"), "the page's image is the series poster")
        assertEquals(12, h.world.announced.count { it.second == "Created" })
        assertFalse(h.onDisk("$season/.jellystructure"), "no work folder is left behind")
        assertEquals(0, h.world.tmdbCalls)
        val item = h.store.itemByPlacedPath("$season/Dagbók - S2026E300 - 2026-10-27 - Dagbók · 27. oktober.mkv")!!
        assertEquals("Lýsing 27", item.description); assertEquals("2026-10-27", item.published)   // the page's words, verbatim
    }

    // ---- Acceptance 2/15: idempotent, item_page ---------------------------------------------------------------------

    @Test
    fun `the second run says no new episodes and requests only the listing`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.engine.run(f.id, RunTrigger.MANUAL)
        h.site.reset()
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("no new episodes", r.summary)
        assertEquals(listOf("https://varpid.example.fo/sendingar/dagbok"), h.site.nonRobots())
    }

    @Test
    fun `the first run reads one item page per new item 10 seconds apart and stores the value`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.site.reset()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val pages = h.site.requests.filter { it.first.contains("/sendingar/dagbok/") }
        assertEquals(12, pages.size)
        assertTrue(h.site.requests.filter { !it.first.contains("media.example.fo") }.map { it.second }.zipWithNext().all { (a, b) -> b - a >= 10_000 })
        assertEquals("DOV7327", h.store.item(f.id, "https://varpid.example.fo/sendingar/dagbok/27")!!.mediaValue)
    }

    @Test
    fun `an item page value that fails the character rule fails the proof when it is the one probed`() = runBlocking<Unit> {
        h.site.badMedia = setOf(27)
        val r = refused(h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe)))
        assertTrue("character rule" in r.reason, r.reason)
        assertTrue(h.store.fetchers().isEmpty())
    }

    @Test
    fun `a bad media value on a later item is skipped and the rest are placed`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.site.badMedia = setOf(20)
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(11, r.placed)
        assertTrue(r.lines.any { it.kind == "skipped" && "character rule" in it.text })
        assertFalse(h.site.requests.any { it.first.contains("DOV7320") || it.first.contains("passwd") })
    }

    // ---- Acceptance 10: date numbering --------------------------------------------------------------------------------

    @Test
    fun `two items on one date fail the proof at save`() = runBlocking<Unit> {
        h.site.days = listOf(27, 20, 20)
        val r = refused(h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe)))
        assertTrue("share the date" in r.reason, r.reason)
        assertTrue(h.store.fetchers().isEmpty())
    }

    @Test
    fun `on a later run a second item on a date already placed is skipped with its line`() = runBlocking<Unit> {
        val f = h.saveDagbok(RetentionSpec("all"))
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertEquals(12, h.files(f).size)
        h.site.extraItems = """<li class="ep"><a href="/sendingar/dagbok/280">Dagbók · 27. oktober (aftur)</a><time datetime="2026-10-27">27.10.2026</time><p>x</p></li>"""
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, r.placed)
        assertTrue(r.lines.any { it.kind == "skipped" && "shares 2026-10-27 with an episode already placed" in it.text }, r.lines.toString())
        assertEquals(12, h.files(f).size)
        assertEquals("ok", r.status)   // the fetcher carries on
    }

    // ---- Acceptance 11: the newest 12 -----------------------------------------------------------------------------------

    @Test
    fun `a thirteenth item downloads and the oldest is deleted with its nfo and thumb`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val season = h.seasonDir(f)
        val oldest = "$season/Dagbók - S2026E289 - 2026-10-16 - Dagbók · 16. oktober.mkv"
        PosixFileFixShell.writeText(oldest.removeSuffix(".mkv") + ".nfo", "<episodedetails/>")
        PosixFileFixShell.writeText("$season/Dagbók - S2026E280 - hand placed.mkv", "mine")   // never touched
        h.site.days = (17..28).toList().reversed()
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(1, r.placed); assertEquals(1, r.deleted)
        assertFalse(h.onDisk(oldest)); assertFalse(h.onDisk(oldest.removeSuffix(".mkv") + ".nfo")); assertFalse(h.onDisk(oldest.removeSuffix(".mkv") + "-thumb.jpg"))
        assertTrue(h.onDisk("$season/Dagbók - S2026E280 - hand placed.mkv"))
        assertTrue(h.world.announced.contains(oldest to "Deleted"))
        assertTrue(r.lines.any { it.kind == "deleted" && it.text.startsWith("−1 · S2026E289 (2026-10-16)") && "newest 12 kept" in it.text }, r.lines.toString())
        assertTrue(r.summary.contains("−1 by retention"))
        assertTrue(h.world.activities.any { "−1" in it })
        assertEquals(listOf(oldest), h.world.forgotten)
        // The next run does not download the deleted item again.
        h.site.days = (16..28).toList().reversed()
        val again = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, again.placed); assertFalse(h.onDisk(oldest))
    }

    @Test
    fun `a first run on a long back catalogue downloads only the newest 12`() = runBlocking<Unit> {
        h.site.days = (1..31).toList().reversed()   // 31 items on the page
        val f = h.saveDagbok()
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(12, r.placed); assertEquals(0, r.deleted)
        assertTrue(h.files(f).any { it.contains("2026-10-31") }); assertFalse(h.files(f).any { it.contains("2026-10-01") })
    }

    @Test
    fun `at most 20 episodes are fetched per run`() = runBlocking<Unit> {
        h.site.days = (1..31).toList().reversed()
        val f = h.saveDagbok(RetentionSpec("all"))
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(20, r.placed)
        assertTrue(r.lines.any { it.kind == "info" && "20 of 31 fetched" in it.text }, r.lines.toString())
    }

    // ---- Acceptance 12: a bad read deletes nothing -----------------------------------------------------------------------

    private suspend fun placed12(): Fetcher {
        val f = h.saveDagbok()
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertEquals(12, h.files(f).size)
        return f
    }

    @Test
    fun `a 503 a 403 an empty listing and a listing sharing nothing each delete nothing`() = runBlocking<Unit> {
        val f = placed12()
        h.clock.advanceHours(24)
        h.site.listingStatus = 503; h.site.listingHeaders = mapOf("Retry-After" to "60")
        assertEquals("waiting", h.engine.run(f.id, RunTrigger.MANUAL)!!.status)
        h.clock.advanceHours(24)
        h.site.listingStatus = 403
        val stopped = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("stopped", stopped.status)
        assertEquals(1, h.world.stops.size)
        h.lanes.forSite("varpid.example.fo").resume()
        h.site.listingStatus = 200
        h.site.empty = true
        val empty = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("needs_reinvestigation", empty.status)
        assertEquals(12, h.files(f).size)
        assertEquals(12, h.files(f).count { h.onDisk(h.seasonDir(f) + "/" + it) })
    }

    @Test
    fun `a listing that shares no item with the library deletes nothing and says why`() = runBlocking<Unit> {
        val f = placed12()
        h.site.days = (1..12).toList().reversed().map { it }   // days 1..12: none of 16..27
        h.clock.advanceHours(12)
        h.engine.run(f.id, RunTrigger.MANUAL)
        h.clock.advanceHours(12)
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, r.deleted)
        assertEquals(12, h.files(f).count { h.onDisk(h.seasonDir(f) + "/" + it) })
    }

    @Test
    fun `five items missing once are deleted only after a second full read six hours later`() = runBlocking<Unit> {
        val f = placed12()
        h.site.days = (21..27).toList().reversed()   // 16..20 have left the page
        h.clock.advanceHours(1)
        val first = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, first.deleted)
        assertEquals(12, h.files(f).count { h.onDisk(h.seasonDir(f) + "/" + it) })
        h.clock.advanceHours(7)
        val second = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(5, second.deleted)
        assertEquals(7, h.files(f).count { h.onDisk(h.seasonDir(f) + "/" + it) })
        assertTrue(second.lines.count { it.kind == "deleted" && "left the page" in it.text } == 5)
    }

    // ---- Acceptance 13: playback holds a deletion ---------------------------------------------------------------------

    @Test
    fun `an episode in a playback session survives the run that would delete it and goes at the next`() = runBlocking<Unit> {
        val f = placed12()
        val oldest = h.seasonDir(f) + "/Dagbók - S2026E289 - 2026-10-16 - Dagbók · 16. oktober.mkv"
        h.world.playingPaths += oldest
        h.site.days = (17..28).toList().reversed()
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, r.deleted); assertTrue(h.onDisk(oldest))
        assertTrue(r.lines.any { it.kind == "retention" && "held" in it.text && "playing" in it.text })
        h.world.playingPaths.clear()
        assertEquals(1, h.engine.run(f.id, RunTrigger.MANUAL)!!.deleted)
        assertFalse(h.onDisk(oldest))
    }

    // ---- Acceptance 14: defaults and preview ------------------------------------------------------------------------------

    @Test
    fun `a TMDB fetcher starts at all episodes and lowering 12 to 8 previews 4 then deletes exactly those 4`() = runBlocking<Unit> {
        val f = placed12()
        assertEquals(4, h.service.preview(f.id, RetentionSpec("newest", 8, false)))
        assertEquals(0, h.service.preview(f.id, RetentionSpec("newest", 12, false)))
        assertEquals(0, h.service.preview(f.id, RetentionSpec("all")))
        assertNull(h.service.update(f.id, RetentionSpec("newest", 8, false), null, null))
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(4, r.deleted)
        assertEquals(8, h.files(f).count { h.onDisk(h.seasonDir(f) + "/" + it) })
        assertTrue(h.files(f).filter { h.onDisk(h.seasonDir(f) + "/" + it) }.none { it.contains("2026-10-16") || it.contains("2026-10-19") })
    }

    @Test
    fun `a TMDB fetcher defaults to all`() = runBlocking<Unit> {
        h.world.tmdbEps = listOf(TmdbEpisodeFact(2026, 1, "Útfall", "2026-01-10"), TmdbEpisodeFact(2026, 2, "Onnur", "2026-02-10"))
        val r = h.service.save(SaveRequest("https://varpid.example.fo/sendingar/royndin", 9000001, false, h.royndinRecipe)) as SaveResult.Saved
        assertEquals(RetentionSpec("all"), r.fetcher.retention)
    }

    // ---- Acceptance 6: refusals -------------------------------------------------------------------------------------------------

    @Test
    fun `a film id a one item page a DRM manifest and a Sonarr series are each refused and nothing is written`() = runBlocking<Unit> {
        h.world.kind = TmdbKind.FILM
        assertEquals("this id is a film — Auto-fetch needs a series", refused(h.service.save(SaveRequest("https://varpid.example.fo/sendingar/royndin", 9000001, false, h.royndinRecipe))).reason)
        h.world.kind = TmdbKind.SERIES
        h.site.onlyOnePage = true
        val one = refused(h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe)))
        assertTrue(one.notASeries, one.reason)
        h.site.onlyOnePage = false
        h.site.drm = true
        assertEquals(RecipeRefusal.DRM, refused(h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe))).reason)
        h.site.drm = false
        h.world.sonarr = "Sonarr already downloads this series"
        assertEquals("Sonarr already downloads this series", refused(h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe))).reason)
        assertTrue(h.store.fetchers().isEmpty())
        assertFalse(h.onDisk("${h.lib}/Dagbók"))
    }

    @Test
    fun `a series Sonarr takes on later stops that fetcher with the same reason`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.world.sonarr = "Sonarr already downloads this series"
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("refused", r.status)
        assertEquals(0, r.placed)
        assertEquals("refused", h.store.fetcher(f.id)!!.state)
    }

    @Test
    fun `a ytdlp recipe is refused with the sentence`() = runBlocking<Unit> {
        val r = refused(h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe.copy(list = h.dagbokRecipe.list.copy(kind = "ytdlp")))))
        assertEquals(RecipeRefusal.YTDLP, r.reason)
    }

    @Test
    fun `an auto-fetch that is switched off saves nothing and runs nothing`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.world.on = false
        assertEquals(409, refused(h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe))).status)
        assertNull(h.engine.run(f.id, RunTrigger.MANUAL))
    }

    // ---- Acceptance 4/5: the lane ------------------------------------------------------------------------------------------

    @Test
    fun `two downloads never overlap and are at least 60 seconds apart`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertFalse(h.tools.overlapping)
        assertTrue(h.tools.downloads.map { it.second }.zipWithNext().all { (a, b) -> b - a >= 60_000 }, h.tools.downloads.map { it.second }.toString())
    }

    @Test
    fun `a 429 stops the run where it is and the lane waits what Retry-After says`() = runBlocking<Unit> {
        val f = placed12()
        h.clock.advanceHours(24)
        h.site.listingStatus = 429; h.site.listingHeaders = mapOf("Retry-After" to "120")
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("waiting", r.status)
        h.site.reset()
        assertEquals("waiting", h.engine.run(f.id, RunTrigger.MANUAL)!!.status)
        assertTrue(h.site.nonRobots().isEmpty(), "nothing reaches the site while the lane waits")
    }

    @Test
    fun `a stopped site runs nothing for any of its fetchers until it is resumed`() = runBlocking<Unit> {
        val f = placed12()
        h.clock.advanceHours(24)
        h.site.listingStatus = 403
        assertEquals("stopped", h.engine.run(f.id, RunTrigger.MANUAL)!!.status)
        h.site.reset(); h.site.listingStatus = 200
        assertEquals("stopped", h.engine.run(f.id, RunTrigger.MANUAL)!!.status)
        assertTrue(h.site.nonRobots().isEmpty())
        assertNull(h.service.resume("varpid.example.fo"))
        assertEquals("ok", h.engine.run(f.id, RunTrigger.MANUAL)!!.status)
    }

    @Test
    fun `a scheduled run waits while someone is watching and Run now does not`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.world.viewers = true
        assertNull(h.engine.run(f.id, RunTrigger.SCHEDULE))
        assertNull(h.engine.run(f.id, RunTrigger.ALL))
        assertEquals(12, h.engine.run(f.id, RunTrigger.MANUAL)!!.placed)
    }

    @Test
    fun `a paused fetcher is not run on a schedule`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        assertNull(h.service.update(f.id, null, null, false))
        assertNull(h.engine.run(f.id, RunTrigger.SCHEDULE))
    }

    // ---- FR-6.5/6.6: checked before placed, placed over nothing ---------------------------------------------------------

    @Test
    fun `a file whose audio is not tagged is never placed and after three failures the item is marked failed`() = runBlocking<Unit> {
        val f = h.saveDagbok(RetentionSpec("all"))
        h.tools.audioLanguage = "und"
        repeat(3) { h.engine.run(f.id, RunTrigger.MANUAL) }
        assertEquals(0, h.files(f).size)
        assertTrue(h.store.items(f.id).all { it.state == "failed" || it.placedPath == null })
        assertTrue(h.store.items(f.id).any { it.state == "failed" })
        assertFalse(h.onDisk(h.seasonDir(f)), "a failed first download leaves no folder")
    }

    @Test
    fun `Tracks after the first Cluster fails the check`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.tools.first = "Cluster"
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, r.placed)
        assertTrue(r.lines.any { "Tracks is not before the first Cluster" in it.text })
    }

    @Test
    fun `the rename never overwrites what appeared at the path during the download`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        val target = h.seasonDir(f) + "/Dagbók - S2026E300 - 2026-10-27 - Dagbók · 27. oktober.mkv"
        h.tools.onDownload = { work -> if (work.contains("27. oktober")) PosixFileFixShell.writeText(target, "someone else's") }
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("someone else's", kotlinx.io.files.SystemFileSystem.source(kotlinx.io.files.Path(target)).buffered().use { it.readString() })
        assertEquals(11, r.placed)
    }

    // ---- FR-6.8: delete removes records, never a file ------------------------------------------------------------------

    @Test
    fun `deleting a fetcher removes its records and never a file`() = runBlocking<Unit> {
        val f = placed12()
        assertTrue(h.service.delete(f.id))
        assertNull(h.store.fetcher(f.id)); assertTrue(h.store.items(f.id).isEmpty()); assertFalse(h.store.isPageOnlySeries(f.seriesPath))
        assertEquals(12, PosixFileFixShell.listDir(h.seasonDir(f)).count { it.endsWith(".mkv") })
    }

    // ---- Acceptance 2/3: a TMDB fetcher -----------------------------------------------------------------------------------

    @Test
    fun `a TMDB fetcher proven on two agreements places the episodes under the page's numbering`() = runBlocking<Unit> {
        h.world.tmdbEps = listOf(TmdbEpisodeFact(2026, 1, "Útfall", "2026-01-10"), TmdbEpisodeFact(2026, 2, "Onnur", "2026-02-12"))
        val saved = h.service.save(SaveRequest("https://varpid.example.fo/sendingar/royndin", 9000001, false, h.royndinRecipe)) as SaveResult.Saved
        assertTrue(saved.proof.proven); assertEquals(2, saved.proof.agreements)
        val r = h.engine.run(saved.fetcher.id, RunTrigger.MANUAL)!!
        assertEquals(3, r.placed)   // Q1: S2026E03 is not on TMDB yet and is filed under the page's numbering
        assertTrue(h.onDisk("${saved.fetcher.seriesPath}/Season 2026/Royndin - S2026E01 - Royndin 2026 · 1. partur.mkv"))
        assertTrue(h.onDisk("${saved.fetcher.seriesPath}/Season 2026/Royndin - S2026E03 - Royndin 2026 · 3. partur.mkv"))
        assertFalse(h.onDisk("${saved.fetcher.seriesPath}/poster.jpg"), "a TMDB fetcher's poster is TMDB's")
        h.site.reset()
        assertEquals("no new episodes", h.engine.run(saved.fetcher.id, RunTrigger.MANUAL)!!.summary)
        assertEquals(listOf("https://varpid.example.fo/sendingar/royndin"), h.site.nonRobots())
    }

    @Test
    fun `a disagreement with TMDB fails the proof with the rows listed`() = runBlocking<Unit> {
        h.world.tmdbEps = listOf(TmdbEpisodeFact(2026, 1, "Útfall", "2026-01-10"), TmdbEpisodeFact(2026, 2, "Eitt heilt annað", "2025-07-01"))
        val r = refused(h.service.save(SaveRequest("https://varpid.example.fo/sendingar/royndin", 9000001, false, h.royndinRecipe)))
        assertTrue("disagrees with TMDB" in r.reason, r.reason)
        assertEquals(1, r.proof!!.disagreements.size)
    }

    @Test
    fun `fewer than two agreements saves unproven and episodes TMDB lacks wait for the admin`() = runBlocking<Unit> {
        h.world.tmdbEps = listOf(TmdbEpisodeFact(2026, 1, "Útfall", "2026-01-10"))
        val saved = h.service.save(SaveRequest("https://varpid.example.fo/sendingar/royndin", 9000001, false, h.royndinRecipe)) as SaveResult.Saved
        assertFalse(saved.proof.proven)
        val r = h.engine.run(saved.fetcher.id, RunTrigger.MANUAL)!!
        assertEquals(1, r.placed)
        assertEquals(2, r.lines.count { it.kind == "waiting" })
    }

    @Test
    fun `a listing that yields nothing marks only that fetcher Needs re-investigation and downloads nothing`() = runBlocking<Unit> {
        val f = placed12()
        h.site.empty = true
        h.clock.advanceHours(24)
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("needs_reinvestigation", r.status)
        assertEquals("needs_reinvestigation", h.store.fetcher(f.id)!!.state)
        assertFalse(h.lanes.forSite("varpid.example.fo").state().stopped)
    }

    @Test
    fun `the schedule tick data comes from the store and keeps the six hour floor`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val ran = h.store.fetcher(f.id)!!
        assertNotNull(ran.lastRunAt)
        assertTrue(FetchSchedule.nextDue(ran.schedule, ran.id, ran.lastRunAt, h.clock.nowSec()) >= ran.lastRunAt!! + 6 * 3600)
    }
}
