package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-4072 (DEB-3995 FR-15) — the outbox's ordering, its conflict rule and what it keeps when the server goes away. */
class ProgressOutboxTest {
    private class Sink : OutboxSink {
        val server = HashMap<String, CardPlayState>()
        val sent = mutableListOf<String>()
        var playstateFails: Throwable? = null
        /** Throw this on the n-th write (1-based), by item id. */
        val failOn = HashMap<String, Throwable>()
        override suspend fun playstate(ids: List<String>): Map<String, CardPlayState> {
            playstateFails?.let { throw it }
            return server.filterKeys { it in ids }
        }
        override suspend fun progress(itemId: String, positionMs: Long) { failOn[itemId]?.let { throw it }; sent += "progress:$itemId:$positionMs" }
        override suspend fun mark(itemId: String, watched: Boolean) { failOn[itemId]?.let { throw it }; sent += "mark:$itemId:$watched" }
    }

    private val never = ServerPlay()
    private fun outbox() = ProgressOutbox(InMemoryDownloadIndexStorage())

    @Test fun `titles drain oldest first, a title's progress before its watched mark`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("u", "b", 1_000, 10, never)
        o.recordProgress("u", "a", 2_000, 20, never)
        o.recordWatched("u", "b", 30, never)
        val r = o.drain("u", s)
        assertEquals(listOf("progress:b:1000", "mark:b:true", "progress:a:2000"), s.sent)
        assertEquals(3, r.sent)
        assertTrue(o.isEmpty("u"))
    }

    @Test fun `a later position replaces the earlier one and keeps the title's place in the queue`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("u", "a", 1_000, 10, never)
        o.recordProgress("u", "b", 5_000, 20, never)
        o.recordProgress("u", "a", 9_000, 30, never)
        o.drain("u", s)
        assertEquals(listOf("progress:a:9000", "progress:b:5000"), s.sent)
    }

    @Test fun `the server wins when it holds a later play of the title`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("u", "a", 1_000, 10, ServerPlay(resumeMs = 0, played = false))
        o.recordWatched("u", "a", 20, ServerPlay(0, false))
        o.recordProgress("u", "b", 3_000, 30, ServerPlay(0, false))
        // Another device finished "a" while the phone was away; "b" is untouched.
        s.server["a"] = CardPlayState(resumeMs = 0, played = true)
        val r = o.drain("u", s)
        assertEquals(listOf("progress:b:3000"), s.sent)
        assertEquals(2, r.dropped)
        assertTrue(o.isEmpty("u"))
    }

    @Test fun `a moved resume position elsewhere is a later play too`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("u", "a", 1_000, 10, ServerPlay(resumeMs = 60_000, played = false))
        s.server["a"] = CardPlayState(resumeMs = 600_000)
        o.drain("u", s)
        assertTrue(s.sent.isEmpty())
    }

    @Test fun `a title the server holds no user data for reads as never played, not as gone`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("u", "a", 1_000, 10, never)
        o.drain("u", s)   // "a" is absent from the playstate answer
        assertEquals(listOf("progress:a:1000"), s.sent)
    }

    @Test fun `the base comes from the title's first entry, not from a later one`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("u", "a", 1_000, 10, ServerPlay(resumeMs = 5, played = false))
        o.recordProgress("u", "a", 2_000, 20, ServerPlay(resumeMs = 99, played = true))
        assertEquals(5L, o.entries("u").single().baseResumeMs)
        s.server["a"] = CardPlayState(resumeMs = 5)
        o.drain("u", s)
        assertEquals(listOf("progress:a:2000"), s.sent)
    }

    @Test fun `an unreachable server keeps everything, in order, for next time`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("u", "a", 1_000, 10, never)
        o.recordProgress("u", "b", 2_000, 20, never)
        s.playstateFails = RuntimeException("no route")
        val r = o.drain("u", s)
        assertTrue(r.unreachable)
        assertEquals(listOf("a", "b"), o.pendingItems("u"))
        s.playstateFails = null
        o.drain("u", s)
        assertEquals(listOf("progress:a:1000", "progress:b:2000"), s.sent)
    }

    @Test fun `the server going away part-way leaves what was not sent`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("u", "a", 1_000, 10, never)
        o.recordProgress("u", "b", 2_000, 20, never)
        s.failOn["b"] = RuntimeException("dropped")
        val r = o.drain("u", s)
        assertEquals(listOf("progress:a:1000"), s.sent)
        assertTrue(r.unreachable)
        assertEquals(listOf("b"), o.pendingItems("u"))
    }

    @Test fun `a 401 stops the drain and keeps the outbox`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("u", "a", 1_000, 10, never)
        s.playstateFails = TvApiError.Http(401, "no")
        val r = o.drain("u", s)
        assertTrue(r.rejected)
        assertEquals(listOf("a"), o.pendingItems("u"))
    }

    @Test fun `a title the server no longer offers is dropped and the rest goes on`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("u", "a", 1_000, 10, never)
        o.recordProgress("u", "b", 2_000, 20, never)
        s.failOn["a"] = TvApiError.Http(404, "gone")
        val r = o.drain("u", s)
        assertEquals(listOf("progress:b:2000"), s.sent)
        assertEquals(1, r.dropped)
        assertTrue(o.isEmpty("u"))
    }

    @Test fun `each viewer has an outbox of their own and it outlives everything but its own drain`() = runTest {
        val o = outbox(); val s = Sink()
        o.recordProgress("alice", "a", 1_000, 10, never)
        o.recordProgress("bob", "b", 2_000, 20, never)
        o.drain("alice", s)
        assertEquals(listOf("progress:a:1000"), s.sent)
        assertEquals(listOf("b"), o.pendingItems("bob"))
    }

    @Test fun `an offline resume shows the recorded position, and nothing for a title finished offline`() {
        val o = outbox()
        assertNull(o.offlineResumeMs("u", "a"))
        o.recordProgress("u", "a", 4_000, 10, never)
        assertEquals(4_000L, o.offlineResumeMs("u", "a"))
        o.recordWatched("u", "a", 20, never)
        assertNull(o.offlineResumeMs("u", "a"))
        assertFalse(o.isEmpty("u"))
    }
}
