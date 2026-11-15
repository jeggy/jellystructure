package dev.jellystructure.shared.tv

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-4692 (DEB-2205 FR-6) — the JS clients' ticket-subtitle fetch: where the token goes, and how a 202 is waited out. */
class TicketSubtitlesTest {
    private val base = "https://jelly.example.org"
    private val own = "/api/tv/subtitles/abc123/2.vtt"

    // ── where it is asked for, and with what ──────────────────────────────────────────────────────
    @Test fun a_path_is_resolved_on_the_server() = assertEquals("$base$own", TicketSubtitles.absolute(own, "$base/"))
    @Test fun an_absolute_sidecar_is_kept() =
        assertEquals("https://jf.example.org/Videos/x/Subtitles/3/Stream.vtt?api_key=k", TicketSubtitles.absolute("https://jf.example.org/Videos/x/Subtitles/3/Stream.vtt?api_key=k", base))
    @Test fun a_path_without_a_server_has_no_address() = assertNull(TicketSubtitles.absolute(own, ""))

    @Test fun our_own_track_carries_the_device_token() = assertEquals("tok", TicketSubtitles.bearer("$base$own", base, "tok"))
    @Test fun another_host_never_gets_the_token() = assertNull(TicketSubtitles.bearer("https://jf.example.org/api/tv/subtitles/a/2.vtt", base, "tok"))
    @Test fun a_lookalike_host_never_gets_the_token() = assertNull(TicketSubtitles.bearer("https://jelly.example.org.evil.test/api/tv/subtitles/a/2.vtt", base, "tok"))
    @Test fun a_path_outside_the_tv_api_never_gets_the_token() = assertNull(TicketSubtitles.bearer("$base/cast/x.vtt", base, "tok"))
    @Test fun no_token_is_no_header() = assertNull(TicketSubtitles.bearer("$base$own", base, null))

    @Test fun retry_after_is_seconds() {
        assertEquals(3_000L, TicketSubtitles.retryAfterMs(" 3 "))
        assertNull(TicketSubtitles.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(TicketSubtitles.retryAfterMs(null))
    }

    // ── the Cast receiver's request handler ───────────────────────────────────────────────────────
    private val ownUrl = "$base$own"

    @Test fun cafs_request_for_our_track_carries_the_token() =
        assertEquals(TicketSubtitles.CastRequest(ownUrl, "tok"), TicketSubtitles.castRequest(ownUrl, setOf(ownUrl), emptyMap(), base, "tok"))
    @Test fun cafs_request_for_a_track_already_fetched_goes_to_the_copy() =
        assertEquals(TicketSubtitles.CastRequest("blob:x", null), TicketSubtitles.castRequest(ownUrl, setOf(ownUrl), mapOf(ownUrl to "blob:x"), base, "tok"))
    @Test fun a_segment_or_a_sidecar_is_left_alone() {
        assertNull(TicketSubtitles.castRequest("$base/api/tv/stream/s1/v/0/3.m4s", setOf(ownUrl), emptyMap(), base, "tok"))
        assertNull(TicketSubtitles.castRequest("https://jf.example.org/Videos/x/Subtitles/3/Stream.vtt?api_key=k", setOf(ownUrl), emptyMap(), base, "tok"))
    }

    @Test fun a_track_that_was_waited_for_is_shown_again() =
        assertEquals(true, TicketSubtitles.castReshows(TicketSubtitles.Arrival("x", waited = true), cafAsked = true))
    @Test fun a_track_caf_never_asked_for_with_the_token_is_shown_again() =
        assertEquals(true, TicketSubtitles.castReshows(TicketSubtitles.Arrival("x", waited = false), cafAsked = false))
    @Test fun a_track_caf_had_at_once_is_left_as_it_is() =
        assertEquals(false, TicketSubtitles.castReshows(TicketSubtitles.Arrival("x", waited = false), cafAsked = true))

    // ── the retry loop ────────────────────────────────────────────────────────────────────────────
    private class Clock { var now = 0L; val sleeps = mutableListOf<Long>() }

    private fun run(clock: Clock, answers: (Int) -> SubtitleAttempt): Pair<TicketSubtitles.Arrival?, Int> {
        var calls = 0
        val arrival = runBlocking {
            TicketSubtitles.fetch({ answers(calls++) }, { clock.now }) { ms -> clock.sleeps += ms; clock.now += ms }
        }
        return arrival to calls
    }

    @Test fun a_file_that_is_there_comes_at_once() {
        val clock = Clock()
        val (arrival, calls) = run(clock) { SubtitleAttempt.Body("WEBVTT") }
        assertEquals(TicketSubtitles.Arrival("WEBVTT", waited = false), arrival)
        assertEquals(1, calls)
        assertEquals(emptyList(), clock.sleeps)
    }

    @Test fun a_202_is_asked_again_after_its_retry_after_and_shows_when_it_arrives() {
        val clock = Clock()
        val (arrival, calls) = run(clock) { if (it < 2) SubtitleAttempt.NotReady(3_000) else SubtitleAttempt.Body("WEBVTT\n\n00:01.000 --> 00:02.000\nhej") }
        assertEquals(true, arrival?.waited)
        assertEquals(3, calls)
        assertEquals(listOf(3_000L, 3_000L), clock.sleeps)
    }

    @Test fun retry_after_is_clamped() {
        val clock = Clock()
        run(clock) { when (it) { 0 -> SubtitleAttempt.NotReady(0); 1 -> SubtitleAttempt.NotReady(600_000); 2 -> SubtitleAttempt.NotReady(null); else -> SubtitleAttempt.Body("") } }
        assertEquals(listOf(TicketSubtitles.MIN_RETRY_MS, TicketSubtitles.MAX_RETRY_MS, TicketSubtitles.FAILURE_RETRY_MS), clock.sleeps)
    }

    @Test fun a_track_that_never_arrives_is_given_up_after_ten_minutes() {
        val clock = Clock()
        val (arrival, calls) = run(clock) { SubtitleAttempt.NotReady(15_000) }
        assertNull(arrival)
        assertEquals(TicketSubtitles.GIVE_UP_MS / 15_000 + 1, calls.toLong())
    }

    @Test fun a_failure_is_retried_twice_then_dropped() {
        val clock = Clock()
        val (arrival, calls) = run(clock) { SubtitleAttempt.Failed("HTTP 401") }
        assertNull(arrival)
        assertEquals(1 + TicketSubtitles.FAILURE_RETRIES, calls)
        assertEquals(List(TicketSubtitles.FAILURE_RETRIES) { TicketSubtitles.FAILURE_RETRY_MS }, clock.sleeps)
    }

    @Test fun a_202_does_not_use_up_the_failure_retries() {
        val clock = Clock()
        val (arrival, _) = run(clock) { if (it < 5) SubtitleAttempt.NotReady(1_000) else if (it < 7) SubtitleAttempt.Failed("reset") else SubtitleAttempt.Body("x") }
        assertEquals("x", arrival?.text)
    }
}
