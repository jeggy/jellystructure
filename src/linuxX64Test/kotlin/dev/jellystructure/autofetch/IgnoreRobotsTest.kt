package dev.jellystructure.autofetch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3592 (DEB-2474, FR-2.8, FR-10.7, Acceptance 20) — a fetcher may ignore the site's `robots.txt`. The stand-in site's file has
 * `Disallow: /player/` and `Crawl-delay: 20` for the lane tests; the engine tests forbid the paths the stand-in site serves.
 */
class IgnoreRobotsLaneTest {
    private class Clock : LaneClock {
        var ms = 0L
        override fun nowSec() = 1_800_000_000L + ms / 1000
        override fun monoMs() = ms
        override suspend fun sleepMs(ms: Long) { this.ms += ms }
    }

    private class Mem : SiteStore {
        val rows = HashMap<String, SiteState>()
        override fun get(site: String) = rows[site] ?: SiteState(site)
        override fun put(state: SiteState) { rows[state.site] = state }
    }

    private class Site(val clock: Clock, var robots: String? = "User-agent: *\nDisallow: /player/\nCrawl-delay: 20\n") : Transport {
        val requests = ArrayList<Pair<String, Long>>()
        var listingStatus = 200
        override suspend fun get(url: String): HttpReply {
            requests += url to clock.ms
            if (url.endsWith("/robots.txt")) return robots?.let { HttpReply(200, emptyMap(), it) } ?: throw RuntimeException("no route")
            if (url.endsWith("/serier")) return HttpReply(listingStatus, emptyMap(), """{"items":[]}""")
            return HttpReply(200, emptyMap(), """{"items":[]}""")
        }
        fun made(path: String) = requests.any { it.first.endsWith(path) }
    }

    private val host = "varpid.example.fo"
    private val player = "https://$host/player/x"
    private val listing = "https://$host/serier"

    @Test
    fun `an unticked fetcher is refused a forbidden path and nothing is requested - a ticked one is paced 20 seconds behind the request before it`() = runBlocking<Unit> {
        val clock = Clock(); val site = Site(clock); val lanes = SiteLanes(Mem(), site, clock)
        val unticked = lanes.forSite(host)
        assertEquals("this site's robots.txt does not allow /player/x", assertIs<LaneOutcome.Forbidden>(unticked.page(player, Expect.JSON)).reason)
        assertFalse(site.made("/player/x"))

        val ticked = lanes.forSite(host, ignoreRobots = true)
        assertIs<LaneOutcome.Ok>(ticked.page(listing, Expect.JSON))
        val before = site.requests.last().second
        assertIs<LaneOutcome.Ok>(ticked.page(player, Expect.JSON))
        assertTrue(site.made("/player/x"))
        val (url, at) = site.requests.last()
        assertEquals(player, url)
        assertTrue(at - before >= 20_000, "Crawl-delay still paces a ticked fetcher: ${at - before} ms")
    }

