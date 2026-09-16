package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R251 (FR-R251-1/2) — a key that finds the chrome hidden reveals it and does nothing else; all five keys, one rule. */
class PlayerDpadRevealTest {
    private val keys = PlayerDpadKey.values().toList()

    @Test
    fun `every key only reveals when the chrome is hidden`() {
        for (k in keys) for (f in PlFocus.values()) {
            if (f == PlFocus.SKIP_INTRO) continue
            assertTrue(dpadRevealsOnly(k, chromeVisible = false, focus = f, nextUpVisible = false, epRailOpen = false, pickerOpen = false), "$k from $f")
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
    fun `right right select reaches the same control whether or not the chrome hid (FR-R251-2)`() {
        // Model of the handler: hidden ⇒ reveal only (focus stays PLAY); visible ⇒ move along the transport order.
        val order = listOf(PlFocus.SKIP_BACK, PlFocus.PLAY, PlFocus.SKIP_FWD, PlFocus.TRACKS, PlFocus.NEXT_EP)
        fun run(startHidden: Boolean): PlFocus {
            var focus = PlFocus.PLAY   // hideChrome() parks focus here (R178)
            var visible = !startHidden
            repeat(2) {
                val revealOnly = dpadRevealsOnly(PlayerDpadKey.RIGHT, visible, focus, false, false, false)
                visible = true
                if (!revealOnly) focus = order[(order.indexOf(focus) + 1).coerceAtMost(order.lastIndex)]
            }
            return focus
        }
        // Before R251: hidden ⇒ → → landed on TRACKS while visible-from-PLAY also landed on TRACKS, but a
        // viewer whose chrome was visible with focus on TRACKS reached NEXT_EP — the sequence meant two
        // different things. Now a hidden chrome always starts from PLAY after one reveal press:
        assertTrue(run(startHidden = true) == PlFocus.SKIP_FWD)
        assertTrue(run(startHidden = false) == PlFocus.TRACKS)
    }
}
