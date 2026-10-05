package dev.jellystructure.shared.tv

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R370 (found on the Pixel) — a frame emitted the moment the socket opens reaches the socket. Before, the sender was
 * launched after `onOpen`, so the phone's `cast_devices_seen` (and a remote's re-attach) went to a flow nobody collected.
 */
class EventsSenderTest {
    @Test fun `a frame emitted right after the sender starts is sent`() = runBlocking {
        val outgoing = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val sent = mutableListOf<String>()
        val job = launchEventsSender(outgoing) { sent += it }!!
        // What onOpen does: emit at once, without a suspension in between.
        check(outgoing.tryEmit("""{"type":"cast_devices_seen","devices":[]}"""))
        withTimeout(2_000) { while (sent.isEmpty()) yield() }
        assertEquals(listOf("""{"type":"cast_devices_seen","devices":[]}"""), sent)
        job.cancel()
    }

    @Test fun `no outgoing flow starts no sender`() = runBlocking {
        assertEquals(null, launchEventsSender(null) {})
    }
}
