package dev.jellystructure.tv

import io.ktor.websocket.CloseReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 256 acceptance 1 — the close classifier, the header sanitizer and the flap counter. */
class EventsSocketHealthTest {
    @Test
    fun `four causes classify`() {
        assertEquals("client close 1000 background", EventsCloseCause.classify(CloseReason(CloseReason.Codes.NORMAL, "background"), null, false))
        assertEquals("eof", EventsCloseCause.classify(null, null, false))
        assertEquals("ping timeout", EventsCloseCause.classify(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Ping timeout"), null, false))
        assertEquals("replaced", EventsCloseCause.classify(CloseReason(CloseReason.Codes.NORMAL, ""), null, true))
        assertEquals("reset", EventsCloseCause.classify(null, RuntimeException("recv: ECONNRESET on ws://host/api/tv/events?token=SECRET"), false))
        assertEquals("server error IllegalStateException", EventsCloseCause.classify(null, IllegalStateException("something with a token=SECRET"), false))
    }

    @Test
    fun `the previous-sockets header is bounded and whitelisted`() {
        assertNull(sanitizeEventsPrev(null))
        assertNull(sanitizeEventsPrev("   "))
        assertEquals("68;close:1000:background;started;on;wifi+ok;31", sanitizeEventsPrev("68;close:1000:background;started;on;wifi+ok;31"))
        // a seventh field is dropped, a URL is neutered, a long field is cut
        val out = sanitizeEventsPrev("2;err:X;created;off;none;31;EXTRA,5;http://h/p?token=abc;s;on;eth;31")
        assertEquals("2;err:X;created;off;none;31,5;http:__h_p_token_abc;s;on;eth;31", out)
        val big = sanitizeEventsPrev((1..40).joinToString(",") { "$it;err:${"Y".repeat(80)};a;b;c;d" })!!
        assertTrue(big.split(',').size <= 10)
        assertTrue(big.length <= 512 + 10 * 6)   // bounded by the 512-byte take, then per-field cuts only shorten
    }

    @Test
    fun `the flap counter crosses twelve an hour and clears`() {
        var now = 0L
        val c = DeviceFlapCounter(threshold = 12, clock = { now })
        repeat(12) { c.connected("tv"); c.closed("tv", 68_000L); now += 60_000L }
        assertNull(c.unstable("tv", now))                       // 12 is the threshold, not above it
        c.connected("tv"); now += 1_000L
        val st = assertNotNull(c.unstable("tv", now))
        assertEquals(13, st.connectsLastHour)
        assertEquals(68_000L, st.medianLifetimeMs)
        assertNotNull(c.warnDue("tv", now))
        assertNull(c.warnDue("tv", now + 10_000L))             // once per hour
        now += 3_600_000L + 1L                                  // the window rolls past every connect
        assertNull(c.unstable("tv", now))
        assertEquals(0, c.stats("tv", now).connectsLastHour)
        assertEquals(emptyList(), c.unstableSnapshot(now))
    }

    @Test
    fun `behind is said only after seven days and never for a dev build`() {
        val day = 24 * 3_600_000L
        val t = VersionBehindTracker(graceMs = 7 * day)
        assertNull(t.observe("d", "1.35", "1.38", 0L))                  // first seen: not yet
        assertNull(t.observe("d", "1.35", "1.38", 6 * day))
        assertEquals(VersionBehindTracker.Behind(3, 0L), t.observe("d", "1.35", "1.38", 7 * day))
        assertNull(t.observe("d", "1.37", "1.38", 8 * day))             // one behind is not "behind"
        assertNull(t.observe("d", "1.35", "1.38-6-gabcdef1", 9 * day))  // a dev server says nothing
        assertNull(t.observe("d", "1.35-2-gabcdef1", "1.38", 9 * day))  // nor a dev client
    }
}
