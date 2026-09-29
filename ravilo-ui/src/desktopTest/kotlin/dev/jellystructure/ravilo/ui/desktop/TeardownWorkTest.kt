package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.TeardownWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeardownWorkTest {
    @Test
    fun `the quit waits for a tracked stop`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var sent = false
        TeardownWork.track(launch { gate.await(); sent = true })
        val waiting = async { TeardownWork.awaitAll() }
        yield()
        assertFalse(waiting.isCompleted, "the stop has not been sent yet")
        gate.complete(Unit)
        waiting.await()
        assertTrue(sent)
        TeardownWork.awaitAll()   // nothing left: returns at once
    }
}
