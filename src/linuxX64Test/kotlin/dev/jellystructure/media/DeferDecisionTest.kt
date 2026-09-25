package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 262 acceptance 1 — the household switch overrides a row's wish to defer. */
class DeferDecisionTest {
    @Test
    fun `a row defers only when it asked to and the household switch is on`() {
        assertTrue(MediaJobQueue.deferDecision(rowDefers = true, householdDefers = true))
        assertFalse(MediaJobQueue.deferDecision(rowDefers = true, householdDefers = false))
        assertFalse(MediaJobQueue.deferDecision(rowDefers = false, householdDefers = true))
        assertFalse(MediaJobQueue.deferDecision(rowDefers = false, householdDefers = false))
    }
}
