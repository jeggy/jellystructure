package dev.jellystructure.autofetch

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** DEB-2480 (DEB-2474, FR-6.3) — a `ytdlp` recipe through the proof, the lane and a run, against the stand-in site and a fake yt-dlp. */
class YtDlpEngineTest {
    private val h = Harness()

    @AfterTest
    fun tearDown() = h.close()

    /** Dagbók's page is read for the programme's name (the `series` block); its episodes come from yt-dlp's flat playlist and are downloaded by it. */
    private val recipe get() = h.dagbokRecipe.copy(
        list = ListSpec(kind = "ytdlp"),
        media = MediaSpec(kind = "ytdlp"),
    )

    private suspend fun save(r: Recipe = recipe): Fetcher = when (val s = h.service.save(SaveRequest(h.dagbokUrl, null, true, r))) {
        is SaveResult.Saved -> s.fetcher
        is SaveResult.Refused -> error("save refused: ${s.reason}")
    }

    @Test
    fun `a ytdlp recipe is proven through yt-dlp's listing and one item's metadata and saved`() = runBlocking<Unit> {
        val f = save()
        assertEquals("Dagbók", f.name)
        assertEquals(listOf(h.dagbokUrl), h.tools.ytdlpListings.take(1), "the listing is yt-dlp's flat playlist of the source page")
        assertEquals(1, h.tools.ytdlpInfos.size, "one item is probed")
        assertEquals("https://varpid.example.fo/sendingar/dagbok/27", h.tools.ytdlpInfos.single())
        assertTrue(h.tools.probed.isEmpty(), "no manifest was probed")
    }

    @Test
    fun `a run downloads each missing episode with yt-dlp and on the lane's download slot and and asks for no manifest`() = runBlocking<Unit> {
        val f = save()
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("ok", r.status)
        assertEquals(12, r.placed)
        assertEquals(12, h.tools.ytdlpDownloads.size)
        assertTrue(h.tools.ytdlpDownloads.all { it.link.startsWith("https://varpid.example.fo/sendingar/dagbok/") && it.audioLang == "fao" && it.outPath.endsWith(".mkv") && "/.jellystructure/" in it.outPath })
        assertTrue(h.site.nonRobots().none { "media.example.fo" in it }, "no manifest request: ${h.site.nonRobots()}")
        assertFalse(h.tools.overlapping)
        val gaps = h.tools.downloads.zipWithNext { a, b -> b.second - a.second }
        assertTrue(gaps.all { it >= LaneRules.EPISODE_GAP_SEC * 1000 }, "60 s between two episodes: $gaps")
        assertEquals(12, h.world.announced.count { it.second == "Created" })
        assertTrue(h.files(f).first().startsWith("Dagbók - "), h.files(f).toString())
    }

