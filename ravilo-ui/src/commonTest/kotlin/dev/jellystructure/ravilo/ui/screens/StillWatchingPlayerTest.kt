package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.STILL_WATCHING_CLOSE_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5843 (FR-3, FR-4) — the app player's ask, as the pure decisions its state class wires. */
class StillWatchingPlayerTest {
    @Test fun `FR-3 a countdown that ends with the ask due asks and does not advance`() {
        assertEquals(CountdownEnd.ASK, countdownEnd(cardStillUp = true, autoplay = true, askDue = true))
    }

    @Test fun `FR-3 a countdown that ends with no ask due advances as today`() {
        assertEquals(CountdownEnd.ADVANCE, countdownEnd(cardStillUp = true, autoplay = true, askDue = false))
    }

    @Test fun `FR-3 autoplay off or a card dismissed under the countdown does nothing, whatever is due`() {
        for (due in listOf(true, false)) {
            assertEquals(CountdownEnd.NOTHING, countdownEnd(cardStillUp = true, autoplay = false, askDue = due))
            assertEquals(CountdownEnd.NOTHING, countdownEnd(cardStillUp = false, autoplay = true, askDue = due))
        }
    }

    @Test fun `FR-3 the ask pauses a playing item and has nothing to pause once it ended`() {
        assertTrue(askPausesPlayer(playing = true, ended = false))
        assertFalse(askPausesPlayer(playing = true, ended = true))
        assertFalse(askPausesPlayer(playing = false, ended = false))
    }

    @Test fun `FR-3 Continue watching is first and focused, Back is second`() {
        assertEquals(AskAnswer.CONTINUE, askAnswerFor(NuFocus.PLAY))
        assertEquals(AskAnswer.LEAVE, askAnswerFor(NuFocus.STAY))
    }

    @Test fun `FR-4 thirty minutes unanswered closes the player once`() {
        assertFalse(askClosesPlayer(STILL_WATCHING_CLOSE_MS - 1, alreadyClosed = false))
        assertTrue(askClosesPlayer(STILL_WATCHING_CLOSE_MS, alreadyClosed = false))
        assertFalse(askClosesPlayer(STILL_WATCHING_CLOSE_MS * 2, alreadyClosed = true))
    }

    @Test fun `FR-3 the new card mode never raises an early card`() {
        assertFalse(earlyCardAllowed(trustedMarker = false, mode = CreditsCardMode.STILL_WATCHING, canAdvance = true))
    }
}
