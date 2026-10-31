package dev.jellystructure.shared.tv

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** DEB-3169 (DEB-3103 FR-6) — the warm-up reads a segment to its end through one small buffer and keeps none of it. */
class HlsWarmDrainTest {
    @Test
    fun `a segment far larger than the buffer is read to its end through that buffer alone`() = runBlocking {
        val body = ByteChannel()
        val piece = ByteArray(64 * 1024)
        launch {
            repeat(160) { body.writeFully(piece) }   // 10 MB, the size of a 2160p segment
            body.close()
        }
        // A 1 KB buffer cannot hold what it counts: the bytes pass through it and are gone.
        assertEquals(160L * 64 * 1024, HlsWarm.drain(body, ByteArray(1024)))
    }

    @Test
    fun `a body that ends in an error fails the attempt`() = runBlocking {
        val body = ByteChannel()
        launch {
            body.writeFully(ByteArray(4096))
            body.close(IllegalStateException("the connection dropped"))
        }
        // Ktor surfaces the writer's failure as a closed-channel error carrying it as the cause.
        val e = assertFailsWith<ClosedByteChannelException> { HlsWarm.drain(body) }
        assertEquals("the connection dropped", generateSequence<Throwable>(e) { it.cause }.last().message)
        Unit
    }

    @Test
    fun `an empty body is zero bytes and not a failure`() = runBlocking {
        val body = ByteChannel().also { it.close() }
        assertEquals(0L, HlsWarm.drain(body))
    }
}
