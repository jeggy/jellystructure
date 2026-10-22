package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * FR-R168-8 — the card corner, the card subtitle and the detail page's Release type row all draw
 * `releaseTypeLabelKey`: an estimated film reads Expected, an item with a type keeps its type label.
 */
class UpcomingReleaseLabelTest {
    @Test
    fun anEstimatedFilmIsLabelledExpected() {
        assertEquals("up.release_expected", releaseTypeLabelKey(releaseType = null, dateEstimated = true))
    }

    @Test
    fun aTypedItemKeepsItsTypeLabel() {
        assertEquals("up.release_digital", releaseTypeLabelKey("digital", dateEstimated = false))
        assertEquals("up.release_physical", releaseTypeLabelKey("physical", dateEstimated = false))
        assertEquals("up.release_cinema", releaseTypeLabelKey("cinema", dateEstimated = false))
    }

    @Test
    fun aTypeWinsOverTheEstimateFlag() {
        assertEquals("up.release_digital", releaseTypeLabelKey("digital", dateEstimated = true))
    }

    @Test
    fun anItemWithNeitherHasNoLabel() {
        assertNull(releaseTypeLabelKey(null, dateEstimated = false))
    }
}