    @Test
    fun `a second run reads only the listing and downloads nothing`() = runBlocking<Unit> {
        val f = save()
        h.engine.run(f.id, RunTrigger.MANUAL)
        h.tools.ytdlpDownloads.clear(); h.tools.ytdlpInfos.clear(); h.tools.ytdlpListings.clear()
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, r.placed)
        assertEquals(1, h.tools.ytdlpListings.size); assertTrue(h.tools.ytdlpDownloads.isEmpty())
    }

    @Test
    fun `yt-dlp's last line about a subtitle reaches the run line`() = runBlocking<Unit> {
        val f = save()
        h.tools.downloadNote = "no subtitle: yt-dlp could not embed it: Postprocessing: x"
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertTrue(r.lines.filter { it.kind == "placed" }.all { it.text.endsWith("· no subtitle: yt-dlp could not embed it: Postprocessing: x") })
    }

    @Test
    fun `DRM from yt-dlp refuses the item with the FR-3-3 sentence and the rest carry on`() = runBlocking<Unit> {
        val f = save()
        h.tools.ytdlpFail = ToolResult(false, RecipeRefusal.DRM, drm = true)
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, r.placed)
        assertTrue(r.lines.any { it.text.endsWith(RecipeRefusal.DRM) })
        assertTrue(h.store.items(f.id).all { it.state == "refused" || it.state == "new" }, h.store.items(f.id).map { it.state }.toString())
        assertTrue(h.store.items(f.id).any { it.state == "refused" })
    }

    @Test
    fun `a 429 yt-dlp reports backs the lane off and the run waits`() = runBlocking<Unit> {
        val f = save()
        h.tools.ytdlpFail = ToolResult(false, "yt-dlp: HTTP Error 429: Too Many Requests", status = 429)
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, r.placed)
        assertNotNull(h.lanes.forSite("varpid.example.fo").state().backoffUntil)
        assertTrue(r.lines.any { it.kind == "stopped" && it.text.startsWith("waiting") }, r.lines.toString())
        assertEquals(1, h.tools.ytdlpDownloads.size, "nothing more is asked of a site that said slow down")
    }

    @Test
    fun `an ordinary yt-dlp failure counts toward the three and names yt-dlp's last line`() = runBlocking<Unit> {
        val f = save()
        h.tools.ytdlpFail = ToolResult(false, "yt-dlp: Video unavailable")
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertTrue(r.lines.any { it.kind == "failed" && "the download failed: yt-dlp: Video unavailable" in it.text && "retried next run" in it.text }, r.lines.toString())
    }

    @Test
    fun `a link on a host the recipe does not record is skipped and yt-dlp is never started for it`() = runBlocking<Unit> {
        val f = save()
        h.tools.ytdlpEntries = listOf(listOf("N27", "https://elsewhere.example.fo/v/27", "Dagbók · 27. oktober", "20261027")) + h.tools.ytdlpEntries.drop(1)
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertTrue(r.lines.any { it.kind == "skipped" && "host the recipe records" in it.text }, r.lines.toString())
        assertTrue(h.tools.ytdlpDownloads.none { "elsewhere" in it.link })
    }

    @Test
    fun `robots txt that forbids an item's link keeps yt-dlp away from it`() = runBlocking<Unit> {
        val f = save()
        h.site.robots = "User-agent: *\nDisallow: /sendingar/dagbok/\n"
        h.clock.advanceHours(25) // robots.txt is read once a day
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, r.placed)
        assertTrue(h.tools.ytdlpDownloads.isEmpty())
        assertTrue(r.lines.any { it.kind == "failed" && "robots.txt does not allow" in it.text }, r.lines.toString())
    }

    @Test
    fun `a listing yt-dlp cannot read is a refusal at save and says why`() = runBlocking<Unit> {
        h.tools.ytdlpListingOut = YtDlpOutput(false, message = YtDlpTexts.NO_EXTRACTOR)
        val r = assertIs<SaveResult.Refused>(h.service.save(SaveRequest(h.dagbokUrl, null, true, recipe)))
        assertTrue(YtDlpTexts.NO_EXTRACTOR in r.reason, r.reason)
        assertTrue(h.store.fetchers().isEmpty())
    }

    @Test
    fun `a listing answered 429 by yt-dlp slows the lane the way a page request would`() = runBlocking<Unit> {
        h.tools.ytdlpListingOut = YtDlpOutput(false, message = "yt-dlp: HTTP Error 429", status = 429)
        assertIs<SaveResult.Refused>(h.service.save(SaveRequest(h.dagbokUrl, null, true, recipe)))
        assertNotNull(h.lanes.forSite("varpid.example.fo").state().backoffUntil)
    }

    @Test
    fun `an item whose every format is DRM fails the proof with the FR-3-3 sentence`() = runBlocking<Unit> {
        h.tools.ytdlpInfoOut = YtDlpOutput(true, body = """{"duration":1800,"formats":[{"vcodec":"avc1","has_drm":true}]}""")
        val r = assertIs<SaveResult.Refused>(h.service.save(SaveRequest(h.dagbokUrl, null, true, recipe)))
        assertTrue(RecipeRefusal.DRM in r.reason, r.reason)
    }

    @Test
    fun `an item with no video format fails the proof`() = runBlocking<Unit> {
        h.tools.ytdlpInfoOut = YtDlpOutput(true, body = """{"duration":1800,"formats":[{"vcodec":"none"}]}""")
        val r = assertIs<SaveResult.Refused>(h.service.save(SaveRequest(h.dagbokUrl, null, true, recipe)))
        assertTrue("no video variant" in r.reason, r.reason)
    }

    @Test
    fun `a binding without yt-dlp says it is not installed and nothing else is asked`() = runBlocking<Unit> {
        val bare = object : MediaTools by h.tools {
            override suspend fun ytdlpListing(url: String) = YtDlpOutput.NOT_INSTALLED
        }
        val lane = h.lanes.forSite("varpid.example.fo")
        val read = ListingFetcher.read(recipe, lane, bare)
        val blocked = assertIs<ListingRead.Blocked>(read)
        assertEquals(LaneOutcome.Failed(YtDlpTexts.NOT_INSTALLED), blocked.outcome)
        assertIs<ListingRead.Blocked>(ListingFetcher.read(recipe, lane, null))
    }

    @Test
    fun `the listing is paced as one page request and yt-dlp's date numbers the episodes`() = runBlocking<Unit> {
        val f = save()
        val items = h.store.items(f.id)
        assertTrue(items.isNotEmpty())
        val read = ListingFetcher.read(recipe, h.lanes.forSite("varpid.example.fo"), h.tools) as ListingRead.Read
        assertEquals("2026-10-27", Numbering.isoDate(read.items.first().published))
        assertEquals(12, read.items.size); assertTrue(read.full)
    }
}
