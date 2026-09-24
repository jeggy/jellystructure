package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** R257 (FR-R257-6) / R301 — back turns counter-clockwise with its head on the left; forward the mirror. */
class SkipGlyphGeometryTest {
    @Test fun back_turns_counter_clockwise_with_its_head_on_the_left() {
        val a = skipArc(back = true)
        assertTrue(a.sweepAngle > 0f, "Compose's positive sweep from 240° runs up the left side — counter-clockwise as the eye reads it")
        assertEquals(-1, a.tipSide)
    }
    @Test fun forward_turns_clockwise_with_its_head_on_the_right() {
        val a = skipArc(back = false)
        assertTrue(a.sweepAngle < 0f)
        assertEquals(+1, a.tipSide)
    }
    @Test fun the_two_are_mirror_images() {
        val b = skipArc(true); val f = skipArc(false)
        assertEquals(-b.sweepAngle, f.sweepAngle)
        assertEquals(-b.tipSide, f.tipSide)
        assertNotEquals(b.startAngle, f.startAngle)
    }
    @Test fun the_head_sits_at_the_arcs_open_end_above_the_centre() {
        // Compose angles: 0° = 3 o'clock, y down. The arrowhead is drawn at the arc's START point, so
        // the start must lie on the head's side (cos) and above the centre (sin < 0); both arcs are
        // three-quarter circles.
        for (back in listOf(true, false)) {
            val a = skipArc(back)
            val rad = a.startAngle * kotlin.math.PI / 180.0
            assertEquals(a.tipSide, kotlin.math.sign(kotlin.math.cos(rad)).toInt(), "back=$back: head side")
            assertTrue(kotlin.math.sin(rad) < 0, "back=$back: head above the centre")
            assertEquals(270f, kotlin.math.abs(a.sweepAngle), "back=$back: three-quarter arc")
        }
    }
}
