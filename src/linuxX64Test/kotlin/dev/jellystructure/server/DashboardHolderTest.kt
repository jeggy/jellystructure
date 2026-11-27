package dev.jellystructure.server

import dev.jellystructure.model.DashboardDto
import dev.jellystructure.server.routes.DashboardHolder
import dev.jellystructure.server.routes.HeldOverview
import dev.jellystructure.server.routes.servedOverview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2498 — FR-285-15 (a request never builds), FR-285-16 (one owner; triggers coalesce into one follow-up build),
 * FR-285-17 (`built: false` before the first build), FR-285-18 (a row action waits for the coalesced build, bounded).
 */
class DashboardHolderTest {
    private class Probe {
        val started = AtomicInt(0)
        val running = AtomicInt(0)
        val maxRunning = AtomicInt(0)
        val gate = AtomicGate()
    }

    /** A build can be held open until the test says so; [open] lets every later build straight through. */
    private class AtomicGate {
        private val current = CompletableDeferred<Unit>()
        suspend fun pass() = current.await()
        fun open() = current.complete(Unit)
    }

    private fun holder(scope: CoroutineScope, p: Probe, maxAgeMs: Long = 600_000L, fail: () -> Boolean = { false }) =
        DashboardHolder(scope, build = {
            val n = p.started.incrementAndGet()
            val now = p.running.incrementAndGet()
            if (now > p.maxRunning.value) p.maxRunning.value = now
            try {
                p.gate.pass()
                if (fail()) error("outside service down")
                HeldOverview(DashboardDto(computedAt = n.toLong()), emptyMap())
            } finally { p.running.decrementAndGet() }
        }, maxAgeMs = maxAgeMs, minGapMs = 0)

    private suspend fun waitUntil(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        var waited = 0L
        while (!cond()) { check(waited < timeoutMs) { "timed out waiting for $what" }; delay(10); waited += 10 }
    }

    @Test fun `before the first build the response says built false and carries no rows`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val p = Probe()
        val h = holder(scope, p)
        waitUntil("the boot build to start") { p.started.value == 1 }
        assertNull(h.current)
        val served = servedOverview(h.current) { null }
        assertFalse(served.built, "an overview nobody built must not read as an empty, healthy one (FR-285-17)")
        assertTrue(served.rows.isEmpty())
        p.gate.open()
        waitUntil("the first build to land") { h.current != null }
        assertTrue(servedOverview(h.current) { null }.built)
        scope.cancel()
    }

    @Test fun `reading the overview never invokes the builder`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val p = Probe()
        p.gate.open()
        val h = holder(scope, p)
        waitUntil("the boot build") { h.current != null }
        val before = p.started.value
        repeat(2_000) { assertNotNull(servedOverview(h.current) { null }) }
        delay(100)
        assertEquals(before, p.started.value, "a GET is a read; only the worker builds (FR-285-15)")
        scope.cancel()
    }

    @Test fun `triggers during a build coalesce into one follow-up and builds never overlap`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val p = Probe()
        val h = holder(scope, p)
        waitUntil("the boot build to start") { p.started.value == 1 }
        (1..50).map { async(Dispatchers.Default) { h.request() } }.awaitAll()
        p.gate.open()
        waitUntil("the follow-up build") { h.buildCount >= 2 }
        delay(300)   // a third build would have started by now if the 50 triggers had not coalesced
        assertEquals(2, p.started.value, "the boot build plus exactly one follow-up (FR-285-16)")
        assertEquals(1, p.maxRunning.value, "one owner: a build is never run twice at once")
        scope.cancel()
    }

    @Test fun `a row action waits for a build that started after it and gets the new overview`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val p = Probe()
        p.gate.open()
        val h = holder(scope, p)
        waitUntil("the boot build") { h.current != null }
        val seen = h.current!!.dto.computedAt
        assertTrue(h.requestAndWait(timeoutMs = 5_000), "the build landed inside the bound")
        assertTrue(h.current!!.dto.computedAt > seen, "the list read after the action is the one built after it")
        scope.cancel()
    }

    @Test fun `a row action's wait is bounded when the build is slow`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val p = Probe()
        val h = holder(scope, p)   // gate stays shut: the boot build never finishes
        waitUntil("the boot build to start") { p.started.value == 1 }
        assertFalse(h.requestAndWait(timeoutMs = 150), "past the bound the action answers anyway; the push delivers the overview later (FR-285-18)")
        scope.cancel()
    }

    @Test fun `a failed build keeps the last overview and releases the waiter`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val p = Probe()
        p.gate.open()
        val failing = AtomicInt(0)
        val h = holder(scope, p, fail = { failing.value == 1 })
        waitUntil("the boot build") { h.current != null }
        val good = h.current
        failing.value = 1
        h.requestAndWait(timeoutMs = 5_000)
        assertTrue(h.current === good, "the page keeps what it had")
        scope.cancel()
    }

    @Test fun `with no trigger the overview is rebuilt on the interval`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val p = Probe()
        p.gate.open()
        holder(scope, p, maxAgeMs = 60)
        waitUntil("three builds with nobody asking") { p.started.value >= 3 }
        scope.cancel()
    }
}
