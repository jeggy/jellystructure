package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** R259 — a long programme's label stays in the visible window (DR2 13:00–18:15 seen at 16:20). */
class GuideLabelShiftTest {
    @Test fun aCellStartingInsideTheWindowIsUntouched() = assertEquals(0, guideLabelShiftPx(40f, 900, 240))
    @Test fun aCellScrolledOffTheLeftMovesItsLabelToTheWindowEdge() = assertEquals(1600, guideLabelShiftPx(-1600f, 3000, 240))
    @Test fun theLabelAlwaysKeepsItsMinimumRoom() = assertEquals(2760, guideLabelShiftPx(-2990f, 3000, 240))
    @Test fun aCellNarrowerThanTheMinimumNeverShifts() = assertEquals(0, guideLabelShiftPx(-50f, 100, 240))
}
