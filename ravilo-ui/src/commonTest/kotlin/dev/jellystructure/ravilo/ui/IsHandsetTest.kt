package dev.jellystructure.ravilo.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R256 (FR-R256-3) — a TV is 960 x 540 dp and must never read as a handset. */
class IsHandsetTest {
    @Test fun tv1080pIsNotAHandset() = assertFalse(isHandset(isTv = true, widthPx = 1920, heightPx = 1080, density = 2f))
    @Test fun tv4kIsNotAHandset() = assertFalse(isHandset(isTv = true, widthPx = 3840, heightPx = 2160, density = 4f))
    @Test fun theSameWindowWithoutTheTvFlagWouldBe() = assertTrue(isHandset(isTv = false, widthPx = 1920, heightPx = 1080, density = 2f))
    @Test fun pixel9Portrait() = assertTrue(isHandset(isTv = false, widthPx = 1080, heightPx = 2424, density = 2.625f))
    @Test fun pixel9Landscape() = assertTrue(isHandset(isTv = false, widthPx = 2424, heightPx = 1080, density = 2.625f))
    @Test fun tabletIsNot() = assertFalse(isHandset(isTv = false, widthPx = 2560, heightPx = 1600, density = 2f))
    @Test fun zeroSizedWindowIsNot() = assertFalse(isHandset(isTv = false, widthPx = 0, heightPx = 0, density = 2f))
}
