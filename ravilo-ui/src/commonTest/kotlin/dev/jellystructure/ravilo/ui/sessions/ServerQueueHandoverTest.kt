package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.shared.tv.SessionQueueEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R370 (FR-R370-7, DEB-781) — the queue a `session_load` hands over reaches the play that opens behind it, so the
 * first item of a two-item hand-over knows there is a second and the end card is *UP NEXT*, not *End of film*.
 */
class ServerQueueHandoverTest {
    @AfterTest fun tearDown() = ServerQueueHandover.clear()

    private val queue = listOf(
        SessionQueueEntry("ep-1", "A Series", "S01E01"),
        SessionQueueEntry("ep-2", "A Series", "S01E02"),
        SessionQueueEntry("ep-3", "A Series", "S01E03"),
    )

    @Test fun `the item a hand-over opened for claims it, and knows what follows`() {
        ServerQueueHandover.offer("sess-1", queue, now = 1_000L)
        val claim = ServerQueueHandover.claim("ep-1", now = 1_200L)
        assertEquals("sess-1", claim?.sessionId)
        assertEquals(0, claim?.at)
        // The defect: this was null, so the player was told it was the last item there was.
        assertEquals("ep-2", claim?.queue?.getOrNull(1)?.id)
        assertEquals("S01E02", claim?.queue?.getOrNull(1)?.subtitle)
    }

    @Test fun `a hand-over started in the middle claims from its own place`() {
        ServerQueueHandover.offer("sess-1", queue, now = 1_000L)
        val claim = ServerQueueHandover.claim("ep-2", now = 1_000L)
        assertEquals(1, claim?.at)
        assertEquals("ep-3", claim?.queue?.getOrNull(2)?.id)
    }

    @Test fun `the queue's last entry claims with nothing after it — the last-item card is the right one`() {
        ServerQueueHandover.offer("sess-1", queue, now = 1_000L)
        val claim = ServerQueueHandover.claim("ep-3", now = 1_000L)
        assertEquals(2, claim?.at)
        assertNull(claim?.queue?.getOrNull(3))
    }

    @Test fun `a play of anything else is not this hand-over's`() {
        ServerQueueHandover.offer("sess-1", queue, now = 1_000L)
        assertNull(ServerQueueHandover.claim("film-9", now = 1_000L))
    }

    @Test fun `a hand-over nobody opened expires, so a later play of the same item picks up no queue`() {
        ServerQueueHandover.offer("sess-1", queue, now = 1_000L)
        assertNull(ServerQueueHandover.claim("ep-1", now = 1_000L + 60_001L))
    }

    @Test fun `with no hand-over at all there is nothing to claim`() {
        assertNull(ServerQueueHandover.claim("ep-1", now = 1_000L))
    }
}
