package dev.jellystructure.autofetch

import dev.jellystructure.filefix.PosixFileFixShell
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2477 (DEB-2474, FR-8.4, FR-9, FR-11, FR-15.6) — *Not on TMDB yet*, against the stand-in site the test serves itself. `Royndin`'s
 * third part is the one episode TMDB has no entry for; the page's own heading is its name and the page has no description.
 */
class NotOnTmdbQueueTest {
    private val h = Harness()
    private val royndinUrl = "https://varpid.example.fo/sendingar/royndin"

    @AfterTest
    fun tearDown() = h.close()

    private val twoOnTmdb = listOf(TmdbEpisodeFact(2026, 1, "Útfall", "2026-01-10"), TmdbEpisodeFact(2026, 2, "Onnur", "2026-02-12"))

    private suspend fun provenRoyndin(): Fetcher {
        h.world.tmdbEps = twoOnTmdb
        val saved = h.service.save(SaveRequest(royndinUrl, 9000001, false, h.royndinRecipe)) as SaveResult.Saved
        assertTrue(saved.proof.proven)
        return saved.fetcher
    }

    private fun third(f: Fetcher) = h.store.queueOf(f.id).single()

    // ---- FR-9.1/9.2: what lands in it, and what it holds -----------------------------------------------------------------

    @Test
    fun `only the episode TMDB lacks is filed and it carries the page's words and three stills`() = runBlocking<Unit> {
        val f = provenRoyndin()
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(3, r.placed)
        val row = third(f)
        assertEquals(3, row.episode); assertEquals(2026, row.season); assertEquals("queued", row.state)
        assertTrue(row.titleFromPage, "the page's heading is the episode's name"); assertFalse(row.overviewFromPage, "this page has no description")
        assertTrue(r.lines.any { it.kind == "placed" && it.text.endsWith("· not on TMDB → queued") && "S2026E03" in it.text }, r.lines.toString())
        assertEquals(3, row.stills.size)
        for (n in 0 until 3) assertTrue(h.onDisk(h.engine.queue.stillFile(row.id, n)), "still $n is in the data directory")
        assertFalse(PosixFileFixShell.listDir(h.seasonDir(f)).any { it.endsWith(".jpg") && "still" in it }, "nothing is written into the library (FR-8.3)")
    }

