package dev.jellystructure.tv

import dev.jellystructure.db.createDatabase
import dev.jellystructure.shared.tv.PlaybackQoeReport
import dev.jellystructure.shared.tv.QoeStall
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R381 (FR-R381-4, FR-R381-6 test 3) — a legacy row's rebuffer counts are never read; a per-item row's are, with its stalls. */
class PlaybackQoeStorePerItemTest {
    private val path = "/tmp/jellystructure-test-r381-qoe-${getpid()}.db"
    private val store = PlaybackQoeStore(createDatabase(path))

    @AfterTest fun cleanup() { platform.posix.unlink(path) }

    @Test
    fun `a legacy row reports no rebuffers and is not badged for them`() {
        // An app without R381: its count 1 / 144 ms was the session's running total, carried from item to item.
        store.record("tv", "ps-1", PlaybackQoeReport(itemId = "ep-2", rebufferCount = 1, rebufferMs = 144, directPlay = true))
        val row = store.recentForDevice("tv").single()
        assertFalse(row.perItem)
        assertEquals(0, row.rebufferCount)
        assertEquals(0L, row.rebufferMs)
        assertEquals(1, row.legacyRebufferCount, "kept for the record only")
        assertFalse(row.hasIssue, "a legacy count never badges a session")
    }

    @Test
    fun `a per-item row reports its own stalls`() {
        val stall = QoeStall(afterFirstFrameMs = 120_000, positionMs = 120_000, bufferedMs = 300, durationMs = 1_500, phase = "mid", variantBps = null)
        store.record("tv2", "ps-2", PlaybackQoeReport(itemId = "ep-3", rebufferCount = 1, rebufferMs = 1_500, directPlay = true,
            perItem = true, stalls = listOf(stall), sessionRebufferCount = 2, sessionRebufferMs = 2_100, waits = mapOf("start" to 1, "seek" to 2)))
        val row = store.recentForDevice("tv2").single()
        assertTrue(row.perItem)
        assertEquals(1, row.rebufferCount)
        assertEquals(1_500L, row.rebufferMs)
        assertEquals(listOf(stall), row.stalls)
        assertEquals(mapOf("start" to 1, "seek" to 2), row.waits)
        assertEquals(2, row.sessionRebufferCount)
        assertTrue(row.hasIssue)
    }
}
