package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.db.createDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 310 (FR-310-8 tests 1–5, owner decision 2) — the playback writer survives what ended it on 2026-10-06: a
 * **real** `TimeoutCancellationException` that is not the writer's own (the old test faked it as an
 * `IllegalStateException`, which is why the rethrow was never exercised), a hung call, a slow after-stop step, and a
 * restart with stops still queued. Only the writer's own cancellation stops it.
 */
class PlaybackWriterSurvivesTest {
    private fun device(id: String) = DeviceData(
        deviceId = id, deviceToken = "dt-$id", jellyfinUserId = "user-1", jellyfinUsername = "jeggy",
        jellyfinUserToken = "jt-$id", isAdmin = false,
    )

    /** A real TimeoutCancellationException, the kind Ktor's Curl engine handed to an unrelated call (its constructor is internal). */
    private suspend fun foreignTimeout(): TimeoutCancellationException =
        runCatching { withTimeout(1) { awaitCancellation() } }.exceptionOrNull() as TimeoutCancellationException

    private open class Sink : PlaybackSink {
        val progressLanded = mutableListOf<Pair<String, Long>>()
        val stopsLanded = mutableListOf<Pair<String, Long>>()
        val afterStops = mutableListOf<String>()
        var calls = 0
        open suspend fun before(w: PlaybackWriter.PendingWrite) {}
        override suspend fun progress(w: PlaybackWriter.PendingWrite): Boolean { calls++; before(w); progressLanded += w.jellyfinId to w.positionMs; return true }
        override suspend fun stop(w: PlaybackWriter.PendingWrite): Boolean { calls++; before(w); stopsLanded += w.jellyfinId to w.positionMs; return true }
        override suspend fun afterStop(w: PlaybackWriter.PendingWrite) { afterStops += w.jellyfinId }
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 5_000, check: suspend () -> Boolean) {
        // (each check runs to completion; a throw in one counts as a failed check)
        if (runCatching { withTimeout(timeoutMs) { while (!check()) delay(10) } }.isFailure) fail("timed out waiting for: $what")
    }

    @Test
    fun `a foreign TimeoutCancellationException fails one write and the writer goes on`() = runBlocking {
        val tce = foreignTimeout()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var thrown = false
        val sink = object : Sink() {
            override suspend fun before(w: PlaybackWriter.PendingWrite) { if (w.jellyfinId == "ep-a" && !thrown) { thrown = true; throw tce } }
        }
        val writer = PlaybackWriter(scope, sink, baseBackoffMs = 5, maxBackoffMs = 20)
        writer.enqueueProgress(device("tv-a"), "ep-a", 1_000L, false)
        writer.enqueueProgress(device("tv-b"), "ep-b", 2_000L, false)
        waitFor("both writes land") { writer.isIdle() }
        assertTrue(thrown, "the foreign cancellation was thrown")
        assertTrue("ep-a" to 1_000L in sink.progressLanded, "the failed write was retried and landed")
        assertTrue("ep-b" to 2_000L in sink.progressLanded, "the other device's write landed too")
        val s = writer.stats()
        assertTrue(s.alive, "the writer is still running")
        assertEquals(0L, s.restarts)
        assertTrue(s.lastFailure!!.contains("TimeoutCancellationException"), s.lastFailure)
        scope.cancel()
    }

