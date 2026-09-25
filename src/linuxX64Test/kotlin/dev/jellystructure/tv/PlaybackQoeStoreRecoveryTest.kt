package dev.jellystructure.tv

import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.PlaybackQoeReport
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R292 (FR-R292-11) — R220's counter finally reaches a row, with its rung and time, and is READ: a ladder firing
 *  or a restore after a recreation badges the session; a plain return from the background does not. */
class PlaybackQoeStoreRecoveryTest {
    private lateinit var dbPath: String
    private lateinit var store: PlaybackQoeStore

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-qoe-recovery-${getpid()}.db"
        store = PlaybackQoeStore(createDatabase(dbPath))
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    @Test
    fun `the ladder's count rung and time land in the row and badge it`() {
        store.record("dev1", "ps1", PlaybackQoeReport(itemId = "i1", videoOutputRecoveries = 1, videoOutputRecoveryRung = 2, videoOutputRecoveryMs = 5_500))
        val row = store.recentForDevice("dev1").single()
        assertEquals(1, row.videoOutputRecoveries); assertEquals(2, row.videoOutputRecoveryRung); assertEquals(5_500L, row.videoOutputRecoveryMs)
        assertTrue(row.hasIssue, "a recovery is worth a second look")
    }

    @Test
    fun `a return from the background is carried but not badged and a restore after a recreation is`() {
        store.record("dev1", "ps1", PlaybackQoeReport(itemId = "i1", backgroundReturns = 2))
        val a = store.recentForDevice("dev1").single()
        assertEquals(2, a.backgroundReturns); assertFalse(a.hasIssue, "HOME and back is what viewers do")
        store.record("dev1", "ps2", PlaybackQoeReport(itemId = "i2", restoredAfterRecreate = 1))
        assertTrue(store.recentForDevice("dev1").first { it.playSessionId == "ps2" }.hasIssue)
    }
}
