package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R376 (FR-R376-4b) — the chrome's full-screen stop and the double-click on the picture. */
class PlayerFullscreenControlTest {
    @Test
    fun `the full screen stop sits right after picture in picture when available`() {
        val order = transportOrder(hasNextEp = true, hasSpeakers = true, hasPip = true, hasFullscreen = true)
        assertEquals(order.indexOf(PlFocus.PIP) + 1, order.indexOf(PlFocus.FULLSCREEN))
        assertEquals(PlFocus.SPEAKERS, order.last())
    }

    @Test
    fun `the full screen stop follows the last transport button when there is no picture in picture`() {
        val order = transportOrder(hasNextEp = false, hasFullscreen = true)
        assertEquals(PlFocus.FULLSCREEN, order.last())
        assertEquals(PlFocus.TRACKS, order[order.lastIndex - 1])
    }

    @Test
    fun `the full screen stop is absent when the page cannot go full screen`() {
        assertFalse(PlFocus.FULLSCREEN in transportOrder(hasNextEp = true, hasSpeakers = true, hasPip = true))
        assertFalse(PlFocus.FULLSCREEN in transportOrder(hasNextEp = false))
    }

    @Test
    fun `two clicks within the span are a double click and a later one is not`() {
        assertTrue(isDoubleClick(1_000L, 1_000L + DOUBLE_CLICK_SPAN_MS))
        assertFalse(isDoubleClick(1_000L, 1_000L + DOUBLE_CLICK_SPAN_MS + 1))
        assertFalse(isDoubleClick(Long.MIN_VALUE, 5L))
        assertFalse(isDoubleClick(2_000L, 1_000L))
    }
}
