package dev.jellystructure.autofetch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2479 (DEB-2474, FR-4.3, FR-10.2, FR-12) — mapped numbering, against the stand-in site: the model's proposed table held against the listing and
 * TMDB, the ticks that approve it, a new item that waits, the number the admin gives, and *Propose*. Stand-in corpus only.
 */
class MappedNumberingTest {
    private val h = Harness()

    @AfterTest
    fun tearDown() = h.close()

    private val url = "https://varpid.example.fo/sendingar/royndin"
    private val mappedRecipe get() = h.royndinRecipe.copy(numbering = NumberingSpec("mapped"), pattern = emptyList())

    private fun tmdb(vararg n: Int) {
        h.world.tmdbEps = n.map { TmdbEpisodeFact(1, it, listOf("", "Fyrsti", "Annar", "Triðji", "Fjórði")[it], "2026-0$it-12", "Lýsing $it") }
    }

    private fun row(n: Int, season: Int = 1, episode: Int = n) =
        MapRow("/sendingar/royndin/$n", "Royndin 2026 · $n. partur", season, episode, "same part $n")

    private fun save(accept: List<String>, mapping: List<MapRow> = listOf(row(1), row(2), row(3))): Fetcher = runBlocking {
        when (val r = h.service.save(SaveRequest(url, 9000001, false, mappedRecipe, mapping = mapping, accept = accept))) {
            is SaveResult.Saved -> r.fetcher
            is SaveResult.Refused -> error("save refused: ${r.reason}")
        }
    }

    private fun refused(mapping: List<MapRow>): String = runBlocking {
        (h.service.save(SaveRequest(url, 9000001, false, mappedRecipe, mapping = mapping, accept = emptyList())) as SaveResult.Refused).reason
    }

    private fun run(f: Fetcher) = runBlocking { h.engine.run(f.id, RunTrigger.MANUAL)!! }

    // ---- the proof of a proposal ---------------------------------------------------------------------------------------------

    @Test
    fun `a proposal is held against the listing and tmdb and never proven`() = runBlocking<Unit> {
        tmdb(1, 2, 3)
        val lane = h.lanes.forSite("varpid.example.fo")
        val pr = RecipeProof(h.tools, h.world).prove(mappedRecipe, false, 9000001, lane, listOf(row(1), row(2)))
        assertTrue(pr.ok, pr.failure); assertTrue(pr.mapped); assertFalse(pr.proven)
        assertEquals(3, pr.items.size, "every listed item stays a row")
        assertEquals(listOf("S01E01", "S01E02"), pr.numbered.map { Numbering.code(it.second) })
        assertEquals("same part 1", pr.whys["R1"])
    }

    @Test
    fun `a row finds its item by link then id then a title only one item has and a row naming nothing is dropped`() = runBlocking<Unit> {
        tmdb(1, 2, 3)
        val items = listOf(
            ListedItem(title = "Fyrsti", link = "https://varpid.example.fo/a", id = "A1"), ListedItem(title = "Annar", link = "https://varpid.example.fo/b", id = "B2"),
            ListedItem(title = "Sama", link = "https://varpid.example.fo/c", id = "C3"), ListedItem(title = "sama", link = "https://varpid.example.fo/d", id = "D4"),
        )
        val got = RecipeProof(h.tools, h.world).resolveMapping(
            url, items, listOf(
                MapRow("/a", "x", 1, 1), MapRow("B2", "x", 1, 2), MapRow("", "Sama", 1, 3), MapRow("/nowhere", "Nothing", 1, 4), MapRow("/a", "x", 1, 4),
            ),
        )
        assertEquals(listOf("A1", "B2"), got.map { itemKeyOf(it.first) }, "link, then id; a title two items share names neither; the item a row took is not taken twice")
    }

