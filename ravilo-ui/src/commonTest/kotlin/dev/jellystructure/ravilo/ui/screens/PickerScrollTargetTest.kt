package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 2026-09-24 — the picker keeps one row of context around focus and only scrolls when it must.
class PickerScrollTargetTest {
    // 20 items (0..19); the list shows 7 fully (items 3..9).
    private fun target(idx: Int) = pickerScrollTarget(idx, lastItem = 19, firstFull = 3, lastFull = 9)

    @Test fun aMoveInsideTheVisibleWindowScrollsNothing() {
        assertNull(target(5))
        assertNull(target(4))
        assertNull(target(8))
    }

    @Test fun movingToTheTopVisibleRowRevealsTheRowAboveIt() {
        assertEquals(2, target(3))
    }

    @Test fun movingToTheBottomVisibleRowRevealsTheRowBelowIt() {
        // below = 10 must become the last fully visible item: top = 10 - (9 - 3) = 4.
        assertEquals(4, target(9))
    }

    @Test fun theFirstItemNeedsNoRowAboveIt() {
        assertNull(pickerScrollTarget(0, lastItem = 19, firstFull = 0, lastFull = 6))
    }

    @Test fun theLastItemNeedsNoRowBelowIt() {
        assertNull(pickerScrollTarget(19, lastItem = 19, firstFull = 13, lastFull = 19))
    }

    @Test fun offStaysVisibleWhenEnglishIsFocusedNearTheTop() {
        // Off = 0, English = 1; the list had been scrolled so English was first.
        assertEquals(0, pickerScrollTarget(1, lastItem = 30, firstFull = 1, lastFull = 7))
    }
}
