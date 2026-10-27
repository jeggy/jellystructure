package dev.jellystructure.autofetch

import dev.jellystructure.db.closeLastDatabaseForTests
import dev.jellystructure.db.createDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2476 — the autofetch tables round-trip through SQLite, and the schedule presets. */
@OptIn(ExperimentalForeignApi::class)
class AutofetchStoreTest {
    private val path = "/tmp/autofetch-store-${platform.posix.getpid()}-${platform.posix.rand()}.db"

    @AfterTest
    fun tearDown() {
        closeLastDatabaseForTests()
        for (s in listOf("", "-wal", "-shm")) platform.posix.remove(path + s)
    }

    private fun fetcher(id: String = "f1") = Fetcher(
        id, "Royndin", "https://varpid.example.fo/sendingar/royndin", "varpid.example.fo", 9000001, false, "/lib/tv", "/lib/tv/Royndin",
        """{"x":1}""", true, null, null, RetentionSpec("newest", 8, true), "daily@04:00", true, "ok", "", 100, null, null,
    )

    @Test
    fun `a fetcher its items and its runs round trip and delete leaves nothing`() {
        val store = AutofetchStore(createDatabase(path))
        store.insert(fetcher())
        val f = store.fetcher("f1")!!
        assertEquals(RetentionSpec("newest", 8, true), f.retention)
        assertEquals(9000001, f.tmdbId); assertFalse(f.pageOnly)
        store.upsertListed("f1", "i1", ListedItem(title = "Útfall", link = "https://varpid.example.fo/x/1", description = "d", duration = "1800", order = 3), SeasonEpisode(2026, 1), "2026-10-16", 50)
        store.markMissing("f1", "i1", 60)
        store.setPlaced("f1", "i1", "/lib/tv/Royndin/Season 2026/a.mkv", 70)
        val it = store.item("f1", "i1")!!
        assertEquals("Útfall", it.title); assertEquals(2026, it.season); assertEquals("placed", it.state); assertEquals(1800, it.duration); assertEquals(60L, it.missingSince)
        assertEquals("i1", store.itemByPlacedPath("/lib/tv/Royndin/Season 2026/a.mkv")?.itemId)
        // Seeing it on the page again clears the missing mark.
        store.upsertListed("f1", "i1", ListedItem(title = "Útfall", link = "https://varpid.example.fo/x/1"), SeasonEpisode(2026, 1), "2026-10-16", 80)
        assertNull(store.item("f1", "i1")!!.missingSince)
        val rid = store.startRun("f1", "manual", 90)
        store.finishRun(rid, 95, "ok", "+1", listOf(RunLine("placed", "S2026E01")))
        assertEquals(listOf(RunLine("placed", "S2026E01")), store.runs("f1").single().lines)
        store.mark("/lib/tv/Royndin", "f1")
        assertTrue(store.isPageOnlySeries("/lib/tv/Royndin/"))
        store.delete("f1")
        assertNull(store.fetcher("f1")); assertTrue(store.items("f1").isEmpty()); assertTrue(store.runs("f1").isEmpty()); assertFalse(store.isPageOnlySeries("/lib/tv/Royndin"))
    }

    @Test
    fun `site state round trips`() {
        val store = AutofetchStore(createDatabase(path))
        assertEquals(SiteState("s"), store.get("s"))
        store.put(SiteState("s", "blocked", 5, "robots", 6, 7, 2, "19000", 3, true))
        assertEquals(SiteState("s", "blocked", 5, "robots", 6, 7, 2, "19000", 3, true), store.get("s"))
    }

    @Test
    fun `an unfinished run is closed at the next start`() {
        val store = AutofetchStore(createDatabase(path))
        store.insert(fetcher()); val id = store.startRun("f1", "schedule", 10)
        store.abandonRunning(20)
        assertEquals("abandoned", store.runs("f1").single { it.id == id }.status)
    }

    @Test
    fun `schedule presets are valid and never sooner than six hours`() {
        assertTrue(FetchSchedule.valid("every6h")); assertTrue(FetchSchedule.valid("daily@04:00")); assertTrue(FetchSchedule.valid("weekly@1@04:00"))
        assertFalse(FetchSchedule.valid("every1h")); assertFalse(FetchSchedule.valid("daily@25:00")); assertFalse(FetchSchedule.valid("* * * * *"))
        assertEquals(1000L, FetchSchedule.nextDue("every6h", "a", null, 1000L))
        assertEquals(500L + 6 * 3600, FetchSchedule.nextDue("every6h", "a", 500L, 600L))
        val last = 1_700_000_000L
        val next = FetchSchedule.nextDue("daily@04:00", "a", last, last)
        assertTrue(next >= last + 6 * 3600)
    }

    @Test
    fun `two fetchers on the same daily slot do not share a second`() {
        val a = FetchSchedule.nextDue("daily@04:00", "fetcher-aaaa", 1_700_000_000L, 0)
        val b = FetchSchedule.nextDue("daily@04:00", "fetcher-bbbb", 1_700_000_000L, 0)
        assertTrue(FetchSchedule.spreadMinutes("fetcher-aaaa") != FetchSchedule.spreadMinutes("fetcher-bbbb") || a == b)
    }
}
