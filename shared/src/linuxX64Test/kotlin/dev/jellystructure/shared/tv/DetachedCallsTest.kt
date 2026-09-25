package dev.jellystructure.shared.tv

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.pthread_self
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R305 — a request whose caller is cancelled is cancelled too (FR-R305-2), but its cancellation handlers
 * run on another thread than the caller's (FR-R305-1): Ktor's Android engine closes its socket in exactly
 * such a handler, and on the main thread that closing is a dead process.
 */
@OptIn(ExperimentalForeignApi::class, InternalCoroutinesApi::class)
class DetachedCallsTest {
    @Test
    fun `a cancelled caller cancels its request off the caller thread`() = runBlocking {
        val calls = DetachedCalls()
        val callerThread = pthread_self()
        val started = CompletableDeferred<Unit>()
        val handlerThread = CompletableDeferred<ULong>()
        val caller = launch {
            calls.run {
                // What Ktor's response channel does (the same internal API): a handler run when the request is cancelled.
                currentCoroutineContext().job.invokeOnCompletion(onCancelling = true) { cause ->
                    if (cause is CancellationException) handlerThread.complete(pthread_self())
                }
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        caller.cancel()   // on this (the "main") thread, as Compose cancels an effect
        val ranOn = withTimeout(5_000) { handlerThread.await() }
        assertNotEquals(callerThread, ranOn, "the request's cancellation ran on the caller's thread")
        caller.join()
        assertTrue(caller.isCancelled)
    }

    @Test
    fun `results and failures reach the caller as a direct call's would`() = runBlocking {
        val calls = DetachedCalls()
        assertEquals(42, calls.run { 42 })
        val e = assertFailsWith<IllegalStateException> { calls.run<Int> { error("server said no") } }
        assertEquals("server said no", e.message)
    }
}
