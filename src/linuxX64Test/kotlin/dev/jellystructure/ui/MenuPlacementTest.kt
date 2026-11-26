package dev.jellystructure.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-3907 FR-9 — `fixedMenuPlacement`, the pure placement of a menu that leaves its `.mu-scroll` wrapper. */
class MenuPlacementTest {
    @Test fun roomBelowOpensUnderTheTriggerWithLeftEdgesAligned() {
        assertEquals(300.0 to 126.0, fixedMenuPlacement(300.0, 100.0, 120.0, 224.0, 150.0, 1200.0, 800.0))
    }

    @Test fun aTriggerNearTheRightEdgePullsTheMenuInSoItEndsEightPixelsFromTheWindow() {
        val (left, _) = fixedMenuPlacement(1150.0, 100.0, 120.0, 224.0, 150.0, 1200.0, 800.0)
        assertEquals(1200.0 - 8.0, left + 224.0)
    }

    @Test fun aTriggerNearTheBottomOpensTheMenuAboveIt() {
        assertEquals(300.0 to 544.0, fixedMenuPlacement(300.0, 700.0, 720.0, 224.0, 150.0, 1200.0, 800.0))
    }

    @Test fun aMenuTallerThanTheSpaceAboveAndBelowSitsEightPixelsFromTheTop() {
        assertEquals(8.0, fixedMenuPlacement(300.0, 300.0, 320.0, 224.0, 700.0, 1200.0, 800.0).second)
    }

    @Test fun aMenuWiderThanTheWindowSitsEightPixelsFromTheLeft() {
        assertEquals(8.0, fixedMenuPlacement(100.0, 100.0, 120.0, 500.0, 150.0, 400.0, 800.0).first)
    }

    @Test fun aTriggerLeftOfTheWindowIsPulledInToEightPixels() {
        assertEquals(8.0, fixedMenuPlacement(-40.0, 100.0, 120.0, 224.0, 150.0, 1200.0, 800.0).first)
    }
}
