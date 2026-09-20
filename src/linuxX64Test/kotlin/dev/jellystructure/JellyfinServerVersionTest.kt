package dev.jellystructure

import dev.jellystructure.auth.JellyfinServerVersion
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 243. The version strings here are real: `12.1.0` is what the household server answered on
 * 2026-09-19, `10.11.11` is what it answered before the upgrade that broke three routes at once.
 *
 * The failure this pins against is the quiet one. An unknown version rendering as "below the floor"
 * would put a permanent, untrue warning on the advisor of every installation that has not yet reached
 * Jellyfin once — the exact class of false finding phase 246 was written to remove.
 */
class JellyfinServerVersionTest {

    @BeforeTest fun clear() = JellyfinServerVersion.resetForTest()
    @AfterTest fun clean() = JellyfinServerVersion.resetForTest()

    @Test
    fun unobservedVersionIsNotBelowTheFloor() {
        assertNull(JellyfinServerVersion.current())
        assertFalse(
            JellyfinServerVersion.isBelowFloor(),
            "\"we have not asked yet\" must never report as \"your server is too old\"",
        )
    }

    @Test
    fun anUnparseableVersionIsNotBelowTheFloor() {
        JellyfinServerVersion.record("unstable-nightly")
        assertEquals("unstable-nightly", JellyfinServerVersion.current())
        assertNull(JellyfinServerVersion.majorOf("unstable-nightly"))
        assertFalse(JellyfinServerVersion.isBelowFloor())
    }

    @Test
    fun theHouseholdServerIsAboveTheFloor() {
        JellyfinServerVersion.record("12.1.0")
        assertEquals(12, JellyfinServerVersion.majorOf("12.1.0"))
        assertFalse(JellyfinServerVersion.isBelowFloor())
    }

    @Test
    fun twelvePointZeroIsExactlyTheFloorAndPasses() {
        // Dev-review item 3: the comparison is major-only, which is what makes 12.0-vs-12.1 academic.
        JellyfinServerVersion.record("12.0.0")
        assertFalse(JellyfinServerVersion.isBelowFloor())
    }

    @Test
    fun theVersionThisProductWasWrittenAgainstBeforeTheUpgradeIsBelowTheFloor() {
        JellyfinServerVersion.record("10.11.11")
        assertTrue(JellyfinServerVersion.isBelowFloor())
        val detail = JellyfinServerVersion.belowFloorDetail()
        assertTrue(detail.contains("10.11.11"), "the finding must name the version found: $detail")
        assertTrue(detail.contains("12.0"), "the finding must name the version required: $detail")
    }

    @Test
    fun aFutureMajorIsAboveTheFloor() {
        // FR-243-4: this object exists to report, so a Jellyfin newer than the floor is simply fine.
        // Nothing may branch on the difference between 12 and 13.
        JellyfinServerVersion.record("13.0.0")
        assertFalse(JellyfinServerVersion.isBelowFloor())
    }

    @Test
    fun blankAndNullRecordsAreIgnoredRatherThanLatched() {
        JellyfinServerVersion.record("12.1.0")
        JellyfinServerVersion.record(null)
        JellyfinServerVersion.record("   ")
        assertEquals(
            "12.1.0", JellyfinServerVersion.current(),
            "a failed probe must not erase a version that was genuinely observed",
        )
    }

    @Test
    fun recordingStampsAnObservationTime() {
        assertEquals(0, JellyfinServerVersion.observedAtEpochSec())
        JellyfinServerVersion.record("12.1.0")
        assertTrue(JellyfinServerVersion.observedAtEpochSec() > 0)
    }
}
