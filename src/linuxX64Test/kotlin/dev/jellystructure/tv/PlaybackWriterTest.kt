package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 219 (FR-219-6) — the writer retries a failure and a thrown timeout, drops a superseded tick
 * silently, and lands a stop after the caller's own scope is cancelled (the request handler going away
 * mid-write is exactly the case that used to lose the position).
 */
class PlaybackWriterTest {
    private fun device(id: String = "tv1") = DeviceData(
        deviceId = id, deviceToken = "dt-$id", jellyfinUserId = "user-1", jellyfinUsername = "jeggy",
        jellyfinUserToken = "jt-$id", isAdmin = false,
    )

    private class FakeSink(var failFirst: Int = 0) : PlaybackSink {
        val progressLanded = mutableListOf<Long>()
        val stopsLanded = mutableListOf<Long>()
        var calls = 0
        override suspend fun progress(w: PlaybackWriter.PendingWrite): Boolean {
            calls++
            if (failFirst > 0) { failFirst--; throw IllegalStateException("Timed out waiting for 6000 ms") }
            progressLanded += w.positionMs; return true
        }
        override suspend fun stop(w: PlaybackWriter.PendingWrite): Boolean {
            calls++
            if (failFirst > 0) { failFirst--; return false }
            stopsLanded += w.positionMs; return true
        }
    }

    private suspend fun PlaybackWriter.awaitIdle() = withTimeout(5_000) { while (!isIdle()) delay(10) }

    @Test
    fun `a failed progress write is retried until it lands`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sink = FakeSink(failFirst = 2)
        val writer = PlaybackWriter(scope, sink, baseBackoffMs = 5, maxBackoffMs = 20)
        writer.enqueueProgress(device(), "ep1", 1_000L, false)
        writer.awaitIdle()
        assertEquals(listOf(1_000L), sink.progressLanded)
        assertEquals(3, sink.calls, "two failures, then the ack")
        assertEquals(1L, writer.stats().landed)
        scope.cancel()
    }

    @Test
    fun `a superseded tick is dropped and the newest position wins`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sink = FakeSink(failFirst = 1)
        val writer = PlaybackWriter(scope, sink, baseBackoffMs = 30, maxBackoffMs = 60)
        val tv = device()
        writer.enqueueProgress(tv, "ep1", 1_000L, false)
        writer.enqueueProgress(tv, "ep1", 2_000L, false)
        writer.enqueueProgress(tv, "ep1", 3_000L, false)
        writer.awaitIdle()
        assertEquals(listOf(3_000L), sink.progressLanded, "only the last position for the key reaches Jellyfin")
        assertTrue(writer.stats().superseded >= 2)
        scope.cancel()
    }

    @Test
    fun `a stop lands after the caller's own scope is cancelled`() = runBlocking {
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sink = FakeSink(failFirst = 1)
        val writer = PlaybackWriter(writerScope, sink, baseBackoffMs = 5, maxBackoffMs = 20)
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        requestScope.launch { writer.enqueueStop(device(), "ep1", 44_000L, "psid") }.join()
        requestScope.cancel()   // the request handler is gone; the write must not be
        writer.awaitIdle()
        assertEquals(listOf(44_000L), sink.stopsLanded)
        writerScope.cancel()
    }

    @Test
    fun `a straggling progress tick never overwrites a waiting stop`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sink = FakeSink(failFirst = 1)
        val writer = PlaybackWriter(scope, sink, baseBackoffMs = 30, maxBackoffMs = 60)
        val tv = device()
        writer.enqueueStop(tv, "ep1", 50_000L, null)
        writer.enqueueProgress(tv, "ep1", 51_000L, false)
        writer.awaitIdle()
        assertEquals(listOf(50_000L), sink.stopsLanded)
        assertTrue(sink.progressLanded.isEmpty())
        scope.cancel()
    }
}
