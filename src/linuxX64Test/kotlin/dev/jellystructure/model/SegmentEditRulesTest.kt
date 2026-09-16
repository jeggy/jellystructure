package dev.jellystructure.model

import dev.jellystructure.model.SegmentEditRules.Marker
import dev.jellystructure.model.SegmentEditRules.SnapTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Phase 223 — the rules the drag clamp and the server's 422 share. */
class SegmentEditRulesTest {

    private val dur = 2_612_480L
    private val intro = Marker("intro", 30_000L, 90_000L)
    private val credits = Marker("credits", 2_590_000L, null)

    // FR-223-6 — overlap, and what is not one.

    @Test
    fun touchingIsNotAnOverlapAndOpenEndedCreditsRunToTheEnd() {
        assertEquals(0L, SegmentEditRules.overlapMs(Marker("recap", 0L, 30_000L), intro, dur))
        assertEquals(10_000L, SegmentEditRules.overlapMs(Marker("recap", 0L, 40_000L), intro, dur))
        assertEquals(22_480L, SegmentEditRules.overlapMs(Marker("intro", 2_580_000L, 2_640_000L), credits, dur))
        // With no measured length the credits are unbounded: anything after their start overlaps.
        assertEquals(50_000L, SegmentEditRules.overlapMs(Marker("intro", 2_580_000L, 2_640_000L), credits, 0L))
    }

    @Test
    fun theStingerIsAPointInsideTheCreditsButNotInsideTheIntro() {
        assertEquals(0L, SegmentEditRules.overlapMs(Marker("stinger", 2_600_000L, null), credits, dur))
        assertEquals(1L, SegmentEditRules.overlapMs(Marker("stinger", 60_000L, null), intro, dur))
        assertEquals(0L, SegmentEditRules.overlapMs(Marker("stinger", 90_000L, null), intro, dur)) // at the intro's end: touching
    }

    @Test
    fun aNewOverlapIsRefusedWithTheNeighbourNamed() {
        val r = SegmentEditRules.refusal(credits, credits.copy(startMs = 80_000L), listOf(intro), dur)
        assertEquals("credits can't start before the intro ends (1:30)", r?.message)
        assertEquals("stops at intro end", r?.stopsAt)
        val r2 = SegmentEditRules.refusal(intro, intro.copy(endMs = 2_600_000L), listOf(credits), dur)
        assertEquals("intro can't end after the credits start (43:10)", r2?.message)
        assertNull(SegmentEditRules.refusal(intro, intro.copy(endMs = 2_590_000L), listOf(credits), dur)) // touching
        assertNull(SegmentEditRules.refusal(null, Marker("recap", 0L, 30_000L), listOf(intro, credits), dur))
    }

    @Test
    fun anExistingOverlapMayShrinkButNeverGrow() {
        // One of the 775 production rows: the credits start inside the intro.
        val badCredits = Marker("credits", 60_000L, null)
        assertNull(SegmentEditRules.refusal(badCredits, badCredits.copy(startMs = 80_000L), listOf(intro), dur))      // smaller overlap
        assertNull(SegmentEditRules.refusal(badCredits, badCredits.copy(startMs = 2_590_000L), listOf(intro), dur))   // cleared
        assertNotNull(SegmentEditRules.refusal(badCredits, badCredits.copy(startMs = 10_000L), listOf(intro), dur))   // worse
        // The intro's end may also be pulled back, and pushed further in is refused.
        assertNull(SegmentEditRules.refusal(intro, intro.copy(endMs = 70_000L), listOf(badCredits), dur))
        assertNotNull(SegmentEditRules.refusal(intro, intro.copy(endMs = 120_000L), listOf(badCredits), dur))
        // A row whose overlap is already total (credits at the intro's own start) cannot get worse by moving
        // earlier — the rule promises "no worse", not "closer to right"; moving later is the fix and is free.
        val swallowed = Marker("credits", 30_000L, null)
        assertNull(SegmentEditRules.refusal(swallowed, swallowed.copy(startMs = 10_000L), listOf(intro), dur))
        assertNull(SegmentEditRules.refusal(swallowed, swallowed.copy(startMs = 90_000L), listOf(intro), dur))
    }

    // FR-223-1 — the slide keeps its length and stays inside the file.

    @Test
    fun aSlidePreservesLengthAndAnOpenEndedMarkerSlidesItsStart() {
        assertEquals(Marker("intro", 100_000L, 160_000L), SegmentEditRules.slid(intro, 100_000L, dur))
        assertEquals(Marker("intro", 2_552_480L, 2_612_480L), SegmentEditRules.slid(intro, 9_999_999L, dur))
        assertEquals(Marker("intro", 0L, 60_000L), SegmentEditRules.slid(intro, -5L, dur))
        assertEquals(Marker("credits", 2_612_480L, null), SegmentEditRules.slid(credits, 9_999_999L, dur))
    }

    @Test
    fun edgesNeverInvertAndNeverLeaveTheFile() {
        assertEquals(89_900L, SegmentEditRules.withStart(intro, 95_000L, dur).startMs)
        assertEquals(30_100L, SegmentEditRules.withEnd(intro, 10L, dur).endMs)
        assertEquals(dur, SegmentEditRules.withEnd(intro, 9_999_999L, dur).endMs)
        assertEquals(dur, SegmentEditRules.withStart(credits, 9_999_999L, dur).startMs)
    }

    @Test
    fun theClampStopsExactlyAtTheNeighbourAndNamesIt() {
        val (v, r) = SegmentEditRules.clampMove(credits, listOf(intro), dur, from = credits.startMs, to = 10_000L) { s -> credits.copy(startMs = s) }
        assertEquals(90_000L, v)
        assertEquals("stops at intro end", r?.stopsAt)
        val (v2, r2) = SegmentEditRules.clampMove(intro, listOf(credits), dur, from = intro.startMs, to = 2_600_000L) { s -> SegmentEditRules.slid(intro, s, dur) }
        assertEquals(2_530_000L, v2)
        assertEquals("stops at credits start", r2?.stopsAt)
        val (v3, r3) = SegmentEditRules.clampMove(intro, listOf(credits), dur, from = intro.startMs, to = 100_000L) { s -> SegmentEditRules.slid(intro, s, dur) }
        assertEquals(100_000L, v3)
        assertNull(r3)
    }

    // FR-223-4 — snapping.

    @Test
    fun snappingPicksTheNearestTargetWithinTheRadiusAndEarlierEntriesWinTies() {
        val targets = listOf(SnapTarget(84_000L, "black frames"), SnapTarget(84_400L, "silence"), SnapTarget(84_200L, "playhead"))
        assertEquals("black frames", SegmentEditRules.nearestSnap(84_100L, targets, 300L)?.label)
        assertEquals("silence", SegmentEditRules.nearestSnap(84_350L, targets, 300L)?.label)
        assertNull(SegmentEditRules.nearestSnap(90_000L, targets, 300L))
        assertEquals("black frames", SegmentEditRules.nearestSnap(84_200L, listOf(SnapTarget(84_000L, "black frames"), SnapTarget(84_400L, "silence")), 300L)?.label)
        assertEquals(84_000L, SegmentEditRules.wholeSecondSnap(84_120L, 200L)?.ms)
        assertNull(SegmentEditRules.wholeSecondSnap(84_400L, 200L))
    }
}