    @Test
    fun `a ticked fetcher's request still goes ahead when robots txt cannot be read - an unticked one's does not`() = runBlocking<Unit> {
        val clock = Clock(); val site = Site(clock, robots = null); val lanes = SiteLanes(Mem(), site, clock)
        assertEquals("robots.txt could not be read, so nothing was requested", assertIs<LaneOutcome.Failed>(lanes.forSite(host).page(listing, Expect.JSON)).reason)
        assertFalse(site.made("/serier"))
        assertIs<LaneOutcome.Ok>(lanes.forSite(host, ignoreRobots = true).page(listing, Expect.JSON))
        assertTrue(site.made("/serier"))
        val times = site.requests.map { it.second }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 10_000 }, "the 10-second pace holds: $times")
    }

    @Test
    fun `two fetchers share one lane - A's request is made and B's refused - and a 403 on A's listing stops the site for B too`() = runBlocking<Unit> {
        val clock = Clock(); val site = Site(clock); val store = Mem(); val lanes = SiteLanes(store, site, clock)
        val a = lanes.forSite(host, ignoreRobots = true); val b = lanes.forSite(host)
        assertIs<LaneOutcome.Ok>(a.page(listing, Expect.JSON, listing = true))   // it has worked once
        assertIs<LaneOutcome.Ok>(a.page(player, Expect.JSON))
        assertIs<LaneOutcome.Forbidden>(b.page(player, Expect.JSON))
        site.listingStatus = 403
        assertIs<LaneOutcome.Stopped>(a.page(listing, Expect.JSON, listing = true))
        assertTrue(store.get(host).stopped)
        val sent = site.requests.size
        assertIs<LaneOutcome.Stopped>(b.page("https://$host/other", Expect.JSON))
        assertEquals(sent, site.requests.size, "B runs nothing")
    }

    @Test
    fun `robots txt coming to forbid a ticked fetcher's listing does not stop the site - an unticked fetcher's does`() = runBlocking<Unit> {
        val clock = Clock(); val site = Site(clock); val store = Mem(); val lanes = SiteLanes(store, site, clock)
        val a = lanes.forSite(host, ignoreRobots = true); val b = lanes.forSite(host)
        assertIs<LaneOutcome.Ok>(a.page(listing, Expect.JSON, listing = true))
        site.robots = "User-agent: *\nDisallow: /serier\n"
        clock.ms += 25 * 3600 * 1000L
        assertIs<LaneOutcome.Ok>(a.page(listing, Expect.JSON, listing = true))
        assertFalse(store.get(host).stopped)
        assertIs<LaneOutcome.Stopped>(b.page(listing, Expect.JSON, listing = true))
        assertTrue(store.get(host).stopped)
    }

    @Test
    fun `a program's admission and paced run follow the same tick`() = runBlocking<Unit> {
        val clock = Clock(); val site = Site(clock); val lanes = SiteLanes(Mem(), site, clock)
        val b = lanes.forSite(host)
        assertIs<LaneOutcome.Forbidden>(b.admit(player))
        assertIs<Gated.Refused>(b.pacedProgram(player, listing = false) { "ran" })
        val a = lanes.forSite(host, ignoreRobots = true)
        assertNull(a.admit(player))
        val before = site.requests.last().second
        assertEquals("ran", assertIs<Gated.Through<String>>(a.pacedProgram(player, listing = false) { clock.ms.toString(); "ran" }).value)
        assertTrue(clock.ms - before >= 20_000, "paced, Crawl-delay included")
    }

    @Test
    fun `a view of the lane shares its gates - the download slot is one for both`() = runBlocking<Unit> {
        val clock = Clock(); val lanes = SiteLanes(Mem(), Site(clock), clock)
        lanes.forSite(host).download { clock.ms += 1_000 }
        val t0 = clock.ms
        lanes.forSite(host, ignoreRobots = true).download { }
        assertTrue(clock.ms - t0 >= LaneRules.EPISODE_GAP_SEC * 1000 - 1, "60 seconds between two episodes, whoever asks")
    }
}

class IgnoreRobotsTest {
    private val h = Harness()
    private val ai = FakeAi()

    @AfterTest
    fun tearDown() = h.close()

    private fun investigator() = Investigator(
        ai, h.lanes, h.tools, h.world, RecipeProof(h.tools, h.world),
        libraries = { listOf(LibraryChoice(h.lib, "TV")) }, existing = { h.engine.existingCodes(it) }, today = { "2026-11-02" },
    )

    private fun run(ignore: Boolean) = runBlocking { investigator().run("inv1", h.dagbokUrl, null, true, null, ignore) {} }

    private val dagbokAnswer = """{"verdict":"series","reason":"A daily programme with a dated listing.","recipe":{"list":{"kind":"html","item_selector":"li.ep","fields":{"title":"a","link":"a@href","published":"time@datetime","description":"p"}},"media":{"kind":"hls","manifest":"https://media.example.fo/{value}/master.m3u8","item_page":{"pattern":"var media = '(?<value>[A-Za-z0-9._-]+)'"}},"numbering":"date","series":{"name":"h1","description":"p.about","image":"img.poster@src"},"hosts":["media.example.fo"],"audio_language":"fao"},"examples":[{"title":"Dagbók · 27. oktober","date":"2026-10-27"}]}"""

