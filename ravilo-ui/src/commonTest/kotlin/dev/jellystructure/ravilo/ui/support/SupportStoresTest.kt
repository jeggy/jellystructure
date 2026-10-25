package dev.jellystructure.ravilo.ui.support

import dev.jellystructure.shared.tv.SupportMessageView
import dev.jellystructure.shared.tv.SupportSendResult
import dev.jellystructure.shared.tv.SupportThread
import dev.jellystructure.shared.tv.SupportTicketRow
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-1501 — FR-5's table (what the viewer sees for each answer), FR-4's read marker, FR-8's offline reads, and the
 * constitution's rule that nothing is drawn before the server answers. Stand-in data only.
 */
class SupportStoresTest {
    private fun row(n: Long = 41) = SupportTicketRow(number = n, state = "OPEN", holder = "AI_AGENT")
    private fun m(id: Long, author: String = "VIEWER") = SupportMessageView(id = id, kind = "MESSAGE", author = author, body = "b$id", createdAt = 1L)
    private fun threadOf(vararg ids: Long) = SupportThread(row(), ids.map { m(it) })

    private class Fake : SupportGateway {
        var unreadAnswer = 0
        var unreadThrows = false
        var markReadThrows = false
        var ticketsAnswer: () -> List<SupportTicketRow> = { emptyList() }
        var threadAnswer: () -> SupportThread = { error("no thread") }
        var sendAnswer: () -> SupportSendResult = { error("no send") }
        val reads = mutableListOf<Pair<Long, Long>>()
        val sent = mutableListOf<String>()
        var filed = 0
        override suspend fun unread(): Int { if (unreadThrows) throw RuntimeException("offline"); return unreadAnswer }
        override suspend fun tickets() = ticketsAnswer()
        override suspend fun thread(n: Long) = threadAnswer()
        override suspend fun markRead(n: Long, upTo: Long) { if (markReadThrows) throw RuntimeException("boom"); reads += n to upTo }
        override suspend fun file(body: String): SupportSendResult { filed++; sent += body; return sendAnswer() }
        override suspend fun post(n: Long, body: String): SupportSendResult { sent += body; return sendAnswer() }
    }