    @Test
    fun `a proposal naming an episode tmdb lacks or one number twice or nothing is refused with its reason`() {
        tmdb(1, 2)
        assertTrue("which TMDB does not have" in refused(listOf(row(1), row(3, 1, 9))), "S01E09")
        assertTrue("both map to S01E01" in refused(listOf(row(1), row(2, 1, 1))))
        assertTrue("proposal maps no item" in refused(listOf(MapRow("/nowhere", "Nothing", 1, 1))))
        assertTrue("proposal maps no item" in refused(emptyList()))
        assertTrue(h.store.fetchers().isEmpty(), "nothing is written")
    }

    @Test
    fun `a page-only fetcher is not offered mapped numbering`() {
        val r = mappedRecipe.copy(series = SeriesSpec(name = Field("h1")))
        assertTrue(RecipeRefusal.MAPPED_PAGE_ONLY in RecipeValidator.validate(r, pageOnly = true))
    }

    // ---- save, run, wait -----------------------------------------------------------------------------------------------------

    @Test
    fun `only the ticked rows are numbered and the others wait`() {
        tmdb(1, 2, 3)
        val f = save(accept = listOf("R1"))
        val items = h.store.items(f.id).associateBy { it.itemId }
        assertEquals(SeasonEpisode(1, 1), items.getValue("R1").approved)
        assertNull(items.getValue("R2").approved); assertNull(items.getValue("R2").season, "a proposal is not a number")
        assertEquals("waiting", items.getValue("R2").state); assertEquals("waiting", items.getValue("R3").state)

        val r = run(f)
        assertEquals("+1", r.summary.substringBefore(' '), r.summary)
        assertEquals(listOf("Season 01/Royndin - S01E01 - Royndin 2026 · 1. partur.mkv"), h.store.items(f.id).mapNotNull { it.placedPath }.map { it.removePrefix(f.seriesPath + "/") })
        assertEquals(2, r.lines.count { it.kind == "waiting" && "waiting for a number" in it.text }, r.lines.toString())
        assertEquals(1, h.tools.downloads.size, "no download for an item with no number")
        assertEquals(setOf("R2", "R3"), AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, CoroutineScope(Dispatchers.Default)).view(f).waiting.map { it.itemId }.toSet())
    }

    @Test
    fun `saving with nothing ticked stores no number and every item waits`() {
        tmdb(1, 2, 3)
        val f = save(accept = emptyList())
        assertTrue(h.store.items(f.id).all { it.approved == null && it.state == "waiting" })
        val r = run(f)
        assertEquals("no new episodes", r.summary); assertEquals(0, h.tools.downloads.size)
    }

    @Test
    fun `a new item on the page is not downloaded until the admin gives it a number`() {
        tmdb(1, 2, 3, 4)
        val f = save(accept = listOf("R1", "R2", "R3"))
        run(f)
        assertEquals(3, h.tools.downloads.size)
        h.site.royndinParts = listOf(1, 2, 3, 4)
        val r = run(f)
        assertEquals(3, h.tools.downloads.size, "the fourth is not fetched")
        assertTrue(r.lines.any { it.kind == "waiting" && "Royndin 2026 · 4. partur: waiting for a number" in it.text }, r.lines.toString())
        assertEquals("waiting", h.store.item(f.id, "R4")!!.state)
    }

    @Test
    fun `the number the admin gives is what the next run downloads under`() = runBlocking<Unit> {
        tmdb(1, 2, 3, 4)
        val f = save(accept = listOf("R1", "R2", "R3"))
        run(f); h.site.royndinParts = listOf(1, 2, 3, 4); run(f)

        val ok = h.service.approveNumber(f.id, "R4", 1, 4)
        assertTrue(ok is NumberResult.Approved, ok.toString())
        assertEquals("new", h.store.item(f.id, "R4")!!.state, "no longer waiting")
        val r = run(f)
        assertTrue(r.summary.startsWith("+1 · S01E04"), r.summary)
        assertTrue(h.store.item(f.id, "R4")!!.placedPath!!.endsWith("Royndin - S01E04 - Royndin 2026 · 4. partur.mkv"))
        assertEquals(4, h.tools.downloads.size)
    }

    @Test
    fun `a number another item has or a file already holds or out of range is refused and an unknown item is not found`() = runBlocking<Unit> {
        tmdb(1, 2, 3)
        val f = save(accept = listOf("R1"))
        run(f)
        suspend fun status(id: String, s: Int, e: Int) = (h.service.approveNumber(f.id, id, s, e) as NumberResult.Refused).let { it.status to it.reason }
        assertEquals(409, status("R2", 1, 1).first, "R1 has S01E01")
        assertTrue("S01E01" in status("R2", 1, 1).second)
        assertEquals(422, status("R2", 1, 10_000).first); assertEquals(422, status("R2", -1, 1).first)
        assertEquals(404, status("nope", 1, 2).first)
        assertEquals(409, status("R1", 1, 5).first, "R1 is in the library already")
        assertEquals(404, (h.service.approveNumber("nope", "R2", 1, 2) as NumberResult.Refused).status)
        // A file the series folder already holds under that number.
        val dir = "${f.seriesPath}/Season 01"
        platform.posix.system("mkdir -p '$dir' && : > '$dir/Royndin - S01E07 - by hand.mkv'")
        assertEquals(409, status("R3", 1, 7).first)
        assertTrue(h.store.item(f.id, "R2")!!.approved == null && h.store.item(f.id, "R3")!!.approved == null, "a refusal writes nothing")
    }

    @Test
    fun `an unproven source fetcher waits for a number on an episode tmdb lacks and downloads it once the admin gives one`() = runBlocking<Unit> {
        tmdb(1)
        h.world.tmdbEps = listOf(TmdbEpisodeFact(2026, 1, "Fyrsti", "2026-01-12"))
        val f = (h.service.save(SaveRequest(url, 9000001, false, h.royndinRecipe)) as SaveResult.Saved).fetcher
        assertFalse(f.proven)
        val r1 = run(f)
        assertEquals(1, h.tools.downloads.size, r1.summary)
        assertEquals(2, r1.lines.count { it.kind == "waiting" && "not proven against TMDB" in it.text })
        assertEquals(2, AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, CoroutineScope(Dispatchers.Default)).view(f).waiting.size)

        assertTrue(h.service.approveNumber(f.id, "R2", 2026, 2) is NumberResult.Approved)
        run(f)
        assertEquals(2, h.tools.downloads.size, "the approved number outranks TMDB's lack")
        assertTrue(h.store.item(f.id, "R2")!!.placedPath!!.contains("S2026E02"))
        assertEquals(listOf("R3"), AutofetchRuntime(h.store, h.engine, h.service, h.lanes, h.world, CoroutineScope(Dispatchers.Default)).view(f).waiting.map { it.itemId })
    }

    @Test
    fun `a recipe that stops mapping drops the approvals of items not placed`() = runBlocking<Unit> {
        tmdb(1, 2, 3)
        val f = save(accept = listOf("R1", "R2"))
        run(f)   // R1 and R2 are placed under their approved numbers
        h.store.setApproved(f.id, "R3", SeasonEpisode(1, 3))
        val back = h.service.replaceRecipe(f.id, h.royndinRecipe)
        assertTrue(back is SaveResult.Saved, back.toString())
        assertNull(h.store.item(f.id, "R3")!!.approved, "R3 was not placed: its approval goes")
        assertEquals(SeasonEpisode(1, 1), h.store.item(f.id, "R1")!!.approved, "a placed item keeps its number")
    }

    @Test
    fun `a mapped fetcher shows in its recipe lines and the table is not in the recipe`() {
        tmdb(1, 2, 3)
        val f = save(accept = listOf("R1"))
        assertFalse("R1" in f.recipeJson, "the recipe holds no table")
        val lines = RecipeView.lines(f, f.recipe!!)
        assertTrue(lines.any { it.key == "numbering" && it.text == "mapped" && "only the rows you accepted" in it.comment })
        assertNotNull(f.recipe)
    }
}
