package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.TvApiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R345 (FR-R345-1) — an unreachable server is not "no music". */
class MusicAvailabilityTest {
    private val S = MusicAnswer.SOMETHING
    private val E = MusicAnswer.EMPTY
    private val F = MusicAnswer.FAILED

    @Test
    fun either_answer_with_something_is_yes() {
        assertEquals(true, musicAvailability(S, S))
        assertEquals(true, musicAvailability(S, E))
        assertEquals(true, musicAvailability(E, S))
        assertEquals(true, musicAvailability(S, F), "audiobooks are enough even when music failed")
        assertEquals(true, musicAvailability(F, S), "music is enough even when audiobooks failed")
    }

    @Test
    fun only_two_empty_answers_are_a_definite_no() {
        assertEquals(false, musicAvailability(E, E))
    }

    @Test
    fun any_failure_without_a_yes_is_unknown() {
        assertNull(musicAvailability(F, F))
        assertNull(musicAvailability(E, F), "an empty shelf and a failed music home: unknown, not no")
        assertNull(musicAvailability(F, E))
    }

    @Test
    fun a_404_is_an_answer_and_other_failures_are_not() {
        fun fail(t: Throwable) = musicAnswerOf(Result.failure(t))
        assertEquals(S, musicAnswerOf(Result.success(true)))
        assertEquals(E, musicAnswerOf(Result.success(false)))
        assertEquals(E, fail(TvApiError.Http(404, "not found")), "a server from before 279")
        assertEquals(F, fail(TvApiError.Http(500, "boom")), "a 5xx is unknown (acceptance 5)")
        assertEquals(F, fail(TvApiError.Http(503, "busy", retryAfterSeconds = 5)))
        assertEquals(F, fail(TvApiError.Http(401, "Not logged in")))
        assertEquals(F, fail(IllegalStateException("no network")), "no network is unknown (acceptance 1)")
    }
}
