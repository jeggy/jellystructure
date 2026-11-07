package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.LadderRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-3213 (DEB-3093 FR-9, FR-3 to FR-5) — the web player's answers to trouble, as pure decisions. */
class WebTroubleTest {

    // ── FR-3 / FR-4 — hls.js's load policies ──

    private fun policy(name: String): JsonObject = Json.parseToJsonElement(WebHlsPolicy.configJson()).jsonObject.getValue(name).jsonObject.getValue("default").jsonObject

    @Test fun `segments master and playlists wait 20 s for a first byte`() {
        for (k in listOf("fragLoadPolicy", "manifestLoadPolicy", "playlistLoadPolicy")) {
            assertEquals(20_000, policy(k).getValue("maxTimeToFirstByteMs").jsonPrimitive.int, k)
        }
    }

    @Test fun `all three policies retry five times from 0_5 s exponentially to at most 5 s — a timeout as well as an error`() {
        for (k in listOf("fragLoadPolicy", "manifestLoadPolicy", "playlistLoadPolicy")) {
            for (r in listOf("errorRetry", "timeoutRetry")) {
                val retry = policy(k).getValue(r).jsonObject
                assertEquals(5, retry.getValue("maxNumRetry").jsonPrimitive.int, "$k $r")
                assertEquals(500, retry.getValue("retryDelayMs").jsonPrimitive.int, "$k $r")
                assertEquals(5_000, retry.getValue("maxRetryDelayMs").jsonPrimitive.int, "$k $r")
                assertEquals("exponential", retry.getValue("backoff").jsonPrimitive.content, "$k $r")
            }
        }
    }

