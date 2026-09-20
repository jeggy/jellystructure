package dev.jellystructure.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 238 (FR-238-3, dev review item 4b). The thing being pinned here is a **leak**, not a
 * formatting preference.
 *
 * `/api/health/full`'s `last_error` used to be specified as the exception's own message. Curl and Ktor
 * failure text routinely carries the request URL, and until FR-238-1 that URL was
 * `/socket?api_key=<the household's Jellyfin token>` — so the obvious implementation would have
 * published a live credential to anyone who could read a health endpoint. Classification at the throw
 * site is what makes that impossible rather than merely unlikely.
 */
class BridgeFailureTest {

    @Test
    fun theTwelvePointOneRefusalIsLegibleAsAStatus() {
        // The measured failure this whole phase exists for: Jellyfin 12.1 answers 403 to
        // /socket?api_key=… . An operator reading the health endpoint should see the number.
        val reason = BridgeFailure.classify(RuntimeException("Expected status code 101 but was 403"))
        assertEquals("403 at handshake", reason)
    }

    @Test
    fun aMessageCarryingTheTokenNeverReachesTheReason() {
        // The exact shape of the pre-fix URL, token and all. Nothing but a classified phrase may come
        // out the other side.
        val token = "aa11bb22cc33dd44ee55ff66"
        val e = RuntimeException(
            "Fail to prepare request body: ws://jelly.example/socket?api_key=$token&deviceId=ravilo-1-2",
        )
        val reason = BridgeFailure.classify(e)
        assertFalse(reason.contains(token), "the token must never reach last_error: $reason")
        assertFalse(reason.contains("socket"), "no URL fragment may reach last_error: $reason")
        assertEquals(BridgeFailure.CONNECT_FAILED, reason)
    }

    @Test
    fun transportFailuresAreDistinguishedFromRefusals() {
        assertEquals("connect failed (timeout)", BridgeFailure.classify(RuntimeException("Connection timed out")))
        assertEquals("connect failed (refused)", BridgeFailure.classify(RuntimeException("Connection refused")))
        assertEquals("connect failed (host not found)", BridgeFailure.classify(RuntimeException("Could not resolve host: jelly")))
    }

    @Test
    fun aStatusIsOnlyReadWhenItReallyIsOne() {
        // Guarding against a digit run inside an id or a port being read as an HTTP status.
        assertNull(BridgeFailure.statusIn("device ravilo-84a570800cc828ec-1"))
        assertNull(BridgeFailure.statusIn("connect to host:8096 failed"))
        assertNull(BridgeFailure.statusIn(null))
        assertEquals(403, BridgeFailure.statusIn("status 403 returned"))
        // 101 is the SUCCESS code for a WS upgrade, so it is never a failure reason. Ktor's own
        // message names it first ("Expected status code 101 but was 403"), which is the one status in
        // that sentence that did not happen.
        assertNull(BridgeFailure.statusIn("Expected status code 101 but was"))
    }

    @Test
    fun aNullMessageStillClassifies() {
        // A thrown failure with no message at all must still produce a reason — a null `last_error` is
        // reserved for "connected", and FR-238-6 needs `never_attempted` to be the only other silence.
        val reason = BridgeFailure.classify(RuntimeException())
        assertEquals(BridgeFailure.CONNECT_FAILED, reason)
        assertTrue(reason.isNotBlank())
    }

    @Test
    fun theNeverAttemptedReasonsAreTheirOwnStrings() {
        // FR-238-6 — a blank Jellyfin URL and a blank device token throw nothing, so before this phase
        // they logged nothing at all and would have read as `connected: false, last_error: null`,
        // indistinguishable from a handshake being refused.
        assertTrue(BridgeFailure.NO_JELLYFIN_URL.isNotBlank())
        assertTrue(BridgeFailure.NO_DEVICE_TOKEN.isNotBlank())
        assertFalse(BridgeFailure.NO_JELLYFIN_URL == BridgeFailure.NO_DEVICE_TOKEN)
    }
}
