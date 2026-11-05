package dev.jellystructure.autofetch

import dev.jellystructure.filefix.PosixFileFixShell
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-3695 (DEB-2474 FR-4.5, FR-6.6, FR-6.8, FR-16.2) — Acceptance 23's run bullets, on `Dagbók`: a placed file follows its item's date,
 * and a re-upload replaces the episode that left the page. Item numbers are as in the item's link, so a re-upload is a link of its own.
 */
class AutofetchRedatedItemTest {
    private val h = Harness()

    @AfterTest
    fun tearDown() = h.close()

    private fun days(range: IntRange) = range.map { it to "2026-10-$it" }

    private fun readFile(path: String): String = kotlinx.io.files.SystemFileSystem.source(kotlinx.io.files.Path(path)).buffered().use { it.readString() }

    private fun item(f: Fetcher, link: Int) = h.store.items(f.id).single { it.link.endsWith("/$link") }

    private fun fileOf(f: Fetcher, link: Int) = item(f, link).placedPath

    /** Day 27 is `S2026E300`, 28 is `E301`, 29 is `E302`, and 30 is `E303`. */
    private fun e(n: Int) = "S2026E$n"

    @Test
    fun `a re-upload keeps its file's number until the original has left the page then replaces it and the real episode is placed`() = runBlocking<Unit> {
        h.site.dagbokItems = days(16..27).reversed()
        val f = h.saveDagbok()
        h.engine.run(f.id, RunTrigger.MANUAL)
        val originalPath = fileOf(f, 27)!!
        // Read 1: R, dated 10-28, is listed beside the original and is placed under its upload date.
        h.site.dagbokItems = listOf(100 to "2026-10-28") + days(16..27).reversed()
        h.clock.advanceHours(24)
        h.engine.run(f.id, RunTrigger.MANUAL)
        val rPath = fileOf(f, 100)!!
        assertTrue(e(301) in rPath, rPath)
        PosixFileFixShell.writeText(rPath.substringBeforeLast('.') + "-thumb.jpg", "jpg")
        // Read 2: the page lacks the original, dates R 10-27 and lists N on 10-28.
        h.site.dagbokItems = listOf(101 to "2026-10-28", 100 to "2026-10-27") + days(17..26).reversed()
        h.clock.advanceHours(1)
        h.world.announced.clear()
        val two = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(rPath, fileOf(f, 100), "R keeps the file name its number gave")
        assertEquals(301, item(f, 100).episode, "and the row keeps the number the file carries")
        assertTrue(two.lines.any { it.kind == "skipped" && "the page now dates it 2026-10-27, and ${e(300)} already has a file; kept as ${e(301)}" in it.text }, two.lines.toString())
        assertTrue(two.lines.any { it.kind == "skipped" && "shares 2026-10-28 with an episode already placed" in it.text }, two.lines.toString())
        assertTrue(two.summary.endsWith(" · 2 skipped"), two.summary)
        assertTrue(h.onDisk(originalPath) && h.onDisk(rPath))
        assertTrue(h.world.announced.isEmpty())
        // Read 3, 6 hours on: R's file is renamed onto the original's path.
        h.clock.advanceHours(7)
        h.world.announced.clear()
        val downloadsBefore = h.tools.downloads.size
        val three = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(originalPath, fileOf(f, 100), three.lines.toString())
        assertNull(fileOf(f, 27))
        assertEquals("replaced", item(f, 27).state)
        assertFalse(h.onDisk(rPath), "R's old file is gone")
        assertFalse(h.onDisk(rPath.substringBeforeLast('.') + "-thumb.jpg"), "and so is its thumbnail")
        assertTrue(rPath to "Deleted" in h.world.announced && originalPath to "Modified" in h.world.announced, h.world.announced.toString())
        // N is placed in the same run, under the number R's file left; nothing is downloaded for E300; retention deletes nothing.
        val nPath = fileOf(f, 101)!!
        assertTrue("${e(301)} - 2026-10-28" in nPath, nPath)
        assertEquals(1, h.tools.downloads.size - downloadsBefore)
        assertEquals(0, three.deleted, three.lines.toString())
        assertTrue(three.lines.any { it.kind == "placed" && "${e(300)}" in it.text && "replaces the episode that left the page" in it.text }, three.lines.toString())
        assertTrue(three.summary.startsWith("+2 · "), three.summary)
        // Never a shift: every placed file carries the date its item carries.
        for (row in h.store.items(f.id).filter { it.placedPath != null }) assertTrue("- ${row.published} -" in row.placedPath!!, "${row.placedPath} vs ${row.published}")
    }

