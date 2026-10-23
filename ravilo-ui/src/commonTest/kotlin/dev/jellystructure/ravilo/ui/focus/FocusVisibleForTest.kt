package dev.jellystructure.ravilo.ui.focus

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-2347 (FR-7) — truth table for focusVisibleFor. */
class FocusVisibleForTest {

    @Test
    fun desktopWithKeyboardOnReturnsTrue() {
        assertTrue(focusVisibleFor(isDesktop = true, isWeb = false, handset = false, keyboardOn = true))
    }

    @Test
    fun desktopWithKeyboardOffReturnsFalse() {
        assertFalse(focusVisibleFor(isDesktop = true, isWeb = false, handset = false, keyboardOn = false))
    }

    @Test
    fun wideWebWithKeyboardOnReturnsTrue() {
        assertTrue(focusVisibleFor(isDesktop = false, isWeb = true, handset = false, keyboardOn = true))
    }

    @Test
    fun wideWebWithKeyboardOffReturnsFalse() {
        assertFalse(focusVisibleFor(isDesktop = false, isWeb = true, handset = false, keyboardOn = false))
    }

    @Test
    fun phoneSizedWebWithKeyboardOnReturnsFalse() {
        assertFalse(focusVisibleFor(isDesktop = false, isWeb = true, handset = true, keyboardOn = true))
    }

    @Test
    fun phoneSizedWebWithKeyboardOffReturnsFalse() {
        assertFalse(focusVisibleFor(isDesktop = false, isWeb = true, handset = true, keyboardOn = false))
    }

    @Test
    fun androidTvHandsetFalseReturnsTrue() {
        assertTrue(focusVisibleFor(isDesktop = false, isWeb = false, handset = false, keyboardOn = false))
    }

    @Test
    fun androidTvHandsetFalseKeyboardOnReturnsTrue() {
        assertTrue(focusVisibleFor(isDesktop = false, isWeb = false, handset = false, keyboardOn = true))
    }

    @Test
    fun androidPhoneHandsetTrueReturnsFalse() {
        assertFalse(focusVisibleFor(isDesktop = false, isWeb = false, handset = true, keyboardOn = false))
    }

    @Test
    fun androidPhoneHandsetTrueKeyboardOnReturnsFalse() {
        assertFalse(focusVisibleFor(isDesktop = false, isWeb = false, handset = true, keyboardOn = true))
    }
}
