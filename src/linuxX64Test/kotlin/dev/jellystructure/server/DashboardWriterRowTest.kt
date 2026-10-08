package dev.jellystructure.server

import dev.jellystructure.server.routes.DashboardService
import dev.jellystructure.server.routes.playbackWriterRow
import dev.jellystructure.tv.PlaybackWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Phase 310 (FR-310-3) — the Dashboard's critical row shows exactly when the writer is down or a write is stuck. */
class DashboardWriterRowTest {
    private fun stats(alive: Boolean, oldest: Long, queued: Int = 0) = PlaybackWriter.Stats(queued, 0, 10, 0, 0, null, alive = alive, oldestWaitingS = oldest)

    @Test fun `a healthy writer shows nothing`() = assertNull(playbackWriterRow(stats(alive = true, oldest = 5)))
    @Test fun `no writer at all shows nothing`() = assertNull(playbackWriterRow(null))

    @Test fun `a writer that is not running is critical`() {
        val r = playbackWriterRow(stats(alive = false, oldest = 0, queued = 15))!!
        assertEquals(DashboardService.CRITICAL, r.severity)
        assertEquals(15, r.count)
    }

    @Test fun `a write waiting over two minutes is critical`() {
        assertEquals(DashboardService.CRITICAL, playbackWriterRow(stats(alive = true, oldest = 121, queued = 3))!!.severity)
        assertNull(playbackWriterRow(stats(alive = true, oldest = 120)))
    }
}