    @Test
    fun `the misfiled episode of prod is renamed to its own date and the missing one is placed`() = runBlocking<Unit> {
        h.site.dagbokItems = days(18..29).reversed()
        val f = h.saveDagbok(RetentionSpec("all"))
        h.engine.run(f.id, RunTrigger.MANUAL)
        val originalPath = fileOf(f, 29)!!
        // R, a re-upload of 10-29, is listed on 10-30 and placed under that date.
        h.site.dagbokItems = listOf(100 to "2026-10-30") + days(18..29).reversed()
        h.clock.advanceHours(24)
        h.engine.run(f.id, RunTrigger.MANUAL)
        val misfiled = fileOf(f, 100)!!
        assertTrue("${e(303)} - 2026-10-30" in misfiled, misfiled)
        // The page re-dates R 10-29, drops the original, which retention deleted as it left the page, and lists the real 10-30.
        h.world.announced.clear()
        PosixFileFixShell.remove(originalPath)
        h.store.setDeleted(f.id, item(f, 29).itemId, "deleted: left the page")
        h.site.dagbokItems = listOf(101 to "2026-10-30", 100 to "2026-10-29") + days(18..28).reversed()
        h.clock.advanceHours(24)
        val run = h.engine.run(f.id, RunTrigger.MANUAL)!!
        val fixed = fileOf(f, 100)!!
        assertTrue("Dagbók - ${e(302)} - 2026-10-29 - " in fixed, fixed)
        assertTrue(h.onDisk(fixed) && !h.onDisk(misfiled))
        assertTrue(run.lines.any { it.text == "${e(303)} → ${e(302)} · the page now dates it 2026-10-29" }, run.lines.toString())
        assertTrue(misfiled to "Deleted" in h.world.announced && fixed to "Created" in h.world.announced, h.world.announced.toString())
        assertTrue("Dagbók - ${e(303)} - 2026-10-30 - " in fileOf(f, 101).orEmpty(), fileOf(f, 101).toString())
        assertEquals(1, run.placed)
        assertTrue(run.summary.startsWith("+1 · ${e(303)}") && run.summary.endsWith(" · 1 re-filed"), run.summary)
        assertTrue(h.files(f).none { "2026-10-30" in it && e(302) in it })
        // A second run renames nothing and places nothing.
        h.clock.advanceHours(24)
        val again = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals("no new episodes", again.summary)
        assertTrue(again.lines.none { it.kind == "changed" || it.kind == "placed" }, again.lines.toString())
    }

