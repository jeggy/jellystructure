package dev.jellystructure.ravilo.ui.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-4071 (DEB-3995 FR-10, FR-12) — every delete asks first, naming what it frees; Cancel changes nothing; a confirmed delete frees the space at once. */
class DownloadsPageStateTest {
    private val done = DownloadPhase.Downloaded

    private fun rig(): PageRig = PageRig().apply {
        add("alice", "film1", done, 6_200_000_000L, title = "Film One", watched = true)
        add("alice", "film2", done, 7_900_000_000L, title = "Film Two")
        add("alice", "e1", done, 650_000_000L, kind = "episode", seriesId = "s", seriesTitle = "Series", season = 1, episode = 1, watched = true)
        add("alice", "e2", done, 650_000_000L, kind = "episode", seriesId = "s", seriesTitle = "Series", season = 1, episode = 2)
        add("alice", "run", DownloadPhase.Running(50), 1_000_000_000L, title = "Running", kind = "episode", seriesId = "s", seriesTitle = "Series", season = 1, episode = 3)
        add("bob", "bob1", done, 9_400_000_000L, title = "Not yours")
    }

    private fun ids(r: PageRig) = r.controller.records.value.map { it.itemId }.toSet()

    @Test fun `a swipe asks, naming the title and the size it frees, and Cancel puts everything back as it was`() {
        val r = rig(); val state = DownloadsPageState(r.controller)
        val before = r.controller.records.value; val phasesBefore = r.controller.phases.value
        val row = buildDownloadsPage(before, phasesBefore).films.first { it.itemId == "film2" }
        state.askOne(row)
        val ask = assertNotNull(state.pending)
        assertEquals(PendingDelete.Kind.ONE, ask.kind); assertEquals("Film Two", ask.subject); assertEquals(7_900_000_000L, ask.bytes)
        assertTrue(r.engine.removed.isEmpty(), "asking deletes nothing")
        state.cancel()
        assertNull(state.pending)
        assertEquals(before, r.controller.records.value); assertEquals(phasesBefore, r.controller.phases.value); assertTrue(r.engine.removed.isEmpty())
    }

    @Test fun `confirming a delete removes the media and the record and the totals drop at once`() {
        val r = rig(); val state = DownloadsPageState(r.controller)
        val totalBefore = buildDownloadsPage(r.controller.records.value, r.controller.phases.value).profileBytes
        state.askOne(buildDownloadsPage(r.controller.records.value, r.controller.phases.value).films.first { it.itemId == "film2" })
        state.confirm()
        assertNull(state.pending)
        assertTrue(downloadId("alice", "film2") in r.engine.removed)
        assertTrue("film2" !in ids(r))
        val page = buildDownloadsPage(r.controller.records.value, r.controller.phases.value)
        assertEquals(totalBefore - 7_900_000_000L, page.profileBytes)
    }

    @Test fun `Delete watched names only the watched finished titles and removes only them`() {
        val r = rig(); val state = DownloadsPageState(r.controller)
        state.askWatched()
        val ask = assertNotNull(state.pending)
        assertEquals(PendingDelete.Kind.WATCHED, ask.kind); assertEquals(setOf("film1", "e1"), ask.ids); assertEquals(6_200_000_000L + 650_000_000L, ask.bytes)
        state.confirm()
        assertEquals(setOf("film2", "e2", "run"), ids(r))
    }

    @Test fun `Delete all names this profile's total, running entries included, and leaves other profiles alone`() {
        val r = rig(); val state = DownloadsPageState(r.controller)
        val page = buildDownloadsPage(r.controller.records.value, r.controller.phases.value)
        state.askAll()
        assertEquals(page.profileBytes, state.pending?.bytes)
        state.confirm()
        assertTrue(r.controller.records.value.isEmpty())
        assertEquals(9_400_000_000L, r.controller.otherBytes.value, "Delete all is this profile's only")
    }

    @Test fun `multi-select sums its size, asks once, and a confirm leaves select mode`() {
        val r = rig(); val state = DownloadsPageState(r.controller)
        state.startSelecting("film1"); state.toggle("e2"); state.toggle("film2"); state.toggle("film2")   // film2 on, then off
        assertTrue(state.selecting)
        assertEquals(setOf("film1", "e2"), state.selected)
        assertEquals(6_200_000_000L + 650_000_000L, state.selectedBytes())
        state.askSelection()
        assertEquals(PendingDelete.Kind.SELECTION, state.pending?.kind)
        state.confirm()
        assertTrue(!state.selecting && state.selected.isEmpty())
        assertEquals(setOf("film2", "e1", "run"), ids(r))
    }

    @Test fun `cancelling a selection's confirm keeps the selection`() {
        val r = rig(); val state = DownloadsPageState(r.controller)
        state.startSelecting("film1"); state.askSelection(); state.cancel()
        assertTrue(state.selecting); assertEquals(setOf("film1"), state.selected); assertEquals(5, r.controller.records.value.size)
    }

    @Test fun `a series header deletes every finished episode of the series behind one confirm`() {
        val r = rig(); val state = DownloadsPageState(r.controller)
        val group = buildDownloadsPage(r.controller.records.value, r.controller.phases.value).series.single()
        state.askSeries(group)
        val ask = assertNotNull(state.pending)
        assertEquals("Series", ask.subject); assertEquals(setOf("e1", "e2"), ask.ids); assertEquals(1_300_000_000L, ask.bytes)
        state.confirm()
        assertEquals(setOf("film1", "film2", "run"), ids(r))
    }

    @Test fun `asking with nothing to delete asks nothing`() {
        val r = PageRig(); val state = DownloadsPageState(r.controller)
        state.askAll(); state.askWatched(); state.askSelection(); state.askOtherProfiles()
        assertNull(state.pending)
    }

    @Test fun `other profiles' space is counted, never named, and deleting it leaves this profile's downloads`() {
        val r = rig(); val state = DownloadsPageState(r.controller)
        assertEquals(9_400_000_000L, r.controller.otherBytes.value)
        assertTrue("bob1" !in ids(r), "another profile's title is never on this profile's page")
        state.askOtherProfiles()
        val ask = assertNotNull(state.pending)
        assertEquals(PendingDelete.Kind.OTHER_PROFILES, ask.kind); assertNull(ask.subject); assertEquals(9_400_000_000L, ask.bytes)
        state.confirm()
        assertTrue(downloadId("bob", "bob1") in r.engine.removed)
        assertEquals(0L, r.controller.otherBytes.value)
        assertEquals(5, r.controller.records.value.size)
        assertEquals(emptyList(), r.library.records("bob"))
    }

    @Test fun `the bar's Ravilo part is every profile's bytes while the title bar is this profile's`() {
        val r = rig()
        val mine = buildDownloadsPage(r.controller.records.value, r.controller.phases.value).profileBytes
        val bar = storageBar(mine + r.controller.otherBytes.value, r.controller.freeBytes(), r.controller.totalBytes())
        assertEquals(mine + 9_400_000_000L, bar.ravilo)
        assertTrue(bar.ravilo > mine)
    }

    @Test fun `observe records the server's watched word on a download and ignores a title that is not on the phone`() {
        val r = rig()
        r.controller.observe("film2", true)
        assertEquals(true, r.controller.records.value.first { it.itemId == "film2" }.watched)
        r.controller.observe("film2", false)
        assertEquals(false, r.controller.records.value.first { it.itemId == "film2" }.watched)
        r.controller.observe("nowhere", true)
        assertEquals(5, r.controller.records.value.size)
    }
}
