package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R293 FR-R293-6 — the previous-sockets header: bounded, whitelisted, newest first, ten deep. */
class EventsSocketLogTest {
    @Test
    fun `nothing ended yet means no header`() {
        assertNull(EventsSocketLog().headerValue())
    }

    @Test
    fun `entries are newest first and whitelisted`() {
        val log = EventsSocketLog()
        log.record(68_400L, "close:1000:background", "started;on;wifi+ok;31")
        log.record(2_100L, "err:ClosedReceiveChannelException", "created;off;none;31")
        assertEquals("2;err:ClosedReceiveChannelException;created;off;none;31,68;close:1000:background;started;on;wifi+ok;31", log.headerValue())
    }

    @Test
    fun `a token or a URL in a reason can never reach the header`() {
        assertEquals("http:__host_path_token_abc", EventsSocketLog.clean("http://host/path?token=abc"))
        assertEquals("-", EventsSocketLog.clean("   "))
    }

    @Test
    fun `ten are kept and the value is bounded`() {
        val log = EventsSocketLog()
        repeat(30) { log.record(it * 1000L, "err:" + "X".repeat(60), "a;b;c;d") }
        assertEquals(10, log.size)
        assertTrue(log.headerValue()!!.length <= EventsSocketLog.MAX_HEADER_BYTES)
    }
}