    @Test
    fun `the row shows the runtime ffprobe read when the listing gave no duration`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.tools.duration = 600.0
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertNull(h.store.item(f.id, third(f).itemId)!!.duration, "this listing has no duration (legal for a recipe)")
        assertEquals(600, third(f).runtimeSec)
        val runtime = AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        assertEquals(600, runtime.queueViews().single().runtimeSec, "FR-9.2: the row holds the runtime")
    }

    @Test
    fun `a TMDB that cannot be reached files the episode rather than guess it is there`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.world.tmdbEps = null
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertEquals(3, h.store.queueOf(f.id).size, "the daily check clears any of them TMDB turns out to have")
    }

    @Test
    fun `a fetcher whose pattern is not proven files nothing because it places nothing TMDB lacks`() = runBlocking<Unit> {
        h.world.tmdbEps = listOf(TmdbEpisodeFact(2026, 1, "Útfall", "2026-01-10"))
        val saved = h.service.save(SaveRequest(royndinUrl, 9000001, false, h.royndinRecipe)) as SaveResult.Saved
        assertFalse(saved.proof.proven)
        h.engine.run(saved.fetcher.id, RunTrigger.MANUAL)
        assertTrue(h.store.queue().isEmpty())
    }

    // ---- FR-15.6: a page-only fetcher never enters it ---------------------------------------------------------------------

    @Test
    fun `a page only fetcher's episodes never enter the queue and nothing asks TMDB`() = runBlocking<Unit> {
        val f = h.saveDagbok()
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertTrue(h.store.queue().isEmpty())
        h.clock.advanceHours(48)
        assertEquals(0, h.engine.queue.recheckDue(h.world.nowSec()))
        assertEquals(0, h.world.tmdbCalls); assertTrue(h.world.resyncs.isEmpty())
    }

    // ---- FR-9.3: the daily check ------------------------------------------------------------------------------------------

    @Test
    fun `a season is asked about once a day and a row TMDB still lacks stays`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.engine.run(f.id, RunTrigger.MANUAL)
        h.world.resync = { paths -> paths.associateWith { EpisodeAfterSync(false, null, null) } }
        // Filed just now: nothing is due yet.
        assertEquals(0, h.engine.queue.recheckDue(h.world.nowSec()))
        h.clock.advanceHours(25)
        assertEquals(1, h.engine.queue.recheckDue(h.world.nowSec()))
        assertEquals(listOf(Triple(f.seriesPath, 9000001, 2026)), h.world.resyncs)
        assertEquals("queued", third(f).state)
        // Within the day, again: not asked.
        assertEquals(0, h.engine.queue.recheckDue(h.world.nowSec()))
        h.clock.advanceHours(25)
        assertEquals(1, h.engine.queue.recheckDue(h.world.nowSec()))
    }

    @Test
    fun `when TMDB returns the episode the row clears and a field TMDB leaves blank keeps the page's text`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val page = h.store.item(f.id, third(f).itemId)!!.title
        // TMDB has the number and no name yet: the sync leaves the page's title in place.
        h.world.resync = { paths -> paths.associateWith { EpisodeAfterSync(true, page, null) } }
        h.clock.advanceHours(25)
        h.engine.queue.recheckDue(h.world.nowSec())
        var row = third(f)
        assertEquals("cleared", row.state)
        assertTrue(row.titleFromPage, "TMDB gave no name, so the marker holds")
        assertTrue(row.needsCheck, "a cleared row with a marker still on is checked until TMDB has its text")
        // The next day TMDB has a name of its own: the marker goes, and there is nothing left to ask.
        h.world.resync = { paths -> paths.associateWith { EpisodeAfterSync(true, "Eitt nýtt navn", null) } }
        h.clock.advanceHours(25)
        h.engine.queue.recheckDue(h.world.nowSec())
        row = third(f)
        assertFalse(row.titleFromPage); assertFalse(row.markerOn); assertFalse(row.needsCheck)
        h.clock.advanceHours(25)
        assertEquals(0, h.engine.queue.recheckDue(h.world.nowSec()), "the row is history now")
    }

    @Test
    fun `a series the library does not have yet is tried again tomorrow and not every minute`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.engine.run(f.id, RunTrigger.MANUAL)
        h.world.resync = { null }
        h.clock.advanceHours(25)
        assertEquals(1, h.engine.queue.recheckDue(h.world.nowSec()))
        assertEquals(0, h.engine.queue.recheckDue(h.world.nowSec()))
        assertEquals("queued", third(f).state)
    }

    @Test
    fun `a file moved by hand has nothing to ask TMDB about`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.engine.run(f.id, RunTrigger.MANUAL)
        PosixFileFixShell.remove(h.store.item(f.id, third(f).itemId)!!.placedPath!!)
        h.clock.advanceHours(25)
        h.engine.queue.recheckDue(h.world.nowSec())
        assertTrue(h.world.resyncs.isEmpty())
    }

    // ---- FR-9.3: Mark as submitted is a note ------------------------------------------------------------------------------

    @Test
    fun `mark as submitted keeps the row marked until TMDB returns it and sends nothing`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val calls = h.world.tmdbCalls
        assertTrue(h.engine.queue.markSubmitted(third(f).id))
        assertEquals("submitted", third(f).state); assertNotNull(third(f).submittedAt)
        assertEquals(calls, h.world.tmdbCalls)
        h.world.resync = { paths -> paths.associateWith { EpisodeAfterSync(true, "Navn", "Lýsing") } }
        h.clock.advanceHours(25)
        h.engine.queue.recheckDue(h.world.nowSec())
        assertEquals("cleared", third(f).state)
        assertFalse(h.engine.queue.markSubmitted(third(f).id), "nothing to note once TMDB has it")
    }

    // ---- FR-9.4: the stills -----------------------------------------------------------------------------------------------

    @Test
    fun `pick a different moment cuts one slot again and refuses a frame it cannot cut`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val row = third(f)
        assertTrue(h.engine.queue.cutAgain(row.id, 1, 754))
        assertEquals(754, third(f).stills[1])
        assertEquals("jpg@754", dev.jellystructure.io.FileIo.readText(kotlinx.io.files.Path(h.engine.queue.stillFile(row.id, 1))))
        h.tools.blackAt = 12
        assertFalse(h.engine.queue.cutAgain(row.id, 1, 12))
        assertEquals(754, third(f).stills[1], "a refused frame changes nothing")
        assertFalse(h.engine.queue.cutAgain(row.id, 3, 10), "there are three stills")
        assertFalse(h.engine.queue.cutAgain(row.id, 0, -1))
    }

    // ---- delete and retention --------------------------------------------------------------------------------------------

    @Test
    fun `deleting a fetcher takes its rows and its stills and never a file`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val row = third(f)
        val still = h.engine.queue.stillFile(row.id, 0)
        assertTrue(h.service.delete(f.id))
        assertTrue(h.store.queue().isEmpty()); assertFalse(h.onDisk(still))
        assertTrue(h.onDisk("${f.seriesPath}/Season 2026/Royndin - S2026E03 - Royndin 2026 · 3. partur.mkv"))
    }

    @Test
    fun `retention deleting a queued episode takes its row with it`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val row = third(f)
        h.engine.queue.forget(f.id, row.itemId)
        assertNull(h.store.queueRow(f.id, row.itemId)); assertFalse(h.onDisk(h.engine.queue.stillFile(row.id, 0)))
    }

    // ---- FR-8.1/8.4: what the scan reads ---------------------------------------------------------------------------------

    @Test
    fun `the scan takes the page's words only for a queued episode and only for the fields the marker names`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val hooks = StoreScanHooks(h.store)
        val queued = h.store.item(f.id, third(f).itemId)!!.placedPath!!
        val ordinary = h.store.items(f.id).first { it.episode == 1 }.placedPath!!
        assertNull(hooks.episodeText(ordinary), "an episode TMDB has is ordinary (FR-8.1)")
        assertNull(hooks.pageMarker(ordinary))
        val text = assertNotNull(hooks.episodeText(queued))
        assertEquals("Royndin 2026 · 3. partur", text.title); assertNull(text.overview, "the page has no description: nothing is invented")
        assertEquals(PageMarker(title = true, overview = false), hooks.pageMarker(queued))
        // Once TMDB has the episode its air date is TMDB's.
        h.store.queueCleared(third(f).id, h.world.nowSec())
        assertNull(hooks.episodeText(queued)!!.airDate)
    }

    // ---- FR-11: a run is an entry with its lines under it -----------------------------------------------------------------

    @Test
    fun `a run is one Activity entry and its lines are logged under it`() = runBlocking<Unit> {
        val f = provenRoyndin()
        h.engine.run(f.id, RunTrigger.MANUAL)
        assertEquals(1, h.world.runsStarted.size); assertEquals(h.world.runsStarted, h.world.runsEnded)
        val id = h.world.runsStarted.single()
        val under = h.world.activityRuns.filter { it.first == id }.map { it.second }
        assertTrue(under.first().startsWith("Auto-fetch · Royndin · +3"), under.toString())
        assertTrue(under.any { "S2026E03" in it && "not on TMDB → queued" in it }, under.toString())
    }

    @Test
    fun `a deletion by retention is a line under the run's entry`() = runBlocking<Unit> {
        val f = h.saveDagbok(RetentionSpec("newest", 12, true))
        h.engine.run(f.id, RunTrigger.MANUAL)
        h.site.days = (17..28).toList().reversed()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val second = h.world.runsStarted.last()
        val under = h.world.activityRuns.filter { it.first == second }.map { it.second }
        assertTrue(under.any { it.startsWith("−1 · ") }, under.toString())
    }

    // ---- FR-2.5: the Dashboard -------------------------------------------------------------------------------------------

    @Test
    fun `a stopped site is one Dashboard row however many fetchers it stops and none while it runs`() = runBlocking<Unit> {
        val a = h.saveDagbok()
        val runtime = AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        assertTrue(runtime.dashboardRows(tabShown = true).isEmpty())
        h.site.listingStatus = 403
        h.engine.run(a.id, RunTrigger.MANUAL)
        val row = runtime.dashboardRows(tabShown = true).single()
        assertEquals("autofetch", row.domain); assertEquals("warning", row.severity); assertEquals("varpid.example.fo has stopped us", row.label)
        assertEquals(1, row.count); assertEquals("fetcher", row.unit); assertEquals("open", row.fix); assertEquals("/ai?tab=fetchers", row.href)
        assertTrue("never retried on a timer" in row.sentence && "403" in row.sentence, row.sentence)
        assertEquals("Fetchers", row.action); assertEquals("AI ▸ Fetchers › varpid.example.fo", row.path)
        assertTrue(dev.jellystructure.server.routes.DashboardService.DOMAINS.any { it == ("autofetch" to "Auto-fetch") }, "its own domain chip")
    }

    @Test
    fun `a stopped site's Dashboard row opens Settings when Fetchers is hidden`() = runBlocking<Unit> {
        val a = h.saveDagbok()
        val runtime = AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        h.site.listingStatus = 403
        h.engine.run(a.id, RunTrigger.MANUAL)
        val visible = runtime.dashboardRows(tabShown = true).single()
        val hidden = runtime.dashboardRows(tabShown = false).single()
        assertEquals("/settings?tab=ai", hidden.href)
        assertEquals("Settings", hidden.action)
        assertEquals("Settings ▸ AI › Auto-fetch", hidden.path)
        assertEquals(visible.sentence, hidden.sentence)
        assertEquals(visible.count, hidden.count)
    }
}
