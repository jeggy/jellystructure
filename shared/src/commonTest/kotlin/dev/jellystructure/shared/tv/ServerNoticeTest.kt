package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R354 (FR-R354-9) — the dashboard's *Send message*, as every screen reads it. */
class ServerNoticeTest {

    /** The frame the server writes (`TvEventBus.notifyServerMessage`), decoded the way the app's events socket does. */
    private fun frame(raw: String) = RaviloWireJson.decodeFromString(ServerMessageEnvelope.serializer(), raw)

    @Test
    fun `the server frame reads back with header and text and timeout`() {
        val env = frame("""{"type":"server_message","text":"Dinner is ready","header":"Mum","timeout_ms":5000}""")
        assertEquals(ServerNotice("Mum", "Dinner is ready", 5_000L), serverNoticeOf(env))
    }

    @Test
    fun `no header and no timeout gives the text alone timed by its length`() {
        val env = frame("""{"type":"server_message","text":"Bedtime"}""")
        assertEquals(ServerNotice(null, "Bedtime", 3_000L), serverNoticeOf(env))
        // 1.5 s + 75 ms a character, at most 15 s.
        val long = "x".repeat(400)
        assertEquals(15_000L, serverNoticeOf(frame("""{"type":"server_message","text":"$long"}"""))!!.durationMs)
        assertEquals(1_500L + 40 * 75L, serverNoticeOf(ServerMessageEnvelope(text = "y".repeat(40)))!!.durationMs)
    }

    @Test
    fun `the header counts toward an untimed message's length`() {
        val n = serverNoticeOf(ServerMessageEnvelope(text = "a".repeat(30), header = "b".repeat(20)))!!
        assertEquals(1_500L + (20 + 1 + 30) * 75L, n.durationMs)
    }

    @Test
    fun `a sender's timeout is kept between 3 and 60 seconds`() {
        assertEquals(3_000L, serverNoticeOf(ServerMessageEnvelope(text = "hi", timeoutMs = 500))!!.durationMs)
        assertEquals(60_000L, serverNoticeOf(ServerMessageEnvelope(text = "hi", timeoutMs = 600_000))!!.durationMs)
        assertEquals(12_000L, serverNoticeOf(ServerMessageEnvelope(text = "hi", timeoutMs = 12_000))!!.durationMs)
        // Zero or negative is "not given": the length rule.
        assertEquals(3_000L, serverNoticeOf(ServerMessageEnvelope(text = "hi", timeoutMs = 0))!!.durationMs)
    }

    @Test
    fun `blank text shows nothing and a blank header is no header and both are trimmed`() {
        assertNull(serverNoticeOf(ServerMessageEnvelope(text = "   ", header = "Mum")))
        assertNull(serverNoticeOf(frame("""{"type":"server_message"}""")))
        assertEquals(ServerNotice(null, "Hello", 4_000L), serverNoticeOf(ServerMessageEnvelope(text = "  Hello ", header = "  ", timeoutMs = 4_000)))
        assertEquals("Mum", serverNoticeOf(ServerMessageEnvelope(text = "x", header = " Mum "))!!.header)
    }

    @Test
    fun `a receiver with a screen takes messages and a speaker does not`() {
        assertEquals(REMOTE_DECLARATION_RECEIVER, receiverRemoteDeclaration(headless = true))
        assertEquals("DisplayMessage,$REMOTE_DECLARATION_RECEIVER", receiverRemoteDeclaration(headless = false))
        // The phone and the other apps declare it too (FR-R354-1).
        assertEquals("DisplayMessage", REMOTE_DECLARATION_APP.split(',').first())
    }
}
