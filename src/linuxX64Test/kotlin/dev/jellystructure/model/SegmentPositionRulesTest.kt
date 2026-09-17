package dev.jellystructure.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 233 (FR-233-1). */
class SegmentPositionRulesTest {
    private val fiveMin = 300_000L

    @Test fun introEndingAtHalfIsPlausibleOneMsLaterIsNot() {
        assertTrue(SegmentPositionRules.plausible("intro", 100_000, 150_000, fiveMin))
        assertFalse(SegmentPositionRules.plausible("intro", 100_000, 150_001, fiveMin))
    }

    @Test fun creditsStartingAtHalfArePlausibleOneMsEarlierAreNot() {
        assertTrue(SegmentPositionRules.plausible("credits", 150_000, null, fiveMin))
        assertFalse(SegmentPositionRules.plausible("credits", 149_999, null, fiveMin))
    }

    /** Æbler i natkjole S01E02 on production: both rows at ~333.7 s of a 350.8 s file. */
    @Test fun theProductionPairIsAnOutroNotAnIntro() {
        assertFalse(SegmentPositionRules.plausible("intro", 333_713, 348_199, 350_824))
        assertTrue(SegmentPositionRules.plausible("credits", 333_650, null, 350_824))
    }

    @Test fun unknownDurationAndOtherKindsJudgeNothing() {
        assertTrue(SegmentPositionRules.plausible("credits", 5_000, null, null))
        assertTrue(SegmentPositionRules.plausible("intro", 5_000, 9_000, 0))
        assertTrue(SegmentPositionRules.plausible("recap", 290_000, 299_000, fiveMin))
    }

    @Test fun creditsInsideIntroNeedsNoDuration() {
        assertTrue(SegmentPositionRules.creditsInsideIntro(3_879, 33_841, 8_512))
        assertFalse(SegmentPositionRules.creditsInsideIntro(3_879, 33_841, 33_841))
        assertTrue(SegmentPositionRules.creditsInsideIntro(5_000, null, 5_000))
    }

    @Test fun aPersonsRowIsNeverAutomatic() {
        assertTrue(SegmentPositionRules.humanTouched("manual", false, null))
        assertTrue(SegmentPositionRules.humanTouched("fingerprint", true, null))
        assertTrue(SegmentPositionRules.humanTouched("chapter", false, 1L))
        assertFalse(SegmentPositionRules.humanTouched("fingerprint", false, null))
    }
}
