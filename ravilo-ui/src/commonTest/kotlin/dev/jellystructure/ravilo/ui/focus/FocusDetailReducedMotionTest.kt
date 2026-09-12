package dev.jellystructure.ravilo.ui.focus

import kotlin.test.Test
import kotlin.test.assertEquals

/** R240 open question 3, resolved — reduce-motion downgrades "rowOpen" to "line", touches nothing else. */
class FocusDetailReducedMotionTest {
    @Test
    fun `reduce motion downgrades rowOpen to line`() {
        assertEquals("line", effectiveFocusDetailMode("rowOpen", reduceMotion = true))
    }

    @Test
    fun `without reduce motion rowOpen is untouched`() {
        assertEquals("rowOpen", effectiveFocusDetailMode("rowOpen", reduceMotion = false))
    }

    @Test
    fun `reduce motion never touches an already resolved line`() {
        assertEquals("line", effectiveFocusDetailMode("line", reduceMotion = true))
    }

    @Test
    fun `reduce motion never invents a mode out of none`() {
        assertEquals("none", effectiveFocusDetailMode("none", reduceMotion = true))
    }
}