    private fun dagbokTools() {
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok/27"}""")
        ai.tool("probe", """{"url":"https://media.example.fo/DOV7327/master.m3u8"}""")
    }

    private fun forbidSendingar() { h.site.robots = "User-agent: *\nDisallow: /sendingar\nCrawl-delay: 20\n" }

    // ---- the investigation ------------------------------------------------------------------------------------------------------

    @Test
    fun `an investigation ended by a robots txt refusal pushes robotsRefused - one ended by anything else does not - and neither does a ticked one`() {
        forbidSendingar()
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        val refused = run(false).view
        assertEquals("failed", refused.state); assertTrue(refused.robotsRefused); assertFalse(refused.ignoreRobots)

        // A refusal that is not robots.txt's: the manifest carries DRM.
        h.site.robots = "User-agent: *\nDisallow: /private\n"; h.clock.advanceHours(25); h.site.drm = true; h.site.listingExtra = """<link href="https://media.example.fo/">"""
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.tool("probe", """{"url":"https://media.example.fo/DOV7327/master.m3u8"}""")
        val drm = run(false).view
        assertEquals("failed", drm.state); assertEquals(RecipeRefusal.DRM, drm.reason); assertFalse(drm.robotsRefused)

        // Ticked, the same forbidding file stops nothing: the investigation reaches its review.
        h.site.drm = false; forbidSendingar(); h.clock.advanceHours(25)
        dagbokTools(); ai.answer(dagbokAnswer)
        val ticked = run(true)
        assertEquals("review", ticked.view.state, ticked.view.reason)
        assertTrue(ticked.view.ignoreRobots); assertFalse(ticked.view.robotsRefused)
    }

    @Test
    fun `a failed ticked investigation says so in its bug report - an unticked one does not`() {
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        val ticked = assertNotNull(run(true).view.report).body
        assertTrue("\nFetcher: page only · robots.txt ignored\nServer: " in ticked, ticked)

        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        ai.answer("""{"verdict":"not_a_series","reason":"x"}""")
        val plain = assertNotNull(run(false).view.report).body
        assertTrue("\nFetcher: page only\nServer: " in plain, plain)
        assertFalse("robots.txt ignored" in plain)
    }

    // ---- the registry and the routes' contracts ---------------------------------------------------------------------------------

    private fun registry(): Investigations {
        var ids = 0
        return Investigations(ai, investigator(), h.service, h.store, h.world, CoroutineScope(Dispatchers.Default)) { "inv${++ids}" }
    }

    private suspend fun awaitDone(r: Investigations): InvestigationView {
        repeat(2000) { val v = r.view(); if (v != null && v.state != "running") return v; kotlinx.coroutines.delay(10) }
        error("never finished: ${r.view()}")
    }

    @Test
    fun `saving from a ticked investigation stores the tick - whatever the save says - and an unticked one stores none`() = runBlocking<Unit> {
        forbidSendingar()
        val r = registry()
        h.site.listingExtra = """<link rel="preload" href="https://media.example.fo/">"""
        dagbokTools(); ai.answer(dagbokAnswer)
        val id = (r.start(InvestigateRequest(h.dagbokUrl, null, true, ignoreRobots = true)) as StartResult.Started).view.also { assertTrue(it.ignoreRobots) }.id
        val v = awaitDone(r)
        assertEquals("review", v.state, v.reason); assertTrue(v.ignoreRobots)
        val saved = r.save(id, InvestigationSave(runFirst = false)) as InvestigationSaveResult.Saved
        assertTrue(saved.fetcher.ignoreRobots); assertTrue(h.store.fetcher(saved.fetcher.id)!!.ignoreRobots)
    }

    @Test
    fun `re-investigate page takes the fetcher's setting and ignores the request's`() = runBlocking<Unit> {
        val r = registry()
        val f = (h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe), ignoreRobots = true) as SaveResult.Saved).fetcher
        assertTrue(f.ignoreRobots)
        h.site.listingExtra = """<link rel="preload" href="https://media.example.fo/">"""
        forbidSendingar(); h.clock.advanceHours(25)
        dagbokTools(); ai.answer(dagbokAnswer)
        val started = (r.start(InvestigateRequest(fetcherId = f.id, ignoreRobots = false)) as StartResult.Started).view
        assertTrue(started.ignoreRobots, "the fetcher's tick, not the body's")
        assertEquals("review", awaitDone(r).state)

        h.store.setIgnoreRobots(f.id, false)
        r.discard(r.view()!!.id)
        ai.tool("fetch", """{"url":"https://varpid.example.fo/sendingar/dagbok"}""")
        val again = (r.start(InvestigateRequest(fetcherId = f.id, ignoreRobots = true)) as StartResult.Started).view
        assertFalse(again.ignoreRobots)
        val done = awaitDone(r)
        assertEquals("failed", done.state); assertTrue(done.robotsRefused)
    }

    @Test
    fun `an unticked PATCH binds the fetcher's next request to robots txt again - and the list carries the tick`() = runBlocking<Unit> {
        val f = (h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe), ignoreRobots = true) as SaveResult.Saved).fetcher
        forbidSendingar(); h.clock.advanceHours(25)
        assertEquals("ok", h.engine.run(f.id, RunTrigger.MANUAL)!!.status, "ticked: the forbidden listing is read")
        assertNull(h.service.update(f.id, null, null, null, ignoreRobots = false))
        assertFalse(h.store.fetcher(f.id)!!.ignoreRobots)
        h.clock.advanceHours(25)
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertTrue(h.store.sites().single().stopped, "unticked: robots.txt forbidding the listing stops the site (FR-2.5)")
        assertNotNull(r)
        assertNull(h.service.update(f.id, null, null, null, ignoreRobots = true))
        assertTrue(h.store.fetcher(f.id)!!.ignoreRobots)
    }

    // ---- yt-dlp -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a ticked fetcher's yt-dlp item link under a forbidden path is admitted and paced - an unticked fetcher's is refused`() = runBlocking<Unit> {
        h.site.robots = "User-agent: *\nDisallow: /sendingar/dagbok/\nCrawl-delay: 20\n"
        val recipe = h.dagbokRecipe.copy(list = ListSpec(kind = "ytdlp"), media = MediaSpec(kind = "ytdlp"))
        val refused = assertIs<SaveResult.Refused>(h.service.save(SaveRequest(h.dagbokUrl, null, true, recipe)))
        assertTrue("robots.txt does not allow" in refused.reason, refused.reason)
        assertTrue(h.tools.ytdlpInfos.isEmpty())

        val f = (h.service.save(SaveRequest(h.dagbokUrl, null, true, recipe), ignoreRobots = true) as SaveResult.Saved).fetcher
        assertEquals("https://varpid.example.fo/sendingar/dagbok/27", h.tools.ytdlpInfos.single(), "the forbidden item link was admitted")
        val run = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("ok", run.status, run.lines.toString())
        assertEquals(12, run.placed)
        val reads = h.site.requests.filter { it.first.endsWith("/robots.txt") }.size
        assertEquals(1, reads, "robots.txt is still read once")
    }

    @Test
    fun `the view and the wire carry the tick`() = runBlocking<Unit> {
        val f = (h.service.save(SaveRequest(h.dagbokUrl, null, true, h.dagbokRecipe), ignoreRobots = true) as SaveResult.Saved).fetcher
        val rt = AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, CoroutineScope(Dispatchers.Default))
        val json = Json { encodeDefaults = true }
        val view = json.parseToJsonElement(json.encodeToString(FetcherView.serializer(), rt.view(f))).jsonObject
        assertEquals("true", view["ignoreRobots"]!!.jsonPrimitive.content)
        val plain = (h.service.save(SaveRequest("https://varpid.example.fo/sendingar/royndin", 9000001, false, h.royndinRecipe)) as SaveResult.Saved).fetcher
        assertEquals("false", json.parseToJsonElement(json.encodeToString(FetcherView.serializer(), rt.view(plain))).jsonObject["ignoreRobots"]!!.jsonPrimitive.content)
    }
}