    // ── My tickets ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the list is null until the server answers, then exactly its answer`() = runTest {
        val gw = Fake().apply { ticketsAnswer = { listOf(row(41), row(38)) } }
        val store = SupportListStore(gw)
        assertNull(store.rows)
        assertEquals(ReadEnd.OK, store.refresh())
        assertEquals(listOf(41L, 38L), store.rows?.map { it.number })
        assertFalse(store.offline)
    }

    @Test fun `a read with no answer keeps the rows and raises the offline strip, and the next read lowers it`() = runTest {
        val gw = Fake().apply { ticketsAnswer = { listOf(row(41)) } }
        val store = SupportListStore(gw)
        store.refresh()
        gw.ticketsAnswer = { throw RuntimeException("no route to host") }
        assertEquals(ReadEnd.OFFLINE, store.refresh())
        assertTrue(store.offline)
        assertEquals(listOf(41L), store.rows?.map { it.number })
        gw.ticketsAnswer = { listOf(row(41), row(42)) }
        store.refresh()
        assertFalse(store.offline)
        assertEquals(2, store.rows?.size)
    }

    @Test fun `a 404 on the list means the lane is closed`() = runTest {
        val gw = Fake().apply { ticketsAnswer = { throw TvApiError.Http(404, "{}") } }
        assertEquals(ReadEnd.GONE, SupportListStore(gw).refresh())
    }

    // ── the thread ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `opening a thread reads it and posts the read marker once per last id`() = runTest {
        val gw = Fake().apply { threadAnswer = { threadOf(1, 2, 3) } }
        var unreadReads = 0
        val store = SupportThreadStore(gw, 41) { unreadReads++ }
        store.refresh()
        store.refresh()
        assertEquals(listOf(41L to 3L), gw.reads)
        assertEquals(1, unreadReads)
        gw.threadAnswer = { threadOf(1, 2, 3, 4) }
        store.refresh()
        assertEquals(listOf(41L to 3L, 41L to 4L), gw.reads)
    }

    @Test fun `a failed read marker is not an offline state`() = runTest {
        val gw = Fake().apply { threadAnswer = { threadOf(1) }; markReadThrows = true }
        val store = SupportThreadStore(gw, 41)
        assertEquals(ReadEnd.OK, store.refresh())
        assertFalse(store.offline)
        assertEquals(1, store.thread?.messages?.size)
    }

    @Test fun `a thread read that fails keeps what was loaded`() = runTest {
        val gw = Fake().apply { threadAnswer = { threadOf(1) } }
        val store = SupportThreadStore(gw, 41)
        store.refresh()
        gw.threadAnswer = { throw RuntimeException("offline") }
        assertEquals(ReadEnd.OFFLINE, store.refresh())
        assertTrue(store.offline)
        assertEquals(1, store.thread?.messages?.size)
    }

    @Test fun `a 404 on a thread is gone`() = runTest {
        val gw = Fake().apply { threadAnswer = { throw TvApiError.Http(404, "{}") } }
        assertEquals(ReadEnd.GONE, SupportThreadStore(gw, 41).refresh())
    }

    // ── FR-5 — sending ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `no bubble before the answer - the thread is the server's until it carries the message`() = runTest {
        val gw = Fake().apply {
            threadAnswer = { threadOf(1) }
            sendAnswer = { throw RuntimeException("offline") }
        }
        val store = SupportThreadStore(gw, 41)
        store.refresh()
        val end = store.send("hello")
        assertEquals(SendEnd.Failed, end)
        assertEquals(1, store.thread?.messages?.size)
        assertEquals(SendPhase.NOT_SENT_OFFLINE, store.phase)
        assertEquals(listOf("hello"), gw.sent)
    }

    @Test fun `a 200 replaces the thread with the answer`() = runTest {
        val gw = Fake().apply {
            threadAnswer = { threadOf(1) }
            sendAnswer = { SupportSendResult.Sent(threadOf(1, 2)) }
        }
        val store = SupportThreadStore(gw, 41)
        store.refresh()
        val end = store.send("hello")
        assertIs<SendEnd.Sent>(end)
        assertFalse(end.filed)
        assertEquals(2, store.thread?.messages?.size)
        assertEquals(SendPhase.IDLE, store.phase)
        assertEquals(41L to 2L, gw.reads.last())
    }

    @Test fun `429 and 400 each show their line, 404 goes back`() = runTest {
        val gw = Fake().apply { threadAnswer = { threadOf(1) } }
        val store = SupportThreadStore(gw, 41)
        store.refresh()
        gw.sendAnswer = { SupportSendResult.Refused(429) }
        assertEquals(SendEnd.Failed, store.send("a"))
        assertEquals(SendPhase.LIMIT, store.phase)
        gw.sendAnswer = { SupportSendResult.Refused(400) }
        assertEquals(SendEnd.Failed, store.send("a"))
        assertEquals(SendPhase.TOO_LONG, store.phase)
        gw.sendAnswer = { SupportSendResult.Refused(404) }
        assertEquals(SendEnd.Gone, store.send("a"))
        assertEquals(SendPhase.IDLE, store.phase)
    }

    @Test fun `a blank draft is never sent`() = runTest {
        val gw = Fake()
        val store = SupportThreadStore(gw, 41, threadOf(1))
        assertEquals(SendEnd.Failed, store.send("  \n "))
        assertTrue(gw.sent.isEmpty())
    }

    @Test fun `try again sends the same draft once the network is back`() = runTest {
        val gw = Fake().apply { sendAnswer = { throw RuntimeException("offline") } }
        val store = SupportThreadStore(gw, 41, threadOf(1))
        store.send("hello")
        assertEquals(SendPhase.NOT_SENT_OFFLINE, store.phase)
        gw.sendAnswer = { SupportSendResult.Sent(threadOf(1, 2)) }
        assertIs<SendEnd.Sent>(store.send("hello"))
        assertEquals(listOf("hello", "hello"), gw.sent)
        assertEquals(SendPhase.IDLE, store.phase)
    }

    // ── FR-6 — the first message ──────────────────────────────────────────────────────────────────────────────────

    @Test fun `the first message files a ticket and the 201 carries the receipt`() = runTest {
        val filedThread = SupportThread(row(43), listOf(m(1), SupportMessageView(2, "SYSTEM", event = "RECEIVED", eventArgs = listOf("43"))))
        val gw = Fake().apply { sendAnswer = { SupportSendResult.Sent(filedThread) } }
        val store = SupportThreadStore(gw, number = null)
        assertNull(store.thread)
        val end = store.send("my TV drops sound")
        assertIs<SendEnd.Sent>(end)
        assertTrue(end.filed)
        assertEquals(1, gw.filed)
        assertEquals(43L, store.thread?.ticket?.number)
    }

    // ── FR-2 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the unread count is the server's, and a failed read leaves the last answer`() = runTest {
        val gw = Fake().apply { unreadAnswer = 2 }
        SupportLane.clear()
        SupportLane.readUnread(gw)
        assertEquals(2, SupportLane.unread.value)
        gw.unreadThrows = true
        SupportLane.readUnread(gw)
        assertEquals(2, SupportLane.unread.value)
        SupportLane.clear()
        assertEquals(0, SupportLane.unread.value)
    }

    @Test fun `a change that lands while the first read is in flight is read again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val gw = object : SupportGateway by Fake() {
            override suspend fun unread(): Int { reads++; if (reads == 1) gate.await(); return reads }
        }
        val job = launch { SupportLane.follow { SupportLane.readUnread(gw) } }
        runCurrent()
        assertEquals(1, reads)
        SupportLane.changes.emit(SupportLane.ALL)   // the first read has not answered: nobody was listening before this fix
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, reads)
        job.cancel()
    }

    @Test fun `a change another ticket sent is not read again by the thread`() = runTest {
        var reads = 0
        val job = launch { SupportLane.follow({ it == 41L || it == SupportLane.ALL }) { reads++ } }
        runCurrent()
        SupportLane.changes.emit(38L)
        runCurrent()
        assertEquals(1, reads)
        SupportLane.changes.emit(41L)
        runCurrent()
        assertEquals(2, reads)
        job.cancel()
    }
}
