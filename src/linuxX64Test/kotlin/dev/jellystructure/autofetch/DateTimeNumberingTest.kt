package dev.jellystructure.autofetch

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** DEB-3567 (DEB-2474 FR-4.5, amended 2026-11-04) — `numbering: datetime` on `Kvøldbók`, a stand-in programme that airs more than once a day. */
class DateTimeNumberingTest {
    private val h = Harness()

    @AfterTest
    fun tearDown() = h.close()

    private fun refused(r: SaveResult) = assertIs<SaveResult.Refused>(r)

    private fun season(f: Fetcher, n: Int) = "${f.seriesPath}/Season $n"

    @Test
    fun `the listing saves under datetime and a run places five items under two seasons in air order`() = runBlocking<Unit> {
        val f = h.saveKvoldbok(RetentionSpec("all"))
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("ok", r.status); assertEquals(5, r.placed)
        assertTrue(h.onDisk("${season(f, 2644)}/Kvøldbók - S2644E808 - 2026-11-03 18.30 - Kvøldbók · 3.mkv"), h.files(f).toString())
        assertTrue(h.onDisk("${season(f, 2644)}/Kvøldbók - S2644E823 - 2026-11-03 21.00 - Kvøldbók · 4.mkv"), h.files(f).toString())
        assertTrue(h.onDisk("${season(f, 2644)}/Kvøldbók - S2644E736 - 2026-11-03 06.00 - Kvøldbók · 2.mkv"), h.files(f).toString())
        assertTrue(h.onDisk("${season(f, 2645)}/Kvøldbók - S2645E036 - 2026-11-05 06.00 - Kvøldbók · 5.mkv"), h.files(f).toString())
        // Seasons sort by week, episodes by day and time: sorting by number is sorting by air time.
        val byNumber = store(f).sortedWith(compareBy({ it.season }, { it.episode })).map { it.title }
        assertEquals(listOf("Kvøldbók · 1", "Kvøldbók · 2", "Kvøldbók · 3", "Kvøldbók · 4", "Kvøldbók · 5"), byNumber)
        assertEquals(5, h.store.items(f.id).map { it.season to it.episode }.toSet().size)
    }

    private fun store(f: Fetcher) = h.store.items(f.id)