    @Test
    fun `a call that never returns fails at the deadline and other writes land meanwhile`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var hungCalls = 0
        val sink = object : Sink() {
            override suspend fun before(w: PlaybackWriter.PendingWrite) { if (w.jellyfinId == "ep-hung") { hungCalls++; awaitCancellation() } }
        }
        val writer = PlaybackWriter(scope, sink, baseBackoffMs = 50, maxBackoffMs = 100, attemptDeadlineMs = 150)
        writer.enqueueProgress(device("tv-a"), "ep-hung", 1_000L, false)
        writer.enqueueProgress(device("tv-b"), "ep-ok", 2_000L, false)
        waitFor("the other device's write lands") { "ep-ok" to 2_000L in sink.progressLanded }
        waitFor("the hung write is tried again") { hungCalls >= 2 }
        assertTrue(writer.stats().alive)
        assertTrue(writer.stats().lastFailure!!.contains("TimeoutCancellationException"))
        scope.cancel()
    }

    @Test
    fun `a slow after-stop step never holds the next write`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sink = object : Sink() {
            override suspend fun afterStop(w: PlaybackWriter.PendingWrite) { afterStops += w.jellyfinId; awaitCancellation() }
        }
        val writer = PlaybackWriter(scope, sink, baseBackoffMs = 5, maxBackoffMs = 20)
        writer.enqueueStop(device("tv-a"), "ep-1", 60_000L, "psid")
        waitFor("the after-stop step started") { "ep-1" in sink.afterStops }
        writer.enqueueProgress(device("tv-b"), "ep-2", 5_000L, false)
        waitFor("the next write lands at once", timeoutMs = 1_000) { "ep-2" to 5_000L in sink.progressLanded }
        assertEquals(listOf("ep-1" to 60_000L), sink.stopsLanded)
        scope.cancel()
    }

    @Test
    fun `the writer's own cancellation stops it — with no restart`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writer = PlaybackWriter(scope, Sink(), baseBackoffMs = 5, maxBackoffMs = 20, restartDelayMs = 10)
        waitFor("the writer runs") { writer.stats().alive }
        scope.cancel()
        delay(100)
        val s = writer.stats()
        assertFalse(s.alive, "cancelled")
        assertEquals(0L, s.restarts)
    }

    @Test
    fun `a drain loop that ends on any throwable is restarted and counted`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var blowUp = true
        // The drain reads the clock on every turn; a clock that throws once ends the loop the way a stray bug would.
        val clock: () -> Long = { if (blowUp) { blowUp = false; throw IllegalStateException("boom") }; 0L }
        val sink = Sink()
        val writer = PlaybackWriter(scope, sink, clock = clock, baseBackoffMs = 5, maxBackoffMs = 20, restartDelayMs = 10)
        waitFor("the drain loop hit the fault") { !blowUp }   // nothing else may read the clock before it throws
        waitFor("restarted once") { writer.stats().restarts == 1L }
        writer.enqueueProgress(device("tv-a"), "ep-1", 1_000L, false)
        waitFor("it writes again after the restart") { "ep-1" to 1_000L in sink.progressLanded }
        assertTrue(writer.stats().alive)
        scope.cancel()
    }

    @Test
    fun `a queued stop survives a restart and lands from the outbox`() = runBlocking {
        val db = createDatabase("/tmp/jellystructure-test-310-outbox-${getpid()}.db")
        val tv = device("tv-outbox")
        val outbox = SqlPlaybackOutbox(db, { id, _ -> if (id == tv.deviceId) tv else null })
        // Run 1: Jellyfin never answers; the stop waits in the outbox when the process goes away.
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val never = object : Sink() { override suspend fun stop(w: PlaybackWriter.PendingWrite): Boolean = false }
        val w1 = PlaybackWriter(scope1, never, baseBackoffMs = 1_000, maxBackoffMs = 1_000, outbox = outbox)
        w1.enqueueStop(tv, "ep-9", 333_000L, "psid-9", reason = "user", userData = StopUserData(played = false, positionMs = 333_000L, lastPlayedDate = null))
        delay(50)
        scope1.cancel()
        assertEquals(1, outbox.loadAll().size, "kept in SQLite")
        // Run 2: a fresh writer on the same database lands it, user data and all, and forgets it.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sink = Sink()
        val w2 = PlaybackWriter(scope2, sink, baseBackoffMs = 5, maxBackoffMs = 20, outbox = outbox)
        waitFor("the restored stop lands") { "ep-9" to 333_000L in sink.stopsLanded }
        waitFor("the outbox is empty") { outbox.loadAll().isEmpty() }
        assertTrue(w2.stats().alive)
        scope2.cancel()
        platform.posix.unlink("/tmp/jellystructure-test-310-outbox-${getpid()}.db")
        Unit
    }
}