    @Test fun `hls_js's exponential delays are the schedule Android has — 0_5 1 2 4 then 5 s`() {
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 5_000L), (0 until 5).map { WebHlsPolicy.hlsDelayMs(it) })
        assertEquals(LadderRules.RETRY_DELAYS_MS, (0 until 5).map { WebHlsPolicy.hlsDelayMs(it) })
        assertEquals(5_000L, WebHlsPolicy.hlsDelayMs(9))   // and it stays there
    }

    @Test fun `a failed load is retried past the fifth try for as long as the buffer holds picture and not once it is empty`() {
        assertTrue(WebHlsPolicy.retryAllowed(0, 0))
        assertTrue(WebHlsPolicy.retryAllowed(4, 0))
        assertFalse(WebHlsPolicy.retryAllowed(5, 0))      // empty buffer, every retry has run: the error
        assertTrue(WebHlsPolicy.retryAllowed(5, 1))       // picture in hand: go on
        assertTrue(WebHlsPolicy.retryAllowed(500, 8_000))
    }

    @Test fun `a retry beyond the scheduled five is delayed by hls_js at 5 s, not started at once (DEB-3546, FR-3)`() {
        // hls.js delays only while failures < maxNumRetry; at or beyond it the next load starts immediately (≈6 a second on a 404).
        fun delayedByHls(failures: Int, bufferedMs: Long): Boolean = failures < WebHlsPolicy.maxNumRetryFor(failures, bufferedMs)
        for (n in 0 until 5) assertTrue(delayedByHls(n, 0), "scheduled retry $n")
        for (n in listOf(5, 6, 50, 322, 5_000)) {
            assertTrue(WebHlsPolicy.retryAllowed(n, 100))
            assertTrue(delayedByHls(n, 100), "retry $n with picture in hand is delayed")
            assertEquals(5_000L, WebHlsPolicy.hlsDelayMs(n), "…by the 5 s ceiling")
        }
        assertEquals(5_000L, WebHlsPolicy.hlsDelayMs(100))
    }

    @Test fun `with an empty buffer after the fifth try the failure is the error — the retry count goes back to five`() {
        assertEquals(5, WebHlsPolicy.maxNumRetryFor(5, 0))
        assertFalse(5 < WebHlsPolicy.maxNumRetryFor(5, 0))
        assertEquals(5, WebHlsPolicy.maxNumRetryFor(322, 0))
    }

    @Test fun `the config carries the numbers the page hook uses for maxNumRetry`() {
        val cfg = Json.parseToJsonElement(WebHlsPolicy.configJson()).jsonObject
        assertEquals(5, cfg.getValue("scheduledRetries").jsonPrimitive.int)
        assertEquals(WebHlsPolicy.UNBOUNDED_RETRIES, cfg.getValue("unboundedRetries").jsonPrimitive.int)
    }

    @Test fun `hls_js stops skipping a rung after a failed load when the 15 s hold ends — its default never does (DEB-3544)`() {
        val penalty = Json.parseToJsonElement(WebHlsPolicy.configJson()).jsonObject.getValue("errorPenaltyExpireMs").jsonPrimitive.int
        assertEquals(15_000, penalty)
        assertEquals(LadderRules.EXCLUDE_FAILED_RUNG_MS.toInt(), penalty)
    }

    // ── FR-4 / FR-5 — what holds the rung down ──

    @Test fun `a failed load keeps its rung out for 15 s`() {
        val h = WebRungHolds()
        assertNull(h.ceiling(0.0))
        h.failedLoad(level = 3, nowMs = 1_000.0)
        assertEquals(2, h.ceiling(1_000.0))
        assertEquals(2, h.ceiling(15_999.0))
        assertNull(h.ceiling(16_000.0))
    }

    @Test fun `an abandoned segment holds the climb below its rung for 30 s`() {
        val h = WebRungHolds()
        h.abandoned(level = 2, nowMs = 0.0)
        assertEquals(1, h.ceiling(29_999.0))
        assertNull(h.ceiling(30_000.0))
    }

    @Test fun `a stall caps one rung down at once and holds for as long as Android's cap`() {
        val h = WebRungHolds()
        h.stalled(level = 2, nowMs = 5_000.0)
        assertEquals(1, h.ceiling(5_000.0))
        assertEquals(1, h.ceiling(124_999.0))
        assertNull(h.ceiling(125_000.0))
    }

    @Test fun `the lowest ceiling wins while several hold and each goes when its time is up`() {
        val h = WebRungHolds()
        h.stalled(level = 4, nowMs = 0.0)          // 3 until 120 s
        h.failedLoad(level = 2, nowMs = 1_000.0)   // 1 until 16 s
        assertEquals(1, h.ceiling(2_000.0))
        assertEquals(3, h.ceiling(16_000.0))
        assertNull(h.ceiling(120_000.0))
    }

    @Test fun `nothing is held for the lowest rung there is none below to ask for`() {
        val h = WebRungHolds()
        h.failedLoad(0, 0.0); h.abandoned(0, 0.0); h.stalled(0, 0.0)
        assertNull(h.ceiling(1.0))
    }

    @Test fun `a new load forgets every hold`() {
        val h = WebRungHolds()
        h.stalled(3, 0.0)
        h.reset()
        assertNull(h.ceiling(1.0))
    }

    @Test fun `a hold bounds the ladder rule and steps the next segment down when the rung playing is above it`() {
        val d = WebLadderCap.Decision(capLevel = 3, stepDown = false)
        assertEquals(d, boundedByHolds(d, null, currentLevel = 2))                            // nothing held: the rule as it was
        assertEquals(WebLadderCap.Decision(1, true), boundedByHolds(d, 1, currentLevel = 2))  // playing 2, held under 2: move now
        assertEquals(WebLadderCap.Decision(1, false), boundedByHolds(d, 1, currentLevel = 1)) // already there: only the cap
        assertEquals(WebLadderCap.Decision(0, true), boundedByHolds(WebLadderCap.Decision(0, true), 2, currentLevel = 1)) // the rule's own lower bound stays
        assertEquals(WebLadderCap.Decision(1, true), boundedByHolds(null, 1, currentLevel = 3))  // the rule has no bound, the hold alone
        assertNull(boundedByHolds(null, null, currentLevel = 3))
    }

    // ── FR-4 — the slow segment ──

    private fun load(key: String = "7:3", level: Int = 3, age: Long = 2_000, bytes: Long = 100_000, firstByteAgo: Long = 1_500,
                     bitrate: Long = 24_000_000, segmentMs: Long = 2_000, estimate: Long = 4_000_000) =
        WebSegmentAbandon.Load(key, level, age, bytes, segmentMs, bitrate, firstByteAgo, estimate)

    @Test fun `a segment whose projected finish is later than the buffer minus 5 s is abandoned`() {
        val a = WebSegmentAbandon()
        // 24 Mb/s for 2 s = 6 MB; 100 kB in 1.5 s is 0.53 Mb/s: about 88 s more, against 40 s - 5 s.
        assertTrue(a.shouldAbandon(load(), bufferedMs = 40_000))
    }

    @Test fun `a fast link is not abandoned and nothing is judged at under a second old with nothing buffered or on the lowest rung`() {
        val a = WebSegmentAbandon()
        assertFalse(a.shouldAbandon(load(bytes = 5_000_000, firstByteAgo = 1_000), bufferedMs = 40_000))    // 40 Mb/s: done in a moment
        assertFalse(a.shouldAbandon(load(age = 900), bufferedMs = 40_000))
        assertFalse(a.shouldAbandon(load(), bufferedMs = 0))
        assertFalse(a.shouldAbandon(load(level = 0), bufferedMs = 40_000))
    }

    @Test fun `after a start or a seek nothing is judged until the buffer has once held the margin plus the segment`() {
        val a = WebSegmentAbandon()
        assertFalse(a.shouldAbandon(load(), bufferedMs = 6_900))   // 5 s + the 2 s segment = 7 s, not yet
        val b = WebSegmentAbandon()
        assertFalse(b.shouldAbandon(load(), bufferedMs = 6_900))
        assertTrue(b.shouldAbandon(load(), bufferedMs = 40_000))   // reached 7 s once (here at once, with 40 s): judged from then on
        b.reset()                                                  // a seek outside the buffer
        assertFalse(b.shouldAbandon(load(key = "9:3"), bufferedMs = 6_000))
    }

    @Test fun `the carve-out stays open once the buffer has been deep enough even when it thins later`() {
        val a = WebSegmentAbandon()
        assertFalse(a.shouldAbandon(load(bytes = 5_000_000), bufferedMs = 12_000))   // fast load: armed, not abandoned
        assertTrue(a.shouldAbandon(load(key = "8:3"), bufferedMs = 6_000))           // thin buffer, slow load: the rule is what it is for
    }

    @Test fun `one load is abandoned once`() {
        val a = WebSegmentAbandon()
        assertTrue(a.shouldAbandon(load(), bufferedMs = 40_000))
        assertFalse(a.shouldAbandon(load(), bufferedMs = 40_000))
        assertTrue(a.shouldAbandon(load(key = "8:3"), bufferedMs = 40_000))   // the next one is judged afresh
    }

    @Test fun `a load with no byte yet is judged on the player's estimate not on the wait`() {
        val a = WebSegmentAbandon()
        // 6 MB at 4 Mb/s = 12 s after a 2 s wait: within 40 s - 5 s.
        assertFalse(a.shouldAbandon(load(bytes = 0, firstByteAgo = -1, age = 2_000), bufferedMs = 40_000))
        // at 0.5 Mb/s it is 96 s.
        assertTrue(a.shouldAbandon(load(key = "8:3", bytes = 0, firstByteAgo = -1, age = 2_000, estimate = 500_000), bufferedMs = 40_000))
    }

    // ── FR-6 — the held frame keeps the play's own position (DEB-3547) ──

    @Test fun `while the held frame is up the position is where the replacement starts, not the element's 0`() {
        assertEquals(115_000L, WebHeldFrame.positionMs(held = true, startMs = 115_000, elementMs = 0))
        assertEquals(115_000L, WebHeldFrame.positionMs(held = true, startMs = 115_000, elementMs = 114_000))
    }

    @Test fun `once the new source has its first frame the position is the element's`() {
        assertEquals(116_200L, WebHeldFrame.positionMs(held = false, startMs = 115_000, elementMs = 116_200))
        assertEquals(0L, WebHeldFrame.positionMs(held = false, startMs = 115_000, elementMs = 0))
    }

    @Test fun `the length does not drop to 0 inside the window, and the new source's own length wins once it has one`() {
        assertEquals(7_200_000L, WebHeldFrame.durationMs(held = true, heldMs = 7_200_000, elementMs = 0))
        assertEquals(7_201_000L, WebHeldFrame.durationMs(held = true, heldMs = 7_200_000, elementMs = 7_201_000))
        assertEquals(0L, WebHeldFrame.durationMs(held = false, heldMs = 7_200_000, elementMs = 0))
    }
}
