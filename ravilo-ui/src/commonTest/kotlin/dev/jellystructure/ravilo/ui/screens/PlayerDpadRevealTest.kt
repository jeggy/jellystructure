package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R251 → R350 (FR-R350-7) — what a key does when it finds the player's chrome hidden. Hiding no longer moves focus,
 * so Left, Right and Up act on the first press from the remembered control; OK stays play/pause (R178) and Down
 * never opens the episode rail from a hidden chrome.
 */
class PlayerDpadRevealTest {
    private val keys = PlayerDpadKey.values().toList()
    private fun hidden(k: PlayerDpadKey, f: PlFocus) =
        dpadRevealsOnly(k, chromeVisible = false, focus = f, nextUpVisible = false, epRailOpen = false, pickerOpen = false)

    @Test
    fun `Left, Right and Up act on the first press with the chrome hidden`() {
        for (k in listOf(PlayerDpadKey.LEFT, PlayerDpadKey.RIGHT, PlayerDpadKey.UP)) for (f in PlFocus.values()) {
            assertFalse(hidden(k, f), "$k from $f")
        }
    }

    @Test
    fun `OK with the chrome hidden never fires the remembered control`() {
        for (f in PlFocus.values()) {
            if (f == PlFocus.SKIP_INTRO) continue   // the pill is on screen and genuinely focused
            assertTrue(hidden(PlayerDpadKey.SELECT, f), "SELECT from $f")
        }
    }

    @Test
    fun `Down with the chrome hidden moves within the transport but never opens the rail`() {
        assertFalse(hidden(PlayerDpadKey.DOWN, PlFocus.SEEK_BAR), "seek bar → Play is a move")
        for (f in PlFocus.values()) {
            if (f == PlFocus.SEEK_BAR || f == PlFocus.SKIP_INTRO) continue
            assertTrue(hidden(PlayerDpadKey.DOWN, f), "DOWN from $f would open the episode rail")
        }
    }

    @Test
    fun `every key acts when the chrome is visible`() {
        for (k in keys) for (f in PlFocus.values()) {
            assertFalse(dpadRevealsOnly(k, chromeVisible = true, focus = f, nextUpVisible = false, epRailOpen = false, pickerOpen = false), "$k from $f")
        }
    }

    @Test
    fun `the skip-intro pill and the next-up card are focused even with the chrome hidden`() {
        for (k in keys) {
            assertFalse(dpadRevealsOnly(k, chromeVisible = false, focus = PlFocus.SKIP_INTRO, nextUpVisible = false, epRailOpen = false, pickerOpen = false), "$k pill")
            assertFalse(dpadRevealsOnly(k, chromeVisible = false, focus = PlFocus.PLAY, nextUpVisible = true, epRailOpen = false, pickerOpen = false), "$k card")
        }
    }

    @Test
    fun `an open picker or episode rail is never treated as hidden`() {
        for (k in keys) {
            assertFalse(dpadRevealsOnly(k, chromeVisible = false, focus = PlFocus.PLAY, nextUpVisible = false, epRailOpen = true, pickerOpen = false), "$k rail")
            assertFalse(dpadRevealsOnly(k, chromeVisible = false, focus = PlFocus.TRACKS, nextUpVisible = false, epRailOpen = false, pickerOpen = true), "$k picker")
        }
    }

    @Test
    fun `right right reaches the same control whether or not the chrome hid (R251's rule, kept by not moving focus)`() {
        // Model of the handler: hiding keeps focus where it was; a direction key always moves along the transport.
        val order = listOf(PlFocus.SKIP_BACK, PlFocus.PLAY, PlFocus.SKIP_FWD, PlFocus.TRACKS, PlFocus.NEXT_EP)
        fun run(from: PlFocus, startHidden: Boolean): PlFocus {
            var focus = from
            var visible = !startHidden
            repeat(2) {
                val revealOnly = dpadRevealsOnly(PlayerDpadKey.RIGHT, visible, focus, false, false, false)
                visible = true
                if (!revealOnly) focus = order[(order.indexOf(focus) + 1).coerceAtMost(order.lastIndex)]
            }
            return focus
        }
        for (from in order) assertEquals(run(from, startHidden = false), run(from, startHidden = true), "from $from")
        // The owner's case: focus on Play, the chrome hid while watching — Right, Right is Audio & Subs, two presses.
        assertEquals(PlFocus.TRACKS, run(PlFocus.PLAY, startHidden = true))
    }
}
