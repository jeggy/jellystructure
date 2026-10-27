package dev.jellystructure.ravilo.ui.focus

import kotlin.test.Test
import kotlin.test.assertEquals

/** R350 (FR-R350-11, FR-R350-19) — where the root turns an unconsumed arrow into a focus search. */
class ArrowKeysMoveFocusForTest {
    @Test fun desktopHandsetMovesFocus() = assertEquals(true, arrowKeysMoveFocusFor(isDesktop = true, isWeb = false, handset = true))
    @Test fun desktopWideMovesFocus() = assertEquals(true, arrowKeysMoveFocusFor(isDesktop = true, isWeb = false, handset = false))
    @Test fun webWideMovesFocus() = assertEquals(true, arrowKeysMoveFocusFor(isDesktop = false, isWeb = true, handset = false))
    @Test fun webHandsetMovesNothing() = assertEquals(false, arrowKeysMoveFocusFor(isDesktop = false, isWeb = true, handset = true))
    @Test fun androidWideLeftToThePlatform() = assertEquals(false, arrowKeysMoveFocusFor(isDesktop = false, isWeb = false, handset = false))
    @Test fun androidHandsetLeftToThePlatform() = assertEquals(false, arrowKeysMoveFocusFor(isDesktop = false, isWeb = false, handset = true))
}
