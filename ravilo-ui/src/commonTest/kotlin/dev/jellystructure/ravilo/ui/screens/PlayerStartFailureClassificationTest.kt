package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.shared.tv.TvApiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R237 (FR-R237-1) — the classification that decides whether a failed start is retried at all.
 *
 * The bug these guard: on 2026-09-06 a `409` — deterministic, unchanged for the next ten minutes, with
 * a human-readable explanation in the body — was retried on the identical 1/2/4/8 s schedule as a
 * dropped packet. All five attempts were guaranteed to fail identically before the first was sent, and
 * the viewer backed out after 4 s of spinner without the re-pair message ever being rendered.
 */
class PlayerStartFailureClassificationTest {

    private fun http(status: Int, retryAfter: Int? = null) =
        classifyStartFailure(TvApiError.Http(status, "body", retryAfterSeconds = retryAfter))

    @Test
    fun reauthIsTerminalAndNamesItself() {
        val c = http(409)
        assertFalse(c.retryable, "a 409 cannot succeed on retry — surfacing it is the whole phase")
        assertEquals(LoadErrorKind.REAUTH, c.kind)
        assertEquals(409, c.status)
    }

    /**
     * R280 (FR-R280-1) — the hole this phase closes. R237 was written against the playback route,
     * where a dead session is a `409`, so `401` fell through to `400..499 -> GENERIC`: "Something
     * went wrong", offering a Retry that cannot ever succeed. Every other TV route answers `401` for
     * a dead device token (nineteen `TvRoutes.kt` sites), so that was the app's most common failure.
     */
    @Test
    fun unauthorizedIsReauthNotGeneric() {
        val c = http(401)
        assertEquals(LoadErrorKind.REAUTH, c.kind, "401 means the session is gone, exactly as 409 does")
        assertFalse(c.retryable, "a dead token is a verdict — retrying it is a spinner in front of it")
        assertEquals(401, c.status)
    }

    @Test
    fun forbiddenAndGoneAreTerminal() {
        assertFalse(http(403).retryable)
        assertEquals(LoadErrorKind.FORBIDDEN, http(403).kind)
        assertFalse(http(404).retryable)
        assertEquals(LoadErrorKind.GONE, http(404).kind)
    }

    @Test
    fun otherClientErrorsAreAnswersNotFaults() {
        val c = http(422)
        assertFalse(c.retryable)
        assertEquals(LoadErrorKind.GENERIC, c.kind)
    }

    @Test
    fun busyAndServerErrorsStayRetryable() {
        assertTrue(http(408).retryable)
        assertTrue(http(429).retryable)
        assertTrue(http(500).retryable)
        // Phase 182 FR-182-8's gate-saturation response is explicitly "try again shortly".
        assertTrue(http(503).retryable)
    }

    @Test
    fun retryAfterIsHonouredAndBounded() {
        assertEquals(2_000L, http(503, retryAfter = 2).retryAfterMs)
        // Nonsense values fall back to our own backoff rather than parking the viewer indefinitely.
        assertNull(http(503, retryAfter = 9_999).retryAfterMs)
        assertNull(http(503).retryAfterMs)
        // A terminal status never carries a wait — there is nothing to wait for.
        assertNull(http(409, retryAfter = 2).retryAfterMs)
    }

    @Test
    fun aTransportFailureWithNoResponseIsStillRetried() {
        // The auto-advance blip this retry loop was originally written for. Unchanged by R237.
        val c = classifyStartFailure(RuntimeException("connection reset"))
        assertTrue(c.retryable)
        assertEquals(LoadErrorKind.UNREACHABLE, c.kind)
        assertNull(c.status)
    }
}
