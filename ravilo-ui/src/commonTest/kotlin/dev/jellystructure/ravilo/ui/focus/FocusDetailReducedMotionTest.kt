package dev.jellystructure.ravilo.ui.focus

import kotlin.test.Test
import kotlin.test.assertEquals

/** R240 open question 3, resolved — reduce-motion downgrades "rowOpen" to "line", touches nothing else. */
class FocusDetailReducedMotionTest {
    @Test
    fun `reduce motion downgrades rowOpen to line`() {
        assertEquals("line", effectiveFocusDetailMode("rowOpen", reduceMotion = true, isTv = true))
    }

    @Test
    fun `without reduce motion rowOpen is untouched`() {
        assertEquals("rowOpen", effectiveFocusDetailMode("rowOpen", reduceMotion = false, isTv = true))
    }

    @Test
    fun `reduce motion never touches an already resolved line`() {
        assertEquals("line", effectiveFocusDetailMode("line", reduceMotion = true, isTv = true))
    }

    @Test
    fun `reduce motion never invents a mode out of none`() {
        assertEquals("none", effectiveFocusDetailMode("none", reduceMotion = true, isTv = true))
    }

    // R254 (FR-R254-7) — J is a TV direction.
    @Test fun rowOpenOnATvStaysRowOpen() = assertEquals("rowOpen", effectiveFocusDetailMode("rowOpen", reduceMotion = false, isTv = true))

    // R314 (FR-R314-1/5) — off a TV there is no focus detail at all: the web app and a phone at any size,
    // with or without reduced motion (R298's handset rule, now every non-TV platform).
    @Test fun offATvThereIsNoFocusDetail() {
        for (mode in listOf("line", "rowOpen", "none")) for (reduce in listOf(false, true)) {
            assertEquals("none", effectiveFocusDetailMode(mode, reduceMotion = reduce, isTv = false), "$mode reduce=$reduce")
        }
    }
    @Test fun aTvKeepsTheHouseholdsLine() = assertEquals("line", effectiveFocusDetailMode("line", reduceMotion = false, isTv = true))
}
