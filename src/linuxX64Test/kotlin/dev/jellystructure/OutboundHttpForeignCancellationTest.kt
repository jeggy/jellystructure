package dev.jellystructure

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Phase 310 (dev review item 3) — at the one place all outbound HTTP passes, a cancellation the caller did not cause
 * becomes an I/O failure (and an idempotent call is tried once more); the caller's own cancellation is untouched.
 */
class OutboundHttpForeignCancellationTest {
    private suspend fun foreignTimeout(): TimeoutCancellationException =
        runCatching { withTimeout(1) { awaitCancellation() } }.exceptionOrNull() as TimeoutCancellationException

    @Test
    fun `a foreign cancellation becomes an IOException`() = runBlocking {
        val tce = foreignTimeout()
        val e = assertFailsWith<OutboundHttp.ForeignCancellationException> { OutboundHttp.withPermit<Unit> { throw tce } }
        assertIs<kotlinx.io.IOException>(e)
        assertEquals(tce, e.cause)
    }

    @Test
    fun `an idempotent call is retried once after a foreign cancellation`() = runBlocking {
        val tce = foreignTimeout()
        var calls = 0
        val r = OutboundHttp.withPermit(retryForeignCancellation = true) { calls++; if (calls == 1) throw tce; "ok" }
        assertEquals("ok", r)
        assertEquals(2, calls)
    }

    @Test
    fun `the caller's own timeout still propagates as a cancellation`() = runBlocking {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(50) { OutboundHttp.withPermit { delay(5_000) } }
        }
        Unit
    }
}