    @Test
    fun `a re-upload on the date of an original that left the page replaces it in place after the checks under retention all`() = runBlocking<Unit> {
        h.site.dagbokItems = days(16..27).reversed()
        val f = h.saveDagbok(RetentionSpec("all"))
        h.engine.run(f.id, RunTrigger.MANUAL)
        val originalPath = fileOf(f, 27)!!
        h.site.dagbokItems = listOf(100 to "2026-10-27") + days(16..27).reversed()
        h.clock.advanceHours(24)
        val first = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertTrue(first.lines.any { it.kind == "skipped" && "shares 2026-10-27" in it.text }, first.lines.toString())
        assertEquals("no new episodes · 1 skipped", first.summary)
        // The original leaves the page: the first read that lacks it is not enough.
        h.site.dagbokItems = listOf(100 to "2026-10-27") + days(16..26).reversed()
        h.clock.advanceHours(1)
        val oneRead = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(0, oneRead.placed)
        assertEquals("placed", item(f, 27).state)
        // Two full reads 6 hours apart; the re-upload fails FR-6.5's checks first, and the original is as it was.
        h.clock.advanceHours(7)
        h.tools.failDownload = true
        h.world.announced.clear()
        val failed = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertTrue(failed.summary.contains(" · 1 failed"), failed.summary)
        assertEquals(originalPath, fileOf(f, 27))
        assertTrue(PosixFileFixShell.exists(originalPath) && !readFile(originalPath).contains("DOV73100"))
        assertNull(fileOf(f, 100))
        h.tools.failDownload = false
        h.clock.advanceHours(1)
        val replaced = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(1, replaced.placed, replaced.lines.toString())
        assertTrue(replaced.lines.any { it.kind == "placed" && it.text.startsWith("${e(300)} (") && it.text.endsWith(" · replaces the episode that left the page") }, replaced.lines.toString())
        assertEquals(originalPath, fileOf(f, 100))
        assertNull(fileOf(f, 27))
        assertEquals("replaced", item(f, 27).state)
        assertTrue(readFile(originalPath).contains("DOV73100"), "the file at the old path is the new copy")
        assertTrue(originalPath to "Modified" in h.world.announced, h.world.announced.toString())
        assertEquals(12, h.files(f).size)
        assertTrue(h.files(f).none { "100" in it })
        // Retention never counts a replaced row, and the next run changes nothing.
        h.clock.advanceHours(24)
        assertEquals("no new episodes", h.engine.run(f.id, RunTrigger.MANUAL)!!.summary)
    }

    @Test
    fun `an original still on the page keeps its number and a re-upload never replaces it`() = runBlocking<Unit> {
        h.site.dagbokItems = days(16..27).reversed()
        val f = h.saveDagbok(RetentionSpec("all"))
        h.engine.run(f.id, RunTrigger.MANUAL)
        h.site.dagbokItems = listOf(100 to "2026-10-27") + days(16..27).reversed()
        for (i in 1..3) {
            h.clock.advanceHours(8)
            val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
            assertEquals(0, r.placed, r.lines.toString())
        }
        assertEquals("placed", item(f, 27).state)
        assertNull(fileOf(f, 100))
    }

    @Test
    fun `a number that is not free keeps the file and the row at the number the file carries`() = runBlocking<Unit> {
        h.site.dagbokItems = listOf(100 to "2026-10-28") + days(16..26).reversed()
        val f = h.saveDagbok(RetentionSpec("all"))
        h.engine.run(f.id, RunTrigger.MANUAL)
        val rPath = fileOf(f, 100)!!
        assertTrue(e(301) in rPath)
        val hand = "${h.seasonDir(f)}/Dagbók - ${e(300)} - by hand.mkv"
        PosixFileFixShell.writeText(hand, "hand")
        h.site.dagbokItems = listOf(100 to "2026-10-27") + days(16..26).reversed()
        h.clock.advanceHours(24)
        h.world.announced.clear()
        val r = h.engine.run(f.id, RunTrigger.MANUAL)!!
        assertEquals(rPath, fileOf(f, 100))
        assertEquals(301, item(f, 100).episode)
        assertTrue(r.lines.any { it.text.endsWith("the page now dates it 2026-10-27, and ${e(300)} already has a file; kept as ${e(301)}") }, r.lines.toString())
        assertEquals("no new episodes · 1 skipped", r.summary)
        assertTrue(h.onDisk(hand) && h.onDisk(rPath))
        assertTrue(h.world.announced.isEmpty())
        // Run again: nothing moves, and nothing is deleted.
        h.clock.advanceHours(24)
        assertEquals(rPath, fileOf(h.store.fetcher(f.id)!!, 100))
        assertNotNull(h.engine.run(f.id, RunTrigger.MANUAL))
        assertEquals(rPath, fileOf(f, 100))
    }
}
