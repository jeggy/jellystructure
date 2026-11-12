package dev.jellystructure.autofetch

import co.touchlab.sqliter.DatabaseConfiguration
import dev.jellystructure.db.JellystructureDb
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
        val zone = kotlinx.datetime.TimeZone.UTC
        fun refused(s: String) = FetchSchedule.refusal(s, 1_700_000_000L, zone) != null
        assertFalse(refused("every6h")); assertFalse(refused("daily@04:00")); assertFalse(refused("weekly@1@04:00"))
        assertTrue(refused("every1h")); assertTrue(refused("daily@25:00")); assertTrue(refused("* * * * *")); assertTrue(refused(""))
        assertTrue(refused("0 */1 * * *"), "DEB-4387: the 6 h floor holds for the new forms too")
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

    @Test
    fun `the robots tick is off by default - round trips - and a database from before the column reads every fetcher as unticked`() {
        val store = AutofetchStore(createDatabase(path))
        store.insert(fetcher("a")); store.insert(fetcher("b").copy(ignoreRobots = true))
        assertFalse(store.fetcher("a")!!.ignoreRobots); assertTrue(store.fetcher("b")!!.ignoreRobots)
        store.setIgnoreRobots("a", true); assertTrue(store.fetcher("a")!!.ignoreRobots)
        store.setIgnoreRobots("a", false); assertFalse(store.fetcher("a")!!.ignoreRobots)

        // DEB-3592 (FR-2.8) — 97.sqm adds the column: a database one version back, with its fetchers in it, upgrades and reads them unticked.
        closeLastDatabaseForTests()
        val v = JellystructureDb.Schema.version.toInt()
        val raw = app.cash.sqldelight.driver.native.NativeSqliteDriver(DatabaseConfiguration(
            name = path.substringAfterLast('/'), version = v, create = {}, upgrade = { _, _, _ -> },
            extendedConfig = DatabaseConfiguration.Extended(basePath = path.substringBeforeLast('/')),
        ))
        raw.execute(null, "ALTER TABLE autofetch_fetcher DROP COLUMN ignore_robots", 0)
        // DEB-3613 — 98.sqm adds the session table, which runs after 97 here, so the simulated database must not have it yet.
        raw.execute(null, "DROP TABLE autofetch_site_session", 0)
        // DEB-3637 — 99.sqm creates file_timeline, which runs after 98 here too.
        raw.execute(null, "DROP TABLE IF EXISTS file_timeline", 0)
        // …and DEB-3704's 100 creates music_js_tag (its index goes with it).
        raw.execute(null, "DROP TABLE IF EXISTS music_js_tag", 0)
        raw.execute(null, "PRAGMA user_version = $MIGRATION_ROBOTS", 0)
        raw.close()
        val again = AutofetchStore(createDatabase(path))
        assertEquals(setOf("a", "b"), again.fetchers().map { it.id }.toSet())
        assertTrue(again.fetchers().none { it.ignoreRobots })
        // 98.sqm created the session table on the way: a session round trips and is forgotten.
        assertNull(again.session("x.example"))
        again.putSession("x.example", "c", 5, null)
        assertEquals(5L, again.session("x.example")!!.savedAt)
        again.deleteSession("x.example"); assertNull(again.session("x.example"))
    }
    @Test
    fun `the old words are read for good and written in the new form`() {
        assertEquals("0 4 * * 3", FetchSchedule.canonical("weekly@3@04:00"))
        assertEquals("30 4 * * *", FetchSchedule.canonical("daily@04:30"))
        assertEquals("0 */6 * * *", FetchSchedule.canonical("every6h"))
        assertEquals("0 4 * * 1,2", FetchSchedule.canonical("0 4 * * 1,2"))
        val zone = kotlinx.datetime.TimeZone.UTC
        assertEquals(null, FetchSchedule.refusal("weekly@3@04:00", 1_700_000_000L, zone))
        assertEquals(null, FetchSchedule.refusal("0 4 * * 1,2", 1_700_000_000L, zone))
        assertEquals(null, FetchSchedule.refusal("every:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;AT=04:00;FROM=2026-11-18", 1_700_000_000L, zone))
        // Read as the old word or as its cron, the fetcher is due at the same time.
        val last = 1_700_000_000L
        assertEquals(FetchSchedule.nextDue("weekly@3@04:00", "a", last, last), FetchSchedule.nextDue("0 4 * * 3", "a", last, last))
    }

    @Test
    fun `the spread moves the fire later by the fetchers own minutes and the schedule zone is the configured one`() {
        val id = "fetcher-aaaa"
        val spread = FetchSchedule.spreadMinutes(id) * 60L
        val last = 1_700_000_000L - 3 * 3600L   // 2023-11-14 19:13 UTC: the 6 h floor (01:13) is not what decides
        val utc = FetchSchedule.nextDue("0 4 * * *", id, last, last, kotlinx.datetime.TimeZone.UTC)
        assertEquals(4 * 3600L + spread, utc % 86_400L)
        val faroeWinter = FetchSchedule.nextDue("0 4 * * *", id, last, last, kotlinx.datetime.TimeZone.of("Atlantic/Faroe"))
        assertEquals(4 * 3600L + spread, faroeWinter % 86_400L, "04:00 Faroe is 04:00 UTC in November (WET)")
        val july = 1_720_000_000L
        val faroeSummer = FetchSchedule.nextDue("0 4 * * *", id, july, july, kotlinx.datetime.TimeZone.of("Atlantic/Faroe"))
        assertEquals(3 * 3600L + spread, faroeSummer % 86_400L, "04:00 Faroe is 03:00 UTC in July (WEST)")
    }
}

/** DEB-3592's migration is 97.sqm: a database at user_version 97 runs it (migration N takes N to N+1). */
private const val MIGRATION_ROBOTS = 97
