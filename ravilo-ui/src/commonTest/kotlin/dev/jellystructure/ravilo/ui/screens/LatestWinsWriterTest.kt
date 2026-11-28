package dev.jellystructure.ravilo.ui.screens

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-5839 (FR-5) — Home's saved copy is written off the caller's thread, and the later save is the one left. */
class LatestWinsWriterTest {
    @Test
    fun submitReturnsWithoutRunningTheWriteOnTheCaller() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val ran = CompletableDeferred<Unit>()
        val writer = LatestWinsWriter<String, String>(write = { _, _ -> gate.await0(); ran.complete(Unit) })
        writer.submit("u", "v")          // would deadlock here if the write ran on this thread
        assertTrue(!ran.isCompleted)
        gate.complete(Unit)
        withTimeout(5_000) { ran.await() }
    }

    @Test
    fun twoQuickSavesLeaveTheLaterOne() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val written = mutableListOf<Pair<String, String?>>()
        val writer = LatestWinsWriter<String, String>(write = { k, v ->
            gate.await0()
            synchronized(written) { written += k to v }
            if (v == "second") done.complete(Unit)
        })
        writer.submit("u", "first")
        writer.submit("u", "second")
        gate.complete(Unit)
        withTimeout(5_000) { done.await() }
        synchronized(written) { assertEquals("second", written.last().second) }
    }

    @Test
    fun aDeleteKeepsItsPlaceAfterAnEarlierSave() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val written = mutableListOf<String?>()
        val writer = LatestWinsWriter<String, String>(write = { _, v ->
            gate.await0()
            synchronized(written) { written += v }
            if (v == null) done.complete(Unit)
        })
        writer.submit("u", "saved")
        writer.submit("u", null)
        gate.complete(Unit)
        withTimeout(5_000) { done.await() }
        synchronized(written) { assertEquals(null, written.last()) }
    }
}

/** The writer's lambda is not suspending, so the tests park its thread until the gate opens. */
private fun CompletableDeferred<Unit>.await0() {
    val g = this
    runBlocking { withTimeout(5_000) { g.await() } }
}
