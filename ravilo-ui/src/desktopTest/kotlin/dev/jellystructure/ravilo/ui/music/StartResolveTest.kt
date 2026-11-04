package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** DEB-3599 — FR-3/FR-5: a song started while the server is unreachable starts by itself once it is back. */
class StartResolveTest {
    /** A clock that only moves when [pause] or a failing attempt moves it. */
    private class Clock { var t = 0L }

    @Test
    fun `a server that answers at once is resolved with no failures`() = runBlocking {
        val ok = assertIs<StartResolve.Resolved<String>>(resolveStart(ask = { "ticket" }))
        assertEquals("ticket", ok.value)
        assertEquals(0, ok.failures)
    }

    @Test
    fun `an unreachable server is asked again until it is back, and that is one restart`() = runBlocking {
        val clock = Clock()
        val lines = mutableListOf<Throwable>()
        var attempts = 0
        val r = resolveStart(
            now = { clock.t },
            pause = { clock.t += it },
            onUnreachable = { lines += it },
            ask = {
                attempts++
                clock.t += 5_000L                       // the connect timeout
                if (clock.t < 15_000L) throw IOException("connect timed out") else "ticket"
            },
        )
        val ok = assertIs<StartResolve.Resolved<String>>(r)
        assertEquals("ticket", ok.value)
        assertEquals(2, ok.failures)                   // 0–5 s and 8–13 s failed, 16–21 s answered
        assertEquals(3, attempts)
        assertEquals(1, lines.size)                    // the log line comes once, with the first failure
        assertEquals(21_000L, ok.waitedMs)               // 5 + 3 + 5 + 3 + 5 s
    }

    @Test
    fun `a server that stays away ends on the sheet after the window`() = runBlocking {
        val clock = Clock()
        val r = resolveStart(windowMs = 45_000L, now = { clock.t }, pause = { clock.t += it }, ask = { clock.t += 5_000L; throw IOException("down") })
        val f = assertIs<StartResolve.Failed>(r)
        assertTrue(f.network)
        assertTrue(f.waitedMs <= 45_000L && f.failures >= 5)
    }

    @Test
    fun `a server that answers with an error status is never asked again`() = runBlocking {
        var attempts = 0
        val r = resolveStart(ask = { attempts++; throw TvApiError.Http(404, "gone") })
        val f = assertIs<StartResolve.Failed>(r)
        assertEquals(false, f.network)
        assertEquals(1, attempts)
    }

    @Test
    fun `cancelling the start is not a failure and is not retried`() {
        var attempts = 0
        assertFailsWith<CancellationException> { runBlocking { resolveStart(ask = { attempts++; throw CancellationException("skipped") }) } }
        assertEquals(1, attempts)
    }

    @Test
    fun `no client is a failure that waits like a network one`() = runBlocking {
        val clock = Clock()
        var attempts = 0
        val r = resolveStart(windowMs = 10_000L, now = { clock.t }, pause = { clock.t += it }, ask = { attempts++; null })
        assertIs<StartResolve.Failed>(r)
        assertTrue(attempts > 1)
    }
}
