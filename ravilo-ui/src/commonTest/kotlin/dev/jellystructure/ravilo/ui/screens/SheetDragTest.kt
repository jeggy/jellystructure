package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R244 FR-R244-10a — a sheet follows the thumb down, resists up, and leaves past a third or on a flick. */
class SheetDragTest {
    private val over = 60f
    private val fling = 2_600f

    @Test fun down_follows_the_finger_one_to_one() {
        assertEquals(40f, SheetDrag.next(0f, 40f, over, dismissible = true))
        assertEquals(10f, SheetDrag.next(40f, -30f, over, dismissible = true))
    }

    @Test fun up_resists_and_is_capped_and_retraces_on_the_way_back() {
        val up = SheetDrag.next(0f, -40f, over, dismissible = true)
        assertEquals(-40f * SheetDrag.RESISTANCE, up)
        assertEquals(0f, SheetDrag.next(up, 40f, over, dismissible = true), 0.001f)
        assertEquals(-over, SheetDrag.next(0f, -1_000f, over, dismissible = true))
    }

    @Test fun a_sheet_that_must_be_answered_resists_down_too() {
        val down = SheetDrag.next(0f, 100f, over, dismissible = false)
        assertEquals(minOf(over, 100f * SheetDrag.RESISTANCE), down)
        assertFalse(SheetDrag.leaves(down, 400f, 9_000f, fling, dismissible = false))
    }

    @Test fun a_release_leaves_past_a_third_or_on_a_downward_flick() {
        assertTrue(SheetDrag.leaves(130f, 400f, 0f, fling, dismissible = true))
        assertFalse(SheetDrag.leaves(100f, 400f, 0f, fling, dismissible = true))
        assertTrue(SheetDrag.leaves(20f, 400f, fling, fling, dismissible = true))
        assertFalse(SheetDrag.leaves(300f, 400f, -fling, fling, dismissible = true), "an upward flick keeps it")
        assertFalse(SheetDrag.leaves(0f, 400f, fling, fling, dismissible = true), "a flick that never moved it down is a scroll")
    }
}
