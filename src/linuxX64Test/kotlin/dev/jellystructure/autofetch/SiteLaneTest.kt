package dev.jellystructure.autofetch

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** DEB-2476 (FR-2) — the lane, on a clock the test advances. Acceptance 4 and 5. */
class SiteLaneTest {
    private class FakeClock : LaneClock {
        var ms = 0L
        var wall = 1_700_000_000L
        override fun nowSec() = wall + ms / 1000
        override fun monoMs() = ms
        override suspend fun sleepMs(ms: Long) { this.ms += ms }
    }

    private class MemStore : SiteStore {
        val rows = HashMap<String, SiteState>()
        override fun get(site: String) = rows[site] ?: SiteState(site)
        override fun put(state: SiteState) { rows[state.site] = state }
    }

    private class Site(val clock: FakeClock, var robots: String = "User-agent: *\nDisallow: /private\n") : Transport {
        val requests = ArrayList<Pair<String, Long>>()
        var listing: (Int) -> HttpReply = { HttpReply(200, emptyMap(), """{"items":[]}""") }
        var n = 0
        override suspend fun get(url: String): HttpReply {
            requests += url to clock.ms
            if (url.endsWith("/robots.txt")) return HttpReply(200, emptyMap(), robots)
            return listing(n++)
        }
    }

    private fun lane(site: Site, clock: FakeClock, store: MemStore = MemStore()) = SiteLane("varpid.example.fo", store, site, clock)
    private val url = "https://varpid.example.fo/sendingar/dagbok"