    @Test
    fun `a second run places nothing and renames nothing`() = runBlocking<Unit> {
        val f = h.saveKvoldbok(RetentionSpec("all"))
        h.engine.run(f.id, RunTrigger.MANUAL)
        val before = h.files(f)
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, r.placed)
        assertEquals(before, h.files(f))
        assertFalse(r.lines.any { it.kind == "failed" }, r.lines.toString())
    }

    @Test
    fun `the same listing under date fails naming the shared date`() = runBlocking<Unit> {
        val r = refused(h.service.save(SaveRequest(h.kvoldbokUrl, null, true, h.kvoldbokRecipe("date"))))
        assertTrue("3 items share the date 2026-11-03" in r.reason, r.reason)
        assertTrue(h.store.fetchers().isEmpty())
    }

    @Test
    fun `two items 18_25 and 18_30 on one date fail the proof naming both times`() = runBlocking<Unit> {
        h.site.kvoldbok = listOf(3 to "3. Nov. 2026 18.30 | 25m", 2 to "3. Nov. 2026 18.25 | 25m", 1 to "2. Nov. 2026 18.30 | 25m")
        val r = refused(h.service.save(SaveRequest(h.kvoldbokUrl, null, true, h.kvoldbokRecipe())))
        assertEquals("2 items on 2026-11-03 (18.25, 18.30) air too close together to get numbers of their own", r.reason)
    }

    @Test
    fun `a pattern that matches no item fails the proof quoting the text`() = runBlocking<Unit> {
        val recipe = h.kvoldbokRecipe().let { it.copy(numbering = it.numbering.copy(whenPattern = it.numbering.whenPattern!!.copy(months = emptyMap()))) }
        val r = refused(h.service.save(SaveRequest(h.kvoldbokUrl, null, true, recipe)))
        assertEquals("the date pattern finds no date in '5. Nov. 2026 6.00 | 25m'", r.reason)
    }

    @Test
    fun `an item with a date and no time fails the proof`() = runBlocking<Unit> {
        h.site.kvoldbok = listOf(3 to "3. Nov. 2026 18.30 | 25m", 2 to "3. Nov. 2026", 1 to "2. Nov. 2026 18.30 | 25m")
        val r = refused(h.service.save(SaveRequest(h.kvoldbokUrl, null, true, h.kvoldbokRecipe())))
        assertEquals("the date pattern finds no date in '3. Nov. 2026'", r.reason)
    }

    @Test
    fun `on a later run an item with no time is skipped with its line and is not a failure`() = runBlocking<Unit> {
        val f = h.saveKvoldbok(RetentionSpec("all"))
        h.engine.run(f.id, RunTrigger.MANUAL)
        h.site.kvoldbok = listOf(6 to "6. Nov. 2026") + h.site.kvoldbok
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("ok", r.status); assertEquals(0, r.placed)
        assertTrue(r.lines.any { it.kind == "skipped" && it.text == "Kvøldbók · 6: the page gives it no time" }, r.lines.toString())
        assertTrue(h.store.items(f.id).none { it.failures > 0 })
    }

    @Test
    fun `on a later run a second item in a placed slot is skipped naming the one placed`() = runBlocking<Unit> {
        val f = h.saveKvoldbok(RetentionSpec("all"))
        h.engine.run(f.id, RunTrigger.MANUAL)
        h.site.kvoldbok = listOf(6 to "3. Nov. 2026 18.25 | 25m") + h.site.kvoldbok
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("ok", r.status); assertEquals(0, r.placed)
        assertTrue(r.lines.any { it.kind == "skipped" && it.text == "Kvøldbók · 6: airs too close to 2026-11-03 18.30, already placed" }, r.lines.toString())
    }

    @Test
    fun `a datetime recipe is page only and needs a published field`() {
        val noPublished = h.kvoldbokRecipe().let { it.copy(list = it.list.copy(fields = it.list.fields - "published")) }
        assertTrue(RecipeValidator.validate(noPublished, true).any { "datetime numbering needs the listing's published field" in it })
        val noWhen = h.kvoldbokRecipe().let { it.copy(numbering = it.numbering.copy(whenPattern = null)) }
        assertTrue(RecipeValidator.validate(noWhen, true).any { "datetime numbering needs numbering.when" in it })
        val noHour = h.kvoldbokRecipe().let { it.copy(numbering = it.numbering.copy(whenPattern = it.numbering.whenPattern!!.copy(regex = "(?<day>\\d+)\\.(?<month>\\p{L}+)\\.(?<year>\\d{4})"))) }
        assertTrue(RecipeValidator.validate(noHour, true).any { "numbering.when" in it && "'hour'" in it }, RecipeValidator.validate(noHour, true).toString())
        val backtracking = h.kvoldbokRecipe().let { it.copy(numbering = it.numbering.copy(whenPattern = it.numbering.whenPattern!!.copy(regex = "(?<day>(\\d+)+)(?<month>a)(?<year>b)(?<hour>c)(?<minute>d)"))) }
        assertTrue(RecipeValidator.validate(backtracking, true).any { "numbering.when" in it }, "a backtracking pattern is refused before the proof")
        assertTrue(RecipeValidator.validate(h.kvoldbokRecipe(), false).any { "datetime numbering is offered only to a page-only fetcher" in it })
        assertTrue(RecipeValidator.validate(h.kvoldbokRecipe(), true).none { "numbering" in it })
    }

    @Test
    fun `re-investigating from date to datetime keeps the placed names and numbers only the unplaced items the new way`() = runBlocking<Unit> {
        h.site.kvoldbok = listOf(2 to "4. Nov. 2026 6.00 | 25m", 1 to "3. Nov. 2026 18.30 | 25m")
        val f = h.service.save(SaveRequest(h.kvoldbokUrl, null, true, h.kvoldbokRecipe("date"), retention = RetentionSpec("all"))).let { assertIs<SaveResult.Saved>(it).fetcher }
        h.engine.run(f.id, RunTrigger.MANUAL)
        val placed = h.files(f)
        assertEquals(2, placed.size)
        assertTrue(placed.all { it.contains("S2026E") }, placed.toString())
        h.site.kvoldbok = listOf(3 to "4. Nov. 2026 21.00 | 25m") + h.site.kvoldbok
        assertIs<SaveResult.Saved>(h.service.replaceRecipe(f.id, h.kvoldbokRecipe("datetime")))
        h.engine.run(f.id, RunTrigger.MANUAL)
        val all = h.files(f)
        assertTrue(placed.all { it in all }, all.toString())
        assertEquals(3, all.size)
        assertTrue(all.any { it.startsWith("Kvøldbók - S2644E963 - 2026-11-04 21.00 - ") }, all.toString())
    }
}
