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
    @Test fun rowOpenOnAPhoneOrTheWebIsTheLine() = assertEquals("line", effectiveFocusDetailMode("rowOpen", reduceMotion = false, isTv = false))
    @Test fun rowOpenOnATvStaysRowOpen() = assertEquals("rowOpen", effectiveFocusDetailMode("rowOpen", reduceMotion = false, isTv = true))
    @Test fun bothFlagsSetIsTheLine() = assertEquals("line", effectiveFocusDetailMode("rowOpen", reduceMotion = true, isTv = false))
    @Test fun lineAndNoneAreUnchangedOffTv() {
        assertEquals("line", effectiveFocusDetailMode("line", reduceMotion = false, isTv = false))
        assertEquals("none", effectiveFocusDetailMode("none", reduceMotion = false, isTv = false))
    }

    // R298 (FR-R298-3) — a handset has no focus detail: no remote moves focus there.
    @Test fun aHandsetHasNoFocusDetail() {
        for (mode in listOf("line", "rowOpen", "none")) {
            assertEquals("none", effectiveFocusDetailMode(mode, reduceMotion = false, isTv = false, handset = true), mode)
        }
    }
    @Test fun notAHandsetKeepsTheOldRules() = assertEquals("line", effectiveFocusDetailMode("line", reduceMotion = false, isTv = false, handset = false))
}