    @Test
    fun `requests are never closer than 10 seconds`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val l = lane(site, clock)
        repeat(4) { assertIs<LaneOutcome.Ok>(l.page(url, Expect.JSON, listing = true)) }
        val times = site.requests.map { it.second }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 10_000 }, times.toString())
    }

    @Test
    fun `a Crawl-delay above 10 seconds is honoured`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock, "User-agent: *\nCrawl-delay: 30\n"); val l = lane(site, clock)
        repeat(3) { l.page(url, Expect.JSON) }
        val times = site.requests.map { it.second }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 30_000 }, times.toString())
    }

    @Test
    fun `robots txt is read once a day`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val l = lane(site, clock)
        repeat(3) { l.page(url, Expect.JSON) }
        assertEquals(1, site.requests.count { it.first.endsWith("/robots.txt") })
        clock.ms += 25 * 3600 * 1000L
        l.page(url, Expect.JSON)
        assertEquals(2, site.requests.count { it.first.endsWith("/robots.txt") })
    }

    @Test
    fun `a path robots txt forbids is never requested`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val l = lane(site, clock)
        val out = l.page("https://varpid.example.fo/private/x", Expect.HTML)
        assertEquals("this site's robots.txt does not allow /private/x", assertIs<LaneOutcome.Forbidden>(out).reason)
        assertFalse(site.requests.any { it.first.endsWith("/private/x") })
    }

    @Test
    fun `an answer from another host is the lane's only when the recipe records that host`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val l = lane(site, clock)
        val edge = "https://edge2.example.fo/x/playlist.m3u8"
        site.listing = { HttpReply(200, emptyMap(), "#EXTM3U", finalUrl = edge) }
        val ok = assertIs<LaneOutcome.Ok>(l.page(url, Expect.MANIFEST, player = true, hosts = setOf("varpid.example.fo", "edge2.example.fo")))
        assertEquals(edge, ok.reply.finalUrl)
        val refused = assertIs<LaneOutcome.Failed>(l.page(url, Expect.MANIFEST, player = true, hosts = setOf("varpid.example.fo")))
        assertEquals(310, refused.status)
        // the same host answering after a redirect inside the site needs no entry
        site.listing = { HttpReply(200, emptyMap(), "#EXTM3U", finalUrl = "https://varpid.example.fo/other") }
        assertIs<LaneOutcome.Ok>(l.page(url, Expect.MANIFEST, player = true))
    }

    @Test
    fun `a rule that names only AI agents does not bind`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock, "User-agent: ClaudeBot\nDisallow: /\n\nUser-agent: *\nDisallow: /private\n")
        assertIs<LaneOutcome.Ok>(lane(site, clock).page(url, Expect.JSON))
        Unit
    }

    @Test
    fun `robots newly forbidding the listing stops the site`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val store = MemStore(); val l = lane(site, clock, store)
        assertIs<LaneOutcome.Ok>(l.page(url, Expect.JSON, listing = true))
        site.robots = "User-agent: *\nDisallow: /sendingar\n"
        clock.ms += 25 * 3600 * 1000L
        assertIs<LaneOutcome.Stopped>(l.page(url, Expect.JSON, listing = true))
        assertTrue(store.get("varpid.example.fo").stopped)
    }

    @Test
    fun `a 429 with Retry-After makes the lane wait that long`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val l = lane(site, clock)
        site.listing = { HttpReply(429, mapOf("Retry-After" to "120"), "") }
        val out = assertIs<LaneOutcome.BackOff>(l.page(url, Expect.JSON))
        assertEquals(clock.nowSec() + 120, out.untilSec)
        site.requests.clear()
        assertIs<LaneOutcome.BackOff>(l.page(url, Expect.JSON))
        assertTrue(site.requests.isEmpty(), "nothing is requested while the lane waits")
        clock.ms += 121_000
        site.listing = { HttpReply(200, emptyMap(), "{}") }
        assertIs<LaneOutcome.Ok>(l.page(url, Expect.JSON))
    }

    @Test
    fun `without Retry-After the wait is 1 hour then 4 then 24`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val store = MemStore(); val l = lane(site, clock, store)
        site.listing = { HttpReply(503, emptyMap(), "") }
        val waits = ArrayList<Long>()
        repeat(3) {
            val o = assertIs<LaneOutcome.BackOff>(l.page(url, Expect.JSON))
            waits += o.untilSec - clock.nowSec()
            clock.ms += (o.untilSec - clock.nowSec() + 1) * 1000
        }
        assertEquals(listOf(3600L, 4 * 3600L, 24 * 3600L), waits)
    }

    @Test
    fun `a third 429 in one day stops the site`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val store = MemStore(); val l = lane(site, clock, store)
        site.listing = { HttpReply(429, mapOf("Retry-After" to "1"), "") }
        assertIs<LaneOutcome.BackOff>(l.page(url, Expect.JSON)); clock.ms += 5_000
        assertIs<LaneOutcome.BackOff>(l.page(url, Expect.JSON)); clock.ms += 5_000
        assertIs<LaneOutcome.Stopped>(l.page(url, Expect.JSON))
        assertTrue(store.get("varpid.example.fo").stopped)
    }

    @Test
    fun `a 403 on a page that worked stops the site until it is resumed`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val store = MemStore(); val l = lane(site, clock, store)
        assertIs<LaneOutcome.Ok>(l.page(url, Expect.JSON, listing = true))
        site.listing = { HttpReply(403, emptyMap(), "denied") }
        assertIs<LaneOutcome.Stopped>(l.page(url, Expect.JSON, listing = true))
        site.requests.clear()
        assertIs<LaneOutcome.Stopped>(l.page(url, Expect.JSON, listing = true))
        assertTrue(site.requests.isEmpty(), "no request reaches a stopped site")
        l.resume()
        site.listing = { HttpReply(403, emptyMap(), "denied") }
        assertIs<LaneOutcome.Stopped>(l.page(url, Expect.JSON, listing = true))
        assertEquals(1, site.requests.count { !it.first.endsWith("robots.txt") }, "Resume site makes one probe and goes back to stopped")
    }

    @Test
    fun `a first ever 403 fails the request without stopping the site`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val store = MemStore(); val l = lane(site, clock, store)
        site.listing = { HttpReply(403, emptyMap(), "denied") }
        assertIs<LaneOutcome.Failed>(l.page(url, Expect.JSON))
        assertFalse(store.get("varpid.example.fo").stopped)
    }

    @Test
    fun `an HTML answer where JSON was expected is a challenge`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val store = MemStore(); val l = lane(site, clock, store)
        site.listing = { HttpReply(200, emptyMap(), "<!DOCTYPE html><html><title>Just a moment...</title></html>") }
        assertIs<LaneOutcome.Stopped>(l.page(url, Expect.JSON))
        assertTrue(store.get("varpid.example.fo").stopped)
    }

    @Test
    fun `downloads run one at a time with 60 seconds between episodes`() = runBlocking<Unit> {
        val clock = FakeClock(); val site = Site(clock); val l = lane(site, clock)
        val starts = ArrayList<Long>()
        repeat(3) { l.download { starts += clock.ms; clock.ms += 5_000 } }
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a >= 60_000 + 5_000 }, starts.toString())
    }

    @Test
    fun `robots matching prefers the longest rule and honours wildcards`() {
        val r = Robots.parse("User-agent: *\nDisallow: /a\nAllow: /a/ok\nDisallow: /*.json\$\n")
        assertFalse(r.allows("/a/no")); assertTrue(r.allows("/a/ok/x")); assertFalse(r.allows("/x/feed.json")); assertTrue(r.allows("/x/feed.jsonp"))
    }
}
